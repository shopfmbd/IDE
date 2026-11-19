package com.fahim.cloudide;

import android.net.Uri;
import android.os.Environment;
import android.provider.DocumentsContract;
import androidx.activity.ComponentActivity;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import java.io.File;

/**
 * System folder picker. Create one as a field of an activity (it registers an activity-result launcher, which must
 * happen before the activity is started), then call pick(). The chosen tree is converted to a real File path, since
 * the rest of the app works on paths and has All files access.
 */
final class FolderPicker {
    interface Result { void run(File dir); }

    private final ComponentActivity act;
    private final ActivityResultLauncher<Uri> launcher;
    private Result pending;

    FolderPicker(ComponentActivity a) {
        act = a;
        launcher = a.registerForActivityResult(new ActivityResultContracts.OpenDocumentTree(), uri -> {
            Result cb = pending;
            pending = null;
            if (uri == null || cb == null) return; // cancelled
            File f = toFile(uri);
            if (f == null || !f.isDirectory()) {
                Ui.toast(act, "That location is not a folder on device storage. Pick a folder from internal storage or an SD card.");
                return;
            }
            cb.run(f);
        });
    }

    void pick(Result cb) {
        pending = cb;
        launcher.launch(null);
    }

    /** Maps an external-storage tree Uri such as primary:AppProjects/Foo to /storage/emulated/0/AppProjects/Foo. */
    static File toFile(Uri tree) {
        if (tree == null || !"com.android.externalstorage.documents".equals(tree.getAuthority())) return null;
        String id;
        try {
            id = DocumentsContract.getTreeDocumentId(tree);
        } catch (Exception e) {
            return null;
        }
        int i = id.indexOf(':');
        String vol = i < 0 ? id : id.substring(0, i);
        String rel = i < 0 ? "" : id.substring(i + 1);
        File base = vol.equalsIgnoreCase("primary") ? Environment.getExternalStorageDirectory() : new File("/storage/" + vol);
        return rel.isEmpty() ? base : new File(base, rel);
    }
}
