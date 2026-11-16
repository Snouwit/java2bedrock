package net.snouli.bedrockbridge.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

class Model3dTest {

    @TempDir
    static Path temp;
    static Path zip;
    static BuildOutput output;

    @BeforeAll
    static void build() throws IOException {
        zip = TestPack.standard().write(temp.resolve("generated.zip"));
        output = PackBuilder.build(BuildInput.packOnly(zip, "Test"));
    }

    private static JsonObject json(String path) {
        byte[] data = output.pack().files().get(path);
        assertNotNull(data, path + " fehlt im Bedrock-Paket");
        return JsonParser.parseString(new String(data, StandardCharsets.UTF_8)).getAsJsonObject();
    }

    private static JsonObject attachable() {
        return json("attachables/snouli/deco/bench.attachable.json").getAsJsonObject("minecraft:attachable")
            .getAsJsonObject("description");
    }

    /** @return the cubes of the geometry that uses the given texture path */
    private static JsonArray cubes(String texturePath) {
        JsonObject description = attachable();
        for (Map.Entry<String, JsonElement> texture : description.getAsJsonObject("textures").entrySet()) {
            if (!texture.getValue().getAsString().equals(texturePath)) {
                continue;
            }
            String geometryId = description.getAsJsonObject("geometry").get(texture.getKey()).getAsString();
            for (JsonElement geometry : json("models/entity/snouli/deco/bench.geo.json").getAsJsonArray("minecraft:geometry")) {
                if (geometry.getAsJsonObject().getAsJsonObject("description").get("identifier").getAsString().equals(geometryId)) {
                    JsonArray bones = geometry.getAsJsonObject().getAsJsonArray("bones");
                    return bones.get(bones.size() - 1).getAsJsonObject().getAsJsonArray("cubes");
                }
            }
        }
        throw new AssertionError("keine Geometrie fuer " + texturePath);
    }

    @Test
    void furnitureBecomesACustomItemWithAttachable() {
        JsonArray stick = output.mappings().getAsJsonObject("items").getAsJsonObject("items").getAsJsonArray("minecraft:stick");
        assertEquals(1, stick.size(), "nur das umwandelbare Modell bekommt ein Mapping");
        JsonObject definition = stick.get(0).getAsJsonObject();
        assertEquals("deco:bench", definition.get("bedrock_identifier").getAsString());
        assertEquals(1, definition.get("custom_model_data").getAsInt());
        assertEquals("deco:bench", attachable().get("identifier").getAsString());

        String icon = definition.getAsJsonObject("bedrock_options").get("icon").getAsString();
        String iconPath = json("textures/item_texture.json").getAsJsonObject("texture_data").getAsJsonObject(icon)
            .get("textures").getAsString();
        assertTrue(output.pack().contains(iconPath + ".png"));
        // the bench plus the small lamp model of the standard test pack
        assertEquals(2, output.report().convertedCount(Report.Kind.MODEL));
    }

    @Test
    void clientTexturesAreReferencedNotCopied() {
        Set<String> textures = new HashSet<>();
        attachable().getAsJsonObject("textures").entrySet().forEach(e -> textures.add(e.getValue().getAsString()));
        assertEquals(Set.of("textures/blocks/planks_spruce", "textures/blocks/sea_lantern", "textures/models/shop/coin",
            "textures/misc/enchanted_item_glint"), textures);
        assertTrue(output.pack().contains("textures/models/shop/coin.png"), "eigene Textur wird mitgeliefert");
        for (String path : output.pack().files().keySet()) {
            assertFalse(path.contains("planks") || path.contains("sea_lantern"), path + " darf nicht im Paket sein");
        }
        assertTrue(output.report().warnings(Report.Kind.MODEL).stream().anyMatch(w -> w.detail().contains("spruce_planks")));
    }

    @Test
    void cubesFollowTheBedrockConventions() {
        // Java from [0,0,2] to [32,4,14]: X is mirrored around the block centre, Z is centred
        JsonObject plank = cubes("textures/blocks/planks_spruce").get(0).getAsJsonObject();
        assertEquals("[-24,0,-6]", plank.get("origin").toString());
        assertEquals("[32,4,12]", plank.get("size").toString());
        JsonObject uv = plank.getAsJsonObject("uv");
        assertEquals(Set.of("north", "up"), uv.keySet(), "nur Flaechen dieser Textur");
        // The element is wider than the texture, so the default UV is fitted onto it
        assertEquals("[0,12]", uv.getAsJsonObject("north").get("uv").toString());
        assertEquals("[16,4]", uv.getAsJsonObject("north").get("uv_size").toString());
        // up/down are written from the opposite corner
        assertEquals("[16,14]", uv.getAsJsonObject("up").get("uv").toString());
        assertEquals("[-16,-12]", uv.getAsJsonObject("up").get("uv_size").toString());

        // The same element contributes its bottom face to the geometry of the other texture
        JsonObject bottom = cubes("textures/models/shop/coin").get(0).getAsJsonObject();
        assertEquals("[-24,0,-6]", bottom.get("origin").toString());
        assertEquals(Set.of("down"), bottom.getAsJsonObject("uv").keySet());
        assertEquals("[8,4]", bottom.getAsJsonObject("uv").getAsJsonObject("down").get("uv").toString());
        assertEquals("[-8,-4]", bottom.getAsJsonObject("uv").getAsJsonObject("down").get("uv_size").toString());
    }

    @Test
    void rotationsAndAnimatedClientTextures() {
        JsonObject lamp = cubes("textures/blocks/sea_lantern").get(0).getAsJsonObject();
        assertEquals("[-1,4,-1]", lamp.get("origin").toString());
        assertEquals("[0,5,0]", lamp.get("pivot").toString());
        assertEquals("[0,-22.5,0]", lamp.get("rotation").toString(), "Y-Drehung kehrt sich durch die X-Spiegelung um");
        // sea_lantern has 5 stacked frames on Bedrock: V is squeezed onto the first frame
        JsonObject north = lamp.getAsJsonObject("uv").getAsJsonObject("north");
        assertEquals("[7,2]", north.get("uv").toString());
        assertEquals("[2,0.4]", north.get("uv_size").toString());
        JsonObject up = lamp.getAsJsonObject("uv").getAsJsonObject("up");
        assertEquals(90, up.get("uv_rotation").getAsInt());
        assertEquals("[16,3.2]", up.get("uv").toString());
        assertEquals("1.21.0", json("models/entity/snouli/deco/bench.geo.json").get("format_version").getAsString());
    }

    @Test
    void everyReferenceInTheAttachableResolves() {
        JsonObject description = attachable();
        Set<String> geometryIds = new HashSet<>();
        for (JsonElement geometry : json("models/entity/snouli/deco/bench.geo.json").getAsJsonArray("minecraft:geometry")) {
            geometryIds.add(geometry.getAsJsonObject().getAsJsonObject("description").get("identifier").getAsString());
            JsonArray bones = geometry.getAsJsonObject().getAsJsonArray("bones");
            assertTrue(bones.get(0).getAsJsonObject().get("binding").getAsString().contains("snouli_display"));
        }
        JsonObject controllers = json("render_controllers/snouli/deco/bench.render_controllers.json")
            .getAsJsonObject("render_controllers");
        JsonObject animations = json("animations/snouli/deco/bench.animation.json").getAsJsonObject("animations");

        assertEquals(3, description.getAsJsonArray("render_controllers").size());
        for (JsonElement controller : description.getAsJsonArray("render_controllers")) {
            JsonObject definition = controllers.getAsJsonObject(controller.getAsString());
            assertNotNull(definition, controller.getAsString());
            String slot = definition.get("geometry").getAsString().substring("Geometry.".length());
            assertTrue(description.getAsJsonObject("textures").has(slot));
            assertTrue(geometryIds.contains(description.getAsJsonObject("geometry").get(slot).getAsString()));
            assertEquals("Texture." + slot, definition.getAsJsonArray("textures").get(0).getAsString());
        }
        Set<String> animated = new HashSet<>();
        for (JsonElement entry : description.getAsJsonObject("scripts").getAsJsonArray("animate")) {
            animated.addAll(entry.getAsJsonObject().keySet());
        }
        assertEquals(description.getAsJsonObject("animations").keySet(), animated);
        for (Map.Entry<String, JsonElement> animation : description.getAsJsonObject("animations").entrySet()) {
            assertTrue(animations.has(animation.getValue().getAsString()), animation.getValue().getAsString());
        }
    }

    @Test
    void displayTransformsBecomeAnimations() {
        JsonObject animations = json("animations/snouli/deco/bench.animation.json").getAsJsonObject("animations");
        // Item display: centred on the entity, plus the model's "head" transform
        JsonObject display = animations.getAsJsonObject("animation.snouli.deco.bench.display").getAsJsonObject("bones");
        assertEquals("[0,-8,0]", display.getAsJsonObject("geyser_custom").get("position").toString());
        assertEquals("[0,13,0]", display.getAsJsonObject("geyser_custom_x").get("position").toString());
        // Armour stand head: Bedrock renders head items at 0.625
        JsonObject head = animations.getAsJsonObject("animation.snouli.deco.bench.head").getAsJsonObject("bones");
        assertEquals("[0,8.125,0]", head.getAsJsonObject("geyser_custom_x").get("position").toString());
        assertEquals("[0.625,0.625,0.625]", head.getAsJsonObject("geyser_custom_x").get("scale").toString());
        // In hand
        JsonObject hand = animations.getAsJsonObject("animation.snouli.deco.bench.thirdperson_main_hand").getAsJsonObject("bones");
        assertEquals("[-10,0,0]", hand.getAsJsonObject("geyser_custom_x").get("rotation").toString());
        assertEquals("[-1,2,3]", hand.getAsJsonObject("geyser_custom_x").get("position").toString());
        assertEquals("[0,-20,0]", hand.getAsJsonObject("geyser_custom_y").get("rotation").toString());
        assertEquals("[0,0,30]", hand.getAsJsonObject("geyser_custom_z").get("rotation").toString());
    }

    @Test
    void displayEntityAndExtensionMappingsAreIncluded() {
        JsonObject entity = json("entity/snouli_item_display.entity.json").getAsJsonObject("minecraft:client_entity")
            .getAsJsonObject("description");
        assertEquals("geyser:item_display", entity.get("identifier").getAsString());
        assertTrue(entity.get("enable_attachables").getAsBoolean());
        String geometryId = entity.getAsJsonObject("geometry").get("default").getAsString();
        JsonObject geometry = json("models/entity/snouli_item_display.geo.json").getAsJsonArray("minecraft:geometry")
            .get(0).getAsJsonObject();
        assertEquals(geometryId, geometry.getAsJsonObject("description").get("identifier").getAsString());
        assertTrue(geometry.getAsJsonArray("bones").toString().contains("\"snouli_display\""),
            "der Knochen, an den die Attachables binden");
        assertTrue(json("animations/snouli_item_display.animation.json").getAsJsonObject("animations")
            .has(entity.getAsJsonObject("animations").get("transform").getAsString()));
        assertTrue(json("render_controllers/snouli_item_display.render_controllers.json").getAsJsonObject("render_controllers")
            .has(entity.getAsJsonArray("render_controllers").get(0).getAsString()));

        String yaml = output.mappings().get("display_entities").getAsString();
        assertTrue(yaml.contains("type: \"minecraft:stick\""));
        assertTrue(yaml.contains("item-identifier: \"deco:bench\""));
        assertTrue(yaml.contains("y-offset: 0"));
        assertFalse(yaml.contains("rotation:"));
    }

    @Test
    void unsupportedModelsAreSkippedWithReasons() {
        List<Report.Entry> skipped = output.report().skipped(Report.Kind.MODEL);
        assertTrue(skipped.stream().anyMatch(e -> e.name().contains("unknown_texture")
            && e.detail().contains("minecraft:block/this_does_not_exist")));
        assertTrue(skipped.stream().anyMatch(e -> e.name().contains("tilted") && e.detail().contains("mehrere Achsen")));
        assertFalse(output.pack().contains("attachables/snouli/deco/unknown_texture.attachable.json"));
    }

    @Test
    void optionsControlDisplayContextCorrectionsAndSwitch() throws IOException {
        ModelOptions tuned = new ModelOptions(true, "none", new double[] {0, 180, 0}, new double[] {0, 0.5, 0});
        BuildOutput built = PackBuilder.build(new BuildInput(zip, "Test", Set.of(), List.of(), Map.of(), 64, tuned, m -> {}));
        JsonObject display = JsonParser.parseString(new String(
                built.pack().files().get("animations/snouli/deco/bench.animation.json"), StandardCharsets.UTF_8))
            .getAsJsonObject().getAsJsonObject("animations").getAsJsonObject("animation.snouli.deco.bench.display")
            .getAsJsonObject("bones");
        assertFalse(display.has("geyser_custom_x"), "ohne display-Kontext keine Modell-Transformation");
        String yaml = built.mappings().get("display_entities").getAsString();
        assertTrue(yaml.contains("rotation: [0, 180, 0]"));
        assertTrue(yaml.contains("translation: [0, 0.5, 0]"));

        ModelOptions perItem = new ModelOptions(true, "head", new double[3], new double[] {0, 0.5, 0},
            Map.of("bench", new double[] {0, 1.5, 0}));
        String perItemYaml = PackBuilder.build(new BuildInput(zip, "Test", Set.of(), List.of(), Map.of(), 64, perItem, m -> {}))
            .mappings().get("display_entities").getAsString();
        assertTrue(perItemYaml.contains("translation: [0, 1.5, 0]"), perItemYaml);
        assertEquals("[0.0, 1.5, 0.0]", java.util.Arrays.toString(perItem.translationFor("snouli:deco/bench")));
        assertEquals("[0.0, 0.5, 0.0]", java.util.Arrays.toString(perItem.translationFor("snouli:deco/lamp")));

        ModelOptions off = new ModelOptions(false, "head", new double[3], new double[3]);
        BuildOutput disabled = PackBuilder.build(new BuildInput(zip, "Test", Set.of(), List.of(), Map.of(), 64, off, m -> {}));
        assertEquals(0, disabled.report().convertedCount(Report.Kind.MODEL));
        assertFalse(disabled.mappings().has("display_entities"));
        assertNull(disabled.pack().files().get("entity/snouli_item_display.entity.json"));
        assertFalse(disabled.mappings().getAsJsonObject("items").getAsJsonObject("items").has("minecraft:stick"));
    }

    @Test
    void vanillaTextureNamesMapToExistingBedrockTextures() {
        VanillaTextures textures = new VanillaTextures();
        for (Map.Entry<String, String> entry : VanillaTextures.EXPLICIT.entrySet()) {
            assertTrue(textures.exists(entry.getValue()), entry.getKey() + " -> " + entry.getValue() + " gibt es in Bedrock nicht");
        }
        assertEquals("textures/blocks/stone_andesite_smooth", textures.resolve(Id.parse("block/polished_andesite")).path());
        assertEquals("textures/blocks/planks_big_oak", textures.resolve(Id.parse("block/dark_oak_planks")).path());
        assertEquals("textures/blocks/wool_colored_silver", textures.resolve(Id.parse("block/light_gray_wool")).path());
        assertEquals("textures/blocks/polished_blackstone", textures.resolve(Id.parse("block/polished_blackstone")).path());
        assertEquals("textures/blocks/deepslate/deepslate_bricks", textures.resolve(Id.parse("block/deepslate_bricks")).path());
        assertEquals(5, textures.resolve(Id.parse("block/sea_lantern")).frames());
        assertEquals(1, textures.resolve(Id.parse("block/stone")).frames());
        assertNull(textures.resolve(Id.parse("block/no_such_block")));
        assertNull(textures.resolve(Id.parse("item/diamond")), "nur Block-Texturen");
        assertNull(textures.resolve(Id.parse("shop:block/stone")));
    }
}
