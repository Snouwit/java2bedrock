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
        String displayEntities;

        try (JavaPack javaPack = JavaPack.open(input.javaPack())) {
            Models models = new Models(javaPack);
            report.note("Java-Paket: Format " + javaPack.targetFormat() + ", Ebenen: "
                + String.join(" > ", javaPack.layers().stream().map(l -> l.isEmpty() ? "Basis" : l.replace("/", "")).toList()));
            report.note(input.items().isEmpty()
                ? "Ohne ItemsAdder-API-Daten gebaut: Namen und IDs sind aus den Modell-Pfaden abgeleitet."
                : input.items().size() + " Items aus der ItemsAdder-API.");

            new GlyphConverter(javaPack, input, report).convert(pack);
            ItemConverter itemConverter = new ItemConverter(javaPack, models, input, report);
            items = itemConverter.convert(pack);
            displayEntities = itemConverter.displayEntityMappings();
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
        if (displayEntities != null) {
            mappings.addProperty("display_entities", displayEntities);
            report.note("3D-Modelle an Item-Displays (Moebel) brauchen auf dem Proxy zusaetzlich die Geyser-Extension"
                + " GeyserDisplayEntity; Moebel auf Ruestungsstaendern und 3D-Items in der Hand funktionieren ohne sie.");
        }
        return new BuildOutput(pack, mappings, report);
    }
}
