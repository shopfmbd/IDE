package com.fahim.cloudide;

import java.util.ArrayList;
import java.util.List;

/**
 * Carries an assistant conversation from one screen to the next. Opening another project starts a new editor, and the assistant's
 * work must go on there: the old panel leaves the chat so far and the results still to be sent, the new panel picks them up once.
 */
final class AiSession {
    private AiSession() { }

    static final class Carry {
        final List<AiProtocol.Msg> history;
        final String results;
        final int steps;

        Carry(List<AiProtocol.Msg> history, String results, int steps) {
            this.history = new ArrayList<>(history);
            this.results = results;
            this.steps = steps;
        }
    }

    private static Carry pending;

    static synchronized void put(Carry c) { pending = c; }

    /** Returns what was left and forgets it, so a conversation is only ever continued once. */
    static synchronized Carry take() {
        Carry c = pending;
        pending = null;
        return c;
    }
}
