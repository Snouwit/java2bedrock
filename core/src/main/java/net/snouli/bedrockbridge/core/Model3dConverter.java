package net.snouli.bedrockbridge.core;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Polygon;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Converts 3D Java item models (furniture, 3D items) into Bedrock attachables.
 *
 * <p>Each model becomes a custom item with an attachable: a Bedrock geometry made of the model's
 * cuboids, bound to whoever holds or wears the item. That covers three cases at once:
 * <ul>
 *   <li>a player holding the item (animations from the Java {@code display} transforms),</li>
 *   <li>furniture on an armour stand's head (plain Geyser), and</li>
 *   <li>furniture shown by an item display, which the GeyserDisplayEntity extension spawns as
 *       {@code geyser:item_display} wearing the item - this class also writes that entity's
 *       client definition and the extension's mapping file.</li>
 * </ul>
 * Coordinates, UVs and hand animations follow Kas-tle's java2bedrock converter, the reference
 * the Geyser community uses for attachables.
 *
 * <p>A model may use several textures, including vanilla ones that only exist on the client.
 * Since nothing may be merged into an atlas, every texture gets its own geometry and render
 * controller inside the same attachable.
 */
final class Model3dConverter {

    private static final String[] FACES = {"north", "south", "east", "west", "up", "down"};
    private static final String BINDING = "c.item_slot == 'head' ? 'head' : ((c.item_slot == 'main_hand' ||"
        + " c.item_slot == 'off_hand') ? q.item_slot_to_bone_name(c.item_slot) : 'snouli_display')";

    private final Models models;
    private final BuildInput input;
    private final Report report;
    private final VanillaTextures vanilla = new VanillaTextures();

    private final Set<String> copiedTextures = new HashSet<>();
    private final TreeMap<String, String[]> displayMappings = new TreeMap<>();
    private int converted;

    Model3dConverter(Models models, BuildInput input, Report report) {
        this.models = models;
        this.input = input;
        this.report = report;
    }

    /** One texture of a model with the cubes (faces) that use it. */
    private static final class Slot {
        final Id source;
        final String bedrockPath;
        final int frames;
        final BufferedImage image; // null for textures that only exist on the client
        final JsonArray cubes = new JsonArray();

        Slot(Id source, String bedrockPath, int frames, BufferedImage image) {
            this.source = source;
            this.bedrockPath = bedrockPath;
            this.frames = frames;
            this.image = image;
        }
    }

    private record Box(double[] from, double[] to, Map<String, Slot> faces) {}

    /** A model that passed all checks and can be written. */
    static final class Prepared {
        private final Models.Resolved model;
        private final List<Slot> slots;
        private final List<Box> boxes;
        private final List<String> warnings;
        private final boolean faceRotation;

        private Prepared(Models.Resolved model, List<Slot> slots, List<Box> boxes, List<String> warnings, boolean faceRotation) {
            this.model = model;
            this.slots = slots;
            this.boxes = boxes;
            this.warnings = warnings;
            this.faceRotation = faceRotation;
        }

        String summary() {
            return boxes.size() + " Wuerfel, " + slots.size() + (slots.size() == 1 ? " Textur" : " Texturen");
        }

        List<String> warnings() {
            return warnings;
        }
    }

    /**
     * Checks a model and collects its geometry.
     *
     * @return {@code null} if the model cannot be converted (the reason is added to the report)
     */
    Prepared prepare(Models.Resolved model, String label) throws IOException {
        Map<String, Slot> slots = new LinkedHashMap<>();
        List<Box> boxes = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        boolean faceRotation = false;
        boolean rescale = false;
        int missingFaces = 0;

        for (JsonElement el : model.elements) {
            if (!(el instanceof JsonObject element)) {
                continue;
            }
            double[] from = vector(element.get("from"));
            double[] to = vector(element.get("to"));
            if (from == null || to == null) {
                report.skipped(Report.Kind.MODEL, label, "Element ohne gueltige from/to-Koordinaten");
                return null;
            }

            double[] rotation = null;
            double[] pivot = null;
            if (element.get("rotation") instanceof JsonObject rot) {
                if (!rot.has("axis") || !rot.has("angle")) {
                    report.skipped(Report.Kind.MODEL, label, "Element mit Drehung um mehrere Achsen - nur eine Achse"
                        + " pro Element wird unterstuetzt");
                    return null;
                }
                double angle = rot.get("angle").getAsDouble();
                double[] origin = rot.has("origin") ? vector(rot.get("origin")) : new double[] {8, 8, 8};
                if (origin == null) {
                    report.skipped(Report.Kind.MODEL, label, "Element mit ungueltigem Drehpunkt");
                    return null;
                }
                if (angle != 0) {
                    // Bedrock model space mirrors X, which flips rotations around X and Y
                    rotation = switch (rot.get("axis").getAsString()) {
                        case "x" -> new double[] {-angle, 0, 0};
                        case "y" -> new double[] {0, -angle, 0};
                        default -> new double[] {0, 0, angle};
                    };
                    pivot = new double[] {8 - origin[0], origin[1], origin[2] - 8};
                    rescale |= rot.has("rescale") && rot.get("rescale").getAsBoolean();
                }
            }

            if (!(element.get("faces") instanceof JsonObject faces)) {
                continue;
            }
            Map<Slot, JsonObject> uvBySlot = new LinkedHashMap<>();
            Map<String, Slot> faceSlots = new LinkedHashMap<>();
            for (String face : FACES) {
                if (!(faces.get(face) instanceof JsonObject definition) || !definition.has("texture")) {
                    continue;
                }
                Id texture = models.texture(model, definition.get("texture").getAsString());
                if (texture == null) {
                    missingFaces++;
                    continue;
                }
                Slot slot = slots.get(texture.toString());
                if (slot == null) {
                    slot = slot(texture);
                    if (slot == null) {
                        report.skipped(Report.Kind.MODEL, label, "Textur " + texture + " ist weder im ItemsAdder-Paket noch"
                            + " als Bedrock-eigene Textur bekannt");
                        return null;
                    }
                    slots.put(texture.toString(), slot);
                }
                double[] uv = definition.get("uv") instanceof JsonArray array && array.size() == 4
                    ? new double[] {array.get(0).getAsDouble(), array.get(1).getAsDouble(), array.get(2).getAsDouble(),
                        array.get(3).getAsDouble()}
                    : defaultUv(face, from, to);
                normalize(uv, 0);
                normalize(uv, 1);
                if (slot.frames > 1) {
                    // The Bedrock file stacks its animation frames vertically; use the first one
                    uv[1] = Math.max(0, Math.min(16, uv[1])) / slot.frames;
                    uv[3] = Math.max(0, Math.min(16, uv[3])) / slot.frames;
                }
                JsonObject faceUv = new JsonObject();
                if (face.equals("up") || face.equals("down")) {
                    faceUv.add("uv", numbers(uv[2], uv[3]));
                    faceUv.add("uv_size", numbers(uv[0] - uv[2], uv[1] - uv[3]));
                } else {
                    faceUv.add("uv", numbers(uv[0], uv[1]));
                    faceUv.add("uv_size", numbers(uv[2] - uv[0], uv[3] - uv[1]));
                }
                int turn = definition.has("rotation") ? Math.floorMod(definition.get("rotation").getAsInt(), 360) : 0;
                if (turn != 0) {
                    faceUv.addProperty("uv_rotation", turn);
                    faceRotation = true;
                }
                uvBySlot.computeIfAbsent(slot, k -> new JsonObject()).add(face, faceUv);
                faceSlots.put(face, slot);
            }
            if (uvBySlot.isEmpty()) {
                continue;
            }
            for (Map.Entry<Slot, JsonObject> entry : uvBySlot.entrySet()) {
                JsonObject cube = new JsonObject();
                cube.add("origin", numbers(8 - to[0], from[1], from[2] - 8));
                cube.add("size", numbers(to[0] - from[0], to[1] - from[1], to[2] - from[2]));
                if (rotation != null) {
                    cube.add("pivot", numbers(pivot));
                    cube.add("rotation", numbers(rotation));
                }
                cube.add("uv", entry.getValue());
                entry.getKey().cubes.add(cube);
            }
            boxes.add(new Box(from, to, faceSlots));
        }

        if (boxes.isEmpty()) {
            report.skipped(Report.Kind.MODEL, label, "Modell hat keine Flaechen mit Textur");
            return null;
        }
        if (missingFaces > 0) {
            warnings.add(missingFaces + " Flaechen ohne aufloesbare Textur wurden weggelassen.");
        }
        if (rescale) {
            warnings.add("'rescale' bei gedrehten Elementen wird nicht nachgebildet.");
        }
        if (faceRotation) {
            warnings.add("gedrehte Textur-Flaechen (uv rotation) - Ausrichtung auf Bedrock pruefen.");
        }
        List<String> clientTextures = slots.values().stream().filter(s -> s.image == null)
            .map(s -> s.source.path().substring(s.source.path().lastIndexOf('/') + 1)).toList();
        if (!clientTextures.isEmpty()) {
            warnings.add("nutzt Bedrock-eigene Texturen statt der Java-Vanilla-Texturen: " + String.join(", ", clientTextures)
                + " (sehen je nach Bedrock-Texturpaket leicht anders aus).");
        }
        return new Prepared(model, new ArrayList<>(slots.values()), boxes, warnings, faceRotation);
    }

    private Slot slot(Id texture) throws IOException {
        BufferedImage image = models.loadTexture(texture);
        if (image != null) {
            String path = "textures/models/" + Id.sanitize(texture.namespace()) + "/" + Id.sanitize(texture.path());
            return new Slot(texture, path, 1, image);
        }
        VanillaTextures.Texture bedrock = vanilla.resolve(texture);
        return bedrock == null ? null : new Slot(texture, bedrock.path(), bedrock.frames(), null);
    }

    /** UVs that Java derives from the element bounds when a face has none. */
    private static double[] defaultUv(String face, double[] from, double[] to) {
        return switch (face) {
            case "down" -> new double[] {from[0], 16 - to[2], to[0], 16 - from[2]};
            case "up" -> new double[] {from[0], from[2], to[0], to[2]};
            case "north" -> new double[] {16 - to[0], 16 - to[1], 16 - from[0], 16 - from[1]};
            case "south" -> new double[] {from[0], 16 - to[1], to[0], 16 - from[1]};
            case "west" -> new double[] {from[2], 16 - to[1], to[2], 16 - from[1]};
            default -> new double[] {16 - to[2], 16 - to[1], 16 - from[2], 16 - from[1]};
        };
    }

    /** Moves a UV range that lies outside the texture (oversized models) back onto it. */
    private static void normalize(double[] uv, int axis) {
        double low = Math.min(uv[axis], uv[axis + 2]);
        double high = Math.max(uv[axis], uv[axis + 2]);
        if (low >= 0 && high <= 16) {
            return;
        }
        double shift = -Math.floor(low / 16) * 16;
        if (high - low > 16) {
            // larger than the texture: stretch it over the face
            boolean flipped = uv[axis] > uv[axis + 2];
            uv[axis] = flipped ? 16 : 0;
            uv[axis + 2] = flipped ? 0 : 16;
            return;
        }
        if (high + shift > 16) {
            shift -= high + shift - 16;
        }
        uv[axis] += shift;
        uv[axis + 2] += shift;
    }

    /**
     * Writes geometry, textures, render controllers, animations and the attachable for an item.
     *
     * @param identifier  Bedrock item identifier, e.g. {@code snouli:park_bench}
     * @param vanillaItem Java base item, e.g. {@code minecraft:paper}
     */
    void write(BedrockPack out, Prepared prepared, String identifier, Id vanillaItem) throws IOException {
        String key = identifier.replace(':', '.');
        String file = identifier.replace(':', '/');
        String geometryId = "geometry.snouli." + key;

        double[] min = {Double.MAX_VALUE, Double.MAX_VALUE, Double.MAX_VALUE};
        double[] max = {-Double.MAX_VALUE, -Double.MAX_VALUE, -Double.MAX_VALUE};
        for (Box box : prepared.boxes) {
            for (int i = 0; i < 3; i++) {
                min[i] = Math.min(min[i], Math.min(box.from[i], box.to[i]));
                max[i] = Math.max(max[i], Math.max(box.from[i], box.to[i]));
            }
        }
        double reach = Math.max(Math.max(Math.abs(min[0] - 8), Math.abs(max[0] - 8)), Math.max(Math.abs(min[2] - 8), Math.abs(max[2] - 8)));

        JsonArray geometries = new JsonArray();
        JsonObject textures = new JsonObject();
        JsonObject geometryRefs = new JsonObject();
        JsonArray controllerRefs = new JsonArray();
        JsonObject controllers = new JsonObject();
        for (int i = 0; i < prepared.slots.size(); i++) {
            Slot slot = prepared.slots.get(i);
            String name = "t" + i;
            if (slot.image != null && copiedTextures.add(slot.bedrockPath)) {
                out.put(slot.bedrockPath + ".png", Images.png(slot.image));
            }
            textures.addProperty(name, slot.bedrockPath);
            geometryRefs.addProperty(name, geometryId + "." + name);
            controllerRefs.add("controller.render.snouli." + key + "." + name);

            JsonObject controller = new JsonObject();
            controller.addProperty("geometry", "Geometry." + name);
            JsonArray materials = new JsonArray();
            JsonObject material = new JsonObject();
            material.addProperty("*", "Material.default");
            materials.add(material);
            controller.add("materials", materials);
            JsonArray controllerTextures = new JsonArray();
            controllerTextures.add("Texture." + name);
            controller.add("textures", controllerTextures);
            controllers.add("controller.render.snouli." + key + "." + name, controller);

            JsonObject description = new JsonObject();
            description.addProperty("identifier", geometryId + "." + name);
            description.addProperty("texture_width", 16);
            description.addProperty("texture_height", 16);
            description.add("visible_bounds_width", number(Math.ceil(reach / 8) + 2));
            description.add("visible_bounds_height", number(Math.ceil((max[1] - min[1]) / 16) + 2));
            description.add("visible_bounds_offset", numbers(0, (min[1] + max[1]) / 32, 0));

            JsonArray bones = new JsonArray();
            bones.add(bone("geyser_custom", null, BINDING));
            bones.add(bone("geyser_custom_x", "geyser_custom", null));
            bones.add(bone("geyser_custom_y", "geyser_custom_x", null));
            bones.add(bone("geyser_custom_z", "geyser_custom_y", null));
            JsonObject cubes = bone("cubes", "geyser_custom_z", null);
            cubes.add("cubes", slot.cubes);
            bones.add(cubes);

            JsonObject geometry = new JsonObject();
            geometry.add("description", description);
            geometry.add("bones", bones);
            geometries.add(geometry);
        }
        textures.addProperty("enchanted", "textures/misc/enchanted_item_glint");

        JsonObject geometryFile = new JsonObject();
        geometryFile.addProperty("format_version", prepared.faceRotation ? "1.21.0" : "1.16.0");
        geometryFile.add("minecraft:geometry", geometries);
        out.putJson("models/entity/snouli/" + file + ".geo.json", geometryFile);

        JsonObject controllerFile = new JsonObject();
        controllerFile.addProperty("format_version", "1.8.0");
        controllerFile.add("render_controllers", controllers);
        out.putJson("render_controllers/snouli/" + file + ".render_controllers.json", controllerFile);

        String animationPrefix = "animation.snouli." + key + ".";
        JsonObject display = prepared.model.display;
        JsonObject animations = new JsonObject();
        animations.add(animationPrefix + "thirdperson_main_hand", animation(
            root(new double[] {90, 0, 0}, new double[] {0, 13, -3}, null),
            transform(display, "thirdperson_righthand", "thirdperson_righthand", -1, 1, 1, false)));
        animations.add(animationPrefix + "thirdperson_off_hand", animation(
            root(new double[] {90, 0, 0}, new double[] {0, 13, -3}, null),
            transform(display, "thirdperson_lefthand", "thirdperson_righthand", 1, 1, 1, false)));
        animations.add(animationPrefix + "head", animation(
            root(null, new double[] {0, 19.9, 0}, null),
            transform(display, "head", "head", -0.625, 0.625, 0.625, false)));
        animations.add(animationPrefix + "firstperson_main_hand", animation(
            root(new double[] {90, 60, -40}, new double[] {4, 10, 4}, 1.5),
            transform(display, "firstperson_righthand", "firstperson_righthand", -1, 1, -1, true)));
        animations.add(animationPrefix + "firstperson_off_hand", animation(
            root(new double[] {90, 60, -40}, new double[] {4, 10, 4}, 1.5),
            transform(display, "firstperson_lefthand", "firstperson_righthand", 1, 1, -1, true)));
        // Shown by an item display: Java centres the model on the entity, Bedrock model space starts at its feet
        String context = input.models().displayContext().toLowerCase(Locale.ROOT);
        animations.add(animationPrefix + "display", animation(
            root(null, new double[] {0, -8, 0}, null),
            transform(context.equals("none") ? null : display, context, context, -1, 1, 1, false)));
        JsonObject disable = new JsonObject();
        disable.addProperty("loop", true);
        disable.addProperty("override_previous_animation", true);
        JsonObject hidden = new JsonObject();
        hidden.addProperty("scale", 0);
        JsonObject disableBones = new JsonObject();
        disableBones.add("geyser_custom", hidden);
        disable.add("bones", disableBones);
        animations.add(animationPrefix + "disable", disable);

        JsonObject animationFile = new JsonObject();
        animationFile.addProperty("format_version", "1.8.0");
        animationFile.add("animations", animations);
        out.putJson("animations/snouli/" + file + ".animation.json", animationFile);

        JsonObject materials = new JsonObject();
        materials.addProperty("default", "entity_alphatest_one_sided");
        materials.addProperty("enchanted", "entity_alphatest_one_sided");

        JsonArray preAnimation = new JsonArray();
        preAnimation.add("v.main_hand = c.item_slot == 'main_hand';");
        preAnimation.add("v.off_hand = c.item_slot == 'off_hand';");
        preAnimation.add("v.head = c.item_slot == 'head';");
        preAnimation.add("v.display = !v.main_hand && !v.off_hand && !v.head;");
        JsonArray animate = new JsonArray();
        JsonObject animationRefs = new JsonObject();
        String[][] states = {
            {"thirdperson_main_hand", "v.main_hand && !c.is_first_person", "thirdperson_main_hand"},
            {"thirdperson_off_hand", "v.off_hand && !c.is_first_person", "thirdperson_off_hand"},
            {"thirdperson_head", "v.head && !c.is_first_person", "head"},
            {"firstperson_main_hand", "v.main_hand && c.is_first_person", "firstperson_main_hand"},
            {"firstperson_off_hand", "v.off_hand && c.is_first_person", "firstperson_off_hand"},
            {"firstperson_head", "c.is_first_person && v.head", "disable"},
            {"display", "v.display", "display"}
        };
        for (String[] state : states) {
            JsonObject condition = new JsonObject();
            condition.addProperty(state[0], state[1]);
            animate.add(condition);
            animationRefs.addProperty(state[0], animationPrefix + state[2]);
        }
        JsonObject scripts = new JsonObject();
        scripts.add("pre_animation", preAnimation);
        scripts.add("animate", animate);

        JsonObject description = new JsonObject();
        description.addProperty("identifier", identifier);
        description.add("materials", materials);
        description.add("textures", textures);
        description.add("geometry", geometryRefs);
        description.add("scripts", scripts);
        description.add("animations", animationRefs);
        description.add("render_controllers", controllerRefs);
        JsonObject attachable = new JsonObject();
        attachable.add("description", description);
        JsonObject attachableFile = new JsonObject();
        attachableFile.addProperty("format_version", "1.10.0");
        attachableFile.add("minecraft:attachable", attachable);
        out.putJson("attachables/snouli/" + file + ".attachable.json", attachableFile);

        displayMappings.put(Id.sanitize(identifier), new String[] {vanillaItem.toString(), identifier});
        converted++;
    }

    private static JsonObject bone(String name, String parent, String binding) {
        JsonObject bone = new JsonObject();
        bone.addProperty("name", name);
        if (parent != null) {
            bone.addProperty("parent", parent);
        }
        if (binding != null) {
            bone.addProperty("binding", binding);
        }
        bone.add("pivot", numbers(0, 8, 0));
        return bone;
    }

    private static JsonObject root(double[] rotation, double[] position, Double scale) {
        JsonObject bone = new JsonObject();
        if (rotation != null) {
            bone.add("rotation", numbers(rotation));
        }
        if (position != null) {
            bone.add("position", numbers(position));
        }
        if (scale != null) {
            bone.add("scale", number(scale));
        }
        return bone;
    }

    /**
     * Turns a Java {@code display} entry into the x/y/z helper bones.
     *
     * @param sx sy sz factors for the translation (Bedrock mirrors some axes; heads are scaled by 0.625)
     */
    private static JsonObject[] transform(JsonObject display, String context, String fallback, double sx, double sy,
                                          double sz, boolean firstPerson) {
        JsonObject entry = null;
        if (display != null) {
            entry = display.get(context) instanceof JsonObject found ? found
                : display.get(fallback) instanceof JsonObject other ? other : null;
        }
        double[] rotation = entry == null ? null : vector(entry.get("rotation"));
        double[] translation = entry == null ? null : vector(entry.get("translation"));
        double[] scale = entry == null ? null : vector(entry.get("scale"));
        double headScale = Math.abs(sy) != 1 ? Math.abs(sy) : 1;

        JsonObject x = new JsonObject();
        if (rotation != null) {
            x.add("rotation", numbers(-rotation[0], 0, 0));
        } else if (firstPerson) {
            x.add("rotation", numbers(0.1, 0.1, 0.1));
        }
        if (translation != null) {
            x.add("position", numbers(translation[0] * sx, translation[1] * sy, translation[2] * sz));
        }
        if (scale != null) {
            x.add("scale", numbers(scale[0] * headScale, scale[1] * headScale, scale[2] * headScale));
        } else if (headScale != 1) {
            x.add("scale", number(headScale));
        }
        JsonObject y = new JsonObject();
        JsonObject z = new JsonObject();
        if (rotation != null) {
            y.add("rotation", numbers(0, -rotation[1], 0));
            z.add("rotation", numbers(0, 0, rotation[2]));
        }
        return new JsonObject[] {x, y, z};
    }

    private static JsonObject animation(JsonObject root, JsonObject[] xyz) {
        JsonObject bones = new JsonObject();
        bones.add("geyser_custom", root);
        String[] names = {"geyser_custom_x", "geyser_custom_y", "geyser_custom_z"};
        for (int i = 0; i < 3; i++) {
            if (xyz[i].size() > 0) {
                bones.add(names[i], xyz[i]);
            }
        }
        JsonObject animation = new JsonObject();
        animation.addProperty("loop", true);
        animation.add("bones", bones);
        return animation;
    }

    /**
     * A simple isometric preview used as inventory icon. Textures that only exist on the client
     * cannot be sampled, so faces are filled with one colour per texture.
     */
    BufferedImage icon(Prepared prepared) {
        record Quad(double depth, int[] xs, int[] ys, Color color) {}
        List<Quad> quads = new ArrayList<>();
        double minX = Double.MAX_VALUE, maxX = -Double.MAX_VALUE, minY = Double.MAX_VALUE, maxY = -Double.MAX_VALUE;
        double[][][] projected = new double[prepared.boxes.size()][8][];
        for (int b = 0; b < prepared.boxes.size(); b++) {
            Box box = prepared.boxes.get(b);
            for (int corner = 0; corner < 8; corner++) {
                double x = (corner & 1) == 0 ? box.from[0] : box.to[0];
                double y = (corner & 2) == 0 ? box.from[1] : box.to[1];
                double z = (corner & 4) == 0 ? box.from[2] : box.to[2];
                // Viewed from the south-east and slightly above, like Java's GUI rendering of blocks
                double sx = (x - z) * 0.866;
                double sy = -y + (x + z) * 0.5;
                projected[b][corner] = new double[] {sx, sy, x + y + z};
                minX = Math.min(minX, sx);
                maxX = Math.max(maxX, sx);
                minY = Math.min(minY, sy);
                maxY = Math.max(maxY, sy);
            }
        }
        int size = 64;
        double scale = (size - 4) / Math.max(1e-6, Math.max(maxX - minX, maxY - minY));
        double offsetX = (size - (maxX - minX) * scale) / 2 - minX * scale;
        double offsetY = (size - (maxY - minY) * scale) / 2 - minY * scale;
        // visible faces from this camera: up (corners with y=to), south (z=to), east (x=to)
        int[][] corners = {{2, 3, 7, 6}, {4, 5, 7, 6}, {1, 3, 7, 5}};
        String[] names = {"up", "south", "east"};
        double[] shade = {1.0, 0.8, 0.62};
        for (int b = 0; b < prepared.boxes.size(); b++) {
            for (int f = 0; f < 3; f++) {
                Slot slot = prepared.boxes.get(b).faces.get(names[f]);
                if (slot == null) {
                    continue;
                }
                int[] xs = new int[4];
                int[] ys = new int[4];
                double depth = 0;
                for (int i = 0; i < 4; i++) {
                    double[] point = projected[b][corners[f][i]];
                    xs[i] = (int) Math.round(point[0] * scale + offsetX);
                    ys[i] = (int) Math.round(point[1] * scale + offsetY);
                    depth += point[2];
                }
                Color base = color(slot);
                quads.add(new Quad(depth, xs, ys, new Color((int) (base.getRed() * shade[f]),
                    (int) (base.getGreen() * shade[f]), (int) (base.getBlue() * shade[f]))));
            }
        }
        quads.sort(Comparator.comparingDouble(Quad::depth));
        BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        for (Quad quad : quads) {
            g.setColor(quad.color());
            g.fill(new Polygon(quad.xs(), quad.ys(), 4));
        }
        g.dispose();
        return Images.scale(image, 32, 32);
    }

    private static final String[][] PALETTE = {
        {"sea_lantern", "c8e6dc"}, {"glowstone", "f0c878"}, {"lantern", "e6c88c"}, {"blackstone", "2e2a30"},
        {"deepslate", "505054"}, {"obsidian", "1e1428"}, {"andesite", "8a8a8c"}, {"diorite", "c8c8c8"},
        {"granite", "9c6c5a"}, {"cobble", "7c7c7c"}, {"stone", "828282"}, {"spruce", "6e5030"},
        {"dark_oak", "462c14"}, {"birch", "c8b47c"}, {"jungle", "a87850"}, {"acacia", "b05c30"},
        {"mangrove", "783028"}, {"cherry", "e6b4aa"}, {"crimson", "823c5a"}, {"warped", "288278"},
        {"planks", "a08250"}, {"log", "6e5632"}, {"oak", "a08250"}, {"iron", "d2d2d2"}, {"gold", "f0d23c"},
        {"copper", "c06c50"}, {"quartz", "ebe6dc"}, {"brick", "966450"}, {"leaves", "4a8232"},
        {"glass", "c8dce6"}, {"white", "e6e6e6"}, {"black", "1e1e1e"}, {"red", "a02828"}, {"blue", "324ba0"},
        {"green", "50782a"}, {"yellow", "f0c832"}, {"orange", "e67814"}, {"gray", "5a5a5a"}, {"brown", "6e4628"}
    };

    private static Color color(Slot slot) {
        if (slot.image != null) {
            long r = 0, g = 0, b = 0, n = 0;
            for (int y = 0; y < slot.image.getHeight(); y++) {
                for (int x = 0; x < slot.image.getWidth(); x++) {
                    int argb = slot.image.getRGB(x, y);
                    if ((argb >>> 24) > 32) {
                        r += (argb >> 16) & 0xFF;
                        g += (argb >> 8) & 0xFF;
                        b += argb & 0xFF;
                        n++;
                    }
                }
            }
            return n == 0 ? Color.GRAY : new Color((int) (r / n), (int) (g / n), (int) (b / n));
        }
        String name = slot.source.path();
        for (String[] entry : PALETTE) {
            if (name.contains(entry[0])) {
                return new Color(Integer.parseInt(entry[1], 16));
            }
        }
        return new Color(0x8c8c8c);
    }

    int convertedCount() {
        return converted;
    }

    /** Adds the client definition of {@code geyser:item_display}, the entity GeyserDisplayEntity spawns. */
    void writeDisplayEntity(BedrockPack out) {
        if (converted == 0) {
            return;
        }
        String entity = """
            {
              "format_version": "1.10.0",
              "minecraft:client_entity": {
                "description": {
                  "identifier": "geyser:item_display",
                  "materials": { "default": "entity_alphatest" },
                  "textures": { "default": "textures/entity/armor_stand" },
                  "geometry": { "default": "geometry.snouli.item_display" },
                  "animations": { "transform": "animation.snouli.item_display.transform" },
                  "scripts": { "animate": [ "transform" ] },
                  "render_controllers": [ "controller.render.snouli.item_display" ],
                  "enable_attachables": true
                }
              }
            }
            """;
        // No cubes: the entity only carries the worn item. The bone chain applies the item display's
        // scale and translation, which GeyserDisplayEntity sends as entity properties.
        String geometry = """
            {
              "format_version": "1.12.0",
              "minecraft:geometry": [
                {
                  "description": {
                    "identifier": "geometry.snouli.item_display",
                    "texture_width": 64,
                    "texture_height": 64,
                    "visible_bounds_width": 8,
                    "visible_bounds_height": 8,
                    "visible_bounds_offset": [0, 1, 0]
                  },
                  "bones": [
                    { "name": "geyser", "pivot": [0, 0, 0] },
                    { "name": "geyser_x", "parent": "geyser", "pivot": [0, 0, 0] },
                    { "name": "geyser_y", "parent": "geyser_x", "pivot": [0, 0, 0] },
                    { "name": "geyser_z", "parent": "geyser_y", "pivot": [0, 0, 0] },
                    { "name": "snouli_display", "parent": "geyser_z", "pivot": [0, 0, 0] }
                  ]
                }
              ]
            }
            """;
        // GeyserDisplayEntity sends translations multiplied by 10; one block is 16 model units.
        String animation = """
            {
              "format_version": "1.8.0",
              "animations": {
                "animation.snouli.item_display.transform": {
                  "loop": true,
                  "bones": {
                    "geyser": {
                      "scale": [
                        "q.property('geyser:s_x')",
                        "q.property('geyser:s_y')",
                        "q.property('geyser:s_z')"
                      ],
                      "position": [
                        "q.property('geyser:t_x') * 1.6",
                        "q.property('geyser:t_y') * 1.6",
                        "q.property('geyser:t_z') * -1.6"
                      ]
                    },
                    "geyser_x": { "rotation": [ "q.property('geyser:r_x')", 0, 0 ] },
                    "geyser_y": { "rotation": [ 0, "q.property('geyser:r_y')", 0 ] },
                    "geyser_z": { "rotation": [ 0, 0, "q.property('geyser:r_z')" ] }
                  }
                }
              }
            }
            """;
        String controller = """
            {
              "format_version": "1.8.0",
              "render_controllers": {
                "controller.render.snouli.item_display": {
                  "geometry": "Geometry.default",
                  "materials": [ { "*": "Material.default" } ],
                  "textures": [ "Texture.default" ]
                }
              }
            }
            """;
        out.put("entity/snouli_item_display.entity.json", entity.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        out.put("models/entity/snouli_item_display.geo.json", geometry.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        out.put("animations/snouli_item_display.animation.json", animation.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        out.put("render_controllers/snouli_item_display.render_controllers.json",
            controller.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    /**
     * @return the mapping file for GeyserDisplayEntity ({@code Mappings/*.yml}), or {@code null}
     *         if there are no 3D models
     */
    String displayEntityMappings() {
        if (displayMappings.isEmpty()) {
            return null;
        }
        ModelOptions options = input.models();
        StringBuilder yaml = new StringBuilder();
        yaml.append("# Erzeugt von SnouliBedrockBridge - nicht von Hand bearbeiten, wird ueberschrieben.\n");
        yaml.append("# Drehung/Verschiebung stellt man in der config.yml des Hub-Plugins ein (models-3d).\n");
        yaml.append("mappings:\n");
        for (Map.Entry<String, String[]> entry : displayMappings.entrySet()) {
            // GeyserDisplayEntity only knows y-offset (in blocks); it has no rotation/translation option.
            double[] translation = options.translationFor(entry.getValue()[1]);
            double yOffset = translation != null && translation.length == 3 ? translation[1] : 0;
            yaml.append("  ").append(entry.getKey()).append(":\n");
            yaml.append("    type: \"").append(entry.getValue()[0]).append("\"\n");
            yaml.append("    item-identifier: \"").append(entry.getValue()[1]).append("\"\n");
            yaml.append("    displayentityoptions:\n");
            yaml.append("      y-offset: ").append(number(yOffset)).append('\n');
            yaml.append("      vanilla-scale: false\n");
            yaml.append("      vanilla-scale-multiplier: 1\n");
            yaml.append("      hand: false\n");
        }
        return yaml.toString();
    }

    private static boolean nonZero(double[] values) {
        return values != null && values.length == 3 && (values[0] != 0 || values[1] != 0 || values[2] != 0);
    }

    private static String list(double[] values) {
        return "[" + number(values[0]) + ", " + number(values[1]) + ", " + number(values[2]) + "]";
    }

    private static double[] vector(JsonElement element) {
        if (!(element instanceof JsonArray array) || array.size() != 3) {
            return null;
        }
        try {
            return new double[] {array.get(0).getAsDouble(), array.get(1).getAsDouble(), array.get(2).getAsDouble()};
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** Rounded to four decimals; whole numbers are written without a fraction. */
    private static com.google.gson.JsonPrimitive number(double value) {
        double rounded = Math.round(value * 10000.0) / 10000.0;
        if (rounded == Math.rint(rounded) && Math.abs(rounded) < 1e9) {
            return new com.google.gson.JsonPrimitive((long) rounded);
        }
        return new com.google.gson.JsonPrimitive(rounded);
    }

    private static JsonArray numbers(double... values) {
        JsonArray array = new JsonArray();
        for (double value : values) {
            array.add(number(value));
        }
        return array;
    }
}
