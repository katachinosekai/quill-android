package io.github.katachinosekai.quillremote;

import android.app.AlertDialog;
import android.app.Dialog;
import android.view.Window;

/**
 * Dialogs and popups have their own windows, so their touches and keys never reach Activity.onUserInteraction; this
 * makes them count as activity for Quill Desktop's device lock too.
 */
final class Touch {
    private Touch() {
    }

    /** Called on every touch or key in a watched window; set by MainActivity to Remote.touch. */
    static volatile Runnable sink;

    static void ping() {
        Runnable s = sink;
        if (s != null) {
            s.run();
        }
    }

    /** Wrap a window's callback so touches and keys call ping(); every other call goes to the original. */
    static Window.Callback wrap(Window.Callback inner, Runnable onActivity) {
        if (inner == null) {
            return null;
        }
        return (Window.Callback) java.lang.reflect.Proxy.newProxyInstance(Window.Callback.class.getClassLoader(),
                new Class<?>[]{Window.Callback.class}, (proxy, m, args) -> {
                    String n = m.getName();
                    if (n.equals("dispatchTouchEvent") || n.equals("dispatchKeyEvent") || n.equals("dispatchTrackballEvent")
                            || n.equals("dispatchGenericMotionEvent")) {
                        onActivity.run();
                    }
                    try {
                        return m.invoke(inner, args);
                    } catch (java.lang.reflect.InvocationTargetException e) {
                        throw e.getCause();
                    }
                });
    }

    static <T extends Dialog> T watch(T d) {
        Window w = d.getWindow();
        if (w != null && w.getCallback() != null) {
            w.setCallback(wrap(w.getCallback(), Touch::ping));
        }
        return d;
    }

    static AlertDialog show(AlertDialog.Builder b) {
        AlertDialog d = b.create();
        watch(d);
        d.show();
        return d;
    }

    /** Typing with a soft keyboard never reaches the window's key dispatch, so text changes count as activity. */
    static void typing(android.widget.TextView t) {
        t.addTextChangedListener(new android.text.TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void onTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void afterTextChanged(android.text.Editable e) {
                ping();
            }
        });
    }
}
