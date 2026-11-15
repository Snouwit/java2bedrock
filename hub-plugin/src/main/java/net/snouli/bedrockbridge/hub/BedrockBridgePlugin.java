package net.snouli.bedrockbridge.hub;

import net.snouli.bedrockbridge.core.BridgeHttpServer;
import net.snouli.bedrockbridge.core.BuildInput;
import net.snouli.bedrockbridge.core.BuildOutput;
import net.snouli.bedrockbridge.core.DistStore;
import net.snouli.bedrockbridge.core.IaItem;
import net.snouli.bedrockbridge.core.ModelOptions;
import net.snouli.bedrockbridge.core.PackBuilder;
import net.snouli.bedrockbridge.core.Published;
import net.snouli.bedrockbridge.core.Report;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;

public final class BedrockBridgePlugin extends JavaPlugin {

    private static final String PERMISSION = "snouli.bedrock.admin";
    private static final String PREFIX = ChatColor.AQUA + "[BedrockBridge] " + ChatColor.RESET;
    private static final String TOKEN_PLACEHOLDER = "LANGER-ZUFALLSWERT";

    private final AtomicReference<Published> published = new AtomicReference<>();
    private final AtomicBoolean building = new AtomicBoolean();

    private DistStore store;
    private BridgeHttpServer httpServer;
    private boolean itemsAdderPresent;

    // Only touched on the server thread
    private String builtSignature;
    private String pendingSignature;
    private int stableChecks;
    private volatile Report lastReport;
    private volatile String lastError;
    private volatile String lastBuildInfo = "noch kein Build in dieser Sitzung";

    @Override
    public void onEnable() {
        saveDefaultConfig();
        ensureToken();

        store = new DistStore(getDataFolder().toPath().resolve("dist"));
        Published existing = store.load();
        published.set(existing);
        builtSignature = readSignatureFile();
        if (existing != null) {
            getLogger().info("Letzte Version " + existing.version() + " vom " + existing.builtAt() + " geladen.");
        } else {
            builtSignature = null;
        }

        startHttpServer();

        itemsAdderPresent = Bukkit.getPluginManager().getPlugin("ItemsAdder") != null;
        if (itemsAdderPresent) {
            Bukkit.getPluginManager().registerEvents(new ItemsAdderBridge(this), this);
        } else {
            getLogger().warning("ItemsAdder ist nicht installiert - es wird nur die Paket-Datei ausgewertet.");
        }

        // Cheap file-stat poll; the build itself runs asynchronously.
        Bukkit.getScheduler().runTaskTimer(this, this::watchPack, 100L, 40L);
    }

    @Override
    public void onDisable() {
        if (httpServer != null) {
            httpServer.stop();
        }
    }

    private void ensureToken() {
        String token = getConfig().getString("http.token", "");
        if (token == null || token.isBlank() || token.equals(TOKEN_PLACEHOLDER)) {
            byte[] random = new byte[24];
            new SecureRandom().nextBytes(random);
            getConfig().set("http.token", Base64.getUrlEncoder().withoutPadding().encodeToString(random));
            saveConfig();
            getLogger().info("Neues HTTP-Token erzeugt. Es steht in plugins/SnouliBedrockBridge/config.yml"
                + " (http.token) und muss in die Geyser-Extension eingetragen werden.");
        }
    }

    private void startHttpServer() {
        String bind = getConfig().getString("http.bind", "0.0.0.0");
        int port = getConfig().getInt("http.port", 25887);
        try {
            httpServer = new BridgeHttpServer(bind, port, getConfig().getString("http.token", ""), published::get);
            httpServer.start();
            getLogger().info("HTTP-Server laeuft auf " + bind + ":" + port);
        } catch (IOException e) {
            httpServer = null;
            getLogger().log(Level.SEVERE, "HTTP-Server konnte nicht auf " + bind + ":" + port
                + " starten (Port belegt?). Das Paket wird gebaut, aber nicht ausgeliefert.", e);
        }
    }

    private Path javaPack() {
        return Path.of(getConfig().getString("itemsadder-pack", "plugins/ItemsAdder/output/generated.zip"));
    }

    private Path signatureFile() {
        return getDataFolder().toPath().resolve("dist").resolve("input-signature.txt");
    }

    private String readSignatureFile() {
        try {
            return Files.isRegularFile(signatureFile()) ? Files.readString(signatureFile()).trim() : null;
        } catch (IOException e) {
            return null;
        }
    }

    /** Size and modification time: changes whenever ItemsAdder writes a new zip. */
    private static String signature(Path file) {
        try {
            if (!Files.isRegularFile(file)) {
                return null;
            }
            return Files.size(file) + ":" + Files.getLastModifiedTime(file).toMillis();
        } catch (IOException e) {
            return null;
        }
    }

    void onItemsAdderPackEvent(String what) {
        if (getConfig().getBoolean("auto-build-on-iazip", true)) {
            getLogger().info(what + " - das Bedrock-Paket wird neu gebaut, sobald generated.zip fertig geschrieben ist.");
        }
    }

    /** Runs on the server thread every two seconds. */
    private void watchPack() {
        if (!getConfig().getBoolean("auto-build-on-iazip", true) || building.get()) {
            return;
        }
        String signature = signature(javaPack());
        if (signature == null || signature.equals(builtSignature)) {
            pendingSignature = null;
            return;
        }
        // Wait until ItemsAdder has finished writing: the file must look the same on two polls in a row.
        if (!signature.equals(pendingSignature)) {
            pendingSignature = signature;
            stableChecks = 0;
            return;
        }
        if (++stableChecks < 2) {
            return;
        }
        if (itemsAdderPresent && !ItemsAdderBridge.ready()) {
            return; // ItemsAdder is still loading; names and ids would be missing
        }
        startBuild(null, "generated.zip hat sich geaendert");
    }

    /** Must be called on the server thread. */
    private boolean startBuild(CommandSender requester, String reason) {
        Path zip = javaPack();
        String signature = signature(zip);
        if (signature == null) {
            lastError = "Paket-Datei nicht gefunden: " + zip.toAbsolutePath();
            return false;
        }
        if (!building.compareAndSet(false, true)) {
            return false;
        }

        List<IaItem> items = List.of();
        Map<String, String> fontImages = Map.of();
        if (itemsAdderPresent && ItemsAdderBridge.ready()) {
            try {
                items = ItemsAdderBridge.items(this);
                fontImages = ItemsAdderBridge.fontImages();
            } catch (Throwable t) {
                getLogger().log(Level.WARNING, "ItemsAdder-API konnte nicht gelesen werden, baue nur aus der Paket-Datei.", t);
            }
        }
        BuildInput input = new BuildInput(zip, getConfig().getString("pack-name", "Snouli.net"),
            new HashSet<>(getConfig().getStringList("skip-namespaces")), items, fontImages,
            getConfig().getInt("max-glyph-size", 64), modelOptions(), message -> getLogger().warning(message));

        getLogger().info("Baue Bedrock-Paket (" + reason + ") ...");
        Bukkit.getScheduler().runTaskAsynchronously(this, () -> build(input, signature, requester));
        return true;
    }

    private ModelOptions modelOptions() {
        return new ModelOptions(getConfig().getBoolean("models-3d.enabled", true),
            getConfig().getString("models-3d.display-context", "head"),
            vector("models-3d.display-rotation"), vector("models-3d.display-translation"));
    }

    private double[] vector(String path) {
        List<Double> values = getConfig().getDoubleList(path);
        double[] result = new double[3];
        for (int i = 0; i < 3 && i < values.size(); i++) {
            result[i] = values.get(i);
        }
        return result;
    }

    /** Runs off the server thread. */
    private void build(BuildInput input, String signature, CommandSender requester) {
        long start = System.currentTimeMillis();
        DistStore.Result result = null;
        Report report = null;
        String error = null;
        try {
            BuildOutput output = PackBuilder.build(input);
            report = output.report();
            result = store.publish(output, input.packName(), published.get());
            published.set(result.published());

            String header = "SnouliBedrockBridge - Bericht (Version " + result.published().version() + ", "
                + result.published().builtAt() + ")";
            Files.createDirectories(getDataFolder().toPath());
            Files.writeString(getDataFolder().toPath().resolve("report.txt"), report.render(header), StandardCharsets.UTF_8);
            Files.writeString(signatureFile(), signature, StandardCharsets.UTF_8);
        } catch (Throwable t) {
            // The previous version stays published.
            error = t.getClass().getSimpleName() + ": " + t.getMessage();
            getLogger().log(Level.SEVERE, "Build fehlgeschlagen - die alte Version bleibt online.", t);
        }

        long millis = System.currentTimeMillis() - start;
        DistStore.Result finalResult = result;
        Report finalReport = report;
        String finalError = error;
        Runnable finish = () -> finishBuild(finalResult, finalReport, finalError, signature, millis, requester);
        if (isEnabled()) {
            Bukkit.getScheduler().runTask(this, finish);
        } else {
            building.set(false);
        }
    }

    /** Back on the server thread. */
    private void finishBuild(DistStore.Result result, Report report, String error, String signature, long millis,
                             CommandSender requester) {
        building.set(false);
        // Remember the input even after a failure, so a broken zip is not rebuilt every two seconds.
        builtSignature = signature;
        pendingSignature = null;
        lastError = error;

        if (error != null) {
            lastBuildInfo = "fehlgeschlagen: " + error;
            notifyAdmins(requester, ChatColor.RED + "Build fehlgeschlagen: " + error + " - die alte Version bleibt online.");
            return;
        }
        lastReport = report;
        Published current = result.published();
        String counts = current.glyphs() + " Glyphen, " + current.items() + " Items, " + current.blocks() + " Bloecke, "
            + current.models() + " 3D-Modelle";
        if (!result.changed()) {
            lastBuildInfo = "unveraendert (Version " + current.version() + ")";
            getLogger().info("Keine Aenderung - Version " + current.version() + " bleibt (" + counts + ", " + millis + " ms).");
            if (requester != null) {
                requester.sendMessage(PREFIX + "Keine Aenderung, Version " + current.version() + " bleibt.");
            }
            return;
        }
        lastBuildInfo = "Version " + current.version() + " in " + millis + " ms";
        getLogger().info("Version " + current.version() + " veroeffentlicht: " + counts + " (" + millis + " ms)."
            + " Details: plugins/SnouliBedrockBridge/report.txt");
        notifyAdmins(requester, ChatColor.GREEN + "Bedrock-Paket Version " + current.version() + ": " + counts + ".");
        if (result.mappingsChanged()) {
            getLogger().warning("Neue Bedrock-Items - Proxy-Neustart noetig (Geyser registriert Items/Bloecke nur beim Start).");
            notifyAdmins(requester, ChatColor.YELLOW + "Neue Bedrock-Items - Proxy-Neustart noetig.");
        }
    }

    private void notifyAdmins(CommandSender requester, String message) {
        boolean requesterNotified = false;
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player.hasPermission(PERMISSION)) {
                player.sendMessage(PREFIX + message);
                requesterNotified |= player.equals(requester);
            }
        }
        if (requester != null && !requesterNotified && !(requester instanceof Player && !((Player) requester).isOnline())) {
            requester.sendMessage(PREFIX + message);
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission(PERMISSION)) {
            sender.sendMessage(PREFIX + ChatColor.RED + "Keine Berechtigung.");
            return true;
        }
        String sub = args.length == 0 ? "" : args[0].toLowerCase();
        switch (sub) {
            case "build" -> {
                if (building.get()) {
                    sender.sendMessage(PREFIX + "Es laeuft bereits ein Build.");
                } else if (startBuild(sender, "/bedrockpack build von " + sender.getName())) {
                    sender.sendMessage(PREFIX + "Build gestartet ...");
                } else {
                    sender.sendMessage(PREFIX + ChatColor.RED + (lastError != null ? lastError : "Build konnte nicht starten."));
                }
            }
            case "status" -> {
                Published current = published.get();
                if (current == null) {
                    sender.sendMessage(PREFIX + "Noch keine Version veroeffentlicht.");
                } else {
                    sender.sendMessage(PREFIX + "Version " + current.version() + " vom " + current.builtAt()
                        + " (Paket " + current.packVersion()[0] + "." + current.packVersion()[1] + "." + current.packVersion()[2]
                        + ", " + current.pack().length / 1024 + " KB)");
                    sender.sendMessage(PREFIX + current.glyphs() + " Glyphen, " + current.items() + " Items, "
                        + current.blocks() + " Bloecke, " + current.models() + " 3D-Modelle/Moebel");
                }
                sender.sendMessage(PREFIX + "Letzter Build: " + (building.get() ? "laeuft gerade" : lastBuildInfo));
                sender.sendMessage(PREFIX + "HTTP-Server: " + (httpServer != null ? "Port " + httpServer.port() : ChatColor.RED + "nicht gestartet"));
                if (lastError != null) {
                    sender.sendMessage(PREFIX + ChatColor.RED + "Fehler: " + lastError);
                }
            }
            case "report" -> {
                Report report = lastReport;
                if (report == null) {
                    sender.sendMessage(PREFIX + "Noch kein Bericht in dieser Sitzung - siehe plugins/SnouliBedrockBridge/report.txt"
                        + " oder /bedrockpack build.");
                    return true;
                }
                for (String line : report.summaryLines()) {
                    sender.sendMessage(PREFIX + line);
                }
                for (Report.Kind kind : Report.Kind.values()) {
                    List<Report.Entry> skipped = report.skipped(kind);
                    for (int i = 0; i < Math.min(5, skipped.size()); i++) {
                        sender.sendMessage(ChatColor.GRAY + " - " + skipped.get(i).name() + ": " + skipped.get(i).detail());
                    }
                    if (skipped.size() > 5) {
                        sender.sendMessage(ChatColor.GRAY + " ... und " + (skipped.size() - 5) + " weitere");
                    }
                }
                sender.sendMessage(PREFIX + "Vollstaendig: plugins/SnouliBedrockBridge/report.txt");
            }
            default -> sender.sendMessage(PREFIX + "/bedrockpack <build|status|report>");
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> result = new ArrayList<>();
        if (args.length == 1) {
            for (String option : List.of("build", "status", "report")) {
                if (option.startsWith(args[0].toLowerCase())) {
                    result.add(option);
                }
            }
        }
        return result;
    }
}
