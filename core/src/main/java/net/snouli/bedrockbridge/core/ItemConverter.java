package net.snouli.bedrockbridge.core;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Converts custom items with a flat (2D) texture into Bedrock item icons plus Geyser item
 * mappings (format version 2).
 *
 * <p>Items are found in two ways: by custom model data in the pack's item definitions
 * ({@code assets/minecraft/items/*.json}, or legacy {@code overrides} in item models), and by the
 * {@code minecraft:item_model} component reported by the ItemsAdder API.
 */
final class ItemConverter {

    private static final String[] FLAT_PARENTS = {
        "minecraft:item/generated", "minecraft:item/handheld", "minecraft:item/handheld_rod",
        "minecraft:item/handheld_mace", "minecraft:builtin/generated"
    };
    private static final String[] HANDHELD_PARENTS = {
        "minecraft:item/handheld", "minecraft:item/handheld_rod", "minecraft:item/handheld_mace"
    };

    private final JavaPack pack;
    private final Models models;
    private final BuildInput input;
    private final Report report;

    private final Map<String, IaItem> byCustomModelData = new HashMap<>();
    private final Set<String> usedIdentifiers = new HashSet<>();
    private final TreeMap<String, String> textureData = new TreeMap<>();
    /** vanilla item id to its Geyser definitions */
    private final TreeMap<String, JsonArray> definitions = new TreeMap<>();

    ItemConverter(JavaPack pack, Models models, BuildInput input, Report report) {
        this.pack = pack;
        this.models = models;
        this.input = input;
        this.report = report;
        for (IaItem item : input.items()) {
            if (item.customModelData() != null && item.material() != null) {
                byCustomModelData.putIfAbsent(Id.parse(item.material()) + "#" + item.customModelData(), item);
            }
        }
    }

    private record Candidate(Id vanillaItem, Integer customModelData, Id itemModel, Id model, String problem) {}

    /** @return the {@code items} object of a Geyser mappings file */
    JsonObject convert(BedrockPack out) throws IOException {
        List<Candidate> candidates = new ArrayList<>();
        Set<String> seen = new HashSet<>();

        // 1.21.4+ item definitions
        for (String path : pack.list("assets/minecraft/items/")) {
            if (!path.endsWith(".json") || path.indexOf('/', "assets/minecraft/items/".length()) >= 0) {
                continue;
            }
            Id vanilla = new Id("minecraft", path.substring("assets/minecraft/items/".length(), path.length() - 5));
            JsonObject definition = pack.json(path);
            JsonObject dispatch = definition == null ? null : findCustomModelDataDispatch(definition.get("model"), 0);
            if (dispatch == null || !(dispatch.get("entries") instanceof JsonArray entries)) {
                continue;
            }
            for (JsonElement el : entries) {
                if (!(el instanceof JsonObject entry) || !entry.has("threshold")) {
                    continue;
                }
                double threshold = entry.get("threshold").getAsDouble();
                if (threshold != Math.rint(threshold) || !seen.add(vanilla + "#" + (int) threshold)) {
                    continue;
                }
                String[] problem = new String[1];
                Id model = primaryModel(entry.get("model"), problem, 0);
                candidates.add(new Candidate(vanilla, (int) threshold, null, model, problem[0]));
            }
        }

        // Pre-1.21.4 overrides, for vanilla items that have no item definition
        for (String path : pack.list("assets/minecraft/models/item/")) {
            if (!path.endsWith(".json") || path.indexOf('/', "assets/minecraft/models/item/".length()) >= 0) {
                continue;
            }
            Id vanilla = new Id("minecraft", path.substring("assets/minecraft/models/item/".length(), path.length() - 5));
            JsonObject model = pack.json(path);
            if (model == null || !(model.get("overrides") instanceof JsonArray overrides)) {
                continue;
            }
            for (JsonElement el : overrides) {
                if (!(el instanceof JsonObject override) || !(override.get("predicate") instanceof JsonObject predicate)
                    || predicate.size() != 1 || !predicate.has("custom_model_data") || !override.has("model")) {
                    continue;
                }
                int customModelData = predicate.get("custom_model_data").getAsInt();
                if (seen.add(vanilla + "#" + customModelData)) {
                    candidates.add(new Candidate(vanilla, customModelData, null,
                        Id.parse(override.get("model").getAsString()), null));
                }
            }
        }

        // Items that use the item_model component (only known through the ItemsAdder API)
        Map<String, IaItem> byItemModel = new LinkedHashMap<>();
        for (IaItem item : input.items()) {
            if (item.itemModel() == null || item.material() == null) {
                continue;
            }
            Id itemModel = Id.parse(item.itemModel());
            Id vanilla = Id.parse(item.material());
            if (itemModel.namespace().equals("minecraft") || !seen.add(vanilla + "@" + itemModel)) {
                continue;
            }
            byItemModel.put(vanilla + "@" + itemModel, item);
            JsonObject definition = pack.json("assets/" + itemModel.namespace() + "/items/" + itemModel.path() + ".json");
            String[] problem = new String[1];
            Id model = definition == null ? null : primaryModel(definition.get("model"), problem, 0);
            if (definition == null) {
                problem[0] = "Item-Definition " + itemModel + " fehlt im Paket";
            }
            candidates.add(new Candidate(vanilla, null, itemModel, model, problem[0]));
        }

        for (Candidate candidate : candidates) {
            IaItem ia = candidate.itemModel() != null
                ? byItemModel.get(candidate.vanillaItem() + "@" + candidate.itemModel())
                : byCustomModelData.get(candidate.vanillaItem() + "#" + candidate.customModelData());
            convert(out, candidate, ia);
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
            atlas.addProperty("texture_name", "atlas.items");
            atlas.add("texture_data", data);
            out.putJson("textures/item_texture.json", atlas);
        }

        JsonObject items = new JsonObject();
        for (Map.Entry<String, JsonArray> entry : definitions.entrySet()) {
            items.add(entry.getKey(), entry.getValue());
        }
        return items;
    }

    private void convert(BedrockPack out, Candidate candidate, IaItem ia) throws IOException {
        String source = candidate.itemModel() != null
            ? candidate.vanillaItem().path() + " item_model=" + candidate.itemModel()
            : candidate.vanillaItem().path() + " CMD " + candidate.customModelData();
        String label = ia != null ? ia.namespacedId() + " (" + source + ")"
            : candidate.model() != null ? candidate.model() + " (" + source + ")" : source;

        if (candidate.model() == null) {
            report.skipped(Report.Kind.ITEM, label, candidate.problem() != null ? candidate.problem() : "kein Modell gefunden");
            return;
        }
        Id model = candidate.model();
        String namespace = ia != null ? Id.parse(ia.namespacedId()).namespace() : model.namespace();
        if (input.skipNamespaces().contains(namespace) || input.skipNamespaces().contains(model.namespace())) {
            report.skipped(Report.Kind.ITEM, label, "Namespace in skip-namespaces");
            return;
        }

        Models.Resolved resolved = models.resolve(model);
        if (!resolved.inPack) {
            if (model.namespace().equals("minecraft")) {
                // ItemsAdder items that keep the vanilla look need no mapping
                return;
            }
            report.skipped(Report.Kind.ITEM, label, "Modell " + model + " fehlt im Paket");
            return;
        }
        if (ia != null && ia.furniture()) {
            report.skipped(Report.Kind.ITEM, label, "Moebel - kommt in Phase 2");
            return;
        }

        List<Id> layers = new ArrayList<>();
        boolean handheld = false;
        String kind;
        if (resolved.elements != null) {
            Map<String, Id> faces = BlockConverter.cubeFaces(models, resolved);
            if (faces == null) {
                report.skipped(Report.Kind.ITEM, label, "3D-Modell (" + resolved.elements.size()
                    + " Elemente) - 3D-Items/Moebel kommen spaeter");
                return;
            }
            layers.add(faces.getOrDefault("north", faces.values().iterator().next()));
            kind = "Block-Item als flaches Icon";
        } else if (resolved.hasParent(FLAT_PARENTS)) {
            for (int i = 0; i < 8; i++) {
                Id layer = models.texture(resolved, "layer" + i);
                if (layer == null) {
                    break;
                }
                layers.add(layer);
            }
            handheld = resolved.hasParent(HANDHELD_PARENTS);
            kind = "2D";
        } else {
            Map<String, Id> faces = BlockConverter.cubeFaces(models, resolved);
            if (faces == null) {
                report.skipped(Report.Kind.ITEM, label, "unbekannter Modelltyp (parent "
                    + (resolved.parents.isEmpty() ? "-" : resolved.parents.get(resolved.parents.size() - 1)) + ")");
                return;
            }
            layers.add(faces.getOrDefault("north", faces.values().iterator().next()));
            kind = "Block-Item als flaches Icon";
        }
        if (layers.isEmpty()) {
            report.skipped(Report.Kind.ITEM, label, "Modell hat keine Textur (layer0)");
            return;
        }

        // Only textures that ship in the ItemsAdder pack are used - never vanilla ones.
        List<BufferedImage> images = new ArrayList<>();
        boolean animated = false;
        for (Id layer : layers) {
            BufferedImage image = models.loadTexture(layer);
            if (image == null) {
                if (layer.namespace().equals("minecraft") && model.namespace().equals("minecraft")) {
                    return; // re-declared vanilla model with a vanilla texture
                }
                report.skipped(Report.Kind.ITEM, label, "Textur " + layer + " ist nicht im ItemsAdder-Paket"
                    + (layer.namespace().equals("minecraft") ? " (Vanilla-Textur, wird nicht kopiert)" : ""));
                return;
            }
            animated |= models.isAnimated(layer);
            images.add(image);
        }
        BufferedImage icon = images.get(0);
        if (images.size() > 1) {
            int width = images.stream().mapToInt(BufferedImage::getWidth).max().orElse(16);
            int height = images.stream().mapToInt(BufferedImage::getHeight).max().orElse(16);
            icon = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = icon.createGraphics();
            for (BufferedImage image : images) {
                g.drawImage(Images.scale(image, width, height), 0, 0, null);
            }
            g.dispose();
        }

        Id identity = ia != null ? Id.parse(ia.namespacedId()) : new Id(model.namespace(), model.name());
        String bedrockNamespace = Id.sanitize(identity.namespace());
        if (bedrockNamespace.equals("minecraft")) {
            bedrockNamespace = "snouli";
        }
        String bedrockPath = Id.sanitize(identity.path());
        String identifier = bedrockNamespace + ":" + bedrockPath;
        if (!usedIdentifiers.add(identifier)) {
            String suffix = candidate.customModelData() != null ? "_" + candidate.customModelData() : "";
            identifier = bedrockNamespace + ":" + bedrockPath + "_" + candidate.vanillaItem().path() + suffix;
            for (int i = 2; !usedIdentifiers.add(identifier); i++) {
                identifier = bedrockNamespace + ":" + bedrockPath + "_" + candidate.vanillaItem().path() + suffix + "_" + i;
            }
        }
        String iconName = identifier.replace(':', '_');
        String texturePath = "textures/items/" + identifier.replace(':', '/');
        out.put(texturePath + ".png", Images.png(icon));
        textureData.put(iconName, texturePath);

        JsonObject options = new JsonObject();
        options.addProperty("icon", iconName);
        options.addProperty("allow_offhand", true);
        if (handheld) {
            options.addProperty("display_handheld", true);
        }

        JsonObject definition = new JsonObject();
        if (candidate.itemModel() != null) {
            definition.addProperty("type", "definition");
            definition.addProperty("model", candidate.itemModel().toString());
        } else {
            definition.addProperty("type", "legacy");
            definition.addProperty("custom_model_data", candidate.customModelData());
        }
        definition.addProperty("bedrock_identifier", identifier);
        definition.addProperty("display_name", displayName(ia, identity));
        definition.add("bedrock_options", options);
        definitions.computeIfAbsent(candidate.vanillaItem().toString(), k -> new JsonArray()).add(definition);

        report.converted(Report.Kind.ITEM, label, identifier + " [" + kind + "]");
        if (images.size() > 1) {
            report.warning(Report.Kind.ITEM, label, images.size() + " Textur-Ebenen wurden zu einem Icon zusammengelegt"
                + " (Bedrock-Icons haben eine Ebene; einfaerbbare Ebenen bleiben ungefaerbt).");
        }
        if (animated) {
            report.warning(Report.Kind.ITEM, label, "animierte Textur: nur das erste Bild wird verwendet.");
        }
    }

    private static String displayName(IaItem ia, Id identity) {
        if (ia != null && ia.displayName() != null && !ia.displayName().isBlank()) {
            return ia.displayName();
        }
        StringBuilder name = new StringBuilder();
        for (String word : identity.name().split("_")) {
            if (!word.isEmpty()) {
                name.append(name.length() > 0 ? " " : "").append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
            }
        }
        return name.toString();
    }

    private static String type(JsonObject node) {
        return node.has("type") && node.get("type").isJsonPrimitive() ? Id.parse(node.get("type").getAsString()).path() : "";
    }

    /** Finds the {@code range_dispatch} on {@code custom_model_data} (index 0) in an item definition tree. */
    private static JsonObject findCustomModelDataDispatch(JsonElement element, int depth) {
        if (!(element instanceof JsonObject node) || depth > 16) {
            return null;
        }
        if (type(node).equals("range_dispatch") && node.has("property")
            && Id.parse(node.get("property").getAsString()).path().equals("custom_model_data")
            && (!node.has("index") || node.get("index").getAsInt() == 0)) {
            return node;
        }
        for (JsonElement child : children(node)) {
            JsonObject found = findCustomModelDataDispatch(child, depth + 1);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    private static List<JsonElement> children(JsonObject node) {
        List<JsonElement> children = new ArrayList<>();
        for (String key : new String[] {"fallback", "on_false", "on_true"}) {
            if (node.get(key) instanceof JsonObject child) {
                children.add(child);
            }
        }
        for (String key : new String[] {"entries", "cases"}) {
            if (node.get(key) instanceof JsonArray array) {
                for (JsonElement el : array) {
                    if (el instanceof JsonObject entry && entry.get("model") instanceof JsonObject child) {
                        children.add(child);
                    }
                }
            }
        }
        if (node.get("models") instanceof JsonArray array) {
            array.forEach(children::add);
        }
        return children;
    }

    /** Reduces an item model node (which may branch on trims, conditions, ...) to its default model. */
    private static Id primaryModel(JsonElement element, String[] problem, int depth) {
        if (!(element instanceof JsonObject node) || depth > 16) {
            return null;
        }
        String type = type(node);
        if (type.equals("special")) {
            problem[0] = "Spezial-Modell (z. B. Kopf/Banner) - nicht als 2D-Item darstellbar";
            return null;
        }
        if (node.has("model") && node.get("model").isJsonPrimitive()) {
            return Id.parse(node.get("model").getAsString());
        }
        for (JsonElement child : children(node)) {
            Id model = primaryModel(child, problem, depth + 1);
            if (model != null) {
                return model;
            }
        }
        if (problem[0] == null) {
            problem[0] = "Item-Modell vom Typ '" + type + "' wird nicht unterstuetzt";
        }
        return null;
    }
}
