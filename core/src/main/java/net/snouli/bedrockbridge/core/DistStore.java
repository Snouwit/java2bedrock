package net.snouli.bedrockbridge.core;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

/**
 * Keeps the published files on disk ({@code pack.mcpack}, {@code mappings.json},
 * {@code version.json}) and bumps the version only when the content really changed.
 */
public final class DistStore {

    public record Result(Published published, boolean packChanged, boolean mappingsChanged) {
        public boolean changed() {
            return packChanged || mappingsChanged;
        }
    }

    private final Path directory;

    public DistStore(Path directory) {
        this.directory = directory;
    }

    /** @return the last published version from disk, or {@code null} if there is none (or it is damaged) */
    public Published load() {
        try {
            Path versionFile = directory.resolve("version.json");
            Path packFile = directory.resolve("pack.mcpack");
            Path mappingsFile = directory.resolve("mappings.json");
            if (!Files.isRegularFile(versionFile) || !Files.isRegularFile(packFile) || !Files.isRegularFile(mappingsFile)) {
                return null;
            }
            byte[] versionJson = Files.readAllBytes(versionFile);
            byte[] pack = Files.readAllBytes(packFile);
            byte[] mappings = Files.readAllBytes(mappingsFile);
            JsonObject version = JsonParser.parseString(new String(versionJson, StandardCharsets.UTF_8)).getAsJsonObject();
            JsonObject packInfo = version.getAsJsonObject("pack");
            String packSha1 = Hashes.sha1(pack);
            String mappingsSha1 = Hashes.sha1(mappings);
            if (!packSha1.equals(packInfo.get("sha1").getAsString())
                || !mappingsSha1.equals(version.getAsJsonObject("mappings").get("sha1").getAsString())) {
                return null;
            }
            JsonArray packVersion = packInfo.getAsJsonArray("version");
            JsonObject counts = version.getAsJsonObject("counts");
            return new Published(version.get("version").getAsInt(), version.get("built_at").getAsString(),
                new int[] {packVersion.get(0).getAsInt(), packVersion.get(1).getAsInt(), packVersion.get(2).getAsInt()},
                pack, packSha1, mappings, mappingsSha1, versionJson,
                counts.get("glyphs").getAsInt(), counts.get("items").getAsInt(), counts.get("blocks").getAsInt(),
                counts.has("models") ? counts.get("models").getAsInt() : 0);
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    public Result publish(BuildOutput output, String packName, Published previous) throws IOException {
        Files.createDirectories(directory);
        JsonObject state = readState();

        UUID headerUuid = uuid(state, "pack_uuid");
        UUID moduleUuid = uuid(state, "module_uuid");
        String contentHash = Hashes.sha1((output.pack().contentHash() + "\n" + packName).getBytes(StandardCharsets.UTF_8));
        byte[] mappings = BedrockPack.GSON.toJson(output.mappings()).getBytes(StandardCharsets.UTF_8);
        String mappingsSha1 = Hashes.sha1(mappings);

        boolean packChanged = previous == null || !state.has("content_hash")
            || !contentHash.equals(state.get("content_hash").getAsString());
        boolean mappingsChanged = previous == null || !mappingsSha1.equals(previous.mappingsSha1());
        if (!packChanged && !mappingsChanged) {
            return new Result(previous, false, false);
        }

        int version = (previous == null ? 0 : previous.version()) + 1;
        if (state.has("version")) {
            version = Math.max(version, state.get("version").getAsInt() + 1);
        }
        int[] packVersion;
        byte[] pack;
        if (packChanged) {
            // Bedrock caches packs by UUID + version, so every content change needs a new version.
            packVersion = new int[] {1, 0, version};
            String description = "ItemsAdder-Inhalte fuer Bedrock (Build " + version + ")";
            pack = output.pack().toMcpack(BedrockPack.manifest(packName, description, headerUuid, moduleUuid, packVersion));
        } else {
            packVersion = previous.packVersion();
            pack = previous.pack();
        }
        String packSha1 = Hashes.sha1(pack);
        String builtAt = Instant.now().truncatedTo(ChronoUnit.SECONDS).toString();

        Report report = output.report();
        JsonObject versionJson = new JsonObject();
        versionJson.addProperty("version", version);
        versionJson.addProperty("built_at", builtAt);
        JsonObject packInfo = new JsonObject();
        packInfo.addProperty("sha1", packSha1);
        packInfo.addProperty("size", pack.length);
        packInfo.addProperty("uuid", headerUuid.toString());
        JsonArray versionArray = new JsonArray();
        for (int part : packVersion) {
            versionArray.add(part);
        }
        packInfo.add("version", versionArray);
        versionJson.add("pack", packInfo);
        JsonObject mappingsInfo = new JsonObject();
        mappingsInfo.addProperty("sha1", mappingsSha1);
        mappingsInfo.addProperty("size", mappings.length);
        versionJson.add("mappings", mappingsInfo);
        JsonObject counts = new JsonObject();
        counts.addProperty("glyphs", report.convertedCount(Report.Kind.GLYPH));
        counts.addProperty("items", report.convertedCount(Report.Kind.ITEM));
        counts.addProperty("blocks", report.convertedCount(Report.Kind.BLOCK));
        counts.addProperty("models", report.convertedCount(Report.Kind.MODEL));
        versionJson.add("counts", counts);
        byte[] versionBytes = BedrockPack.GSON.toJson(versionJson).getBytes(StandardCharsets.UTF_8);

        state.addProperty("pack_uuid", headerUuid.toString());
        state.addProperty("module_uuid", moduleUuid.toString());
        state.addProperty("content_hash", contentHash);
        state.addProperty("version", version);

        write("pack.mcpack", pack);
        write("mappings.json", mappings);
        write("version.json", versionBytes);
        write("state.json", BedrockPack.GSON.toJson(state).getBytes(StandardCharsets.UTF_8));

        Published published = new Published(version, builtAt, packVersion, pack, packSha1, mappings, mappingsSha1,
            versionBytes, counts.get("glyphs").getAsInt(), counts.get("items").getAsInt(), counts.get("blocks").getAsInt(),
            counts.get("models").getAsInt());
        return new Result(published, packChanged, mappingsChanged);
    }

    private JsonObject readState() {
        try {
            Path file = directory.resolve("state.json");
            if (Files.isRegularFile(file)) {
                return JsonParser.parseString(Files.readString(file)).getAsJsonObject();
            }
        } catch (IOException | RuntimeException ignored) {
            // start over with fresh UUIDs
        }
        return new JsonObject();
    }

    private static UUID uuid(JsonObject state, String key) {
        try {
            if (state.has(key)) {
                return UUID.fromString(state.get(key).getAsString());
            }
        } catch (RuntimeException ignored) {
            // fall through
        }
        return UUID.randomUUID();
    }

    private void write(String name, byte[] data) throws IOException {
        Path target = directory.resolve(name);
        Path temp = directory.resolve(name + ".tmp");
        Files.write(temp, data);
        try {
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
