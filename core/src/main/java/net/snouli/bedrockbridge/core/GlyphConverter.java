package net.snouli.bedrockbridge.core;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.awt.AlphaComposite;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Draws the bitmap glyphs of the Java default font into Bedrock glyph sheets
 * ({@code font/glyph_XX.png}, a 16x16 grid per sheet) at the same code points.
 *
 * <p>Bedrock limits: every glyph lives in a square cell that is rendered one text line high, the
 * width is taken from the non-transparent pixels, and there are no negative advances or vertical
 * offsets. Wide images therefore shrink, and ItemsAdder's negative-space/GUI glyphs cannot work.
 */
final class GlyphConverter {

    /** Java text line height in GUI pixels; a glyph of this height fills a Bedrock cell. */
    private static final double LINE_HEIGHT = 9.0;
    /** Sheets that Bedrock itself ships (controller/touch button hints and HUD icons). */
    private static final Set<Integer> VANILLA_BEDROCK_SHEETS = Set.of(0xE0, 0xE1);

    private final JavaPack pack;
    private final BuildInput input;
    private final Report report;

    GlyphConverter(JavaPack pack, BuildInput input, Report report) {
        this.pack = pack;
        this.input = input;
        this.report = report;
    }

    private record Glyph(int codePoint, BufferedImage image, int height, int ascent, String label) {
        double javaWidth() {
            return image.getWidth() * (double) height / image.getHeight();
        }

        /** GUI pixels that map onto one cell edge. */
        double reference() {
            return Math.max(LINE_HEIGHT, Math.max(javaWidth(), height));
        }
    }

    void convert(BedrockPack out) throws IOException {
        List<JsonObject> providers = new ArrayList<>();
        collectProviders(new Id("minecraft", "default"), providers, new HashSet<>());
        if (providers.isEmpty()) {
            report.note("Kein assets/minecraft/font/default.json im Paket - keine Font-Bilder.");
            return;
        }

        Map<String, String> names = new TreeMap<>();
        for (Map.Entry<String, String> entry : input.fontImages().entrySet()) {
            names.putIfAbsent(entry.getValue(), entry.getKey());
        }

        TreeMap<Integer, Glyph> glyphs = new TreeMap<>();
        int spacing = 0;
        for (JsonObject provider : providers) {
            String type = provider.has("type") ? Id.parse(provider.get("type").getAsString()).path() : "";
            if (type.equals("space")) {
                spacing += provider.get("advances") instanceof JsonObject advances ? advances.size() : 0;
                continue;
            }
            if (!type.equals("bitmap") || !provider.has("file") || !(provider.get("chars") instanceof JsonArray chars)) {
                continue;
            }
            Id file = Id.parse(provider.get("file").getAsString());
            String path = "assets/" + file.namespace() + "/textures/" + file.path();
            int height = provider.has("height") ? provider.get("height").getAsInt() : 8;
            int ascent = provider.has("ascent") ? provider.get("ascent").getAsInt() : 7;

            List<int[]> rows = new ArrayList<>();
            int columns = 0;
            for (JsonElement row : chars) {
                int[] codePoints = row.getAsString().codePoints().toArray();
                rows.add(codePoints);
                columns = Math.max(columns, codePoints.length);
            }
            if (rows.isEmpty() || columns == 0) {
                continue;
            }

            // ItemsAdder's negative-space glyphs: a 1x1 image far off-screen or with a negative height.
            if (height <= 0 || ascent <= -1000) {
                for (int[] row : rows) {
                    spacing += row.length;
                }
                continue;
            }

            BufferedImage sheet = null;
            for (int r = 0; r < rows.size(); r++) {
                int[] row = rows.get(r);
                for (int c = 0; c < row.length; c++) {
                    int codePoint = row[c];
                    if (codePoint == 0) {
                        continue;
                    }
                    String symbol = new String(Character.toChars(codePoint));
                    String label = String.format("U+%04X %s", codePoint,
                        names.containsKey(symbol) ? names.get(symbol) : file.toString());
                    if (input.skipNamespaces().contains(file.namespace())) {
                        report.skipped(Report.Kind.GLYPH, label, "Namespace in skip-namespaces");
                        continue;
                    }
                    if (codePoint < 0xE000 || codePoint > 0xF8FF) {
                        report.skipped(Report.Kind.GLYPH, label,
                            "Zeichen liegt ausserhalb des Private-Use-Bereichs E000-F8FF");
                        input.log().accept("WARNUNG: Font-Bild " + label + " nutzt ein Zeichen ausserhalb von E000-F8FF"
                            + " und wird fuer Bedrock nicht umgewandelt.");
                        continue;
                    }
                    if (sheet == null) {
                        sheet = pack.image(path);
                        if (sheet == null) {
                            report.skipped(Report.Kind.GLYPH, label, "Bilddatei fehlt im Paket: " + path);
                            continue;
                        }
                        sheet = Images.toArgb(sheet);
                    }
                    int cellWidth = sheet.getWidth() / columns;
                    int cellHeight = sheet.getHeight() / rows.size();
                    if (cellWidth <= 0 || cellHeight <= 0) {
                        report.skipped(Report.Kind.GLYPH, label, "Bild ist kleiner als das Zeichenraster");
                        continue;
                    }
                    BufferedImage image = sheet.getSubimage(c * cellWidth, r * cellHeight, cellWidth, cellHeight);
                    if (Math.max(cellWidth, cellHeight) > input.maxGlyphSize() || height > input.maxGlyphSize()) {
                        report.skipped(Report.Kind.GLYPH, label, "zu gross fuer ein Bedrock-Glyph (" + cellWidth + "x"
                            + cellHeight + ", Hoehe " + height + ") - GUI-/Hintergrundbild");
                        continue;
                    }
                    if (Images.isFullyTransparent(image)) {
                        report.skipped(Report.Kind.GLYPH, label, "Bild ist komplett transparent");
                        continue;
                    }
                    glyphs.put(codePoint, new Glyph(codePoint, image, height, ascent, label));
                }
            }
        }

        if (spacing > 0) {
            report.note(spacing + " Abstands-Zeichen (negative space / Offsets von ItemsAdder) wurden nicht umgewandelt:"
                + " Bedrock kennt keine negativen Zeichenbreiten. Texte, die damit Bilder verschieben (HUDs, GUI-Titel),"
                + " sehen auf Bedrock anders aus.");
        }

        TreeMap<Integer, List<Glyph>> pages = new TreeMap<>();
        for (Glyph glyph : glyphs.values()) {
            pages.computeIfAbsent(glyph.codePoint() >> 8, k -> new ArrayList<>()).add(glyph);
        }
        for (Map.Entry<Integer, List<Glyph>> page : pages.entrySet()) {
            writeSheet(out, page.getKey(), page.getValue());
        }
    }

    private void writeSheet(BedrockPack out, int page, List<Glyph> glyphs) throws IOException {
        // Pick a cell resolution that keeps every glyph at least at its source resolution.
        double needed = 16;
        for (Glyph glyph : glyphs) {
            needed = Math.max(needed, glyph.reference() * glyph.image().getHeight() / glyph.height());
        }
        int cell = 16;
        while (cell < needed && cell < 256) {
            cell *= 2;
        }

        BufferedImage sheet = new BufferedImage(cell * 16, cell * 16, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = sheet.createGraphics();
        g.setComposite(AlphaComposite.Src);
        for (Glyph glyph : glyphs) {
            double reference = glyph.reference();
            double unit = cell / reference;
            int width = (int) Math.max(1, Math.min(cell, Math.round(glyph.javaWidth() * unit)));
            int height = (int) Math.max(1, Math.min(cell, Math.round(glyph.height() * unit)));
            // Java draws the top of a glyph (ascent) pixels above the baseline, which sits 7px below the
            // line top. Shift by one so the common "height 9, ascent 8" icon starts at the cell top.
            double top = Math.max(0, Math.min(reference - glyph.height(), 8 - glyph.ascent()));
            int x = (glyph.codePoint() & 0xF) * cell;
            int y = ((glyph.codePoint() >> 4) & 0xF) * cell + (int) Math.min(cell - height, Math.round(top * unit));
            g.drawImage(Images.scale(glyph.image(), width, height), x, y, null);

            String detail = String.format("glyph_%02X.png, %dx%d px", page, glyph.image().getWidth(), glyph.image().getHeight());
            report.converted(Report.Kind.GLYPH, glyph.label(), detail);

            double aspect = glyph.javaWidth() / Math.max(glyph.height(), LINE_HEIGHT);
            if (aspect > 1.5) {
                report.warning(Report.Kind.GLYPH, glyph.label(), String.format(
                    "breites Bild (%dx%d): Bedrock-Glyphen sind quadratisch, es wird ca. %.0f%% so gross wie auf Java"
                        + " dargestellt. Quadratische Icons (z. B. 16x16) bleiben gross.",
                    glyph.image().getWidth(), glyph.image().getHeight(), 100.0 / aspect));
            }
            if (glyph.ascent() < 0 || glyph.ascent() > glyph.height() + 8) {
                report.warning(Report.Kind.GLYPH, glyph.label(), "ascent " + glyph.ascent()
                    + ": Java verschiebt das Bild vertikal, Bedrock zeichnet es in der Textzeile.");
            }
        }
        g.dispose();
        out.put(String.format("font/glyph_%02X.png", page), Images.png(sheet));

        if (VANILLA_BEDROCK_SHEETS.contains(page)) {
            report.note(String.format("glyph_%02X.png ersetzt ein Bedrock-eigenes Sheet (Tasten-Symbole fuer Controller/Touch"
                + " bzw. HUD-Icons in Hinweistexten). Diese Bedrock-Symbole fehlen, solange ItemsAdder Zeichen im Bereich"
                + " U+%02X00-U+%02XFF vergibt.", page, page, page));
        }
    }

    private void collectProviders(Id font, List<JsonObject> out, Set<String> visited) {
        if (!visited.add(font.toString())) {
            return;
        }
        JsonObject json = pack.json("assets/" + font.namespace() + "/font/" + font.path() + ".json");
        if (json == null || !(json.get("providers") instanceof JsonArray providers)) {
            return;
        }
        for (JsonElement el : providers) {
            if (!(el instanceof JsonObject provider)) {
                continue;
            }
            String type = provider.has("type") ? Id.parse(provider.get("type").getAsString()).path() : "";
            if (type.equals("reference") && provider.has("id")) {
                collectProviders(Id.parse(provider.get("id").getAsString()), out, visited);
            } else {
                out.add(provider);
            }
        }
    }
}
