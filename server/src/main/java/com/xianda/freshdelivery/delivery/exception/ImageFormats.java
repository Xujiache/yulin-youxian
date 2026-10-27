package com.xianda.freshdelivery.delivery.exception;

public final class ImageFormats {
    public static final String JPEG = "jpg";
    public static final String PNG = "png";
    public static final String WEBP = "webp";

    private ImageFormats() {
    }

    public static String sniff(byte[] bytes) {
        if (bytes == null || bytes.length < 12) {
            return null;
        }
        if (matches(bytes, 0, 0xFF, 0xD8, 0xFF)) {
            return JPEG;
        }
        if (matches(bytes, 0, 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)) {
            return PNG;
        }
        if (matches(bytes, 0, 0x52, 0x49, 0x46, 0x46) && matches(bytes, 8, 0x57, 0x45, 0x42, 0x50)) {
            return WEBP;
        }
        return null;
    }

    public static boolean supportedByImageIo(String format) {
        return JPEG.equals(format) || PNG.equals(format);
    }

    private static boolean matches(byte[] bytes, int offset, int... expected) {
        if (bytes.length < offset + expected.length) {
            return false;
        }
        for (int i = 0; i < expected.length; i++) {
            if ((bytes[offset + i] & 0xFF) != expected[i]) {
                return false;
            }
        }
        return true;
    }
}
