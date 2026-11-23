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

    /** Resolves a CLDR skeleton ("MMMdHm") to the locale's own pattern; tests swap in a JDK-backed one (android.text.format is not available there). */
    interface PatternSource {
        String best(Locale locale, String skeleton);
    }

    static volatile PatternSource source = (l, sk) -> android.text.format.DateFormat.getBestDateTimePattern(l, sk);

    /** The clock part of a skeleton: 24-hour or 12-hour by the device setting; the locale decides the exact look (H:mm, h:mm a, a K:mm ...). */
    static String clockSkeleton() {
        return h24 ? "Hm" : "hm";
    }

    /** dateSkeleton: "" (time only), "EEE", "MMMd", "yMMMd"; the order, separators and month/day names follow the user's language. */
    static String format(String dateSkeleton, long ms) {
        Locale l = Locale.getDefault();
        String pattern = source.best(l, dateSkeleton + clockSkeleton());
        return new SimpleDateFormat(pattern, l).format(new Date(ms));
    }

    static String time(long ms) {
        return format("", ms);
    }

    /** A message's time: just the clock today, a localized short date + clock on other days (and the year when it differs). */
    static String stamp(long ms, long now) {
        java.util.Calendar a = java.util.Calendar.getInstance(), b = java.util.Calendar.getInstance();
        a.setTimeInMillis(ms);
        b.setTimeInMillis(now);
        if (a.get(java.util.Calendar.YEAR) != b.get(java.util.Calendar.YEAR)) {
            return format("yMMMd", ms);
        }
        return a.get(java.util.Calendar.DAY_OF_YEAR) == b.get(java.util.Calendar.DAY_OF_YEAR) ? time(ms) : format("MMMd", ms);
    }
}
