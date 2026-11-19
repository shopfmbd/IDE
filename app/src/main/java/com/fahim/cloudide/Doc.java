package com.fahim.cloudide;

import java.io.File;
import java.util.ArrayList;

/** An open file with its own undo history. */
final class Doc {
    final File file;
    final Lang lang;
    String text = "", snap = "";
    boolean dirty;
    int sel, scroll;
    final ArrayList<String> hu = new ArrayList<>(), hr = new ArrayList<>();
    long chars;

    Doc(File f) {
        file = f;
        lang = Lang.forFile(f.getName());
    }

    void commit(String now) {
        if (now.equals(snap)) return;
        hu.add(snap);
        chars += snap.length();
        hr.clear();
        snap = now;
        while (chars > 8000000L && hu.size() > 1) chars -= hu.remove(0).length();
    }

    String undo(String now) {
        commit(now);
        if (hu.isEmpty()) return null;
        hr.add(snap);
        String p = hu.remove(hu.size() - 1);
        chars -= p.length();
        snap = p;
        return p;
    }

    String redo() {
        if (hr.isEmpty()) return null;
        hu.add(snap);
        chars += snap.length();
        String n = hr.remove(hr.size() - 1);
        snap = n;
        return n;
    }
}
