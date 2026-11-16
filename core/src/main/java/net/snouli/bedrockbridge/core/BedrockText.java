package net.snouli.bedrockbridge.core;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Text helpers for Bedrock, which only knows the sixteen classic colour codes. */
final class BedrockText {
    private BedrockText() {}

    private static final Pattern HEX = Pattern.compile("(?i)§x((?:§[0-9a-f]){6})|§#([0-9a-f]{6})|&#([0-9a-f]{6})");
    private static final int[] COLORS = {
        0x000000, 0x0000AA, 0x00AA00, 0x00AAAA, 0xAA0000, 0xAA00AA, 0xFFAA00, 0xAAAAAA,
        0x555555, 0x5555FF, 0x55FF55, 0x55FFFF, 0xFF5555, 0xFF55FF, 0xFFFF55, 0xFFFFFF
    };

    /** Replaces hex colours ({@code §x§R§R§G§G§B§B}, {@code &#RRGGBB}) by the nearest classic colour code. */
    static String downsampleHex(String text) {
        Matcher matcher = HEX.matcher(text);
        StringBuilder out = new StringBuilder();
        while (matcher.find()) {
            String hex = matcher.group(1) != null ? matcher.group(1).replace("§", "")
                : matcher.group(2) != null ? matcher.group(2) : matcher.group(3);
            matcher.appendReplacement(out, "§" + Character.forDigit(nearest(Integer.parseInt(hex, 16)), 16));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    static int nearest(int rgb) {
        int best = 15;
        double bestDistance = Double.MAX_VALUE;
        for (int i = 0; i < COLORS.length; i++) {
            // "redmean": a cheap approximation of how different two colours look
            int r1 = (rgb >> 16) & 0xFF, g1 = (rgb >> 8) & 0xFF, b1 = rgb & 0xFF;
            int r2 = (COLORS[i] >> 16) & 0xFF, g2 = (COLORS[i] >> 8) & 0xFF, b2 = COLORS[i] & 0xFF;
            double mean = (r1 + r2) / 2.0;
            double distance = (2 + mean / 256) * (r1 - r2) * (r1 - r2) + 4.0 * (g1 - g2) * (g1 - g2)
                + (2 + (255 - mean) / 256) * (b1 - b2) * (b1 - b2);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = i;
            }
        }
        return best;
    }
}
