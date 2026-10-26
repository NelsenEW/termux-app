package com.termux.terminal;

/** Straight-alpha root-frame compositing, independent of Android bitmap APIs. */
final class KittyFrameEdit {
    private KittyFrameEdit() {}

    static int[] apply(byte[] destination, int destinationFormat, int stride,
                       byte[] source, int sourceFormat, int x, int y, int width, int height,
                       boolean replace) {
        int db = KittyImage.getBytesPerPixel(destinationFormat);
        int sb = KittyImage.getBytesPerPixel(sourceFormat);
        // Allocate before changing the stored image, so allocation failure cannot leave a partial edit.
        int[] colors = new int[width * height];
        for (int row = 0, pixel = 0; row < height; row++) {
            int d = ((y + row) * stride + x) * db;
            for (int col = 0; col < width; col++, pixel++, d += db) {
                int s = pixel * sb;
                int sa = sb == 4 ? source[s + 3] & 255 : 255;
                int da = db == 4 ? destination[d + 3] & 255 : 255;
                int alphaNumerator = sa * 255 + da * (255 - sa);
                int alpha = replace ? sa : (alphaNumerator + 127) / 255;
                int color = (db == 4 ? alpha : 255) << 24;
                for (int channel = 0; channel < 3; channel++) {
                    int value = source[s + channel] & 255;
                    if (!replace) {
                        value = alphaNumerator == 0 ? 0 :
                            (value * sa * 255 + (destination[d + channel] & 255) * da * (255 - sa)
                                + alphaNumerator / 2) / alphaNumerator;
                    }
                    destination[d + channel] = (byte) value;
                    color |= value << (16 - 8 * channel);
                }
                if (db == 4) destination[d + 3] = (byte) alpha;
                colors[pixel] = color;
            }
        }
        return colors;
    }
}
