package com.fahim.cloudide;

import android.net.Uri;
import android.os.Environment;
import android.provider.DocumentsContract;
import android.provider.OpenableColumns;
import android.database.Cursor;
import androidx.activity.ComponentActivity;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;

/**
 * System file picker, the file twin of FolderPicker. Create one as a field of an activity, then call pick().
 * A file on internal storage or an SD card is used in place (no copy). A file from anywhere else (Downloads
 * list, Drive, another app) is copied into the app cache first, and the copy is passed on. Copies older than an hour
 * are deleted at the next pick.
 */
final class FilePicker {
    interface Result { void run(File file); }

    private final ComponentActivity act;
    private final ActivityResultLauncher<String[]> launcher;
    private Result pending;

    FilePicker(ComponentActivity a) {
        act = a;
        launcher = a.registerForActivityResult(new ActivityResultContracts.OpenDocument(), uri -> {
            Result cb = pending;
            pending = null;
            if (uri == null || cb == null) return; // cancelled
            File direct = toFile(uri);
            if (direct != null && direct.isFile() && direct.canRead()) {
                cb.run(direct);
                return;
            }
            copy(uri, cb);
        });
    }

    void pick(Result cb) {
        pending = cb;
        launcher.launch(new String[]{"*/*"});
    }

    /** Maps primary:Download/x.zip to /storage/emulated/0/Download/x.zip. Null for any other provider. */
    static File toFile(Uri doc) {
        if (doc == null || !"com.android.externalstorage.documents".equals(doc.getAuthority())) return null;
        String id;
        try {
            id = DocumentsContract.getDocumentId(doc);
        } catch (Exception e) {
            return null;
        }
        int i = id.indexOf(':');
        if (i < 0) return null;
        String vol = id.substring(0, i), rel = id.substring(i + 1);
        File base = vol.equalsIgnoreCase("primary") ? Environment.getExternalStorageDirectory() : new File("/storage/" + vol);
        return new File(base, rel);
    }

    private void copy(Uri uri, Result cb) {
        File dir = new File(act.getCacheDir(), "picked");
        dir.mkdirs();
        long old = System.currentTimeMillis() - 3600_000L;
        File[] prev = dir.listFiles();
        if (prev != null) for (File p : prev) if (p.lastModified() < old) p.delete();
        String name = displayName(uri);
        AlertDialog busy = Ui.busy(act, "Reading " + name + "...");
        new Thread(() -> {
            File out = new File(dir, name);
            String err = null;
            try (InputStream in = act.getContentResolver().openInputStream(uri);
                 FileOutputStream os = new FileOutputStream(out)) {
                if (in == null) throw new java.io.IOException("The file could not be opened");
                byte[] buf = new byte[1 << 16];
                int n;
                while ((n = in.read(buf)) > 0) os.write(buf, 0, n);
            } catch (Exception e) {
                AppLog.e("FilePicker", "Copy of " + uri + " failed", e);
                err = e.getMessage();
                out.delete();
            }
            final String m = err;
            act.runOnUiThread(() -> {
                Ui.dismiss(busy);
                if (m != null) Ui.toast(act, "Could not read the file: " + m);
                else cb.run(out);
            });
        }, "file-picker-copy").start();
    }

    private String displayName(Uri uri) {
        String n = "picked-file";
        try (Cursor c = act.getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst() && c.getString(0) != null) n = c.getString(0);
        } catch (Exception ignored) { }
        return n.replaceAll("[/\\\\]", "_");
    }
}
