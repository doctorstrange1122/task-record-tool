package cn.taskrecord.app;

import android.content.Context;
import android.content.SharedPreferences;

/** 悬浮窗开关持久化 */
final class FloatingPrefs {

    private static final String PREF = "app.floating";
    private static final String KEY_ENABLED = "enabled";

    private FloatingPrefs() {}

    static boolean isEnabled(Context c) {
        return c.getSharedPreferences(PREF, Context.MODE_PRIVATE).getBoolean(KEY_ENABLED, false);
    }

    static void setEnabled(Context c, boolean on) {
        SharedPreferences sp = c.getSharedPreferences(PREF, Context.MODE_PRIVATE);
        sp.edit().putBoolean(KEY_ENABLED, on).apply();
    }
}
