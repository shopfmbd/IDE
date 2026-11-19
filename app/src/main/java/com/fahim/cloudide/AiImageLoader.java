package com.fahim.cloudide;

import android.content.ContentResolver;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.provider.OpenableColumns;
import android.util.Base64;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

/** Reads, shrinks (longest side {@value AiImage#MAX_SIDE} px) and re-encodes a picture: PNG stays PNG, everything else becomes JPEG. */
final class AiImageLoader {
    private static final int MAX_SIDE = AiImage.MAX_SIDE;
    private static final int MAX_BYTES = 4 * 1024 * 1024; // under the 5 MB limit of the strictest provider, before base64

    private AiImageLoader() {}

    /** Reads, shrinks and encodes the picture behind {@code uri}. Blocking: call it off the UI thread. */
    static AiImage load(ContentResolver cr, Uri uri) throws IOException {
        String name = displayName(cr, uri);
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        try (InputStream in = cr.openInputStream(uri)) {
            if (in == null) throw new IOException("Could not open the picture.");
            BitmapFactory.decodeStream(in, null, bounds);
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw new IOException("That file is not a picture this device can read.");
        int sample = 1;
        while (Math.max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_SIDE) sample *= 2;
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inSampleSize = sample;
        Bitmap bmp;
        try (InputStream in = cr.openInputStream(uri)) {
            if (in == null) throw new IOException("Could not open the picture.");
            bmp = BitmapFactory.decodeStream(in, null, o);
        } catch (OutOfMemoryError e) {
            throw new IOException("The picture is too large to load.");
        }
        if (bmp == null) throw new IOException("That file is not a picture this device can read.");
        try {
            int longest = Math.max(bmp.getWidth(), bmp.getHeight());
            if (longest > MAX_SIDE) {
                float k = MAX_SIDE / (float) longest;
                Bitmap s = Bitmap.createScaledBitmap(bmp, Math.max(1, Math.round(bmp.getWidth() * k)), Math.max(1, Math.round(bmp.getHeight() * k)), true);
                if (s != bmp) {
                    bmp.recycle();
                    bmp = s;
                }
            }
            String type = cr.getType(uri);
            boolean png = "image/png".equals(type) || (name != null && name.toLowerCase(java.util.Locale.ROOT).endsWith(".png"));
            byte[] data = encode(bmp, png);
            if (data.length > MAX_BYTES && png) {
                data = encode(bmp, false); // a big PNG: fall back to JPEG
                png = false;
            }
            for (int q = 70; data.length > MAX_BYTES && q >= 30; q -= 20) data = jpeg(bmp, q);
            if (data.length > MAX_BYTES) throw new IOException("The picture is still too large after shrinking.");
            String nm = name == null || name.isEmpty() ? "image" : name;
            return new AiImage(nm, png ? "image/png" : "image/jpeg", Base64.encodeToString(data, Base64.NO_WRAP));
        } finally {
            bmp.recycle();
        }
    }

    private static byte[] encode(Bitmap b, boolean png) throws IOException { return png ? png(b) : jpeg(b, 85); }

    private static byte[] png(Bitmap b) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        if (!b.compress(Bitmap.CompressFormat.PNG, 100, out)) throw new IOException("Could not encode the picture.");
        return out.toByteArray();
    }

    private static byte[] jpeg(Bitmap b, int quality) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        // JPEG has no alpha: draw a transparent picture on white, not black
        Bitmap flat = b;
        if (b.hasAlpha()) {
            flat = Bitmap.createBitmap(b.getWidth(), b.getHeight(), Bitmap.Config.RGB_565);
            android.graphics.Canvas cv = new android.graphics.Canvas(flat);
            cv.drawColor(0xFFFFFFFF);
            cv.drawBitmap(b, 0, 0, null);
        }
        try {
            if (!flat.compress(Bitmap.CompressFormat.JPEG, quality, out)) throw new IOException("Could not encode the picture.");
        } finally {
            if (flat != b) flat.recycle();
        }
        return out.toByteArray();
    }

    private static String displayName(ContentResolver cr, Uri uri) {
        try (Cursor c = cr.query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst()) return c.getString(0);
        } catch (Exception ignored) {
            // some providers do not answer; the name is only a label
        }
        String last = uri.getLastPathSegment();
        return last == null ? "image" : last;
    }
}
