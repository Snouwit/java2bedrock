package net.snouli.bedrockbridge.core;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Resolves Java models (parent chains, texture variables) and ItemsAdder's obfuscated sprite ids. */
public final class Models {

    private final JavaPack pack;
    /** Atlas sprite id (e.g. {@code ia:43}) to texture resource (e.g. {@code tutorial:space_sword}). */
    private final Map<String, String> sprites = new HashMap<>();

    public Models(JavaPack pack) {
        this.pack = pack;
        for (String path : pack.list("assets/minecraft/atlases/")) {
            JsonObject atlas = pack.json(path);
            if (atlas == null || !(atlas.get("sources") instanceof JsonArray sources)) {
                continue;
            }
            for (JsonElement el : sources) {
                if (!(el instanceof JsonObject source) || !source.has("resource")) {
                    continue;
                }
                String type = source.has("type") ? source.get("type").getAsString() : "";
                if (!type.equals("single") && !type.equals("minecraft:single")) {
                    continue;
                }
                String resource = Id.parse(source.get("resource").getAsString()).toString();
                String sprite = source.has("sprite") ? Id.parse(source.get("sprite").getAsString()).toString() : resource;
                sprites.put(sprite, resource);
            }
        }
    }

    /** A model with its parent chain flattened. */
    public static final class Resolved {
        public final Id id;
        public final Map<String, String> textures = new LinkedHashMap<>();
        /** Parent ids from nearest to farthest, including parents that are not in the pack (vanilla). */
        public final List<Id> parents = new ArrayList<>();
        /** The elements of the nearest model in the chain that defines any, or {@code null}. */
        public JsonArray elements;
        /** False if the model itself is not part of the pack (i.e. it is a vanilla model). */
        public boolean inPack;

        Resolved(Id id) {
            this.id = id;
        }

        public boolean hasParent(String... candidates) {
            for (Id parent : parents) {
                for (String candidate : candidates) {
                    if (parent.toString().equals(Id.parse(candidate).toString())) {
                        return true;
                    }
                }
            }
            return false;
        }
    }

    public static String modelPath(Id id) {
        return "assets/" + id.namespace() + "/models/" + id.path() + ".json";
    }

    public Resolved resolve(Id id) {
        Resolved resolved = new Resolved(id);
        Id current = id;
        for (int depth = 0; depth < 32 && current != null; depth++) {
            JsonObject model = pack.json(modelPath(current));
            if (model == null) {
                break;
            }
            if (depth == 0) {
                resolved.inPack = true;
            }
            if (model.get("textures") instanceof JsonObject textures) {
                for (Map.Entry<String, JsonElement> entry : textures.entrySet()) {
                    if (entry.getValue().isJsonPrimitive()) {
                        resolved.textures.putIfAbsent(entry.getKey(), entry.getValue().getAsString());
                    }
                }
            }
            if (resolved.elements == null && model.get("elements") instanceof JsonArray elements && !elements.isEmpty()) {
                resolved.elements = elements;
            }
            current = model.has("parent") && model.get("parent").isJsonPrimitive()
                ? Id.parse(model.get("parent").getAsString()) : null;
            if (current != null) {
                resolved.parents.add(current);
            }
        }
        return resolved;
    }

    /**
     * Follows {@code #variable} references and sprite ids.
     *
     * @param variable a texture variable name, with or without the leading {@code #}
     * @return the texture resource id, or {@code null} if the variable is not defined
     */
    public Id texture(Resolved model, String variable) {
        String value = model.textures.get(variable.startsWith("#") ? variable.substring(1) : variable);
        for (int i = 0; i < 16 && value != null && value.startsWith("#"); i++) {
            value = model.textures.get(value.substring(1));
        }
        if (value == null || value.startsWith("#")) {
            return null;
        }
        return spriteToResource(value);
    }

    private Id spriteToResource(String value) {
        String normalized = Id.parse(value).toString();
        return Id.parse(sprites.getOrDefault(normalized, normalized));
    }

    public static String texturePath(Id texture) {
        return "assets/" + texture.namespace() + "/textures/" + texture.path() + ".png";
    }

    public boolean textureExists(Id texture) {
        return pack.exists(texturePath(texture));
    }

    /**
     * Loads a texture from the pack. Animated textures (frames stacked vertically, with a
     * {@code .png.mcmeta}) are cropped to their first frame.
     */
    public BufferedImage loadTexture(Id texture) throws IOException {
        BufferedImage image = pack.image(texturePath(texture));
        if (image == null) {
            return null;
        }
        if (isAnimated(texture) && image.getHeight() > image.getWidth()) {
            image = image.getSubimage(0, 0, image.getWidth(), image.getWidth());
        }
        return Images.toArgb(image);
    }

    public boolean isAnimated(Id texture) {
        return pack.exists(texturePath(texture) + ".mcmeta");
    }
}
