package net.snouli.bedrockbridge.core;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Converts ItemsAdder custom blocks (note block, mushroom and chorus plant states with a custom
 * model) into Geyser block mappings (format version 1) with a full cube geometry and one texture
 * per face. Anything that is not a plain cube is skipped and listed in the report.
 */
final class BlockConverter {

    static final String[] FACES = {"up", "down", "north", "south", "west", "east"};

    /** Java block to its complete, alphabetically ordered property list (Geyser needs full states). */
    private static final Map<String, List<String>> SUPPORTED = Map.of(
        "note_block", List.of("instrument", "note", "powered"),
        "brown_mushroom_block", List.of("down", "east", "north", "south", "up", "west"),
        "red_mushroom_block", List.of("down", "east", "north", "south", "up", "west"),
        "mushroom_stem", List.of("down", "east", "north", "south", "up", "west"),
        "chorus_plant", List.of("down", "east", "north", "south", "up", "west"),
        "tripwire", List.of("attached", "disarmed", "east", "north", "powered", "south", "west"));

    private final JavaPack pack;
    private final Models models;
    private final BuildInput input;
    private final Report report;

    private final Map<String, IaItem> byState = new HashMap<>();
    private final TreeMap<String, String> textureData = new TreeMap<>();
    private final Map<String, String> textureNames = new HashMap<>();
    private final Map<String, Boolean> transparent = new HashMap<>();

    BlockConverter(JavaPack pack, Models models, BuildInput input, Report report) {
        this.pack = pack;
        this.models = models;
        this.input = input;
        this.report = report;
        for (IaItem item : input.items()) {
            if (item.blockState() != null) {
                byState.putIfAbsent(normalizeState(item.blockState()), item);
            }
        }
    }

    /** {@code minecraft:note_block[note=1,instrument=harp]} becomes {@code note_block[instrument=harp,note=1]}. */
    static String normalizeState(String state) {
        String s = state.trim();
        int bracket = s.indexOf('[');
        String block = Id.parse(bracket < 0 ? s : s.substring(0, bracket)).path();
        if (bracket < 0) {
            return block + "[]";
        }
        return block + "[" + sortProperties(s.substring(bracket + 1, s.lastIndexOf(']'))) + "]";
    }

    private static String sortProperties(String properties) {
        String[] parts = properties.split(",");
        for (int i = 0; i < parts.length; i++) {
            parts[i] = parts[i].trim();
        }
        Arrays.sort(parts);
        return String.join(",", parts);
    }

    /** @return the {@code blocks} object of a Geyser mappings file */
    JsonObject convert(BedrockPack out) throws IOException {
        TreeMap<String, JsonObject> blocks = new TreeMap<>();
        for (String path : pack.list("assets/minecraft/blockstates/")) {
            if (!path.endsWith(".json")) {
                continue;
            }
            String block = path.substring("assets/minecraft/blockstates/".length(), path.length() - 5);
            JsonObject blockstate = pack.json(path);
            if (blockstate == null || !(blockstate.get("variants") instanceof JsonObject variants)) {
                continue;
            }
            TreeMap<String, JsonObject> overrides = new TreeMap<>();
            for (Map.Entry<String, JsonElement> variant : variants.entrySet()) {
                JsonElement value = variant.getValue();
                if (value instanceof JsonArray array && !array.isEmpty()) {
                    value = array.get(0);
                }
                if (!(value instanceof JsonObject model) || !model.has("model")) {
                    continue;
                }
                JsonObject override = convertVariant(out, block, variant.getKey(), model);
                if (override != null) {
                    overrides.put(sortProperties(variant.getKey()), override);
                }
            }
            if (overrides.isEmpty()) {
                continue;
            }
            JsonObject stateOverrides = new JsonObject();
            overrides.forEach(stateOverrides::add);
            JsonObject entry = new JsonObject();
            entry.addProperty("name", "snouli_" + block);
            entry.addProperty("only_override_states", true);
            entry.addProperty("included_in_creative_inventory", false);
            entry.add("state_overrides", stateOverrides);
            blocks.put("minecraft:" + block, entry);
        }

        if (!textureData.isEmpty()) {
            JsonObject data = new JsonObject();
            for (Map.Entry<String, String> entry : textureData.entrySet()) {
                JsonObject texture = new JsonObject();
                texture.addProperty("textures", entry.getValue());
                data.add(entry.getKey(), texture);
            }
            JsonObject atlas = new JsonObject();
            atlas.addProperty("resource_pack_name", "snouli_bedrock");
            atlas.addProperty("texture_name", "atlas.terrain");
            atlas.addProperty("padding", 8);
            atlas.addProperty("num_mip_levels", 4);
            atlas.add("texture_data", data);
            out.putJson("textures/terrain_texture.json", atlas);
        }

        JsonObject result = new JsonObject();
        blocks.forEach(result::add);
        return result;
    }

    private JsonObject convertVariant(BedrockPack out, String block, String stateKey, JsonObject variant) throws IOException {
        Id model = Id.parse(variant.get("model").getAsString());
        // ItemsAdder points unused states at the original block model
        if (model.path().startsWith("block/original/")) {
            return null;
        }
        Models.Resolved resolved = models.resolve(model);
        if (!resolved.inPack) {
            return null; // vanilla model, nothing custom about this state
        }

        String state = sortProperties(stateKey);
        IaItem ia = byState.get(block + "[" + state + "]");
        String label = (ia != null ? ia.namespacedId() : model.toString()) + " (" + block + "[" + state + "])";
        String namespace = ia != null ? Id.parse(ia.namespacedId()).namespace() : model.namespace();
        if (input.skipNamespaces().contains(namespace) || input.skipNamespaces().contains(model.namespace())) {
            report.skipped(Report.Kind.BLOCK, label, "Namespace in skip-namespaces");
            return null;
        }

        List<String> expected = SUPPORTED.get(block);
        if (expected == null) {
            report.skipped(Report.Kind.BLOCK, label, "Basis-Block " + block + " wird nicht unterstuetzt");
            return null;
        }
        List<String> actual = Arrays.stream(state.split(",")).map(p -> p.split("=")[0]).toList();
        if (!actual.equals(expected)) {
            report.skipped(Report.Kind.BLOCK, label, "unvollstaendiger Block-Zustand (erwartet: " + String.join(",", expected) + ")");
            return null;
        }

        Map<String, Id> faces = cubeFaces(models, resolved);
        if (faces == null) {
            report.skipped(Report.Kind.BLOCK, label, "komplexes Modell (kein einfacher Wuerfel)");
            return null;
        }
        int x = variant.has("x") ? Math.floorMod(variant.get("x").getAsInt(), 360) : 0;
        int y = variant.has("y") ? Math.floorMod(variant.get("y").getAsInt(), 360) : 0;
        if (x % 90 != 0 || y % 90 != 0) {
            report.skipped(Report.Kind.BLOCK, label, "Drehung ist kein Vielfaches von 90 Grad");
            return null;
        }
        faces = rotate(faces, x, y);

        JsonObject instances = new JsonObject();
        boolean uniform = faces.values().stream().distinct().count() == 1;
        Map<String, Id> emit = new LinkedHashMap<>();
        if (uniform) {
            emit.put("*", faces.values().iterator().next());
        } else {
            for (String face : FACES) {
                emit.put(face, faces.get(face));
            }
        }
        for (Map.Entry<String, Id> face : emit.entrySet()) {
            String textureName = texture(out, face.getValue());
            if (textureName == null) {
                report.skipped(Report.Kind.BLOCK, label, "Textur " + face.getValue() + " ist nicht im ItemsAdder-Paket"
                    + (face.getValue().namespace().equals("minecraft") ? " (Vanilla-Textur, wird nicht kopiert)" : ""));
                return null;
            }
            JsonObject instance = new JsonObject();
            instance.addProperty("texture", textureName);
            instance.addProperty("render_method", transparent.get(textureName) ? "alpha_test" : "opaque");
            instances.add(face.getKey(), instance);
        }

        JsonObject override = new JsonObject();
        if (ia != null && ia.displayName() != null && !ia.displayName().isBlank()) {
            override.addProperty("display_name", ia.displayName());
        }
        override.addProperty("unit_cube", true);
        override.add("material_instances", instances);

        report.converted(Report.Kind.BLOCK, label, emit.size() == 1 ? "1 Textur" : "6 Seiten, "
            + faces.values().stream().distinct().count() + " Texturen");
        if (x != 0 || y != 0) {
            report.warning(Report.Kind.BLOCK, label, "gedrehte Variante (x=" + x + ", y=" + y
                + "): Seiten sind zugeordnet, die Textur-Ausrichtung kann auf Bedrock abweichen.");
        }
        return override;
    }

    /** Copies a block texture into the pack (once) and returns its terrain_texture name. */
    private String texture(BedrockPack out, Id texture) throws IOException {
        String key = texture.toString();
        if (textureNames.containsKey(key)) {
            return textureNames.get(key);
        }
        BufferedImage image = models.loadTexture(texture);
        String name = null;
        if (image != null) {
            name = "snouli_" + Id.sanitize(texture.namespace()) + "_" + Id.sanitize(texture.path());
            String path = "textures/blocks/" + Id.sanitize(texture.namespace()) + "/" + Id.sanitize(texture.path());
            out.put(path + ".png", Images.png(image));
            textureData.put(name, path);
            transparent.put(name, Images.hasTransparency(image));
        }
        textureNames.put(key, name);
        return name;
    }

    /**
     * @return the texture of each of the six faces if the model is a plain full cube, else {@code null}
     */
    static Map<String, Id> cubeFaces(Models models, Models.Resolved model) {
        Map<String, String> variables = new LinkedHashMap<>();
        if (model.elements != null) {
            if (model.elements.size() != 1 || !(model.elements.get(0) instanceof JsonObject element)
                || !isBox(element.get("from"), 0) || !isBox(element.get("to"), 16) || element.has("rotation")
                || !(element.get("faces") instanceof JsonObject faces)) {
                return null;
            }
            for (String face : FACES) {
                if (!(faces.get(face) instanceof JsonObject definition) || !definition.has("texture")) {
                    return null;
                }
                if (definition.get("uv") instanceof JsonArray uv && !isFullUv(uv)) {
                    return null;
                }
                if (definition.has("rotation") && definition.get("rotation").getAsInt() != 0) {
                    return null;
                }
                variables.put(face, definition.get("texture").getAsString());
            }
        } else if (model.hasParent("block/cube_all", "block/cube_mirrored_all", "block/leaves")) {
            for (String face : FACES) {
                variables.put(face, "all");
            }
        } else if (model.hasParent("block/cube_column", "block/cube_column_horizontal", "block/cube_column_mirrored")) {
            for (String face : FACES) {
                variables.put(face, face.equals("up") || face.equals("down") ? "end" : "side");
            }
        } else if (model.hasParent("block/cube_bottom_top")) {
            for (String face : FACES) {
                variables.put(face, face.equals("up") ? "top" : face.equals("down") ? "bottom" : "side");
            }
        } else if (model.hasParent("block/cube_top")) {
            for (String face : FACES) {
                variables.put(face, face.equals("up") ? "top" : "side");
            }
        } else if (model.hasParent("block/orientable_with_bottom", "block/orientable")) {
            boolean bottom = model.hasParent("block/orientable_with_bottom");
            for (String face : FACES) {
                variables.put(face, face.equals("up") ? "top" : face.equals("down") ? (bottom ? "bottom" : "top")
                    : face.equals("north") ? "front" : "side");
            }
        } else if (model.hasParent("block/cube", "block/cube_directional", "block/cube_mirrored")) {
            for (String face : FACES) {
                variables.put(face, face);
            }
        } else {
            return null;
        }

        Map<String, Id> result = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : variables.entrySet()) {
            Id texture = models.texture(model, entry.getValue());
            if (texture == null) {
                return null;
            }
            result.put(entry.getKey(), texture);
        }
        return result;
    }

    private static boolean isBox(JsonElement element, double value) {
        if (!(element instanceof JsonArray array) || array.size() != 3) {
            return false;
        }
        for (JsonElement coordinate : array) {
            if (Math.abs(coordinate.getAsDouble() - value) > 0.001) {
                return false;
            }
        }
        return true;
    }

    private static boolean isFullUv(JsonArray uv) {
        return uv.size() == 4 && uv.get(0).getAsDouble() == 0 && uv.get(1).getAsDouble() == 0
            && uv.get(2).getAsDouble() == 16 && uv.get(3).getAsDouble() == 16;
    }

    /** Applies blockstate x/y rotations (multiples of 90) to the face assignment. */
    private static Map<String, Id> rotate(Map<String, Id> faces, int x, int y) {
        Map<String, Id> current = faces;
        for (int i = 0; i < x / 90; i++) {
            // Rotating around the X axis: up -> north -> down -> south -> up
            Map<String, Id> next = new LinkedHashMap<>(current);
            next.put("north", current.get("up"));
            next.put("down", current.get("north"));
            next.put("south", current.get("down"));
            next.put("up", current.get("south"));
            current = next;
        }
        for (int i = 0; i < y / 90; i++) {
            // Rotating around the Y axis (clockwise seen from above): north -> east -> south -> west
            Map<String, Id> next = new LinkedHashMap<>(current);
            next.put("east", current.get("north"));
            next.put("south", current.get("east"));
            next.put("west", current.get("south"));
            next.put("north", current.get("west"));
            current = next;
        }
        return current;
    }
}
