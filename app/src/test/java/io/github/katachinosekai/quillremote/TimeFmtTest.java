package io.github.katachinosekai.quillremote;

import static org.junit.Assert.assertEquals;

import java.util.Calendar;
import java.util.Locale;
import java.util.TimeZone;

import org.junit.Test;

public class TimeFmtTest {
    /** The device resolves skeletons with ICU; the JVM here uses the JDK's CLDR data, which has the same skeleton matching. */
    @org.junit.Before
    public void jdkPatterns() {
        // JDK 19+ API, called by reflection because the module compiles against an older release.
        TimeFmt.source = (l, sk) -> {
            try {
                return (String) java.time.format.DateTimeFormatterBuilder.class
                        .getMethod("getLocalizedDateTimePattern", String.class, java.time.chrono.Chronology.class, Locale.class)
                        .invoke(null, sk, java.time.chrono.IsoChronology.INSTANCE, l);
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException(e);
            }
        };
    }

    @org.junit.After
    public void restore() {
        TimeFmt.h24 = true;
    }

    /** Newer CLDR puts a narrow no-break space before AM/PM; compare with a plain one. */
    static String n(String s) {
        return s.replace('\u202f', ' ').replace('\u00a0', ' ');
    }

    static String in(Locale l, boolean h24, String skeleton, long ms) {
        Locale before = Locale.getDefault();
        Locale.setDefault(l);
        try {
            TimeFmt.h24 = h24;
            return n(TimeFmt.format(skeleton, ms));
        } finally {
            Locale.setDefault(before);
        }
    }

    @Test
    public void datesAndTimesFollowTheUsersLanguage() {
        long ms = at(14, 5);
        assertEquals("21. Nov., 14:05", in(Locale.GERMANY, true, "MMMd", ms));
        assertEquals("Sa., 14:05", in(Locale.GERMANY, true, "EEE", ms));
        assertEquals("11月21日 14:05", in(Locale.JAPAN, true, "MMMd", ms));
        assertEquals(true, in(Locale.JAPAN, true, "EEE", ms).contains("土"));
        assertEquals("21 Kas 14:05", in(java.util.Locale.forLanguageTag("tr-TR"), true, "MMMd", ms));
        assertEquals("Cmt 14:05", in(java.util.Locale.forLanguageTag("tr-TR"), true, "EEE", ms));
        assertEquals("Nov 21, 14:05", in(Locale.US, true, "MMMd", ms));
        assertEquals("Nov 21, 2:05 PM", in(Locale.US, false, "MMMd", ms));
        assertEquals("21 Nov, 14:05", in(Locale.UK, true, "MMMd", ms));
    }

    long at(int h, int m) {
        Calendar c = Calendar.getInstance(TimeZone.getDefault());
        c.set(2026, Calendar.NOVEMBER, 21, h, m, 0);
        return c.getTimeInMillis();
    }

    @Test
    public void followsTheDeviceClockStyle() {
        Locale before = Locale.getDefault();
        Locale.setDefault(Locale.US);
        try {
            TimeFmt.h24 = true;
            assertEquals("20:25", n(TimeFmt.time(at(20, 25))));
            assertEquals("Sat 07:05", n(TimeFmt.format("EEE", at(7, 5))));
            TimeFmt.h24 = false;
            assertEquals("8:25 PM", n(TimeFmt.time(at(20, 25))));
            assertEquals("Nov 21, 7:05 AM", n(TimeFmt.format("MMMd", at(7, 5))));
        } finally {
            TimeFmt.h24 = true;
            Locale.setDefault(before);
        }
    }

    long day(int y, int mo, int d, int h, int m) {
        Calendar c = Calendar.getInstance(TimeZone.getDefault());
        c.set(y, mo, d, h, m, 0);
        return c.getTimeInMillis();
    }

    @Test
    public void messageStampsShowTheDateOnlyForOtherDays() {
        Locale before = Locale.getDefault();
        Locale.setDefault(Locale.US);
        try {
            TimeFmt.h24 = false;
            long now = day(2026, Calendar.NOVEMBER, 22, 15, 0);
            assertEquals("2:41 PM", n(TimeFmt.stamp(day(2026, Calendar.NOVEMBER, 22, 14, 41), now)));
            assertEquals("Nov 21, 2:41 PM", n(TimeFmt.stamp(day(2026, Calendar.NOVEMBER, 21, 14, 41), now)));
            assertEquals("Dec 31, 2025, 9:00 AM", n(TimeFmt.stamp(day(2025, Calendar.DECEMBER, 31, 9, 0), now)));
            TimeFmt.h24 = true;
            assertEquals("14:41", TimeFmt.stamp(day(2026, Calendar.NOVEMBER, 22, 14, 41), now));
        } finally {
            TimeFmt.h24 = true;
            Locale.setDefault(before);
        }
    }

    @Test
    public void vpnHintOnlyAfterTwoFailures() {
        assertEquals(false, Remote.unreachable(1).contains("VPN"));
        assertEquals(true, Remote.unreachable(2).contains("Not at home? Turn on your VPN"));
    }
}
