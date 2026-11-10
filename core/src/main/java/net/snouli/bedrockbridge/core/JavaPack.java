package net.snouli.bedrockbridge.core;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import javax.imageio.ImageIO;

/**
 * Read-only view of a Java resource pack zip (ItemsAdder's {@code generated.zip}).
 *
 * <p>Resolves pack overlays the way the newest supported client would: a file in an applicable
 * overlay directory replaces the file with the same path in the base pack.
 *
 * <p>ItemsAdder's "protected" zips carry bogus sizes/CRCs in their headers. {@link ZipFile} reads
 * from the central directory and does not verify CRCs, so those archives still open here.
 */
public final class JavaPack implements Closeable {

    private final ZipFile zip;
    /** Directory prefixes in lookup order, highest priority first; the base pack is {@code ""}. */
    private final List<String> layers;
    private final TreeSet<String> names = new TreeSet<>();
    private final Map<String, JsonObject> jsonCache = new HashMap<>();
    private final int targetFormat;

    private JavaPack(ZipFile zip) throws IOException {
        this.zip = zip;
        for (Enumeration<? extends ZipEntry> e = zip.entries(); e.hasMoreElements(); ) {
            ZipEntry entry = e.nextElement();
            if (!entry.isDirectory()) {
                names.add(entry.getName());
            }
        }
        if (!names.contains("pack.mcmeta")) {
            throw new IOException("pack.mcmeta fehlt - das ist kein Java-Ressourcenpaket");
        }

        List<Overlay> overlays = new ArrayList<>();
        int target = 0;
        try {
            JsonObject meta = JsonParser.parseString(new String(readRaw("pack.mcmeta"), StandardCharsets.UTF_8)).getAsJsonObject();
            if (meta.get("pack") instanceof JsonObject pack) {
                target = Math.max(firstInt(pack.get("pack_format"), 0), firstInt(pack.get("min_format"), 0));
            }
            if (meta.get("overlays") instanceof JsonObject o && o.get("entries") instanceof JsonArray entries) {
                for (JsonElement el : entries) {
                    if (!(el instanceof JsonObject entry) || !entry.has("directory")) {
                        continue;
                    }
                    int[] range = formatRange(entry);
                    overlays.add(new Overlay(entry.get("directory").getAsString(), range[0], range[1]));
                    target = Math.max(target, range[0]);
                }
            }
        } catch (RuntimeException e) {
            throw new IOException("pack.mcmeta ist ungueltig: " + e.getMessage(), e);
        }
        this.targetFormat = target;

        List<String> order = new ArrayList<>();
        // Later overlay entries win over earlier ones.
        for (int i = overlays.size() - 1; i >= 0; i--) {
            Overlay overlay = overlays.get(i);
            if (overlay.min <= target && target <= overlay.max) {
                order.add(overlay.directory + "/");
            }
        }
        order.add("");
        this.layers = Collections.unmodifiableList(order);
    }

    public static JavaPack open(Path zipFile) throws IOException {
        ZipFile zip = new ZipFile(zipFile.toFile());
        try {
            return new JavaPack(zip);
        } catch (IOException | RuntimeException e) {
            zip.close();
            throw e;
        }
    }

    private record Overlay(String directory, int min, int max) {}

    private static int firstInt(JsonElement element, int fallback) {
        if (element == null) {
            return fallback;
        }
        if (element.isJsonArray() && !element.getAsJsonArray().isEmpty()) {
            return element.getAsJsonArray().get(0).getAsInt();
        }
        return element.isJsonPrimitive() ? element.getAsInt() : fallback;
    }

    private static int[] formatRange(JsonObject entry) {
        int min = 0;
        int max = Integer.MAX_VALUE;
        JsonElement formats = entry.get("formats");
        if (formats != null) {
            if (formats.isJsonArray() && formats.getAsJsonArray().size() >= 2) {
                min = formats.getAsJsonArray().get(0).getAsInt();
                max = formats.getAsJsonArray().get(1).getAsInt();
            } else if (formats.isJsonObject()) {
                min = firstInt(formats.getAsJsonObject().get("min_inclusive"), min);
                max = firstInt(formats.getAsJsonObject().get("max_inclusive"), max);
            } else if (formats.isJsonPrimitive()) {
                min = max = formats.getAsInt();
            }
        }
        // The newer min_format/max_format fields take precedence over the legacy "formats".
        if (entry.has("min_format")) {
            min = firstInt(entry.get("min_format"), min);
        }
        if (entry.has("max_format")) {
            max = firstInt(entry.get("max_format"), max);
        }
        return new int[] {min, max};
    }

    public int targetFormat() {
        return targetFormat;
    }

    public List<String> layers() {
        return layers;
    }

    private byte[] readRaw(String name) throws IOException {
        ZipEntry entry = zip.getEntry(name);
        if (entry == null) {
            return null;
        }
        try (InputStream in = zip.getInputStream(entry)) {
            return in.readAllBytes();
        }
    }

    /** @param path pack-relative path such as {@code assets/minecraft/font/default.json} */
    public boolean exists(String path) {
        for (String layer : layers) {
            if (names.contains(layer + path)) {
                return true;
            }
        }
        return false;
    }

    /** @return the file contents, or {@code null} if no layer has the file */
    public byte[] read(String path) throws IOException {
        for (String layer : layers) {
            if (names.contains(layer + path)) {
                return readRaw(layer + path);
            }
        }
        return null;
    }

    /** @return the parsed JSON object, or {@code null} if missing or not valid JSON */
    public JsonObject json(String path) {
        if (jsonCache.containsKey(path)) {
            return jsonCache.get(path);
        }
        JsonObject result = null;
        try {
            byte[] data = read(path);
            if (data != null) {
                JsonElement parsed = JsonParser.parseString(new String(data, StandardCharsets.UTF_8));
                if (parsed.isJsonObject()) {
                    result = parsed.getAsJsonObject();
                }
            }
        } catch (IOException | RuntimeException ignored) {
            // treated as missing; callers report the item/block as not convertible
        }
        jsonCache.put(path, result);
        return result;
    }

    public BufferedImage image(String path) throws IOException {
        byte[] data = read(path);
        if (data == null) {
            return null;
        }
        try {
            return LenientPng.decode(data);
        } catch (IOException lenientFailure) {
            // e.g. interlaced files: fall back to the strict JDK decoder
            try {
                BufferedImage image = ImageIO.read(new ByteArrayInputStream(data));
                if (image != null) {
                    return image;
                }
            } catch (IOException ignored) {
                // report the first failure below
            }
            throw new IOException(path + ": " + lenientFailure.getMessage(), lenientFailure);
        }
    }

    /** Lists pack-relative paths below {@code prefix} across all layers (deduplicated, sorted). */
    public List<String> list(String prefix) {
        TreeSet<String> result = new TreeSet<>();
        for (String layer : layers) {
            String full = layer + prefix;
            for (String name : names.tailSet(full)) {
                if (!name.startsWith(full)) {
                    break;
                }
                result.add(name.substring(layer.length()));
            }
        }
        return new ArrayList<>(result);
    }

    /** All namespaces that have an {@code assets/<namespace>/} directory in any layer. */
    public List<String> namespaces() {
        TreeSet<String> result = new TreeSet<>();
        for (String path : list("assets/")) {
            int end = path.indexOf('/', "assets/".length());
            if (end > 0) {
                result.add(path.substring("assets/".length(), end));
            }
        }
        return new ArrayList<>(result);
    }

    @Override
    public void close() throws IOException {
        zip.close();
    }
}
