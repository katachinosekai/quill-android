package io.github.katachinosekai.quillremote;

/** Pure helper: padding for a full-screen window from system bar/cutout insets and the keyboard inset. */
final class InsetPad {
    private InsetPad() {
    }

    /** Returns {left, top, right, bottom}; the bottom is the larger of the bar and the keyboard, never both. */
    static int[] pad(int barL, int barT, int barR, int barB, int imeB) {
        return new int[]{Math.max(0, barL), Math.max(0, barT), Math.max(0, barR), Math.max(Math.max(0, barB), Math.max(0, imeB))};
    }

    /** Installs the same inset handling as the main chat on a full-screen root view. */
    static void install(android.view.View root) {
        root.setOnApplyWindowInsetsListener((v, in) -> {
            android.graphics.Insets b = in.getInsets(android.view.WindowInsets.Type.systemBars() | android.view.WindowInsets.Type.displayCutout());
            android.graphics.Insets ime = in.getInsets(android.view.WindowInsets.Type.ime());
            int[] p = pad(b.left, b.top, b.right, b.bottom, ime.bottom);
            v.setPadding(p[0], p[1], p[2], p[3]);
            return android.view.WindowInsets.CONSUMED;
        });
    }
}
