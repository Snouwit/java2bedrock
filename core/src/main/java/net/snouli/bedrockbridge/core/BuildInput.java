package net.snouli.bedrockbridge.core;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * @param javaPack      ItemsAdder's {@code output/generated.zip}
 * @param packName      name shown in the Bedrock pack list
 * @param skipNamespaces namespaces to leave out entirely
 * @param items         data from the ItemsAdder API; empty = derive everything from the zip alone
 * @param fontImages    ItemsAdder font image id to its character(s), only used for nicer report names
 * @param maxGlyphSize  font images larger than this (pixels) are treated as GUI images and skipped
 * @param models        settings for 3D models and furniture
 * @param itemOptions   settings for armour and item properties
 * @param log           receives warnings that should also show up in the server log
 */
public record BuildInput(Path javaPack, String packName, Set<String> skipNamespaces, List<IaItem> items,
                         Map<String, String> fontImages, int maxGlyphSize, ModelOptions models,
                         ItemOptions itemOptions, Consumer<String> log) {

    public BuildInput(Path javaPack, String packName, Set<String> skipNamespaces, List<IaItem> items,
                      Map<String, String> fontImages, int maxGlyphSize, ModelOptions models, Consumer<String> log) {
        this(javaPack, packName, skipNamespaces, items, fontImages, maxGlyphSize, models, ItemOptions.defaults(), log);
    }

    public static BuildInput packOnly(Path javaPack, String packName) {
        return new BuildInput(javaPack, packName, Set.of(), List.of(), Map.of(), 64, ModelOptions.defaults(), message -> {});
    }
}
