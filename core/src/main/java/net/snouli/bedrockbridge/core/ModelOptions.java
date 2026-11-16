package net.snouli.bedrockbridge.core;

import java.util.Locale;
import java.util.Map;

/**
 * Settings for 3D item models (furniture).
 *
 * @param enabled            convert 3D models at all
 * @param displayContext     which {@code display} entry of the Java model applies when the item is shown by
 *                           an item display entity ({@code head}, {@code fixed}, ... or {@code none})
 * @param displayRotation    extra rotation in degrees for item displays, to correct orientation on Bedrock
 * @param displayTranslation extra translation in blocks for item displays
 * @param itemTranslation    per-item translation in blocks that replaces {@code displayTranslation}; the key
 *                           is the ItemsAdder id ({@code snouli:park_bench}) or just its name ({@code park_bench})
 */
public record ModelOptions(boolean enabled, String displayContext, double[] displayRotation, double[] displayTranslation,
                           Map<String, double[]> itemTranslation) {

    public ModelOptions(boolean enabled, String displayContext, double[] displayRotation, double[] displayTranslation) {
        this(enabled, displayContext, displayRotation, displayTranslation, Map.of());
    }

    public ModelOptions {
        itemTranslation = itemTranslation == null ? Map.of() : Map.copyOf(itemTranslation);
    }

    public static ModelOptions defaults() {
        return new ModelOptions(true, "head", new double[3], new double[3]);
    }

    /** @return the translation for the given Bedrock identifier ({@code namespace:path}) */
    public double[] translationFor(String identifier) {
        String id = identifier.toLowerCase(Locale.ROOT);
        String name = lastSegment(id);
        for (Map.Entry<String, double[]> entry : itemTranslation.entrySet()) {
            String key = entry.getKey().toLowerCase(Locale.ROOT).trim();
            if (key.equals(id) || (!key.contains(":") && key.equals(name))
                || (key.contains(":") && key.substring(0, key.indexOf(':')).equals(id.substring(0, Math.max(0, id.indexOf(':'))))
                    && lastSegment(key).equals(name))) {
                return entry.getValue();
            }
        }
        return displayTranslation;
    }

    private static String lastSegment(String id) {
        int cut = Math.max(id.lastIndexOf(':'), id.lastIndexOf('/'));
        return id.substring(cut + 1);
    }
}
