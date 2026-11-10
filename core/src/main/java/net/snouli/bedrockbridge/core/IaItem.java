package net.snouli.bedrockbridge.core;

/**
 * What the ItemsAdder API knows about one custom item. Collected on the server thread and handed
 * to the (asynchronous) build, so the core never touches Bukkit.
 *
 * @param namespacedId    ItemsAdder id, e.g. {@code tutorial:space_sword}
 * @param displayName     display name (legacy {@code §} codes allowed), may be {@code null}
 * @param material        vanilla base item, e.g. {@code minecraft:paper}
 * @param customModelData custom model data of the item stack, or {@code null}
 * @param itemModel       value of the {@code minecraft:item_model} component, or {@code null}
 * @param block           true for custom blocks
 * @param furniture       true for furniture (handled in phase 2)
 * @param blockState      Java block state of a custom block, e.g. {@code minecraft:note_block[...]}, or {@code null}
 */
public record IaItem(String namespacedId, String displayName, String material, Integer customModelData,
                     String itemModel, boolean block, boolean furniture, String blockState) {
}
