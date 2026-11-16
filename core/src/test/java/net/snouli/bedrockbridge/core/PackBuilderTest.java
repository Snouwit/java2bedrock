package net.snouli.bedrockbridge.core;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.imageio.ImageIO;

class PackBuilderTest {

    @TempDir
    static Path temp;
    static Path zip;
    static BuildOutput output;
    static Report report;

    @BeforeAll
    static void build() throws IOException {
        zip = TestPack.standard().write(temp.resolve("generated.zip"));
        output = PackBuilder.build(BuildInput.packOnly(zip, "Test"));
        report = output.report();
    }

    private static BufferedImage image(String path) throws IOException {
        byte[] data = output.pack().files().get(path);
        assertNotNull(data, path + " fehlt im Bedrock-Paket");
        return ImageIO.read(new ByteArrayInputStream(data));
    }

    private static JsonObject json(String path) {
        return JsonParser.parseString(new String(output.pack().files().get(path), StandardCharsets.UTF_8)).getAsJsonObject();
    }

    private static String skipReason(Report.Kind kind, String nameContains) {
        return report.skipped(kind).stream().filter(e -> e.name().contains(nameContains))
            .map(Report.Entry::detail).findFirst().orElse(null);
    }

    private static JsonObject item(String vanilla, int customModelData) {
        JsonArray definitions = output.mappings().getAsJsonObject("items").getAsJsonObject("items").getAsJsonArray(vanilla);
        if (definitions != null) {
            for (JsonElement el : definitions) {
                JsonObject definition = el.getAsJsonObject();
                if (definition.has("custom_model_data") && definition.get("custom_model_data").getAsInt() == customModelData) {
                    return definition;
                }
            }
        }
        return null;
    }

    @Test
    void glyphsLandOnTheirCodePoint() throws IOException {
        BufferedImage sheet = image("font/glyph_E1.png");
        int cell = sheet.getWidth() / 16;
        assertEquals(sheet.getWidth(), sheet.getHeight());
        // U+E100: row 0, column 0 - a square icon fills its cell
        assertEquals(0xFFFF0000, sheet.getRGB(1, 1));
        assertEquals(0xFFFF0000, sheet.getRGB(cell - 2, cell - 2));
        // U+E1A5: row A, column 5 - wide image is fitted to the cell width and centred vertically
        assertEquals(0xFF00FF00, sheet.getRGB(5 * cell + 1, 10 * cell + cell / 2));
        assertEquals(0xFF00FF00, sheet.getRGB(6 * cell - 2, 10 * cell + cell / 2));
        assertEquals(0, sheet.getRGB(5 * cell + 1, 10 * cell + 1) >>> 24);
        assertEquals(0, sheet.getRGB(5 * cell + 1, 11 * cell - 2) >>> 24);
        // neighbouring cells stay empty
        assertEquals(0, sheet.getRGB(cell + 1, 1) >>> 24);
        assertEquals(0, sheet.getRGB(4 * cell + cell / 2, 10 * cell + 1) >>> 24);
    }

    @Test
    void glyphGridsAndReferencedFontsAreSplit() throws IOException {
        BufferedImage sheet = image("font/glyph_E2.png");
        int cell = sheet.getWidth() / 16;
        // height 8 / ascent 7 sits one GUI pixel below the line top, so sample mid-cell
        assertEquals(0xFFFFFF00, sheet.getRGB(1, cell + cell / 2)); // U+E210
        assertEquals(0xFF00FFFF, sheet.getRGB(cell + 1, cell + cell / 2)); // U+E211
    }

    @Test
    void unsupportedGlyphsAreReported() {
        assertFalse(output.pack().contains("font/glyph_E0.png"), "GUI-Bild darf kein Sheet erzeugen");
        assertFalse(output.pack().contains("font/glyph_F8.png"), "negative space darf kein Sheet erzeugen");
        assertFalse(output.pack().contains("font/glyph_00.png"), "Zeichen ausserhalb PUA darf kein Sheet erzeugen");
        assertTrue(skipReason(Report.Kind.GLYPH, "U+E000").contains("zu gross"));
        assertTrue(skipReason(Report.Kind.GLYPH, "U+00E9").contains("ausserhalb"));
        assertTrue(skipReason(Report.Kind.GLYPH, "U+E101").contains("transparent"));
        assertTrue(report.warnings(Report.Kind.GLYPH).stream().anyMatch(w -> w.name().contains("U+E1A5")));
        assertTrue(report.notes().stream().anyMatch(n -> n.contains("Abstands-Zeichen")));
        assertTrue(report.notes().stream().anyMatch(n -> n.contains("glyph_E1.png ersetzt")));
        assertEquals(4, report.convertedCount(Report.Kind.GLYPH));
    }

    @Test
    void outsidePrivateUseAreaIsLogged() throws IOException {
        List<String> log = new ArrayList<>();
        PackBuilder.build(new BuildInput(zip, "Test", Set.of(), List.of(), Map.of(), 64, ModelOptions.defaults(), log::add));
        assertEquals(1, log.size());
        assertTrue(log.get(0).contains("U+00E9"));
    }

    @Test
    void flatItemsBecomeLegacyDefinitionsWithIcons() throws IOException {
        JsonObject coin = item("minecraft:paper", 10000);
        assertNotNull(coin);
        assertEquals("legacy", coin.get("type").getAsString());
        assertEquals("shop:coin", coin.get("bedrock_identifier").getAsString());
        assertEquals("Coin", coin.get("display_name").getAsString());
        String icon = coin.getAsJsonObject("bedrock_options").get("icon").getAsString();
        assertFalse(coin.getAsJsonObject("bedrock_options").has("display_handheld"));

        String texture = json("textures/item_texture.json").getAsJsonObject("texture_data").getAsJsonObject(icon)
            .get("textures").getAsString();
        BufferedImage image = image(texture + ".png");
        // sprite ia:0 comes from the applicable overlay (gold), with layer1 (white pixel) drawn on top
        assertEquals(0xFFFFD700, image.getRGB(5, 5));
        assertEquals(0xFFFFFFFF, image.getRGB(0, 0));
    }

    @Test
    void overlayOutsideTheTargetFormatIsIgnored() {
        assertNull(item("minecraft:paper", 999));
    }

    @Test
    void branchingItemModelsUseTheirFallback() {
        JsonObject trimmed = item("minecraft:paper", 10006);
        assertNotNull(trimmed);
        assertEquals("shop:coin_paper_10006", trimmed.get("bedrock_identifier").getAsString());
    }

    @Test
    void legacyOverridesAndHandheldAreDetected() {
        JsonObject sword = item("minecraft:diamond_sword", 7);
        assertNotNull(sword);
        assertEquals("shop:sword", sword.get("bedrock_identifier").getAsString());
        assertTrue(sword.getAsJsonObject("bedrock_options").get("display_handheld").getAsBoolean());
        assertEquals(1, output.mappings().getAsJsonObject("items").getAsJsonObject("items")
            .getAsJsonArray("minecraft:diamond_sword").size());
    }

    @Test
    void unsupportedItemsAreSkippedWithReasons() {
        assertNull(item("minecraft:paper", 10002));
        assertTrue(skipReason(Report.Kind.ITEM, "CMD 10002").contains("Vanilla-Textur"));
        assertNull(item("minecraft:paper", 10003));
        assertNull(skipReason(Report.Kind.ITEM, "CMD 10003"), "Vanilla-Modelle sind kein Fehler");
        assertTrue(skipReason(Report.Kind.ITEM, "CMD 10005").contains("Spezial-Modell"));
    }

    @Test
    void blockItemsGetAFlatIcon() throws IOException {
        JsonObject ruby = item("minecraft:paper", 10004);
        assertNotNull(ruby);
        String icon = ruby.getAsJsonObject("bedrock_options").get("icon").getAsString();
        String texture = json("textures/item_texture.json").getAsJsonObject("texture_data").getAsJsonObject(icon)
            .get("textures").getAsString();
        assertEquals(0xFFAA0000, image(texture + ".png").getRGB(8, 8));
    }

    @Test
    void noVanillaTexturesAreCopied() {
        for (String path : output.pack().files().keySet()) {
            assertFalse(path.contains("stone"), path);
        }
    }

    @Test
    void cubeBlocksBecomeStateOverrides() {
        JsonObject blocks = output.mappings().getAsJsonObject("blocks");
        assertEquals(1, blocks.get("format_version").getAsInt());
        JsonObject note = blocks.getAsJsonObject("blocks").getAsJsonObject("minecraft:note_block");
        assertTrue(note.get("only_override_states").getAsBoolean());
        JsonObject overrides = note.getAsJsonObject("state_overrides");
        assertEquals(Set.of(TestPack.NOTE_STATE), overrides.keySet(), "Zustand muss alphabetisch sortiert sein");
        JsonObject ruby = overrides.getAsJsonObject(TestPack.NOTE_STATE);
        assertTrue(ruby.get("unit_cube").getAsBoolean());
        JsonObject all = ruby.getAsJsonObject("material_instances").getAsJsonObject("*");
        assertEquals("opaque", all.get("render_method").getAsString());

        JsonObject terrain = json("textures/terrain_texture.json").getAsJsonObject("texture_data");
        String path = terrain.getAsJsonObject(all.get("texture").getAsString()).get("textures").getAsString();
        assertTrue(output.pack().contains(path + ".png"));
    }

    @Test
    void multiTextureBlocksKeepTheirFaces() {
        JsonObject crate = output.mappings().getAsJsonObject("blocks").getAsJsonObject("blocks")
            .getAsJsonObject("minecraft:red_mushroom_block").getAsJsonObject("state_overrides")
            .getAsJsonObject(TestPack.MUSHROOM_STATE);
        JsonObject instances = crate.getAsJsonObject("material_instances");
        assertEquals(Set.of("up", "down", "north", "south", "west", "east"), instances.keySet());
        assertTrue(instances.getAsJsonObject("up").get("texture").getAsString().endsWith("crate_top"));
        assertTrue(instances.getAsJsonObject("east").get("texture").getAsString().endsWith("crate_side"));
        assertEquals("alpha_test", instances.getAsJsonObject("east").get("render_method").getAsString());
        assertEquals("opaque", instances.getAsJsonObject("up").get("render_method").getAsString());
    }

    @Test
    void unsupportedBlocksAreSkippedWithReasons() {
        assertTrue(skipReason(Report.Kind.BLOCK, "note=4").contains("komplexes Modell"));
        assertTrue(skipReason(Report.Kind.BLOCK, "note=5").contains("Vanilla-Textur"));
        assertTrue(skipReason(Report.Kind.BLOCK, "note_block[instrument=banjo]").contains("unvollstaendig"));
        assertTrue(skipReason(Report.Kind.BLOCK, "fire[").contains("nicht unterstuetzt"));
        assertFalse(output.mappings().getAsJsonObject("blocks").getAsJsonObject("blocks").has("minecraft:fire"));
        assertEquals(2, report.convertedCount(Report.Kind.BLOCK));
    }

    @Test
    void itemsAdderDataProvidesNamesIdsAndItemModels() throws IOException {
        List<IaItem> items = List.of(
            new IaItem("economy:gold_coin", "§6Goldmuenze", "minecraft:paper", 10000, null, false, false, null),
            new IaItem("deco:lamp", "Lampe", "minecraft:paper", 10001, null, false, true, null),
            new IaItem("economy:gem", "Edelstein", "minecraft:emerald", null, "shop:gem", false, false, null),
            new IaItem("blocks:ruby_block", "Rubinblock", "minecraft:paper", 10004, null, true, false,
                "minecraft:note_block[instrument=banjo,note=3,powered=false]"));
        BuildOutput built = PackBuilder.build(new BuildInput(zip, "Test", Set.of(), items,
            Map.of("ranks:owner", ""), 64, ModelOptions.defaults(), message -> {}));

        JsonObject mapped = built.mappings().getAsJsonObject("items").getAsJsonObject("items");
        JsonObject coin = mapped.getAsJsonArray("minecraft:paper").get(0).getAsJsonObject();
        assertEquals("economy:gold_coin", coin.get("bedrock_identifier").getAsString());
        assertEquals("§6Goldmuenze", coin.get("display_name").getAsString());

        JsonObject gem = mapped.getAsJsonArray("minecraft:emerald").get(0).getAsJsonObject();
        assertEquals("definition", gem.get("type").getAsString());
        assertEquals("shop:gem", gem.get("model").getAsString());
        assertEquals("economy:gem", gem.get("bedrock_identifier").getAsString());

        assertTrue(built.report().converted(Report.Kind.MODEL).stream()
            .anyMatch(e -> e.name().startsWith("deco:lamp") && e.detail().contains("deco:lamp [Moebel")));
        assertTrue(built.report().converted(Report.Kind.GLYPH).stream().anyMatch(e -> e.name().contains("ranks:owner")));

        JsonObject ruby = built.mappings().getAsJsonObject("blocks").getAsJsonObject("blocks")
            .getAsJsonObject("minecraft:note_block").getAsJsonObject("state_overrides").getAsJsonObject(TestPack.NOTE_STATE);
        assertEquals("Rubinblock", ruby.get("display_name").getAsString());
        assertTrue(built.report().converted(Report.Kind.BLOCK).stream().anyMatch(e -> e.name().startsWith("blocks:ruby_block")));
    }

    @Test
    void skipNamespacesLeavesContentOut() throws IOException {
        BuildOutput built = PackBuilder.build(new BuildInput(zip, "Test", Set.of("shop", "ranks", "deco", "gear"), List.of(),
            Map.of(), 64, ModelOptions.defaults(), message -> {}));
        assertEquals(0, built.report().convertedCount(Report.Kind.ITEM));
        assertEquals(0, built.report().convertedCount(Report.Kind.BLOCK));
        assertEquals(0, built.report().convertedCount(Report.Kind.GLYPH));
        assertEquals(0, built.report().convertedCount(Report.Kind.MODEL));
        assertTrue(built.pack().files().isEmpty());
        assertFalse(built.mappings().has("display_entities"));
    }

    @Test
    void buildsAreReproducible() throws IOException {
        BuildOutput again = PackBuilder.build(BuildInput.packOnly(zip, "Test"));
        assertEquals(output.pack().contentHash(), again.pack().contentHash());
        assertEquals(output.mappings(), again.mappings());
    }

    @Test
    void protectedPngsWithBrokenChecksumsAreRead(@TempDir Path dir) throws IOException {
        TestPack pack = TestPack.standard();
        byte[] broken = TestPack.corruptChecksums(pack.get("assets/shop/textures/coin.png"));
        assertThrows(IOException.class, () -> {
            if (ImageIO.read(new ByteArrayInputStream(broken)) == null) {
                throw new IOException("unreadable");
            }
        }, "Test setzt voraus, dass ImageIO die Datei ablehnt");
        pack.bytes("assets/shop/textures/coin.png", broken);
        BuildOutput built = PackBuilder.build(BuildInput.packOnly(pack.write(dir.resolve("protected.zip")), "Test"));
        assertArrayEquals(output.pack().files().get("textures/items/shop/coin.png"),
            built.pack().files().get("textures/items/shop/coin.png"));
    }

    @Test
    void brokenZipFailsInsteadOfProducingAnEmptyPack(@TempDir Path dir) throws IOException {
        Path noMeta = new TestPack().text("assets/minecraft/font/default.json", "{}").write(dir.resolve("nometa.zip"));
        assertThrows(IOException.class, () -> PackBuilder.build(BuildInput.packOnly(noMeta, "Test")));
        assertThrows(IOException.class, () -> PackBuilder.build(BuildInput.packOnly(dir.resolve("missing.zip"), "Test")));
    }
}
