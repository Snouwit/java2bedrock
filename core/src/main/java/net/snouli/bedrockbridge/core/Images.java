package net.snouli.bedrockbridge.core;

import java.awt.AlphaComposite;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

import javax.imageio.ImageIO;

final class Images {
    private Images() {}

    static BufferedImage toArgb(BufferedImage source) {
        if (source.getType() == BufferedImage.TYPE_INT_ARGB) {
            return source;
        }
        BufferedImage copy = new BufferedImage(source.getWidth(), source.getHeight(), BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = copy.createGraphics();
        g.setComposite(AlphaComposite.Src);
        g.drawImage(source, 0, 0, null);
        g.dispose();
        return copy;
    }

    static byte[] png(BufferedImage image) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        if (!ImageIO.write(image, "png", out)) {
            throw new IOException("Kein PNG-Writer verfuegbar");
        }
        return out.toByteArray();
    }

    /** Scales with nearest neighbour when enlarging (keeps pixel art crisp) and smoothing when shrinking. */
    static BufferedImage scale(BufferedImage source, int width, int height) {
        width = Math.max(1, width);
        height = Math.max(1, height);
        if (width == source.getWidth() && height == source.getHeight()) {
            return source;
        }
        boolean shrinking = width < source.getWidth() || height < source.getHeight();
        BufferedImage current = source;
        if (shrinking) {
            // Halve repeatedly so that bilinear filtering averages all source pixels.
            while (current.getWidth() / 2 >= width && current.getHeight() / 2 >= height) {
                current = draw(current, current.getWidth() / 2, current.getHeight() / 2, true);
            }
        }
        return draw(current, width, height, shrinking);
    }

    private static BufferedImage draw(BufferedImage source, int width, int height, boolean smooth) {
        BufferedImage target = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = target.createGraphics();
        g.setComposite(AlphaComposite.Src);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, smooth
            ? RenderingHints.VALUE_INTERPOLATION_BILINEAR : RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
        g.drawImage(source, 0, 0, width, height, null);
        g.dispose();
        return target;
    }

    static boolean isFullyTransparent(BufferedImage image) {
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                if ((image.getRGB(x, y) >>> 24) != 0) {
                    return false;
                }
            }
        }
        return true;
    }

    static boolean hasTransparency(BufferedImage image) {
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                if ((image.getRGB(x, y) >>> 24) < 250) {
                    return true;
                }
            }
        }
        return false;
    }
}
