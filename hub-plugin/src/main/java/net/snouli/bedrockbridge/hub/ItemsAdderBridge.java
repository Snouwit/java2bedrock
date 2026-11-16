package net.snouli.bedrockbridge.hub;

import dev.lone.itemsadder.api.CustomBlock;
import dev.lone.itemsadder.api.CustomStack;
import dev.lone.itemsadder.api.Events.ItemsAdderLoadDataEvent;
import dev.lone.itemsadder.api.Events.ItemsAdderPackCompressedEvent;
import dev.lone.itemsadder.api.FontImages.FontImageWrapper;
import dev.lone.itemsadder.api.ItemsAdder;
import net.snouli.bedrockbridge.core.IaItem;
import org.bukkit.block.data.BlockData;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Everything that touches the ItemsAdder API. Only loaded when ItemsAdder is installed, so the
 * plugin still works (zip-only) without it.
 */
final class ItemsAdderBridge implements Listener {

    private final BedrockBridgePlugin plugin;

    ItemsAdderBridge(BedrockBridgePlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPackCompressed(ItemsAdderPackCompressedEvent event) {
        // The zip is written right after this event; the watcher builds once the file is stable.
        plugin.onItemsAdderPackEvent("ItemsAdder hat das Paket neu gepackt");
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onLoadData(ItemsAdderLoadDataEvent event) {
        plugin.onItemsAdderPackEvent("ItemsAdder hat seine Daten geladen");
    }

    static boolean ready() {
        try {
            return ItemsAdder.areItemsLoaded();
        } catch (Throwable t) {
            return false;
        }
    }

    /** Must run on the server thread. */
    static List<IaItem> items(BedrockBridgePlugin plugin) {
        List<IaItem> items = new ArrayList<>();
        int failed = 0;
        for (String id : CustomStack.getNamespacedIdsInRegistry()) {
            try {
                CustomStack stack = CustomStack.getInstance(id);
                if (stack == null) {
                    continue;
                }
                ItemStack itemStack = stack.getItemStack();
                ItemMeta meta = itemStack.getItemMeta();
                Integer customModelData = null;
                String itemModel = null;
                String components = null;
                if (meta != null) {
                    try {
                        // The component patch as text: stable across server versions, parsed by the core
                        components = meta.getAsComponentString();
                    } catch (Throwable ignored) {
                        // server older than 1.20.5: no item properties, the rest still works
                    }
                    if (meta.hasCustomModelData()) {
                        customModelData = meta.getCustomModelData();
                    }
                    try {
                        if (meta.hasItemModel() && meta.getItemModel() != null) {
                            itemModel = meta.getItemModel().toString();
                        }
                    } catch (NoSuchMethodError ignored) {
                        // server older than 1.21.4
                    }
                }
                String blockState = null;
                if (stack.isBlock()) {
                    try {
                        BlockData data = CustomBlock.getBaseBlockData(id);
                        if (data != null) {
                            blockState = data.getAsString();
                        }
                    } catch (Throwable ignored) {
                        // not every block type exposes block data
                    }
                }
                boolean furniture = false;
                try {
                    furniture = CustomStack.isFurniture(itemStack) || CustomStack.isComplexFurniture(itemStack);
                } catch (Throwable ignored) {
                    // older ItemsAdder
                }
                items.add(new IaItem(id, stack.getDisplayName(), itemStack.getType().getKey().toString(),
                    customModelData, itemModel, stack.isBlock(), furniture, blockState, components));
            } catch (Throwable t) {
                failed++;
            }
        }
        if (failed > 0) {
            plugin.getLogger().warning(failed + " ItemsAdder-Items konnten nicht ueber die API gelesen werden.");
        }
        return items;
    }

    static Map<String, String> fontImages() {
        try {
            return new HashMap<>(FontImageWrapper.getNamespacedIdsAndValueInRegistry());
        } catch (Throwable t) {
            return Map.of();
        }
    }
}
