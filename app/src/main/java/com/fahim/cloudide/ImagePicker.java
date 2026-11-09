package com.fahim.cloudide;

import android.net.Uri;
import androidx.activity.ComponentActivity;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;

/**
 * System picture picker. Create one as a field of an activity, then call pick(). The picture is read and shrunk on a
 * background thread; the result is delivered on the UI thread.
 */
final class ImagePicker {
    interface Result {
        void ok(AiImage image);

        void fail(String message);
    }

    private final ComponentActivity act;
    private final ActivityResultLauncher<String> launcher;
    private Result pending;

    ImagePicker(ComponentActivity a) {
        act = a;
        launcher = a.registerForActivityResult(new ActivityResultContracts.GetContent(), this::picked);
    }

    void pick(Result cb) {
        pending = cb;
        launcher.launch("image/*");
    }

    private void picked(Uri uri) {
        final Result cb = pending;
        pending = null;
        if (uri == null || cb == null) return; // cancelled
        new Thread(() -> {
            AiImage img = null;
            String err = null;
            try {
                img = AiImageLoader.load(act.getContentResolver(), uri);
            } catch (Throwable e) {
                err = e.getMessage() == null ? "Could not read the picture." : e.getMessage();
            }
            final AiImage fi = img;
            final String fe = err;
            act.runOnUiThread(() -> {
                if (act.isFinishing() || act.isDestroyed()) return;
                if (fi != null) cb.ok(fi);
                else cb.fail(fe);
            });
        }, "image-load").start();
    }
}
