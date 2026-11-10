package net.snouli.bedrockbridge.core;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

/**
 * PNG decoder that ignores chunk CRCs and the zlib Adler-32 checksum.
 *
 * <p>ItemsAdder's resource pack protection deliberately writes wrong checksums: Minecraft does not
 * verify them, but {@code ImageIO} refuses such files. Supports all colour types and bit depths
 * of non-interlaced PNGs.
 */
final class LenientPng {
    private LenientPng() {}

    private static final byte[] SIGNATURE = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'};

    static BufferedImage decode(byte[] data) throws IOException {
        if (data.length < 8 + 25) {
            throw new IOException("PNG zu kurz");
        }
        for (int i = 0; i < 8; i++) {
            if (data[i] != SIGNATURE[i]) {
                throw new IOException("keine PNG-Signatur");
            }
        }

        int width = 0, height = 0, bitDepth = 0, colorType = -1, interlace = 0;
        byte[] palette = null;
        byte[] transparency = null;
        ByteArrayOutputStream idat = new ByteArrayOutputStream();

        int position = 8;
        while (position + 8 <= data.length) {
            long length = readInt(data, position) & 0xFFFFFFFFL;
            String type = new String(data, position + 4, 4, java.nio.charset.StandardCharsets.ISO_8859_1);
            int start = position + 8;
            if (length > data.length - start) {
                throw new IOException("PNG-Chunk " + type + " ist abgeschnitten");
            }
            int len = (int) length;
            switch (type) {
                case "IHDR" -> {
                    width = readInt(data, start);
                    height = readInt(data, start + 4);
                    bitDepth = data[start + 8] & 0xFF;
                    colorType = data[start + 9] & 0xFF;
                    interlace = data[start + 12] & 0xFF;
                }
                case "PLTE" -> palette = java.util.Arrays.copyOfRange(data, start, start + len);
                case "tRNS" -> transparency = java.util.Arrays.copyOfRange(data, start, start + len);
                case "IDAT" -> idat.write(data, start, len);
                default -> { }
            }
            if (type.equals("IEND")) {
                break;
            }
            position = start + len + 4;
        }

        if (width <= 0 || height <= 0 || width > 16384 || height > 16384) {
            throw new IOException("ungueltige PNG-Groesse " + width + "x" + height);
        }
        if (interlace != 0) {
            throw new IOException("Interlaced-PNG wird nicht unterstuetzt");
        }
        int channels = switch (colorType) {
            case 0, 3 -> 1;
            case 4 -> 2;
            case 2 -> 3;
            case 6 -> 4;
            default -> throw new IOException("unbekannter PNG-Farbtyp " + colorType);
        };
        if (colorType == 3 && palette == null) {
            throw new IOException("PNG-Palette fehlt");
        }

        int bitsPerPixel = channels * bitDepth;
        int bytesPerPixel = Math.max(1, bitsPerPixel / 8);
        int stride = (width * bitsPerPixel + 7) / 8;
        byte[] raw = inflate(idat.toByteArray(), (long) (stride + 1) * height);

        byte[] previous = new byte[stride];
        byte[] line = new byte[stride];
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        int offset = 0;
        for (int y = 0; y < height; y++) {
            int filter = raw[offset++] & 0xFF;
            System.arraycopy(raw, offset, line, 0, stride);
            offset += stride;
            unfilter(filter, line, previous, bytesPerPixel);
            for (int x = 0; x < width; x++) {
                image.setRGB(x, y, pixel(line, x, colorType, bitDepth, palette, transparency));
            }
            byte[] swap = previous;
            previous = line;
            line = swap;
        }
        return image;
    }

    private static int readInt(byte[] data, int position) {
        return (data[position] & 0xFF) << 24 | (data[position + 1] & 0xFF) << 16
            | (data[position + 2] & 0xFF) << 8 | (data[position + 3] & 0xFF);
    }

    /** Inflates a zlib stream as raw deflate so the (wrong) Adler-32 trailer is never checked. */
    private static byte[] inflate(byte[] zlib, long expected) throws IOException {
        if (zlib.length < 2 || expected > Integer.MAX_VALUE) {
            throw new IOException("PNG-Bilddaten fehlen");
        }
        byte[] output = new byte[(int) expected];
        Inflater inflater = new Inflater(true);
        try {
            inflater.setInput(zlib, 2, zlib.length - 2);
            int total = 0;
            while (total < output.length && !inflater.finished()) {
                int read = inflater.inflate(output, total, output.length - total);
                if (read == 0 && (inflater.needsInput() || inflater.needsDictionary())) {
                    break;
                }
                total += read;
            }
            if (total < output.length) {
                throw new IOException("PNG-Bilddaten sind unvollstaendig");
            }
            return output;
        } catch (DataFormatException e) {
            throw new IOException("PNG-Bilddaten sind beschaedigt: " + e.getMessage(), e);
        } finally {
            inflater.end();
        }
    }

    private static void unfilter(int filter, byte[] line, byte[] previous, int bpp) throws IOException {
        switch (filter) {
            case 0 -> { }
            case 1 -> {
                for (int i = bpp; i < line.length; i++) {
                    line[i] += line[i - bpp];
                }
            }
            case 2 -> {
                for (int i = 0; i < line.length; i++) {
                    line[i] += previous[i];
                }
            }
            case 3 -> {
                for (int i = 0; i < line.length; i++) {
                    int left = i >= bpp ? line[i - bpp] & 0xFF : 0;
                    line[i] += (byte) ((left + (previous[i] & 0xFF)) >> 1);
                }
            }
            case 4 -> {
                for (int i = 0; i < line.length; i++) {
                    int a = i >= bpp ? line[i - bpp] & 0xFF : 0;
                    int b = previous[i] & 0xFF;
                    int c = i >= bpp ? previous[i - bpp] & 0xFF : 0;
                    int p = a + b - c;
                    int pa = Math.abs(p - a), pb = Math.abs(p - b), pc = Math.abs(p - c);
                    line[i] += (byte) (pa <= pb && pa <= pc ? a : pb <= pc ? b : c);
                }
            }
            default -> throw new IOException("unbekannter PNG-Filter " + filter);
        }
    }

    /** Reads sample {@code index} of the scanline, scaled to 8 bits unless {@code rawValue}. */
    private static int sample(byte[] line, int index, int bitDepth, boolean rawValue) {
        if (bitDepth == 8) {
            return line[index] & 0xFF;
        }
        if (bitDepth == 16) {
            int value = (line[index * 2] & 0xFF) << 8 | (line[index * 2 + 1] & 0xFF);
            return rawValue ? value : value >> 8;
        }
        int bit = index * bitDepth;
        int value = ((line[bit >> 3] & 0xFF) >> (8 - bitDepth - (bit & 7))) & ((1 << bitDepth) - 1);
        return rawValue ? value : value * 255 / ((1 << bitDepth) - 1);
    }

    private static int pixel(byte[] line, int x, int colorType, int bitDepth, byte[] palette, byte[] transparency) {
        switch (colorType) {
            case 0 -> {
                int gray = sample(line, x, bitDepth, false);
                int alpha = 255;
                if (transparency != null && transparency.length >= 2) {
                    int key = (transparency[0] & 0xFF) << 8 | (transparency[1] & 0xFF);
                    if (sample(line, x, bitDepth, true) == key) {
                        alpha = 0;
                    }
                }
                return alpha << 24 | gray << 16 | gray << 8 | gray;
            }
            case 2 -> {
                int r = sample(line, x * 3, bitDepth, false);
                int g = sample(line, x * 3 + 1, bitDepth, false);
                int b = sample(line, x * 3 + 2, bitDepth, false);
                int alpha = 255;
                if (transparency != null && transparency.length >= 6) {
                    boolean match = true;
                    for (int i = 0; i < 3 && match; i++) {
                        int key = (transparency[i * 2] & 0xFF) << 8 | (transparency[i * 2 + 1] & 0xFF);
                        match = sample(line, x * 3 + i, bitDepth, true) == key;
                    }
                    if (match) {
                        alpha = 0;
                    }
                }
                return alpha << 24 | r << 16 | g << 8 | b;
            }
            case 3 -> {
                int index = sample(line, x, bitDepth, true);
                if (index * 3 + 2 >= palette.length) {
                    return 0;
                }
                int alpha = transparency != null && index < transparency.length ? transparency[index] & 0xFF : 255;
                return alpha << 24 | (palette[index * 3] & 0xFF) << 16 | (palette[index * 3 + 1] & 0xFF) << 8
                    | (palette[index * 3 + 2] & 0xFF);
            }
            case 4 -> {
                int gray = sample(line, x * 2, bitDepth, false);
                int alpha = sample(line, x * 2 + 1, bitDepth, false);
                return alpha << 24 | gray << 16 | gray << 8 | gray;
            }
            default -> {
                int r = sample(line, x * 4, bitDepth, false);
                int g = sample(line, x * 4 + 1, bitDepth, false);
                int b = sample(line, x * 4 + 2, bitDepth, false);
                int alpha = sample(line, x * 4 + 3, bitDepth, false);
                return alpha << 24 | r << 16 | g << 8 | b;
            }
        }
    }
}
