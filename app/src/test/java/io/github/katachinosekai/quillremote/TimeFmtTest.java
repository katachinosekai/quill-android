package io.github.katachinosekai.quillremote;

import static org.junit.Assert.assertEquals;

import java.util.Calendar;
import java.util.Locale;
import java.util.TimeZone;

import org.junit.Test;

public class TimeFmtTest {
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
            assertEquals("20:25", TimeFmt.time(at(20, 25)));
            assertEquals("Sat 7:05", TimeFmt.format("EEE ", at(7, 5)));
            TimeFmt.h24 = false;
            assertEquals("8:25 PM", TimeFmt.time(at(20, 25)));
            assertEquals("Nov 21 7:05 AM", TimeFmt.format("MMM d ", at(7, 5)));
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
            assertEquals("2:41 PM", TimeFmt.stamp(day(2026, Calendar.NOVEMBER, 22, 14, 41), now));
            assertEquals("Nov 21, 2:41 PM", TimeFmt.stamp(day(2026, Calendar.NOVEMBER, 21, 14, 41), now));
            assertEquals("Dec 31, 2025 9:00 AM", TimeFmt.stamp(day(2025, Calendar.DECEMBER, 31, 9, 0), now));
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
