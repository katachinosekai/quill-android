package io.github.katachinosekai.quillremote;

import java.util.function.BooleanSupplier;

/** The unlock expired: try the silent home-network unlock before the password prompt. */
final class Relock {
    private Relock() {
    }

    /**
     * Runs silent (the signed home-network challenge). If it works, onSilent runs and false is returned. If not (and
     * still current), prompt runs (the password dialog) and true is returned, meaning "wait for the password". If
     * the connection is no longer current, nothing else runs and false is returned.
     */
    static boolean silentFirst(BooleanSupplier silent, BooleanSupplier current, Runnable onSilent, Runnable prompt) {
        if (silent.getAsBoolean()) {
            onSilent.run();
            return false;
        }
        if (!current.getAsBoolean()) {
            return false;
        }
        prompt.run();
        return true;
    }
}
