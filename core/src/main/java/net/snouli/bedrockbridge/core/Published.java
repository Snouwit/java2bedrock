package net.snouli.bedrockbridge.core;

/**
 * An immutable snapshot of what the HTTP server hands out. Swapped atomically after a successful
 * build, so a failed build never takes the previous version offline.
 */
public record Published(int version, String builtAt, int[] packVersion, byte[] pack, String packSha1,
                        byte[] mappings, String mappingsSha1, byte[] versionJson,
                        int glyphs, int items, int blocks) {
}
