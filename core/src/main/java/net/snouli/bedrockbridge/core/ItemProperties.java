package net.snouli.bedrockbridge.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The item components that matter on Bedrock, read from an item's component patch (what the item
 * changes compared to its vanilla base item). {@code null} fields are unchanged from the base item.
 */
final class ItemProperties {

    static final ItemProperties NONE = new ItemProperties(Map.of());

    /** Lower-case slot name ({@code head}, {@code chest}, {@code legs}, {@code feet}, ...). */
    final String equipSlot;
    /** Equipment asset id ({@code namespace:name}) of the worn model. */
    final Id equipAsset;
    final Integer maxStackSize;
    final Integer maxDamage;
    final Integer nutrition;
    final Double saturation;
    final Boolean canAlwaysEat;
    final Double consumeSeconds;
    final String consumeAnimation;
    final boolean consumable;
    final Boolean glint;
    final Integer enchantable;
    /** RGB of the {@code dyed_color} component. */
    final Integer dyedColor;
    /** Sum of the flat armour attribute modifiers, i.e. the points shown in the armour bar. */
    final Integer armor;
    /** Components of the patch that the Java server evaluates but Bedrock is not told about. */
    final List<String> serverSideOnly = new ArrayList<>();
    private final Set<String> removed = new java.util.HashSet<>();

    private static final Set<String> SLOTS = Set.of("head", "chest", "legs", "feet", "body", "saddle");
    private static final Set<String> ANIMATIONS = Set.of("none", "eat", "drink", "block", "bow", "spear", "crossbow",
        "spyglass", "brush");

    static ItemProperties parse(String componentString) {
        Map<String, Object> components = Snbt.components(componentString);
        return components.isEmpty() ? NONE : new ItemProperties(components);
    }

    private ItemProperties(Map<String, Object> components) {
        for (Map.Entry<String, Object> entry : components.entrySet()) {
            if (entry.getValue() == Snbt.REMOVED) {
                removed.add(entry.getKey());
            }
        }

        Map<?, ?> equippable = map(components.get("equippable"));
        String slot = equippable == null ? null : string(equippable.get("slot"));
        equipSlot = slot != null && SLOTS.contains(slot.toLowerCase(Locale.ROOT)) ? slot.toLowerCase(Locale.ROOT) : null;
        // "asset_id" since 1.21.5, "model" before
        String asset = equippable == null ? null
            : string(equippable.get("asset_id")) != null ? string(equippable.get("asset_id")) : string(equippable.get("model"));
        equipAsset = asset == null ? null : Id.parse(asset);

        maxStackSize = integer(components.get("max_stack_size"));
        maxDamage = integer(components.get("max_damage"));

        Map<?, ?> food = map(components.get("food"));
        nutrition = food == null ? null : integer(food.get("nutrition"));
        saturation = food == null ? null : number(food.get("saturation"));
        canAlwaysEat = food != null && food.get("can_always_eat") instanceof Boolean always ? always : null;

        Object consumableValue = components.get("consumable");
        consumable = consumableValue != null && consumableValue != Snbt.REMOVED;
        Map<?, ?> consume = map(consumableValue);
        consumeSeconds = consume == null ? null : number(consume.get("consume_seconds"));
        String animation = consume == null ? null : string(consume.get("animation"));
        consumeAnimation = animation != null && ANIMATIONS.contains(animation.toLowerCase(Locale.ROOT))
            ? animation.toLowerCase(Locale.ROOT) : null;

        glint = components.get("enchantment_glint_override") instanceof Boolean value ? value : null;
        Object enchant = components.get("enchantable");
        enchantable = map(enchant) != null ? integer(map(enchant).get("value")) : integer(enchant);

        Object dyed = components.get("dyed_color");
        Integer color = map(dyed) != null ? integer(map(dyed).get("rgb")) : integer(dyed);
        dyedColor = color == null ? null : color & 0xFFFFFF;

        Object attributes = components.get("attribute_modifiers");
        if (map(attributes) != null) {
            attributes = map(attributes).get("modifiers");
        }
        Double armorPoints = null;
        boolean otherAttributes = false;
        if (attributes instanceof List<?> list) {
            for (Object element : list) {
                Map<?, ?> modifier = map(element);
                if (modifier == null) {
                    continue;
                }
                String type = string(modifier.get("type"));
                type = type == null ? "" : Snbt.stripNamespace(type).replace("generic.", "");
                String operation = string(modifier.get("operation"));
                Double amount = number(modifier.get("amount"));
                if (type.equals("armor") && amount != null && (operation == null || operation.equals("add_value"))) {
                    armorPoints = (armorPoints == null ? 0 : armorPoints) + amount;
                } else {
                    otherAttributes = true;
                }
            }
        }
        armor = armorPoints == null ? null : (int) Math.max(0, Math.round(armorPoints));

        if (otherAttributes) {
            serverSideOnly.add("Attribute ausser Ruestungspunkten (Schaden, Tempo, Zaehigkeit ...)");
        }
        if (components.get("tool") != null && components.get("tool") != Snbt.REMOVED) {
            serverSideOnly.add("eigene Werkzeug-Regeln (Abbautempo rechnet Geyser aus dem Java-Item)");
        }
        if (components.get("use_cooldown") != null && components.get("use_cooldown") != Snbt.REMOVED) {
            serverSideOnly.add("Abklingzeit");
        }
    }

    boolean isEmpty() {
        return this == NONE;
    }

    boolean removes(String component) {
        return removed.contains(component);
    }

    private static Map<?, ?> map(Object value) {
        return value instanceof Map<?, ?> map ? map : null;
    }

    private static String string(Object value) {
        return value instanceof String text ? text : null;
    }

    private static Double number(Object value) {
        return value instanceof Double number ? number : null;
    }

    private static Integer integer(Object value) {
        return value instanceof Double number ? (int) Math.round(number) : null;
    }
}
