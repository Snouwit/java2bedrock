package net.snouli.bedrockbridge.core;

/**
 * @param armor      build Bedrock armour models (attachables) for custom armour
 * @param components carry item properties over to Bedrock (stack size, durability, food, glint, ...)
 */
public record ItemOptions(boolean armor, boolean components) {

    public static ItemOptions defaults() {
        return new ItemOptions(true, true);
    }
}
