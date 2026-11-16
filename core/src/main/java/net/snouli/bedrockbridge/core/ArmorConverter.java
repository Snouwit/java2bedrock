package net.snouli.bedrockbridge.core;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Makes custom armour visible on Bedrock: every armour piece gets an attachable that draws
 * Bedrock's own armour geometry with the texture from the ItemsAdder pack.
 *
 * <p>ItemsAdder stores the worn texture in one of two ways:
 * <ul>
 *   <li><b>Equipment assets</b> (1.21.2+): the item's {@code equippable} component names an asset,
 *       {@code assets/<ns>/equipment/<name>.json}, which points at the textures in
 *       {@code textures/entity/equipment/humanoid[_leggings]/}.</li>
 *   <li><b>Shader armour</b>: dyed leather armour whose colour selects a 64x32 tile in a strip
 *       that replaces the vanilla leather texture. The first pixel of each tile holds its colour.</li>
 * </ul>
 * Both textures use the same 64x32 layout as Bedrock armour, so they can be used unchanged.
 */
final class ArmorConverter {

    /** Colour of undyed leather: the tile ItemsAdder keeps for vanilla leather armour. */
    private static final int DEFAULT_LEATHER = 0xA06540;
    private static final int TILE_WIDTH = 64;
    private static final int TILE_HEIGHT = 32;

    private final JavaPack pack;
    private final Report report;
    private final Map<String, BufferedImage> strips = new HashMap<>();
    private final Set<String> written = new HashSet<>();

    ArmorConverter(JavaPack pack, Report report) {
        this.pack = pack;
        this.report = report;
    }

    /**
     * @param slot        {@code head}, {@code chest}, {@code legs} or {@code feet}
     * @param texturePath Bedrock texture path without extension
     * @param notes       things the report should mention
     */
    record Armor(String slot, String texturePath, BufferedImage texture, String source, List<String> notes) {}

    static String slotOf(Id vanillaItem) {
        String name = vanillaItem.path();
        return name.endsWith("_helmet") ? "head" : name.endsWith("_chestplate") ? "chest"
            : name.endsWith("_leggings") ? "legs" : name.endsWith("_boots") ? "feet" : null;
    }

    /**
     * Looks for the worn texture of an item.
     *
     * @return {@code null} if the item is not armour with its own texture
     */
    Armor find(Id vanillaItem, ItemProperties properties, String label) throws IOException {
        String slot = properties.equipSlot != null ? properties.equipSlot : slotOf(vanillaItem);
        if (slot == null || !Set.of("head", "chest", "legs", "feet").contains(slot)) {
            return null;
        }
        String layer = slot.equals("legs") ? "humanoid_leggings" : "humanoid";
        List<String> notes = new ArrayList<>();

        Id asset = properties.equipAsset;
        if (asset != null) {
            JsonObject definition = pack.json("assets/" + asset.namespace() + "/equipment/" + asset.path() + ".json");
            if (definition == null) {
                definition = pack.json("assets/" + asset.namespace() + "/models/equipment/" + asset.path() + ".json");
            }
            if (definition != null) {
                JsonObject first = definition.get("layers") instanceof JsonObject layers
                    && layers.get(layer) instanceof JsonArray entries && !entries.isEmpty()
                    && entries.get(0) instanceof JsonObject entry ? entry : null;
                if (first == null || !first.has("texture")) {
                    report.skipped(Report.Kind.ARMOR, label, "Equipment-Asset " + asset + " hat keine Ebene '" + layer + "'");
                    return null;
                }
                Id texture = Id.parse(first.get("texture").getAsString());
                String file = "assets/" + texture.namespace() + "/textures/entity/equipment/" + layer + "/" + texture.path() + ".png";
                BufferedImage image = pack.image(file);
                if (image == null) {
                    report.skipped(Report.Kind.ARMOR, label, "Ruestungs-Textur fehlt im Paket: " + file);
                    return null;
                }
                if (first.has("dyeable")) {
                    notes.add("faerbbare Ebene: die Farbe wird auf Bedrock nicht uebernommen.");
                }
                if (definition.getAsJsonObject("layers").getAsJsonArray(layer).size() > 1) {
                    notes.add("mehrere Textur-Ebenen: nur die erste wird verwendet.");
                }
                String path = "textures/models/armor/snouli/" + Id.sanitize(texture.namespace()) + "/"
                    + Id.sanitize(texture.path()) + "_" + layer;
                return new Armor(slot, path, Images.toArgb(image), "Equipment-Asset " + asset, notes);
            }
            if (!asset.namespace().equals("minecraft")) {
                report.skipped(Report.Kind.ARMOR, label, "Equipment-Asset " + asset + " fehlt im Paket");
                return null;
            }
        }

        if (!vanillaItem.path().startsWith("leather_")) {
            return null;
        }
        BufferedImage strip = strip(layer);
        if (strip == null) {
            return null;
        }
        int tiles = strip.getWidth() / TILE_WIDTH;
        int tile = -1;
        if (properties.dyedColor != null) {
            for (int i = 1; i < tiles; i++) {
                if ((strip.getRGB(i * TILE_WIDTH, 0) & 0xFFFFFF) == properties.dyedColor
                    && (strip.getRGB(i * TILE_WIDTH, 0) >>> 24) != 0) {
                    tile = i;
                }
            }
            if (tile < 0 || properties.dyedColor == DEFAULT_LEATHER) {
                return null; // ordinary dyed leather armour
            }
        } else {
            // Without ItemsAdder data the item's colour is unknown; only an unambiguous pack can be resolved
            List<Integer> custom = new ArrayList<>();
            for (int i = 1; i < tiles; i++) {
                int marker = strip.getRGB(i * TILE_WIDTH, 0);
                if ((marker >>> 24) != 0 && (marker & 0xFFFFFF) != DEFAULT_LEATHER) {
                    custom.add(i);
                }
            }
            if (custom.size() != 1) {
                if (custom.size() > 1) {
                    report.warning(Report.Kind.ARMOR, label, "mehrere Ruestungs-Sets im Paket - ohne ItemsAdder-Daten"
                        + " (Farbe des Items) laesst sich die Textur nicht zuordnen. Bleibt ein flaches Item.");
                }
                return null;
            }
            tile = custom.get(0);
            notes.add("ohne ItemsAdder-Daten zugeordnet (einziges Ruestungs-Set im Paket).");
        }

        int color = strip.getRGB(tile * TILE_WIDTH, 0) & 0xFFFFFF;
        BufferedImage image = new BufferedImage(TILE_WIDTH, TILE_HEIGHT, BufferedImage.TYPE_INT_ARGB);
        image.getGraphics().drawImage(strip.getSubimage(tile * TILE_WIDTH, 0, TILE_WIDTH, TILE_HEIGHT), 0, 0, null);
        // the first pixels of a tile are ItemsAdder's markers (colour, animation, properties), not texture
        for (int x = 0; x < 3; x++) {
            image.setRGB(x, 0, 0);
        }
        String path = String.format("textures/models/armor/snouli/leather_%06x_%s", color, layer);
        return new Armor(slot, path, image, String.format("Leder-Ruestung, Farbe #%06X", color), notes);
    }

    /** The texture strip that replaces vanilla leather armour, if the pack has ItemsAdder's shader armour. */
    private BufferedImage strip(String layer) throws IOException {
        if (strips.containsKey(layer)) {
            return strips.get(layer);
        }
        BufferedImage strip = pack.image("assets/minecraft/textures/entity/equipment/" + layer + "/leather.png");
        if (strip == null) {
            strip = pack.image("assets/minecraft/textures/models/armor/leather_layer_"
                + (layer.equals("humanoid") ? "1" : "2") + ".png");
        }
        if (strip != null) {
            strip = Images.toArgb(strip);
            if (strip.getWidth() < 2 * TILE_WIDTH || strip.getHeight() < TILE_HEIGHT || strip.getWidth() % TILE_WIDTH != 0) {
                strip = null; // a plain retextured leather armour, not a strip of sets
            }
        }
        strips.put(layer, strip);
        return strip;
    }

    /**
     * Writes the attachable for one armour piece.
     *
     * @param iconTexture Bedrock path of the item's icon, drawn while the piece is held in a hand
     * @param display     the Java model's {@code display} transforms for the held icon, may be {@code null}
     */
    void write(BedrockPack out, Armor armor, String identifier, String iconTexture, JsonObject display) throws IOException {
        if (written.add(armor.texturePath())) {
            out.put(armor.texturePath() + ".png", Images.png(armor.texture()));
        }
        String key = identifier.replace(':', '.');
        String file = identifier.replace(':', '/');
        String piece = switch (armor.slot()) {
            case "head" -> "helmet";
            case "chest" -> "chestplate";
            case "legs" -> "leggings";
            default -> "boots";
        };
        String layerVariable = switch (armor.slot()) {
            case "head" -> "helmet";
            case "chest" -> "chest";
            case "legs" -> "leg";
            default -> "boot";
        };
        String inHand = "(c.item_slot == 'main_hand' || c.item_slot == 'off_hand')";

        // Held in a hand: the flat icon, like any other item
        JsonObject mesh = new JsonObject();
        mesh.addProperty("texture", "icon");
        mesh.add("position", Model3dConverter.numbers(0, 8, 0));
        mesh.add("rotation", Model3dConverter.numbers(90, 0, -180));
        mesh.add("local_pivot", Model3dConverter.numbers(8, 0.5, 8));
        JsonArray meshes = new JsonArray();
        meshes.add(mesh);
        JsonArray bones = new JsonArray();
        bones.add(Model3dConverter.bone("geyser_custom", null, "q.item_slot_to_bone_name(c.item_slot)"));
        bones.add(Model3dConverter.bone("geyser_custom_x", "geyser_custom", null));
        bones.add(Model3dConverter.bone("geyser_custom_y", "geyser_custom_x", null));
        JsonObject z = Model3dConverter.bone("geyser_custom_z", "geyser_custom_y", null);
        z.add("texture_meshes", meshes);
        bones.add(z);
        JsonObject geometryDescription = new JsonObject();
        geometryDescription.addProperty("identifier", "geometry.snouli." + key + ".icon");
        geometryDescription.addProperty("texture_width", 16);
        geometryDescription.addProperty("texture_height", 16);
        geometryDescription.addProperty("visible_bounds_width", 4);
        geometryDescription.addProperty("visible_bounds_height", 4.5);
        geometryDescription.add("visible_bounds_offset", Model3dConverter.numbers(0, 0.75, 0));
        JsonObject geometry = new JsonObject();
        geometry.add("description", geometryDescription);
        geometry.add("bones", bones);
        JsonArray geometries = new JsonArray();
        geometries.add(geometry);
        JsonObject geometryFile = new JsonObject();
        geometryFile.addProperty("format_version", "1.16.0");
        geometryFile.add("minecraft:geometry", geometries);
        out.putJson("models/entity/snouli/" + file + ".icon.geo.json", geometryFile);

        JsonObject iconController = new JsonObject();
        iconController.addProperty("geometry", "Geometry.icon");
        JsonArray materials = new JsonArray();
        JsonObject material = new JsonObject();
        material.addProperty("*", "Material.icon");
        materials.add(material);
        iconController.add("materials", materials);
        JsonArray controllerTextures = new JsonArray();
        controllerTextures.add("Texture.icon");
        iconController.add("textures", controllerTextures);
        JsonObject controllers = new JsonObject();
        controllers.add("controller.render.snouli." + key + ".icon", iconController);
        JsonObject controllerFile = new JsonObject();
        controllerFile.addProperty("format_version", "1.8.0");
        controllerFile.add("render_controllers", controllers);
        out.putJson("render_controllers/snouli/" + file + ".render_controllers.json", controllerFile);

        // Vanilla hand transforms of flat items (item/generated) where the model has none
        JsonObject transforms = Model3dConverter.parse("""
            {"thirdperson_righthand":{"rotation":[0,0,0],"translation":[0,3,1],"scale":[0.55,0.55,0.55]},
             "firstperson_righthand":{"rotation":[0,-90,25],"translation":[1.13,3.2,1.13],"scale":[0.68,0.68,0.68]}}""");
        if (display != null) {
            display.entrySet().forEach(entry -> transforms.add(entry.getKey(), entry.getValue()));
        }
        String prefix = "animation.snouli." + key + ".";
        JsonObject animations = new JsonObject();
        animations.add(prefix + "thirdperson_main_hand", Model3dConverter.animation(
            Model3dConverter.root(new double[] {90, 0, 0}, new double[] {0, 13, -3}, null),
            Model3dConverter.transform(transforms, "thirdperson_righthand", "thirdperson_righthand", -1, 1, 1, false)));
        animations.add(prefix + "thirdperson_off_hand", Model3dConverter.animation(
            Model3dConverter.root(new double[] {90, 0, 0}, new double[] {0, 13, -3}, null),
            Model3dConverter.transform(transforms, "thirdperson_lefthand", "thirdperson_righthand", 1, 1, 1, false)));
        animations.add(prefix + "firstperson_main_hand", Model3dConverter.animation(
            Model3dConverter.root(new double[] {90, 60, -40}, new double[] {4, 10, 4}, 1.5),
            Model3dConverter.transform(transforms, "firstperson_righthand", "firstperson_righthand", -1, 1, -1, true)));
        animations.add(prefix + "firstperson_off_hand", Model3dConverter.animation(
            Model3dConverter.root(new double[] {90, 60, -40}, new double[] {4, 10, 4}, 1.5),
            Model3dConverter.transform(transforms, "firstperson_lefthand", "firstperson_righthand", 1, 1, -1, true)));
        JsonObject animationFile = new JsonObject();
        animationFile.addProperty("format_version", "1.8.0");
        animationFile.add("animations", animations);
        out.putJson("animations/snouli/" + file + ".animation.json", animationFile);

        JsonObject attachableMaterials = new JsonObject();
        attachableMaterials.addProperty("default", "armor");
        attachableMaterials.addProperty("enchanted", "armor_enchanted");
        attachableMaterials.addProperty("icon", "entity_alphatest_one_sided");
        JsonObject textures = new JsonObject();
        textures.addProperty("default", armor.texturePath());
        textures.addProperty("enchanted", "textures/misc/enchanted_item_glint");
        textures.addProperty("icon", iconTexture);
        JsonObject geometryRefs = new JsonObject();
        // Bedrock's own armour geometry: nothing is copied, the client already has it
        geometryRefs.addProperty("default", "geometry.humanoid.armor." + piece);
        geometryRefs.addProperty("icon", "geometry.snouli." + key + ".icon");

        JsonArray animate = new JsonArray();
        JsonObject animationRefs = new JsonObject();
        String[][] states = {
            {"thirdperson_main_hand", "c.item_slot == 'main_hand' && !c.is_first_person"},
            {"thirdperson_off_hand", "c.item_slot == 'off_hand' && !c.is_first_person"},
            {"firstperson_main_hand", "c.item_slot == 'main_hand' && c.is_first_person"},
            {"firstperson_off_hand", "c.item_slot == 'off_hand' && c.is_first_person"}
        };
        for (String[] state : states) {
            JsonObject condition = new JsonObject();
            condition.addProperty(state[0], state[1]);
            animate.add(condition);
            animationRefs.addProperty(state[0], prefix + state[0]);
        }
        JsonObject scripts = new JsonObject();
        // hides the wearer's own layer underneath, as Bedrock's armour attachables do
        scripts.addProperty("parent_setup", "variable." + layerVariable + "_layer_visible = 0.0;");
        scripts.add("animate", animate);

        // The armour model first: worn armour matters most should a client only draw one controller
        JsonArray renderControllers = new JsonArray();
        JsonObject worn = new JsonObject();
        worn.addProperty("controller.render.armor", "!" + inHand);
        renderControllers.add(worn);
        JsonObject held = new JsonObject();
        held.addProperty("controller.render.snouli." + key + ".icon", inHand);
        renderControllers.add(held);

        JsonObject description = new JsonObject();
        description.addProperty("identifier", identifier);
        description.add("materials", attachableMaterials);
        description.add("textures", textures);
        description.add("geometry", geometryRefs);
        description.add("scripts", scripts);
        description.add("animations", animationRefs);
        description.add("render_controllers", renderControllers);
        JsonObject attachable = new JsonObject();
        attachable.add("description", description);
        JsonObject attachableFile = new JsonObject();
        attachableFile.addProperty("format_version", "1.10.0");
        attachableFile.add("minecraft:attachable", attachable);
        out.putJson("attachables/snouli/" + file + ".attachable.json", attachableFile);
    }
}
