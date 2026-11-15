package net.snouli.bedrockbridge.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Collects what a build converted, skipped and warned about. Rendered to {@code report.txt}. */
public final class Report {

    public enum Kind {
        GLYPH("Font-Bilder (Glyphen)"),
        ITEM("Items"),
        BLOCK("Bloecke"),
        MODEL("3D-Modelle und Moebel");

        final String title;

        Kind(String title) {
            this.title = title;
        }
    }

    public record Entry(String name, String detail) {}

    private final Map<Kind, List<Entry>> converted = new LinkedHashMap<>();
    private final Map<Kind, List<Entry>> skipped = new LinkedHashMap<>();
    private final Map<Kind, List<Entry>> warnings = new LinkedHashMap<>();
    private final List<String> notes = new ArrayList<>();

    public Report() {
        for (Kind kind : Kind.values()) {
            converted.put(kind, new ArrayList<>());
            skipped.put(kind, new ArrayList<>());
            warnings.put(kind, new ArrayList<>());
        }
    }

    public void converted(Kind kind, String name, String detail) {
        converted.get(kind).add(new Entry(name, detail));
    }

    public void skipped(Kind kind, String name, String reason) {
        skipped.get(kind).add(new Entry(name, reason));
    }

    public void warning(Kind kind, String name, String message) {
        warnings.get(kind).add(new Entry(name, message));
    }

    public void note(String note) {
        notes.add(note);
    }

    public int convertedCount(Kind kind) {
        return converted.get(kind).size();
    }

    public int skippedCount(Kind kind) {
        return skipped.get(kind).size();
    }

    public int warningCount() {
        return warnings.values().stream().mapToInt(List::size).sum();
    }

    public List<Entry> converted(Kind kind) {
        return converted.get(kind);
    }

    public List<Entry> skipped(Kind kind) {
        return skipped.get(kind);
    }

    public List<Entry> warnings(Kind kind) {
        return warnings.get(kind);
    }

    public List<String> notes() {
        return notes;
    }

    /** One line per kind, for chat/console. */
    public List<String> summaryLines() {
        List<String> lines = new ArrayList<>();
        for (Kind kind : Kind.values()) {
            lines.add(kind.title + ": " + convertedCount(kind) + " umgewandelt, " + skippedCount(kind)
                + " uebersprungen, " + warnings.get(kind).size() + " Warnungen");
        }
        return lines;
    }

    public String render(String header) {
        StringBuilder out = new StringBuilder();
        out.append(header).append('\n');
        out.append("=".repeat(Math.max(8, header.length()))).append("\n\n");
        out.append("Zusammenfassung\n---------------\n");
        for (String line : summaryLines()) {
            out.append("  ").append(line).append('\n');
        }
        if (!notes.isEmpty()) {
            out.append("\nHinweise\n--------\n");
            for (String note : notes) {
                out.append("  - ").append(note).append('\n');
            }
        }
        for (Kind kind : Kind.values()) {
            out.append('\n').append(kind.title).append('\n').append("-".repeat(kind.title.length())).append('\n');
            section(out, "Umgewandelt", converted.get(kind));
            section(out, "Warnungen", warnings.get(kind));
            section(out, "Uebersprungen", skipped.get(kind));
        }
        return out.toString();
    }

    private static void section(StringBuilder out, String title, List<Entry> entries) {
        out.append("  ").append(title).append(" (").append(entries.size()).append(")\n");
        for (Entry entry : entries) {
            out.append("    ").append(entry.name());
            if (entry.detail() != null && !entry.detail().isEmpty()) {
                out.append("  ->  ").append(entry.detail());
            }
            out.append('\n');
        }
    }
}
