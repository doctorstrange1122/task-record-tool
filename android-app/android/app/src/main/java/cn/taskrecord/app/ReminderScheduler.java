package cn.taskrecord.app;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;

/**
 * 系统提醒调度：AlarmManager setAlarmClock（精确、可唤醒、无需精确闹钟权限）。
 * 数据持久化在 SharedPreferences，开机 / 升级后由 BootReceiver 重新排程。
 *
 * 每项 JSON 字段（由 app.html / floating.html 下发，JS 计算 nextAt）：
 * id, taskId, taskName, accName, catName, time("HH:mm"), repeat(daily|weekly|cycle|date),
 * days[0-6], date("yyyy-MM-dd"), cycleDays, cycleStart, nextAt, enabled
 */
public final class ReminderScheduler {

    private static final String TAG = "ReminderScheduler";
    private static final String PREF = "reminder.store";
    private static final String KEY_ITEMS = "items";

    static final String ACTION_FIRE = "cn.taskrecord.app.FIRE";
    static final String ACTION_SNOOZE = "cn.taskrecord.app.SNOOZE";
    static final String ACTION_DISMISS = "cn.taskrecord.app.DISMISS";
    static final String EXTRA_ID = "id";
    static final String EXTRA_SNOOZE = "snooze";

    private static final long DAY = 24L * 3600 * 1000;

    private ReminderScheduler() {}

    /* ================= 对外接口 ================= */

    /** 全量同步：取消旧排程，持久化新列表，逐条排程 */
    public static synchronized void sync(Context ctx, String json) {
        List<JSONObject> items = parse(json);
        cancelAll(ctx, loadItems(ctx));
        saveItems(ctx, items);
        for (JSONObject it : items) {
            schedule(ctx, it);
        }
        Log.i(TAG, "sync " + items.size() + " reminders");
    }

    /** 开机 / 升级后：按持久化列表重新排程 */
    public static synchronized void rescheduleAll(Context ctx) {
        List<JSONObject> items = loadItems(ctx);
        for (JSONObject it : items) {
            schedule(ctx, it);
        }
        Log.i(TAG, "reschedule " + items.size() + " reminders");
    }

    /* ================= 排程 ================= */

    static void schedule(Context ctx, JSONObject item) {
        String id = item.optString("id", "");
        long nextAt = item.optLong("nextAt", 0);
        if (id.isEmpty() || nextAt <= 0) return;

        AlarmManager am = ctx.getSystemService(AlarmManager.class);
        if (am == null) return;

        Intent i = new Intent(ctx, ReminderReceiver.class)
                .setAction(ACTION_FIRE)
                .putExtra(EXTRA_ID, id);
        PendingIntent pi = PendingIntent.getBroadcast(ctx, requestCode(id, false), i,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        try {
            // setAlarmClock：精确到点、可唤醒设备、无需 SCHEDULE_EXACT_ALARM 权限
            am.setAlarmClock(new AlarmManager.AlarmClockInfo(nextAt, pi), pi);
        } catch (SecurityException e) {
            // 极端机型限制：退到非精确闹钟（仍然会响，可能延迟）
            try {
                am.setWindow(AlarmManager.RTC_WAKEUP, nextAt, 60_000L, pi);
            } catch (Exception ignore) {
            }
        }
    }

    static void cancel(Context ctx, String id) {
        AlarmManager am = ctx.getSystemService(AlarmManager.class);
        if (am == null) return;
        Intent i = new Intent(ctx, ReminderReceiver.class).setAction(ACTION_FIRE);
        PendingIntent pi = PendingIntent.getBroadcast(ctx, requestCode(id, false), i,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        am.cancel(pi);
        pi.cancel();
    }

    private static void cancelAll(Context ctx, List<JSONObject> items) {
        for (JSONObject it : items) {
            cancel(ctx, it.optString("id", ""));
        }
    }

    /** 10 分钟后再提醒（从通知动作触发） */
    static void snooze(Context ctx, String id) {
        AlarmManager am = ctx.getSystemService(AlarmManager.class);
        if (am == null) return;
        long at = System.currentTimeMillis() + 10 * 60 * 1000;
        Intent i = new Intent(ctx, ReminderReceiver.class)
                .setAction(ACTION_FIRE)
                .putExtra(EXTRA_ID, id)
                .putExtra(EXTRA_SNOOZE, true);
        PendingIntent pi = PendingIntent.getBroadcast(ctx, requestCode(id, true), i,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        try {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
        } catch (SecurityException e) {
            am.setWindow(AlarmManager.RTC_WAKEUP, at, 60_000L, pi);
        }
    }

    /* ================= 触发后的补排 ================= */

    /** 计算并排程下一次触发；date 类型（仅一次）直接移除 */
    static synchronized void rescheduleNext(Context ctx, String id) {
        List<JSONObject> items = loadItems(ctx);
        for (int i = 0; i < items.size(); i++) {
            JSONObject it = items.get(i);
            if (!id.equals(it.optString("id"))) continue;
            String repeat = it.optString("repeat", "daily");
            long nextAt = it.optLong("nextAt", 0);
            long now = System.currentTimeMillis();

            if ("date".equals(repeat)) {
                items.remove(i);
                saveItems(ctx, items);
                return;
            }
            long next;
            if ("daily".equals(repeat)) {
                next = nextAt + DAY;
                while (next <= now) next += DAY;
            } else if ("weekly".equals(repeat)) {
                next = nextWeekly(it, nextAt, now);
            } else if ("cycle".equals(repeat)) {
                next = nextCycle(it, now);
            } else {
                next = nextAt + DAY;
                while (next <= now) next += DAY;
            }
            if (next <= 0) {
                items.remove(i);
                saveItems(ctx, items);
                return;
            }
            try {
                it.put("nextAt", next);
            } catch (Exception ignore) {
            }
            saveItems(ctx, items);
            schedule(ctx, it);
            return;
        }
    }

    /** 每周：从当前 nextAt 起找下一个「星期几命中」的同一时刻 */
    private static long nextWeekly(JSONObject item, long fromNextAt, long now) {
        JSONArray days = item.optJSONArray("days");
        if (days == null || days.length() == 0) return 0;
        long timeOfDay = fromNextAt - startOfDay(fromNextAt);
        long day = startOfDay(fromNextAt) + DAY;
        for (int i = 0; i < 21; i++) {
            int dow = dayOfWeek(day);
            for (int j = 0; j < days.length(); j++) {
                if (days.optInt(j, -1) == dow) {
                    long cand = day + timeOfDay;
                    if (cand > now) return cand;
                }
            }
            day += DAY;
        }
        return 0;
    }

    /** 跟随周期：以 cycleStart 为基点，每 cycleDays 天的同一时刻 */
    private static long nextCycle(JSONObject item, long now) {
        long cycleStart = item.optLong("cycleStart", 0);
        int n = Math.max(1, item.optInt("cycleDays", 1));
        long base = cycleStart > 0 ? startOfDay(cycleStart) : startOfDay(now);
        long tod = timeOfDayOf(item);
        long k = (now - base) / (n * DAY) + 1;
        for (int guard = 0; guard < 4000; guard++) {
            long cand = base + k * n * DAY + tod;
            if (cand > now) return cand;
            k++;
        }
        return 0;
    }

    private static long timeOfDayOf(JSONObject item) {
        String time = item.optString("time", "09:00");
        int hh = 9, mm = 0;
        try {
            String[] p = time.split(":");
            hh = Integer.parseInt(p[0]);
            mm = p.length > 1 ? Integer.parseInt(p[1]) : 0;
        } catch (Exception ignore) {
        }
        Calendar c = Calendar.getInstance();
        c.set(Calendar.HOUR_OF_DAY, hh);
        c.set(Calendar.MINUTE, mm);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        return c.getTimeInMillis() - startOfDay(c.getTimeInMillis());
    }

    /* ================= 存储 ================= */

    static List<JSONObject> loadItems(Context ctx) {
        String raw = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
                .getString(KEY_ITEMS, "[]");
        return parse(raw);
    }

    private static void saveItems(Context ctx, List<JSONObject> items) {
        JSONArray arr = new JSONArray();
        for (JSONObject it : items) arr.put(it);
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
                .edit().putString(KEY_ITEMS, arr.toString()).apply();
    }

    static JSONObject findItem(Context ctx, String id) {
        for (JSONObject it : loadItems(ctx)) {
            if (id.equals(it.optString("id"))) return it;
        }
        return null;
    }

    private static List<JSONObject> parse(String json) {
        List<JSONObject> out = new ArrayList<>();
        if (json == null || json.isEmpty()) return out;
        try {
            JSONArray arr = new JSONArray(json);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o != null && o.optBoolean("enabled", true)
                        && !o.optString("id", "").isEmpty()) {
                    out.add(o);
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "parse reminders failed: " + e.getMessage());
        }
        return out;
    }

    /* ================= 工具 ================= */

    private static long startOfDay(long ts) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(ts);
        c.set(Calendar.HOUR_OF_DAY, 0);
        c.set(Calendar.MINUTE, 0);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        return c.getTimeInMillis();
    }

    private static int dayOfWeek(long ts) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(ts);
        return c.get(Calendar.DAY_OF_WEEK) - 1; // 0=周日
    }

    /** 稳定的请求码：同 id 的触发与稍后提醒互不干扰 */
    private static int requestCode(String id, boolean snooze) {
        String key = (snooze ? "snooze|" : "fire|") + id + "|" + id.length();
        return key.hashCode();
    }
}
