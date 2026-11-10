package net.snouli.bedrockbridge.geyser;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.stream.Stream;

/**
 * Downloads pack and mappings from the Hub plugin whenever {@code version.json} changes.
 * Independent of the Geyser API so it can be tested on its own.
 */
public final class BridgeSync {

    /** File names inside Geyser's {@code custom_mappings} folder. */
    public static final String ITEM_MAPPINGS = "snouli_bedrock_bridge_items.json";
    public static final String BLOCK_MAPPINGS = "snouli_bedrock_bridge_blocks.json";

    public record Result(boolean packChanged, boolean mappingsChanged, int version) {}

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    private final Path dataFolder;
    private final Path mappingsFolder;
    private final String baseUrl;
    private final String token;
    private final HttpClient client;

    private volatile int version;
    private volatile String packSha1 = "";
    private volatile String mappingsSha1 = "";
    private volatile Path packFile;

    public BridgeSync(Path dataFolder, Path mappingsFolder, String baseUrl, String token, Duration connectTimeout) {
        this.dataFolder = dataFolder;
        this.mappingsFolder = mappingsFolder;
        this.baseUrl = baseUrl.replaceAll("/+$", "");
        this.token = token;
        this.client = HttpClient.newBuilder().connectTimeout(connectTimeout).build();
        loadState();
    }

    public int version() {
        return version;
    }

    public String mappingsSha1() {
        return mappingsSha1;
    }

    /** @return the downloaded pack, or {@code null} if nothing has been downloaded yet */
    public Path packFile() {
        return packFile;
    }

    private Path packsFolder() {
        return dataFolder.resolve("packs");
    }

    private void loadState() {
        try {
            Path stateFile = dataFolder.resolve("state.json");
            if (!Files.isRegularFile(stateFile)) {
                return;
            }
            JsonObject state = JsonParser.parseString(Files.readString(stateFile)).getAsJsonObject();
            Path pack = packsFolder().resolve(state.get("pack_file").getAsString());
            if (!Files.isRegularFile(pack) || !sha1(Files.readAllBytes(pack)).equals(state.get("pack_sha1").getAsString())) {
                return; // damaged cache: download again
            }
            packFile = pack;
            packSha1 = state.get("pack_sha1").getAsString();
            mappingsSha1 = state.get("mappings_sha1").getAsString();
            version = state.get("version").getAsInt();
        } catch (IOException | RuntimeException ignored) {
            // start with an empty cache
        }
    }

    /**
     * Asks the Hub for its current version and downloads what changed.
     *
     * @throws IOException if the Hub is unreachable or a download does not match its SHA-1;
     *                     the previously downloaded files stay in use
     */
    public synchronized Result check(Duration timeout) throws IOException, InterruptedException {
        JsonObject remote;
        try {
            remote = JsonParser.parseString(new String(get("/version.json", timeout), StandardCharsets.UTF_8)).getAsJsonObject();
        } catch (RuntimeException e) {
            throw new IOException("version.json ist ungueltig", e);
        }
        int remoteVersion;
        String remotePackSha1;
        String remoteMappingsSha1;
        try {
            remoteVersion = remote.get("version").getAsInt();
            remotePackSha1 = remote.getAsJsonObject("pack").get("sha1").getAsString();
            remoteMappingsSha1 = remote.getAsJsonObject("mappings").get("sha1").getAsString();
        } catch (RuntimeException e) {
            throw new IOException("version.json ist unvollstaendig", e);
        }

        boolean packChanged = packFile == null || !remotePackSha1.equals(packSha1);
        boolean mappingsChanged = !remoteMappingsSha1.equals(mappingsSha1) || !mappingFilesPresent();
        if (!packChanged && !mappingsChanged) {
            version = remoteVersion;
            return new Result(false, false, remoteVersion);
        }

        byte[] pack = null;
        if (packChanged) {
            pack = get("/pack.mcpack", timeout);
            if (!sha1(pack).equals(remotePackSha1)) {
                throw new IOException("pack.mcpack passt nicht zur SHA-1 aus version.json (Build lief gerade?)");
            }
        }
        JsonObject mappings = null;
        if (mappingsChanged) {
            byte[] raw = get("/mappings.json", timeout);
            if (!sha1(raw).equals(remoteMappingsSha1)) {
                throw new IOException("mappings.json passt nicht zur SHA-1 aus version.json (Build lief gerade?)");
            }
            try {
                mappings = JsonParser.parseString(new String(raw, StandardCharsets.UTF_8)).getAsJsonObject();
                if (!mappings.has("items") || !mappings.has("blocks")) {
                    throw new IOException("mappings.json hat ein unbekanntes Format");
                }
            } catch (RuntimeException e) {
                throw new IOException("mappings.json ist ungueltig", e);
            }
        }

        // Everything is downloaded and verified - only now touch the files in use.
        if (pack != null) {
            Files.createDirectories(packsFolder());
            // A new file name per version: sessions that are still downloading keep the old file.
            Path target = packsFolder().resolve("pack-" + remotePackSha1.substring(0, 12) + ".mcpack");
            write(target, pack);
            packFile = target;
            packSha1 = remotePackSha1;
        }
        if (mappings != null) {
            Files.createDirectories(mappingsFolder);
            write(mappingsFolder.resolve(ITEM_MAPPINGS), GSON.toJson(mappings.get("items")).getBytes(StandardCharsets.UTF_8));
            write(mappingsFolder.resolve(BLOCK_MAPPINGS), GSON.toJson(mappings.get("blocks")).getBytes(StandardCharsets.UTF_8));
            mappingsSha1 = remoteMappingsSha1;
        }
        version = remoteVersion;

        JsonObject state = new JsonObject();
        state.addProperty("version", version);
        state.addProperty("pack_sha1", packSha1);
        state.addProperty("pack_file", packFile.getFileName().toString());
        state.addProperty("mappings_sha1", mappingsSha1);
        Files.createDirectories(dataFolder);
        write(dataFolder.resolve("state.json"), GSON.toJson(state).getBytes(StandardCharsets.UTF_8));
        return new Result(pack != null, mappings != null, remoteVersion);
    }

    private boolean mappingFilesPresent() {
        return Files.isRegularFile(mappingsFolder.resolve(ITEM_MAPPINGS))
            && Files.isRegularFile(mappingsFolder.resolve(BLOCK_MAPPINGS));
    }

    /** Removes packs of earlier versions. Only safe while no player is downloading (i.e. at startup). */
    public void deleteOldPacks() {
        Path current = packFile;
        if (current == null || !Files.isDirectory(packsFolder())) {
            return;
        }
        try (Stream<Path> files = Files.list(packsFolder())) {
            files.filter(file -> !file.equals(current)).forEach(file -> {
                try {
                    Files.deleteIfExists(file);
                } catch (IOException ignored) {
                    // try again on the next start
                }
            });
        } catch (IOException ignored) {
            // not critical
        }
    }

    private byte[] get(String path, Duration timeout) throws IOException, InterruptedException {
        URI uri = URI.create(baseUrl + path + "?token=" + URLEncoder.encode(token, StandardCharsets.UTF_8));
        HttpRequest request = HttpRequest.newBuilder(uri).timeout(timeout).GET().build();
        HttpResponse<byte[]> response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
        if (response.statusCode() == 403) {
            throw new IOException("Hub lehnt das Token ab (403) - token in der config.yml pruefen");
        }
        if (response.statusCode() == 503) {
            throw new IOException("Hub hat noch kein Paket gebaut (503)");
        }
        if (response.statusCode() != 200) {
            throw new IOException("Hub antwortet mit HTTP " + response.statusCode() + " auf " + path);
        }
        return response.body();
    }

    private static void write(Path target, byte[] data) throws IOException {
        Path temp = target.resolveSibling(target.getFileName() + ".tmp");
        Files.write(temp, data);
        try {
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static String sha1(byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(data));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
