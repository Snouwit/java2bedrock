package net.snouli.bedrockbridge.core;

import java.util.Locale;

/** A namespaced Minecraft identifier ({@code namespace:path}). */
public record Id(String namespace, String path) {

    public static Id parse(String raw) {
        return parse(raw, "minecraft");
    }

    public static Id parse(String raw, String defaultNamespace) {
        int colon = raw.indexOf(':');
        if (colon < 0) {
            return new Id(defaultNamespace, raw);
        }
        return new Id(raw.substring(0, colon), raw.substring(colon + 1));
    }

    @Override
    public String toString() {
        return namespace + ":" + path;
    }

    /** Last path segment, e.g. {@code space_sword} for {@code tutorial:item/ia_auto/space_sword}. */
    public String name() {
        int slash = path.lastIndexOf('/');
        return slash < 0 ? path : path.substring(slash + 1);
    }

    /** Lower-cases and replaces everything Bedrock does not accept in identifiers and file names. */
    public static String sanitize(String raw) {
        String s = raw.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_]+", "_").replaceAll("_+", "_");
        s = s.replaceAll("^_+|_+$", "");
        return s.isEmpty() ? "x" : s;
    }
}
