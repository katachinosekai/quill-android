package io.github.katachinosekai.quillremote;

import static org.junit.Assert.assertEquals;

import android.view.Window;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

public class TouchTest {
    @Test
    public void dialogTouchesAndKeysCountAndEverythingIsForwarded() throws Exception {
        List<String> seen = new ArrayList<>();
        Window.Callback inner = (Window.Callback) Proxy.newProxyInstance(Window.Callback.class.getClassLoader(),
                new Class<?>[]{Window.Callback.class}, (p, m, a) -> {
                    seen.add(m.getName());
                    return m.getReturnType() == boolean.class ? Boolean.TRUE : null;
                });
        int[] activity = {0};
        Window.Callback wrapped = Touch.wrap(inner, () -> activity[0]++);
        assertEquals(true, wrapped.dispatchTouchEvent(null));
        assertEquals(true, wrapped.dispatchKeyEvent(null));
        wrapped.onWindowFocusChanged(true);
        assertEquals(2, activity[0]);
        assertEquals(List.of("dispatchTouchEvent", "dispatchKeyEvent", "onWindowFocusChanged"), seen);
    }
}
