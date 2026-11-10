package net.snouli.bedrockbridge.core;

import com.google.gson.JsonObject;

/**
 * @param pack     Bedrock pack files (without manifest)
 * @param mappings Geyser mappings bundle: {@code {"items": <v2 file>, "blocks": <v1 file>}}
 */
public record BuildOutput(BedrockPack pack, JsonObject mappings, Report report) {
}
