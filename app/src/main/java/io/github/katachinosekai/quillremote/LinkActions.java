package io.github.katachinosekai.quillremote;

import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.provider.Browser;
import android.widget.Toast;

/** Taps on links in the chat: the decisions are LinkPolicy's, this class only builds intents and dialogs. */
final class LinkActions {
    private LinkActions() {
    }

    /** What a chat needs to open file links; null host = file links stay plain text. */
    interface Host {
        /** Hello/stream files.api == 1 and on. */
        boolean filesOn();

        void openFile(String path, int line, int lineEnd);
    }

    static void click(Context c, Links.Link l, Host host) {
        switch (LinkPolicy.decide(l)) {
            case OPEN_WEB:
                openWeb(c, l.target);
                break;
            case LOCAL_DIALOG:
                new AlertDialog.Builder(c).setTitle("This address is on Quill Desktop's PC")
                        .setMessage(l.target + "\n\nIt won't work on this phone unless the same address exists here.")
                        .setPositiveButton("Open anyway", (d, w) -> openWeb(c, l.target))
                        .setNeutralButton("Copy", (d, w) -> copy(c, l.target))
                        .setNegativeButton("Cancel", null).show();
                break;
            case FILE:
                if (host != null && host.filesOn()) {
                    host.openFile(l.target, l.line, l.lineEnd);
                }
                break;
            case CONFIRM_OPEN:
                confirm(c, "Open this link?", l.target, l.target);
                break;
            case CONFIRM_SCHEME:
                confirm(c, "Open " + LinkPolicy.scheme(l.target) + " link with another app?", l.target, l.target);
                break;
            case NEVER:
                Toast.makeText(c, "Quill Android doesn't open this kind of link.", Toast.LENGTH_SHORT).show();
                break;
            default:
                break;
        }
    }

    /** A tap only: http/https in a new page of the phone's browser, never inside Quill Android. */
    static void openWeb(Context c, String url) {
        Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
        i.addCategory(Intent.CATEGORY_BROWSABLE);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        i.putExtra(Browser.EXTRA_CREATE_NEW_TAB, true);
        start(c, i);
    }

    private static void confirm(Context c, String title, String shown, String target) {
        new AlertDialog.Builder(c).setTitle(title).setMessage(shown)
                .setPositiveButton("Open", (d, w) -> {
                    Intent i = new Intent(LinkPolicy.scheme(target).equals("mailto") ? Intent.ACTION_SENDTO : Intent.ACTION_VIEW, Uri.parse(target));
                    i.addCategory(Intent.CATEGORY_BROWSABLE);
                    i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    start(c, i);
                })
                .setNeutralButton("Copy", (d, w) -> copy(c, target))
                .setNegativeButton("Cancel", null).show();
    }

    private static void start(Context c, Intent i) {
        try {
            c.startActivity(i);
        } catch (android.content.ActivityNotFoundException e) {
            Toast.makeText(c, "No app can open this link.", Toast.LENGTH_SHORT).show();
        }
    }

    static void copy(Context c, String text) {
        ClipboardManager cm = (ClipboardManager) c.getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null) {
            cm.setPrimaryClip(ClipData.newPlainText("link", text));
            Toast.makeText(c, "Copied", Toast.LENGTH_SHORT).show();
        }
    }
}
