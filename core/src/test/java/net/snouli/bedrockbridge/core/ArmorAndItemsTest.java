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

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.imageio.ImageIO;

class ArmorAndItemsTest {

    @TempDir
    static Path temp;
    static Path zip;
    static BuildOutput output;

    static final List<IaItem> ITEMS = List.of(
        // equipment asset on an armour base item
        new IaItem("gear:ruby_helmet", "§x§f§f§2§0§2§0Rubinhelm", "minecraft:iron_helmet", 5, null, false, false, null,
            "[minecraft:custom_model_data={floats:[5.0f]},minecraft:equippable={slot:\"head\",asset_id:\"gear:ruby\"},"
                + "minecraft:attribute_modifiers=[{type:\"minecraft:armor\",id:\"a\",amount:4.0d,operation:\"add_value\",slot:\"head\"},"
                + "{type:\"minecraft:armor_toughness\",id:\"t\",amount:2.0d,operation:\"add_value\"}],minecraft:max_damage=600]"),
        // equipment asset on a non-armour base item: the slot must be told to Bedrock
        new IaItem("gear:ruby_leggings", "Rubinhose", "minecraft:paper", 10000, null, false, false, null,
            "[equippable={slot:\"legs\",model:\"gear:ruby\"},attribute_modifiers={modifiers:[{type:\"generic.armor\",amount:6}]}]"),
        // ItemsAdder shader armour: dyed leather selects the green set
        new IaItem("gear:emerald_chestplate", "Smaragdbrust", "minecraft:leather_chestplate", 1, null, false, false, null,
            "[minecraft:dyed_color=65280,minecraft:custom_model_data={floats:[1.0f]}]"),
        // items with properties
        new IaItem("food:soup", "&#00aa00Suppe", "minecraft:paper", 10006, null, false, false, null,
            "[max_stack_size=16,food={nutrition:6,saturation:7.2f,can_always_eat:true},"
                + "consumable={consume_seconds:0.8f,animation:\"drink\"},enchantment_glint_override=true,enchantable={value:10}]"),
        new IaItem("tools:blade", "Klinge", "minecraft:diamond_sword", 7, null, false, false, null,
            "[max_damage=2000,max_stack_size=64,!minecraft:enchantable,custom_name='{\"text\":\"a, b ] }\"}',"
                + "attribute_modifiers=[{type:\"attack_damage\",amount:12.0d,operation:\"add_value\"}],tool={rules:[]}]"));

    @BeforeAll
    static void build() throws IOException {
        zip = TestPack.standard().write(temp.resolve("generated.zip"));
        output = PackBuilder.build(new BuildInput(zip, "Test", Set.of(), ITEMS, Map.of(), 64, ModelOptions.defaults(), m -> {}));
    }

    private static JsonObject json(BuildOutput built, String path) {
        byte[] data = built.pack().files().get(path);
        assertNotNull(data, path + " fehlt im Bedrock-Paket");
        return JsonParser.parseString(new String(data, StandardCharsets.UTF_8)).getAsJsonObject();
    }

    private static JsonObject definition(BuildOutput built, String identifier) {
        for (Map.Entry<String, JsonElement> item : built.mappings().getAsJsonObject("items").getAsJsonObject("items").entrySet()) {
            for (JsonElement definition : item.getValue().getAsJsonArray()) {
                if (definition.getAsJsonObject().get("bedrock_identifier").getAsString().equals(identifier)) {
                    return definition.getAsJsonObject();
                }
            }
        }
        return null;
    }

    private static JsonObject attachable(String file) {
        return json(output, "attachables/snouli/" + file + ".attachable.json").getAsJsonObject("minecraft:attachable")
            .getAsJsonObject("description");
    }

    private static BufferedImage texture(JsonObject attachable, String key) throws IOException {
        byte[] data = output.pack().files().get(attachable.getAsJsonObject("textures").get(key).getAsString() + ".png");
        assertNotNull(data, "Textur '" + key + "' fehlt im Paket");
        return ImageIO.read(new ByteArrayInputStream(data));
    }

    @Test
    void equipmentAssetArmourGetsBedrockArmourGeometry() throws IOException {
        JsonObject helmet = attachable("gear/ruby_helmet");
        assertEquals("gear:ruby_helmet", helmet.get("identifier").getAsString());
        assertEquals("geometry.humanoid.armor.helmet", helmet.getAsJsonObject("geometry").get("default").getAsString());
        assertEquals("armor", helmet.getAsJsonObject("materials").get("default").getAsString());
        assertEquals("armor_enchanted", helmet.getAsJsonObject("materials").get("enchanted").getAsString());
        assertEquals("textures/misc/enchanted_item_glint", helmet.getAsJsonObject("textures").get("enchanted").getAsString());
        assertEquals("variable.helmet_layer_visible = 0.0;", helmet.getAsJsonObject("scripts").get("parent_setup").getAsString());
        BufferedImage worn = texture(helmet, "default");
        assertEquals(64, worn.getWidth());
        assertEquals(0xFFCC0033, worn.getRGB(10, 10));

        // leggings use the second layer and the leggings geometry
        JsonObject leggings = attachable("gear/ruby_leggings");
        assertEquals("geometry.humanoid.armor.leggings", leggings.getAsJsonObject("geometry").get("default").getAsString());
        assertEquals("variable.leg_layer_visible = 0.0;", leggings.getAsJsonObject("scripts").get("parent_setup").getAsString());
        assertEquals(0xFF990022, texture(leggings, "default").getRGB(10, 10));
        assertTrue(output.report().warnings(Report.Kind.ARMOR).stream()
            .anyMatch(w -> w.name().startsWith("gear:ruby_leggings") && w.detail().contains("faerbbare")));
    }

    @Test
    void wornModelOnTheBodyAndIconInTheHand() throws IOException {
        JsonObject helmet = attachable("gear/ruby_helmet");
        JsonArray controllers = helmet.getAsJsonArray("render_controllers");
        JsonObject worn = controllers.get(0).getAsJsonObject();
        JsonObject held = controllers.get(1).getAsJsonObject();
        assertTrue(worn.has("controller.render.armor"), "das Ruestungsmodell steht an erster Stelle");
        assertTrue(worn.get("controller.render.armor").getAsString().startsWith("!"));
        String iconController = held.keySet().iterator().next();
        assertTrue(held.get(iconController).getAsString().contains("main_hand"));
        assertTrue(json(output, "render_controllers/snouli/gear/ruby_helmet.render_controllers.json")
            .getAsJsonObject("render_controllers").has(iconController));

        JsonObject geometry = json(output, "models/entity/snouli/gear/ruby_helmet.icon.geo.json")
            .getAsJsonArray("minecraft:geometry").get(0).getAsJsonObject();
        assertEquals(helmet.getAsJsonObject("geometry").get("icon").getAsString(),
            geometry.getAsJsonObject("description").get("identifier").getAsString());
        assertTrue(geometry.toString().contains("\"texture_meshes\":[{\"texture\":\"icon\""));
        // the held icon is the item's inventory icon
        assertEquals(0xFFFFD700, texture(helmet, "icon").getRGB(5, 5));

        JsonObject animations = json(output, "animations/snouli/gear/ruby_helmet.animation.json").getAsJsonObject("animations");
        for (Map.Entry<String, JsonElement> animation : helmet.getAsJsonObject("animations").entrySet()) {
            assertTrue(animations.has(animation.getValue().getAsString()), animation.getValue().getAsString());
        }
    }

    @Test
    void armourMappingCarriesSlotAndProtection() {
        JsonObject helmet = definition(output, "gear:ruby_helmet");
        assertEquals(4, helmet.getAsJsonObject("bedrock_options").get("protection_value").getAsInt());
        assertEquals("head", helmet.getAsJsonObject("components").getAsJsonObject("minecraft:equippable").get("slot").getAsString());
        assertEquals(600, helmet.getAsJsonObject("components").get("minecraft:max_damage").getAsInt());
        assertEquals("§cRubinhelm", helmet.get("display_name").getAsString(), "Hex-Farbe wird zur naechsten Bedrock-Farbe");

        JsonObject leggings = definition(output, "gear:ruby_leggings");
        assertEquals(6, leggings.getAsJsonObject("bedrock_options").get("protection_value").getAsInt());
        assertEquals("legs", leggings.getAsJsonObject("components").getAsJsonObject("minecraft:equippable").get("slot").getAsString());

        assertEquals(3, output.report().convertedCount(Report.Kind.ARMOR));
        assertTrue(output.report().converted(Report.Kind.ARMOR).stream()
            .anyMatch(e -> e.detail().contains("Ruestungspunkte 4") && e.detail().contains("Equipment-Asset gear:ruby")));
        assertTrue(output.report().warnings(Report.Kind.ARMOR).stream()
            .anyMatch(w -> w.name().startsWith("gear:ruby_helmet") && w.detail().contains("Attribute")));
        assertTrue(output.report().summaryLines().stream().anyMatch(line -> line.startsWith("Ruestungen: 3 umgewandelt")));
    }

    @Test
    void leatherStripTileIsPickedByDyeColour() throws IOException {
        JsonObject chest = attachable("gear/emerald_chestplate");
        assertEquals("geometry.humanoid.armor.chestplate", chest.getAsJsonObject("geometry").get("default").getAsString());
        assertTrue(chest.getAsJsonObject("textures").get("default").getAsString().endsWith("leather_00ff00_humanoid"));
        BufferedImage worn = texture(chest, "default");
        assertEquals(64, worn.getWidth());
        assertEquals(32, worn.getHeight());
        assertEquals(0xFF333333, worn.getRGB(20, 10), "der dritte Satz gehoert zur Farbe 00FF00");
        assertEquals(0, worn.getRGB(0, 0) >>> 24, "Markierungs-Pixel sind entfernt");
        // leather armour is already chest armour: no slot needed, protection comes from the base item
        assertFalse(definition(output, "gear:emerald_chestplate").has("components"));
    }

    @Test
    void withoutItemsAdderDataAnAmbiguousStripStaysAFlatItem() throws IOException {
        BuildOutput packOnly = PackBuilder.build(BuildInput.packOnly(zip, "Test"));
        assertEquals(0, packOnly.report().convertedCount(Report.Kind.ARMOR));
        assertFalse(packOnly.pack().files().keySet().stream().anyMatch(path -> path.startsWith("textures/models/armor/")));
        assertTrue(packOnly.report().warnings(Report.Kind.ARMOR).stream().anyMatch(w -> w.detail().contains("mehrere Ruestungs-Sets")));
        // an ordinary dyed leather armour of another colour is not custom armour either
        List<IaItem> plain = List.of(new IaItem("gear:blue", "Blau", "minecraft:leather_chestplate", 1, null, false, false, null,
            "[dyed_color=255]"));
        BuildOutput dyed = PackBuilder.build(new BuildInput(zip, "Test", Set.of(), plain, Map.of(), 64, ModelOptions.defaults(), m -> {}));
        assertEquals(0, dyed.report().convertedCount(Report.Kind.ARMOR));
    }

    @Test
    void itemPropertiesBecomeGeyserComponents() {
        JsonObject soup = definition(output, "food:soup");
        JsonObject components = soup.getAsJsonObject("components");
        assertEquals(16, components.get("minecraft:max_stack_size").getAsInt());
        assertEquals(6, components.getAsJsonObject("minecraft:food").get("nutrition").getAsInt());
        assertEquals(7.2, components.getAsJsonObject("minecraft:food").get("saturation").getAsDouble(), 1e-6);
        assertTrue(components.getAsJsonObject("minecraft:food").get("can_always_eat").getAsBoolean());
        assertEquals(0.8, components.getAsJsonObject("minecraft:consumable").get("consume_seconds").getAsDouble(), 1e-6);
        assertEquals("drink", components.getAsJsonObject("minecraft:consumable").get("animation").getAsString());
        assertTrue(components.get("minecraft:enchantment_glint_override").getAsBoolean());
        assertEquals(10, components.get("minecraft:enchantable").getAsInt());
        assertEquals("§2Suppe", soup.get("display_name").getAsString());
        assertTrue(output.report().converted(Report.Kind.ITEM).stream().anyMatch(e -> e.name().startsWith("food:soup")
            && e.detail().contains("Stapel 16") && e.detail().contains("trinkbar") && e.detail().contains("Glint")));
    }

    @Test
    void durabilityForcesSingleStackAndServerSideRulesAreReported() {
        JsonObject blade = definition(output, "tools:blade");
        JsonObject components = blade.getAsJsonObject("components");
        assertEquals(2000, components.get("minecraft:max_damage").getAsInt());
        assertEquals(1, components.get("minecraft:max_stack_size").getAsInt(), "Geyser lehnt stapelbare Items mit Haltbarkeit ab");
        assertTrue(components.get("!minecraft:enchantable").getAsBoolean());
        assertTrue(blade.getAsJsonObject("bedrock_options").get("display_handheld").getAsBoolean());
        assertFalse(blade.getAsJsonObject("bedrock_options").has("protection_value"));
        String warning = output.report().warnings(Report.Kind.ITEM).stream().filter(w -> w.name().startsWith("tools:blade"))
            .map(Report.Entry::detail).findFirst().orElse("");
        assertTrue(warning.contains("Attribute") && warning.contains("Werkzeug-Regeln"), warning);
    }

    @Test
    void optionsSwitchArmourAndComponentsOff() throws IOException {
        BuildOutput off = PackBuilder.build(new BuildInput(zip, "Test", Set.of(), ITEMS, Map.of(), 64, ModelOptions.defaults(),
            new ItemOptions(false, false), m -> {}));
        assertEquals(0, off.report().convertedCount(Report.Kind.ARMOR));
        assertNull(off.pack().files().get("attachables/snouli/gear/ruby_helmet.attachable.json"));
        assertFalse(definition(off, "gear:ruby_helmet").has("components"));
        assertFalse(definition(off, "food:soup").has("components"));
        assertEquals("§2Suppe", definition(off, "food:soup").get("display_name").getAsString());
    }

    @Test
    void componentStringsAreParsed() {
        Map<String, Object> parsed = Snbt.components("[minecraft:max_stack_size=16, food={nutrition:4,saturation:2.4f,nested:{a:[1b,2b]}},"
            + "!minecraft:tool,custom_name='{\"text\":\"x, y ] }\"}',lore=[\"a\\\"b\"],ints=[I;1,2],flag=true,id=\"minecraft:stone\"]");
        assertEquals(16.0, parsed.get("max_stack_size"));
        assertEquals(2.4, (Double) ((Map<?, ?>) parsed.get("food")).get("saturation"), 1e-6);
        assertEquals(List.of(1.0, 2.0), ((Map<?, ?>) ((Map<?, ?>) parsed.get("food")).get("nested")).get("a"));
        assertTrue(parsed.get("tool") == Snbt.REMOVED);
        assertEquals("{\"text\":\"x, y ] }\"}", parsed.get("custom_name"));
        assertEquals(List.of("a\"b"), parsed.get("lore"));
        assertEquals(List.of(1.0, 2.0), parsed.get("ints"));
        assertEquals(true, parsed.get("flag"));
        assertEquals("minecraft:stone", parsed.get("id"));

        assertTrue(Snbt.components(null).isEmpty());
        assertTrue(Snbt.components("").isEmpty());
        assertTrue(Snbt.components("[]").isEmpty());
        // a damaged tail keeps what was readable and never throws
        assertEquals(16.0, Snbt.components("[max_stack_size=16,food={nutrition:").get("max_stack_size"));
        assertTrue(ItemProperties.parse("kein snbt").isEmpty());
    }

    @Test
    void hexColoursBecomeTheNearestBedrockColour() {
        assertEquals("§cRot §9Blau", BedrockText.downsampleHex("§x§F§F§4§0§4§0Rot §x§4§0§4§0§f§fBlau"));
        assertEquals("§6Gold", BedrockText.downsampleHex("&#ffaa00Gold"));
        assertEquals("§aohne Hex §lfett", BedrockText.downsampleHex("§aohne Hex §lfett"));
        assertEquals("§fWeiss§0Schwarz", BedrockText.downsampleHex("§#ffffffWeiss§#000000Schwarz"));
    }
}
