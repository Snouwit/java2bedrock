package net.snouli.bedrockbridge.core;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** The files of the generated Bedrock resource pack (everything except {@code manifest.json}). */
public final class BedrockPack {

    static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    private final TreeMap<String, byte[]> files = new TreeMap<>();

    public void put(String path, byte[] data) {
        files.put(path, data);
    }

    public void putJson(String path, JsonElement json) {
        files.put(path, GSON.toJson(json).getBytes(StandardCharsets.UTF_8));
    }

    public boolean contains(String path) {
        return files.containsKey(path);
    }

    public Map<String, byte[]> files() {
        return files;
    }

    /** Hash over paths and contents; identical input yields an identical hash across builds. */
    public String contentHash() {
        MessageDigest digest = Hashes.sha1Digest();
        for (Map.Entry<String, byte[]> entry : files.entrySet()) {
            digest.update(entry.getKey().getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
            digest.update(Integer.toString(entry.getValue().length).getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
            digest.update(entry.getValue());
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    public static JsonObject manifest(String name, String description, UUID headerUuid, UUID moduleUuid, int[] version) {
        JsonArray versionArray = new JsonArray();
        for (int part : version) {
            versionArray.add(part);
        }
        JsonArray minEngine = new JsonArray();
        minEngine.add(1);
        minEngine.add(21);
        minEngine.add(0);

        JsonObject header = new JsonObject();
        header.addProperty("name", name);
        header.addProperty("description", description);
        header.addProperty("uuid", headerUuid.toString());
        header.add("version", versionArray);
        header.add("min_engine_version", minEngine);

        JsonObject module = new JsonObject();
        module.addProperty("type", "resources");
        module.addProperty("uuid", moduleUuid.toString());
        module.add("version", versionArray.deepCopy());
        JsonArray modules = new JsonArray();
        modules.add(module);

        JsonObject manifest = new JsonObject();
        manifest.addProperty("format_version", 2);
        manifest.add("header", header);
        manifest.add("modules", modules);
        return manifest;
    }

    /** Writes a deterministic {@code .mcpack} (sorted entries, fixed timestamps). */
    public byte[] toMcpack(JsonObject manifest) throws IOException {
        TreeMap<String, byte[]> all = new TreeMap<>(files);
        all.put("manifest.json", GSON.toJson(manifest).getBytes(StandardCharsets.UTF_8));
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            for (Map.Entry<String, byte[]> entry : all.entrySet()) {
                ZipEntry zipEntry = new ZipEntry(entry.getKey());
                zipEntry.setTime(946684800000L); // 2000-01-01, keeps the archive reproducible
                zip.putNextEntry(zipEntry);
                zip.write(entry.getValue());
                zip.closeEntry();
            }
        }
        return bytes.toByteArray();
    }
}
