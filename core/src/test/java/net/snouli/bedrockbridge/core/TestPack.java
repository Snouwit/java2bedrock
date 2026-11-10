package net.snouli.bedrockbridge.core;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import javax.imageio.ImageIO;

/** Builds a small Java resource pack shaped like ItemsAdder's output (overlays, sprite ids, block states). */
final class TestPack {

    static final String NOTE_STATE = "instrument=banjo,note=3,powered=false";
    static final String MUSHROOM_STATE = "down=true,east=false,north=true,south=false,up=true,west=false";

    private final Map<String, byte[]> files = new LinkedHashMap<>();

    static TestPack standard() throws IOException {
        TestPack pack = new TestPack();
        pack.text("pack.mcmeta", """
            {"pack":{"pack_format":46,"description":"test"},
             "overlays":{"entries":[
               {"directory":"old_overlay","formats":[40,45],"min_format":40,"max_format":45},
               {"directory":"new_overlay","formats":[46,99],"min_format":46,"max_format":99}]}}""");

        // Fonts: square icon, wide tag, GUI image, negative space, non-PUA character, empty image
        pack.text("assets/minecraft/font/default.json", """
            {"providers":[
              {"type":"bitmap","file":"ranks:font/icon.png","chars":["\\uE100"],"height":9,"ascent":8},
              {"type":"bitmap","file":"ranks:font/wide.png","chars":["\\uE1A5"],"height":9,"ascent":8},
              {"type":"bitmap","file":"ranks:font/gui.png","chars":["\\uE000"],"height":170,"ascent":47},
              {"type":"bitmap","file":"char/black.png","chars":["\\uF801"],"height":-3,"ascent":-5000},
              {"type":"bitmap","file":"ranks:font/icon.png","chars":["\\u00E9"],"height":9,"ascent":8},
              {"type":"bitmap","file":"ranks:font/empty.png","chars":["\\uE101"],"height":9,"ascent":8},
              {"type":"reference","id":"minecraft:extra"}]}""");
        pack.text("assets/minecraft/font/extra.json", """
            {"providers":[{"type":"bitmap","file":"ranks:font/grid.png","chars":["\\uE210\\uE211"],"height":8,"ascent":7}]}""");
        pack.png("assets/ranks/textures/font/icon.png", solid(16, 16, 0xFFFF0000));
        pack.png("assets/ranks/textures/font/wide.png", solid(40, 8, 0xFF00FF00));
        pack.png("assets/ranks/textures/font/gui.png", solid(192, 170, 0xFF0000FF));
        pack.png("assets/minecraft/textures/char/black.png", solid(1, 1, 0xFF000000));
        pack.png("assets/ranks/textures/font/empty.png", solid(16, 16, 0x00000000));
        BufferedImage grid = solid(16, 8, 0xFFFFFF00);
        for (int y = 0; y < 8; y++) {
            for (int x = 8; x < 16; x++) {
                grid.setRGB(x, y, 0xFF00FFFF);
            }
        }
        pack.png("assets/ranks/textures/font/grid.png", grid);

        // Sprite ids as ItemsAdder writes them; the overlay atlas is the one that counts
        pack.text("assets/minecraft/atlases/blocks.json", """
            {"sources":[{"type":"single","resource":"shop:wrong","sprite":"ia:0"}]}""");
        pack.text("new_overlay/assets/minecraft/atlases/blocks.json", """
            {"sources":[
              {"type":"single","resource":"shop:coin","sprite":"ia:0"},
              {"type":"single","resource":"shop:coin_shine","sprite":"ia:1"},
              {"type":"single","resource":"minecraft:block/stone","sprite":"ia:2"},
              {"type":"single","resource":"shop:block/ruby","sprite":"ia:3"},
              {"type":"single","resource":"shop:block/crate_top","sprite":"ia:4"},
              {"type":"single","resource":"shop:block/crate_side","sprite":"ia:5"}]}""");
        pack.png("assets/shop/textures/coin.png", solid(16, 16, 0xFFFFD700));
        BufferedImage shine = solid(16, 16, 0x00000000);
        shine.setRGB(0, 0, 0xFFFFFFFF);
        pack.png("assets/shop/textures/coin_shine.png", shine);
        pack.png("assets/shop/textures/block/ruby.png", solid(16, 16, 0xFFAA0000));
        pack.png("assets/shop/textures/block/crate_top.png", solid(16, 16, 0xFF8B5A2B));
        BufferedImage side = solid(16, 16, 0xFF6B4A1B);
        side.setRGB(3, 3, 0x00000000);
        pack.png("assets/shop/textures/block/crate_side.png", side);
        pack.png("assets/shop/textures/sword.png", solid(16, 16, 0xFFCCCCCC));

        // Item definitions: only the applicable overlay has them
        pack.text("old_overlay/assets/minecraft/items/paper.json", """
            {"model":{"type":"range_dispatch","property":"custom_model_data","entries":[
              {"threshold":999,"model":{"type":"model","model":"shop:item/coin"}}]}}""");
        pack.text("new_overlay/assets/minecraft/items/paper.json", """
            {"model":{"type":"range_dispatch","property":"custom_model_data","index":0,
              "fallback":{"type":"minecraft:model","model":"minecraft:item/paper"},
              "entries":[
                {"threshold":10000,"model":{"type":"model","model":"shop:item/coin"}},
                {"threshold":10001,"model":{"type":"model","model":"shop:item/lamp"}},
                {"threshold":10002,"model":{"type":"model","model":"shop:item/stone_thing"}},
                {"threshold":10003,"model":{"type":"model","model":"item/paper"}},
                {"threshold":10004,"model":{"type":"model","model":"shop:block/ruby"}},
                {"threshold":10005,"model":{"type":"minecraft:special","base":"x","model":{"type":"minecraft:player_head"}}},
                {"threshold":10006,"model":{"type":"minecraft:select","property":"minecraft:trim_material",
                   "cases":[{"when":"minecraft:gold","model":{"type":"model","model":"minecraft:item/gold"}}],
                   "fallback":{"type":"model","model":"shop:item/coin"}}}]}}""");
        pack.text("assets/shop/models/item/coin.json", """
            {"parent":"minecraft:item/generated","textures":{"layer0":"ia:0","layer1":"ia:1"}}""");
        pack.text("assets/shop/models/item/lamp.json", """
            {"textures":{"0":"ia:0"},"elements":[{"from":[4,0,4],"to":[12,10,12],
              "faces":{"north":{"texture":"#0"}}},{"from":[6,10,6],"to":[10,14,10],"faces":{"up":{"texture":"#0"}}}]}""");
        pack.text("assets/shop/models/item/stone_thing.json", """
            {"parent":"item/generated","textures":{"layer0":"ia:2"}}""");

        // Legacy overrides on an item without item definition
        pack.text("assets/minecraft/models/item/diamond_sword.json", """
            {"parent":"minecraft:item/handheld","textures":{"layer0":"minecraft:item/diamond_sword"},"overrides":[
              {"predicate":{"custom_model_data":7},"model":"shop:item/sword"},
              {"predicate":{"custom_model_data":7,"trim_type":0.1},"model":"shop:item/coin"}]}""");
        pack.text("assets/shop/models/item/sword.json", """
            {"parent":"shop:item/sword_base","textures":{"layer0":"shop:sword"}}""");
        pack.text("assets/shop/models/item/sword_base.json", """
            {"parent":"minecraft:item/handheld"}""");

        // item_model component (namespaced item definition)
        pack.text("assets/shop/items/gem.json", """
            {"model":{"type":"minecraft:model","model":"shop:item/coin"}}""");

        // Blocks
        pack.text("assets/minecraft/blockstates/note_block.json", """
            {"variants":{
              "instrument=harp,note=0,powered=false":{"model":"block/original/note_block"},
              "powered=false,note=3,instrument=banjo":{"model":"shop:block/ruby"},
              "instrument=banjo,note=4,powered=false":{"model":"shop:block/statue"},
              "instrument=banjo,note=5,powered=false":{"model":"shop:block/stone_block"},
              "instrument=banjo":{"model":"shop:block/ruby"}}}""");
        pack.text("assets/minecraft/blockstates/red_mushroom_block.json", """
            {"variants":{"%s":[{"model":"shop:block/crate","y":90}]}}""".formatted(MUSHROOM_STATE));
        pack.text("assets/minecraft/blockstates/fire.json", """
            {"variants":{"age=0":{"model":"shop:block/ruby"}}}""");
        pack.text("assets/shop/models/block/ruby.json", """
            {"parent":"shop:block/tile","textures":{"all":"ia:3"}}""");
        pack.text("assets/shop/models/block/tile.json", """
            {"parent":"block/cube_all","display":{}}""");
        pack.text("assets/shop/models/block/crate.json", """
            {"parent":"minecraft:block/cube_bottom_top","textures":{"top":"ia:4","bottom":"ia:4","side":"ia:5"}}""");
        pack.text("assets/shop/models/block/statue.json", """
            {"textures":{"0":"ia:3"},"elements":[{"from":[2,0,2],"to":[14,16,14],"faces":{"north":{"texture":"#0"}}}]}""");
        pack.text("assets/shop/models/block/stone_block.json", """
            {"parent":"block/cube_all","textures":{"all":"ia:2"}}""");
        return pack;
    }

    static BufferedImage solid(int width, int height, int argb) {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                image.setRGB(x, y, argb);
            }
        }
        return image;
    }

    TestPack text(String path, String content) {
        files.put(path, content.getBytes(StandardCharsets.UTF_8));
        return this;
    }

    TestPack png(String path, BufferedImage image) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        files.put(path, out.toByteArray());
        return this;
    }

    TestPack bytes(String path, byte[] data) {
        files.put(path, data);
        return this;
    }

    byte[] get(String path) {
        return files.get(path);
    }

    Path write(Path zipFile) throws IOException {
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(zipFile))) {
            for (Map.Entry<String, byte[]> file : files.entrySet()) {
                zip.putNextEntry(new ZipEntry(file.getKey()));
                zip.write(file.getValue());
                zip.closeEntry();
            }
        }
        return zipFile;
    }

    /** Breaks every chunk CRC and the zlib Adler-32, like ItemsAdder's pack protection does. */
    static byte[] corruptChecksums(byte[] png) {
        byte[] copy = png.clone();
        int position = 8;
        int lastIdatEnd = -1;
        while (position + 8 <= copy.length) {
            int length = (copy[position] & 0xFF) << 24 | (copy[position + 1] & 0xFF) << 16
                | (copy[position + 2] & 0xFF) << 8 | (copy[position + 3] & 0xFF);
            String type = new String(copy, position + 4, 4, StandardCharsets.ISO_8859_1);
            int crcAt = position + 8 + length;
            copy[crcAt] ^= 0x5A;
            if (type.equals("IDAT")) {
                lastIdatEnd = crcAt;
            }
            position = crcAt + 4;
        }
        copy[lastIdatEnd - 1] ^= 0x33; // last Adler-32 byte
        return copy;
    }

    static long crc(byte[] data) {
        CRC32 crc = new CRC32();
        crc.update(data);
        return crc.getValue();
    }
}
