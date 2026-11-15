package net.snouli.bedrockbridge.geyser;

import org.geysermc.event.subscribe.Subscribe;
import org.geysermc.geyser.api.connection.GeyserConnection;
import org.geysermc.geyser.api.event.bedrock.SessionJoinEvent;
import org.geysermc.geyser.api.event.bedrock.SessionLoadResourcePacksEvent;
import org.geysermc.geyser.api.event.lifecycle.GeyserPostInitializeEvent;
import org.geysermc.geyser.api.event.lifecycle.GeyserPreInitializeEvent;
import org.geysermc.geyser.api.event.lifecycle.GeyserRegisterPermissionsEvent;
import org.geysermc.geyser.api.event.lifecycle.GeyserShutdownEvent;
import org.geysermc.geyser.api.extension.Extension;
import org.geysermc.geyser.api.pack.PackCodec;
import org.geysermc.geyser.api.pack.ResourcePack;
import org.geysermc.geyser.api.util.TriState;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Geyser side of SnouliBedrockBridge: keeps the Bedrock pack and the custom item/block mappings
 * in sync with the Hub.
 *
 * <p>The pack is handed to every Bedrock player that joins, so new textures work without a proxy
 * restart. Mappings are written into Geyser's {@code custom_mappings} folder; Geyser only reads
 * them while starting, so changed mappings need a proxy restart.
 */
public class BedrockBridgeExtension implements Extension {

    private static final String RESTART_MESSAGE = "Neue Bedrock-Items - Proxy-Neustart noetig";
    private static final String PLACEHOLDER_TOKEN = "LANGER-ZUFALLSWERT";
    /** Community extension that makes Java item displays visible; needed for furniture on item displays. */
    private static final String DISPLAY_EXTENSION_ID = "geyserdisplayentity";

    private BridgeSync sync;
    private String permission = "snouli.bedrock.admin";
    private int intervalSeconds = 60;
    private ScheduledExecutorService scheduler;

    /** SHA-1 of the mappings Geyser read while starting. */
    private String loadedMappingsSha1 = "";
    private volatile boolean restartNeeded;
    private volatile boolean lastCheckFailed;

    private Path cachedPackFile;
    private ResourcePack cachedPack;

    @Subscribe
    public void onPreInitialize(GeyserPreInitializeEvent event) {
        Map<String, String> config;
        try {
            config = loadConfig();
        } catch (IOException e) {
            logger().error("config.yml konnte nicht gelesen werden - Extension bleibt inaktiv.", e);
            return;
        }
        String url = config.getOrDefault("url", "");
        String token = config.getOrDefault("token", "");
        permission = config.getOrDefault("notify-permission", permission);
        try {
            intervalSeconds = Math.max(10, Integer.parseInt(config.getOrDefault("check-interval-seconds", "60")));
        } catch (NumberFormatException e) {
            logger().warning("check-interval-seconds ist keine Zahl - nutze 60.");
        }
        if (url.isBlank() || token.isBlank() || token.equals(PLACEHOLDER_TOKEN)) {
            logger().warning("Bitte url und token in " + dataFolder().resolve("config.yml")
                + " eintragen (Token aus der config.yml des Hub-Plugins) und den Proxy neu starten.");
            return;
        }

        Path mappingsFolder = geyserApi().configDirectory().resolve("custom_mappings");
        boolean displayExtension = hasDisplayExtension();
        Path displayMappings = displayExtension
            ? dataFolder().resolveSibling(DISPLAY_EXTENSION_ID).resolve("Mappings") : null;
        sync = new BridgeSync(dataFolder(), mappingsFolder, displayMappings, url, token, Duration.ofSeconds(5));

        // Fetch once before Geyser reads custom_mappings, so a restart always picks up the newest items.
        try {
            BridgeSync.Result result = sync.check(Duration.ofSeconds(15));
            logger().info("Hub-Version " + result.version() + (result.packChanged() || result.mappingsChanged()
                ? " heruntergeladen." : " ist bereits vorhanden."));
        } catch (IOException e) {
            lastCheckFailed = true;
            logger().warning("Hub nicht erreichbar (" + e.getMessage() + ")"
                + (sync.packFile() != null ? " - nutze die zuletzt geladene Version " + sync.version() + "." : " - noch kein Paket vorhanden."));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        loadedMappingsSha1 = sync.mappingsSha1();
        sync.deleteOldPacks();
        if (sync.hasDisplayEntities() && !displayExtension) {
            logger().warning("Das Paket enthaelt Moebel/3D-Modelle. Moebel auf Item-Displays sieht Bedrock nur mit der"
                + " Extension GeyserDisplayEntity (in denselben extensions-Ordner legen). Ohne sie bleiben sie unsichtbar.");
        }
    }

    @Subscribe
    public void onPostInitialize(GeyserPostInitializeEvent event) {
        if (sync == null) {
            return;
        }
        scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "SnouliBedrockBridge-Sync");
            thread.setDaemon(true);
            return thread;
        });
        scheduler.scheduleWithFixedDelay(this::poll, intervalSeconds, intervalSeconds, TimeUnit.SECONDS);
    }

    @Subscribe
    public void onShutdown(GeyserShutdownEvent event) {
        if (scheduler != null) {
            scheduler.shutdownNow();
        }
    }

    @Subscribe
    public void onRegisterPermissions(GeyserRegisterPermissionsEvent event) {
        event.register(permission, TriState.NOT_SET);
    }

    private boolean hasDisplayExtension() {
        try {
            return geyserApi().extensionManager().extension(DISPLAY_EXTENSION_ID) != null;
        } catch (Throwable t) {
            return false;
        }
    }

    private void poll() {
        try {
            BridgeSync.Result result = sync.check(Duration.ofSeconds(30));
            if (lastCheckFailed) {
                lastCheckFailed = false;
                logger().info("Hub ist wieder erreichbar.");
            }
            if (result.packChanged()) {
                logger().info("Neues Bedrock-Paket (Hub-Version " + result.version()
                    + ") geladen - gilt fuer alle Bedrock-Spieler, die ab jetzt joinen.");
            }
            boolean needed = !sync.mappingsSha1().equals(loadedMappingsSha1);
            if (needed && (result.mappingsChanged() || !restartNeeded)) {
                restartNeeded = true;
                logger().warning(RESTART_MESSAGE + " (Geyser registriert Custom-Items/-Bloecke nur beim Start).");
                for (GeyserConnection connection : geyserApi().onlineConnections()) {
                    notify(connection);
                }
            } else if (!needed) {
                restartNeeded = false;
            }
        } catch (IOException e) {
            if (!lastCheckFailed) {
                lastCheckFailed = true;
                logger().warning("Hub nicht erreichbar (" + e.getMessage() + ") - die bisherige Version bleibt aktiv.");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Throwable t) {
            logger().error("Unerwarteter Fehler beim Abgleich mit dem Hub", t);
        }
    }

    @Subscribe
    public void onLoadResourcePacks(SessionLoadResourcePacksEvent event) {
        ResourcePack pack = currentPack();
        if (pack != null) {
            event.register(pack);
        }
    }

    @Subscribe
    public void onJoin(SessionJoinEvent event) {
        if (restartNeeded) {
            notify(event.connection());
        }
    }

    private void notify(GeyserConnection connection) {
        try {
            if (connection.hasPermission(permission)) {
                connection.sendMessage("§e[BedrockBridge] " + RESTART_MESSAGE);
            }
        } catch (Throwable ignored) {
            // the player may be mid-login; they are notified on their next join
        }
    }

    private synchronized ResourcePack currentPack() {
        if (sync == null) {
            return null;
        }
        Path file = sync.packFile();
        if (file == null) {
            return null;
        }
        if (!file.equals(cachedPackFile)) {
            try {
                cachedPack = ResourcePack.create(PackCodec.path(file));
                cachedPackFile = file;
            } catch (Throwable t) {
                logger().error("Bedrock-Paket " + file.getFileName() + " konnte nicht geladen werden", t);
                return null;
            }
        }
        return cachedPack;
    }

    /** Reads the flat {@code key: value} config; creates it from the bundled default on first start. */
    private Map<String, String> loadConfig() throws IOException {
        Path file = dataFolder().resolve("config.yml");
        if (!Files.exists(file)) {
            Files.createDirectories(dataFolder());
            try (InputStream in = BedrockBridgeExtension.class.getClassLoader().getResourceAsStream("config.yml")) {
                if (in == null) {
                    throw new IOException("config.yml fehlt in der Extension");
                }
                Files.copy(in, file);
            }
        }
        return parseConfig(Files.readAllLines(file));
    }

    static Map<String, String> parseConfig(Iterable<String> lines) {
        Map<String, String> values = new HashMap<>();
        for (String raw : lines) {
            String line = raw.trim();
            int colon = line.indexOf(':');
            if (line.isEmpty() || line.startsWith("#") || colon <= 0) {
                continue;
            }
            String value = line.substring(colon + 1).trim();
            if (value.length() >= 2 && (value.startsWith("\"") && value.endsWith("\"")
                || value.startsWith("'") && value.endsWith("'"))) {
                value = value.substring(1, value.length() - 1);
            } else {
                int comment = value.indexOf(" #");
                if (comment >= 0) {
                    value = value.substring(0, comment).trim();
                }
            }
            values.put(line.substring(0, colon).trim(), value);
        }
        return values;
    }
}
