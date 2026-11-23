package io.github.katachinosekai.quillremote;

import android.content.SharedPreferences;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** In-memory SharedPreferences for JVM unit tests (edits apply at once on apply/commit). */
final class FakePrefs implements SharedPreferences {
    final Map<String, Object> map = new HashMap<>();

    @Override
    public Map<String, ?> getAll() {
        return new HashMap<>(map);
    }

    @Override
    public String getString(String k, String d) {
        Object v = map.get(k);
        return v instanceof String ? (String) v : d;
    }

    @Override
    @SuppressWarnings("unchecked")
    public Set<String> getStringSet(String k, Set<String> d) {
        Object v = map.get(k);
        return v instanceof Set ? new HashSet<>((Set<String>) v) : d;
    }

    @Override
    public int getInt(String k, int d) {
        Object v = map.get(k);
        return v instanceof Integer ? (Integer) v : d;
    }

    @Override
    public long getLong(String k, long d) {
        Object v = map.get(k);
        return v instanceof Long ? (Long) v : d;
    }

    @Override
    public float getFloat(String k, float d) {
        Object v = map.get(k);
        return v instanceof Float ? (Float) v : d;
    }

    @Override
    public boolean getBoolean(String k, boolean d) {
        Object v = map.get(k);
        return v instanceof Boolean ? (Boolean) v : d;
    }

    @Override
    public boolean contains(String k) {
        return map.containsKey(k);
    }

    @Override
    public Editor edit() {
        return new Editor() {
            final Map<String, Object> put = new HashMap<>();
            final Set<String> remove = new HashSet<>();
            boolean clear;

            @Override
            public Editor putString(String k, String v) {
                put.put(k, v);
                return this;
            }

            @Override
            public Editor putStringSet(String k, Set<String> v) {
                put.put(k, v == null ? null : new HashSet<>(v));
                return this;
            }

            @Override
            public Editor putInt(String k, int v) {
                put.put(k, v);
                return this;
            }

            @Override
            public Editor putLong(String k, long v) {
                put.put(k, v);
                return this;
            }

            @Override
            public Editor putFloat(String k, float v) {
                put.put(k, v);
                return this;
            }

            @Override
            public Editor putBoolean(String k, boolean v) {
                put.put(k, v);
                return this;
            }

            @Override
            public Editor remove(String k) {
                remove.add(k);
                return this;
            }

            @Override
            public Editor clear() {
                clear = true;
                return this;
            }

            @Override
            public boolean commit() {
                apply();
                return true;
            }

            @Override
            public void apply() {
                if (clear) {
                    map.clear();
                }
                for (String k : remove) {
                    map.remove(k);
                }
                map.putAll(put);
            }
        };
    }

    @Override
    public void registerOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener l) {
    }

    @Override
    public void unregisterOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener l) {
    }
}
