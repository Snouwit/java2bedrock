package net.snouli.bedrockbridge.core;

/**
 * Settings for 3D item models (furniture).
 *
 * @param enabled            convert 3D models at all
 * @param displayContext     which {@code display} entry of the Java model applies when the item is shown by
 *                           an item display entity ({@code head}, {@code fixed}, ... or {@code none})
 * @param displayRotation    extra rotation in degrees for item displays, to correct orientation on Bedrock
 * @param displayTranslation extra translation in blocks for item displays
 */
public record ModelOptions(boolean enabled, String displayContext, double[] displayRotation, double[] displayTranslation) {

    public static ModelOptions defaults() {
        return new ModelOptions(true, "head", new double[3], new double[3]);
    }
}
