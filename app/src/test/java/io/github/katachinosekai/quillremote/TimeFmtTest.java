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
}
