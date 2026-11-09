package com.fahim.cloudide;

/**
 * A picture attached to a question (already shrunk and base64-encoded by {@link AiImageLoader}). Plain Java, so the
 * protocol code and its tests do not need Android.
 */
final class AiImage {
    static final int MAX_SIDE = 1568, MAX_IMAGES = 4;

    final String name, mime, base64;

    AiImage(String name, String mime, String base64) {
        this.name = name;
        this.mime = mime;
        this.base64 = base64;
    }
}
