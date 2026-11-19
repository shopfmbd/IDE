package com.fahim.cloudide;

/** A problem (sev 1 error, 2 warning) or a search hit (sev 3). */
final class Diag {
    final String file, msg;
    final int line, col, sev;

    Diag(String file, int line, int col, String msg, int sev) {
        this.file = file;
        this.line = line;
        this.col = col;
        this.msg = msg;
        this.sev = sev;
    }
}
