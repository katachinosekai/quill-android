package io.github.katachinosekai.quillremote;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** Times in the device's own style: 24-hour ("military") or 12-hour with AM/PM, as set in Android's date & time settings. */
final class TimeFmt {
    /** Set from Android's DateFormat.is24HourFormat in MainActivity (onCreate/onResume), so a settings change applies on return. */
    static volatile boolean h24 = true;

    private TimeFmt() {
    }

    static String clock() {
        return h24 ? "H:mm" : "h:mm a";
    }

    /** prefix: extra pattern before the time, e.g. "EEE " or "MMM d ". */
    static String format(String prefix, long ms) {
        return new SimpleDateFormat(prefix + clock(), Locale.getDefault()).format(new Date(ms));
    }

    static String time(long ms) {
        return format("", ms);
    }

    /** A message's time: just the clock today, "MMM d " + clock on other days (and the year when it differs). */
    static String stamp(long ms, long now) {
        java.util.Calendar a = java.util.Calendar.getInstance(), b = java.util.Calendar.getInstance();
        a.setTimeInMillis(ms);
        b.setTimeInMillis(now);
        if (a.get(java.util.Calendar.YEAR) != b.get(java.util.Calendar.YEAR)) {
            return format("MMM d, yyyy ", ms);
        }
        return a.get(java.util.Calendar.DAY_OF_YEAR) == b.get(java.util.Calendar.DAY_OF_YEAR) ? time(ms) : format("MMM d, ", ms);
    }
}
