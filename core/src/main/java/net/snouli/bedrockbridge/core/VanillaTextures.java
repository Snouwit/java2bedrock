package net.snouli.bedrockbridge.core;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Maps Java vanilla block texture names to the texture that Minecraft Bedrock ships itself.
 *
 * <p>Models may reference vanilla textures (planks, stone, ...). Those are not in the ItemsAdder
 * pack and must not be copied, but a Bedrock pack can simply point at the client's own file. The
 * two editions name most of these files differently, hence the rules below. Every candidate is
 * checked against the list of names that really exist in Bedrock.
 */
final class VanillaTextures {

    /** @param frames number of stacked animation frames in the Bedrock file (1 = not animated) */
    record Texture(String path, int frames) {}

    private static final String[] COLORS = {
        "white", "orange", "magenta", "light_blue", "yellow", "lime", "pink", "gray", "light_gray", "cyan",
        "purple", "blue", "brown", "green", "red", "black"
    };
    private static final String[] OLD_WOODS = {"oak", "spruce", "birch", "jungle", "acacia", "dark_oak"};

    static final Map<String, String> EXPLICIT = Map.ofEntries(
        Map.entry("andesite", "stone_andesite"),
        Map.entry("polished_andesite", "stone_andesite_smooth"),
        Map.entry("diorite", "stone_diorite"),
        Map.entry("polished_diorite", "stone_diorite_smooth"),
        Map.entry("granite", "stone_granite"),
        Map.entry("polished_granite", "stone_granite_smooth"),
        Map.entry("stone_bricks", "stonebrick"),
        Map.entry("mossy_stone_bricks", "stonebrick_mossy"),
        Map.entry("cracked_stone_bricks", "stonebrick_cracked"),
        Map.entry("chiseled_stone_bricks", "stonebrick_carved"),
        Map.entry("mossy_cobblestone", "cobblestone_mossy"),
        Map.entry("bricks", "brick"),
        Map.entry("nether_bricks", "nether_brick"),
        Map.entry("red_nether_bricks", "red_nether_brick"),
        Map.entry("end_stone_bricks", "end_bricks"),
        Map.entry("terracotta", "hardened_clay"),
        Map.entry("smooth_stone", "stone_slab_top"),
        Map.entry("smooth_stone_slab_side", "stone_slab_side"),
        Map.entry("sandstone", "sandstone_normal"),
        Map.entry("cut_sandstone", "sandstone_smooth"),
        Map.entry("chiseled_sandstone", "sandstone_carved"),
        Map.entry("red_sandstone", "red_sandstone_normal"),
        Map.entry("cut_red_sandstone", "red_sandstone_smooth"),
        Map.entry("chiseled_red_sandstone", "red_sandstone_carved"),
        Map.entry("oak_log", "log_oak"),
        Map.entry("dirt_path_top", "grass_path_top"),
        Map.entry("dirt_path_side", "grass_path_side"),
        Map.entry("podzol_top", "dirt_podzol_top"),
        Map.entry("podzol_side", "dirt_podzol_side"),
        Map.entry("coarse_dirt", "coarse_dirt"),
        Map.entry("snow", "snow"),
        Map.entry("melon_side", "melon_side"),
        Map.entry("quartz_block_side", "quartz_block_side"),
        Map.entry("quartz_block_top", "quartz_block_top"),
        Map.entry("quartz_block_bottom", "quartz_block_bottom"),
        Map.entry("chiseled_quartz_block", "quartz_block_chiseled"),
        Map.entry("chiseled_quartz_block_top", "quartz_block_chiseled_top"),
        Map.entry("quartz_pillar", "quartz_block_lines"),
        Map.entry("quartz_pillar_top", "quartz_block_lines_top"),
        Map.entry("purpur_pillar", "purpur_pillar"),
        Map.entry("prismarine_bricks", "prismarine_bricks"),
        Map.entry("dark_prismarine", "prismarine_dark"),
        Map.entry("prismarine", "prismarine_rough"),
        Map.entry("glass", "glass"),
        Map.entry("tinted_glass", "tinted_glass"),
        Map.entry("iron_bars", "iron_bars"),
        Map.entry("oak_trapdoor", "trapdoor"),
        Map.entry("iron_trapdoor", "iron_trapdoor"),
        Map.entry("spawner", "mob_spawner"),
        Map.entry("bookshelf", "bookshelf"),
        Map.entry("nether_quartz_ore", "quartz_ore"),
        Map.entry("magma", "magma"),
        Map.entry("wet_sponge", "sponge_wet"),
        Map.entry("hay_block_side", "hay_block_side"),
        Map.entry("hay_block_top", "hay_block_top"),
        Map.entry("redstone_lamp", "redstone_lamp_off"),
        Map.entry("redstone_lamp_on", "redstone_lamp_on"),
        Map.entry("jack_o_lantern", "pumpkin_face_on"),
        Map.entry("carved_pumpkin", "pumpkin_face_off"),
        Map.entry("furnace_front", "furnace_front_off"),
        Map.entry("crafting_table_top", "crafting_table_top"),
        Map.entry("cobweb", "web"),
        Map.entry("torch", "torch_on"),
        Map.entry("soul_torch", "soul_torch"),
        Map.entry("anvil", "anvil_base"),
        Map.entry("anvil_top", "anvil_top_damaged_0"),
        Map.entry("note_block", "noteblock"),
        Map.entry("lapis_block", "lapis_block"),
        Map.entry("ice", "ice"),
        Map.entry("packed_ice", "ice_packed"),
        Map.entry("farmland", "farmland_dry"),
        Map.entry("farmland_moist", "farmland_wet"),
        Map.entry("mycelium_top", "mycelium_top"),
        Map.entry("mushroom_block_inside", "mushroom_block_inside"),
        Map.entry("brown_mushroom_block", "mushroom_block_skin_brown"),
        Map.entry("red_mushroom_block", "mushroom_block_skin_red"),
        Map.entry("mushroom_stem", "mushroom_block_skin_stem"),
        Map.entry("oak_leaves", "leaves_oak"),
        Map.entry("water_still", "water_still_grey"),
        Map.entry("lava_still", "lava_still"));

    private final Map<String, Integer> known = new HashMap<>();
    /** File name without directory to full name, only where that is unambiguous. */
    private final Map<String, String> byBaseName = new HashMap<>();

    VanillaTextures() {
        try (InputStream in = VanillaTextures.class.getResourceAsStream("bedrock_block_textures.txt")) {
            if (in == null) {
                throw new IllegalStateException("bedrock_block_textures.txt fehlt im Plugin");
            }
            BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            for (String line; (line = reader.readLine()) != null; ) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) {
                    continue;
                }
                String[] parts = line.split(" ");
                known.put(parts[0], parts.length > 1 ? Integer.parseInt(parts[1]) : 1);
            }
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        Map<String, Integer> counts = new HashMap<>();
        for (String name : known.keySet()) {
            counts.merge(name.substring(name.lastIndexOf('/') + 1), 1, Integer::sum);
        }
        for (String name : known.keySet()) {
            String base = name.substring(name.lastIndexOf('/') + 1);
            if (counts.get(base) == 1) {
                byBaseName.put(base, name);
            }
        }
    }

    boolean exists(String bedrockName) {
        return known.containsKey(bedrockName);
    }

    /** @return the Bedrock texture for a Java {@code minecraft:block/...} texture, or {@code null} if unknown */
    Texture resolve(Id javaTexture) {
        if (!javaTexture.namespace().equals("minecraft") || !javaTexture.path().startsWith("block/")) {
            return null;
        }
        String name = javaTexture.path().substring("block/".length());
        for (String candidate : candidates(name)) {
            String full = known.containsKey(candidate) ? candidate : byBaseName.get(candidate);
            if (full != null) {
                return new Texture("textures/blocks/" + full, known.get(full));
            }
        }
        return null;
    }

    private static List<String> candidates(String name) {
        List<String> result = new ArrayList<>();
        String explicit = EXPLICIT.get(name);
        if (explicit != null) {
            result.add(explicit);
        }
        for (String wood : OLD_WOODS) {
            String bedrockWood = wood.equals("dark_oak") ? "big_oak" : wood;
            if (name.equals(wood + "_planks")) {
                result.add("planks_" + bedrockWood);
            } else if (name.equals(wood + "_log")) {
                result.add("log_" + bedrockWood);
            } else if (name.equals(wood + "_log_top")) {
                result.add("log_" + bedrockWood + "_top");
            } else if (name.equals(wood + "_leaves")) {
                result.add("leaves_" + bedrockWood);
            } else if (name.equals("stripped_" + wood + "_log")) {
                result.add("stripped_" + wood + "_log");
            }
        }
        for (String color : COLORS) {
            String bedrockColor = color.equals("light_gray") ? "silver" : color;
            if (name.equals(color + "_wool")) {
                result.add("wool_colored_" + bedrockColor);
            } else if (name.equals(color + "_concrete")) {
                result.add("concrete_" + bedrockColor);
            } else if (name.equals(color + "_concrete_powder")) {
                result.add("concrete_powder_" + bedrockColor);
            } else if (name.equals(color + "_terracotta")) {
                result.add("hardened_clay_stained_" + bedrockColor);
            } else if (name.equals(color + "_glazed_terracotta")) {
                result.add("glazed_terracotta_" + bedrockColor);
            } else if (name.equals(color + "_stained_glass")) {
                result.add("glass_" + bedrockColor);
            } else if (name.equals(color + "_shulker_box")) {
                result.add("shulker_top_" + bedrockColor);
            }
        }
        result.add(name);
        return result;
    }
}
