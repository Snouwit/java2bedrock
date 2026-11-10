package net.snouli.bedrockbridge.core;

import com.google.gson.JsonObject;

import java.io.IOException;

/** Turns a Java resource pack (plus optional ItemsAdder API data) into Bedrock pack files and Geyser mappings. */
public final class PackBuilder {

    private PackBuilder() {}

    public static BuildOutput build(BuildInput input) throws IOException {
        Report report = new Report();
        BedrockPack pack = new BedrockPack();
        JsonObject items;
        JsonObject blocks;

        try (JavaPack javaPack = JavaPack.open(input.javaPack())) {
            Models models = new Models(javaPack);
            report.note("Java-Paket: Format " + javaPack.targetFormat() + ", Ebenen: "
                + String.join(" > ", javaPack.layers().stream().map(l -> l.isEmpty() ? "Basis" : l.replace("/", "")).toList()));
            report.note(input.items().isEmpty()
                ? "Ohne ItemsAdder-API-Daten gebaut: Namen und IDs sind aus den Modell-Pfaden abgeleitet."
                : input.items().size() + " Items aus der ItemsAdder-API.");

            new GlyphConverter(javaPack, input, report).convert(pack);
            items = new ItemConverter(javaPack, models, input, report).convert(pack);
            blocks = new BlockConverter(javaPack, models, input, report).convert(pack);
        }

        JsonObject itemsFile = new JsonObject();
        itemsFile.addProperty("format_version", 2);
        itemsFile.add("items", items);
        JsonObject blocksFile = new JsonObject();
        blocksFile.addProperty("format_version", 1);
        blocksFile.add("blocks", blocks);

        // Geyser needs separate files because items (v2) and blocks (v1) use different format versions.
        JsonObject mappings = new JsonObject();
        mappings.add("items", itemsFile);
        mappings.add("blocks", blocksFile);
        return new BuildOutput(pack, mappings, report);
    }
}
