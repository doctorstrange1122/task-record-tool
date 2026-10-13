package cn.taskrecord.app;

import android.app.Notification;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;

import org.json.JSONObject;

/**
 * 闹钟触发：前台时回拨 WebView 弹应用内提醒，后台时发系统通知；
 * 触发后按重复规则补排下一次（「稍后提醒」不补排）。
 */
public class ReminderReceiver extends BroadcastReceiver {

    private static final String TAG = "ReminderReceiver";
    private static final int NOTIFY_ID_BASE = 500000;

    @Override
    public void onReceive(Context ctx, Intent intent) {
        if (intent == null) return;
        String action = intent.getAction();
        if (ReminderScheduler.ACTION_DISMISS.equals(action)) {
            String id = intent.getStringExtra(ReminderScheduler.EXTRA_ID);
            NotificationManagerCompat.from(ctx).cancel(notifyId(id));
            return;
        }
        if (ReminderScheduler.ACTION_SNOOZE.equals(action)) {
            String id = intent.getStringExtra(ReminderScheduler.EXTRA_ID);
            if (id != null && !id.isEmpty()) {
                ReminderScheduler.snooze(ctx, id);
                NotificationManagerCompat.from(ctx).cancel(notifyId(id));
            }
            return;
        }
        String id = intent.getStringExtra(ReminderScheduler.EXTRA_ID);
        boolean snooze = intent.getBooleanExtra(ReminderScheduler.EXTRA_SNOOZE, false);
        if (id == null || id.isEmpty()) return;

        JSONObject item = ReminderScheduler.findItem(ctx, id);
        if (item == null) {
            Log.w(TAG, "reminder not found: " + id);
            return;
        }

        boolean foreground = MainActivity.isResumed();
        if (foreground) {
            MainActivity.fireReminderFromNative(item.toString());
        } else {
            postNotification(ctx, item);
        }

        if (!snooze) {
            ReminderScheduler.rescheduleNext(ctx, id);
        } else {
            // 稍后提醒的那一次：到点只提醒，不改动原 nextAt
            Log.i(TAG, "snoozed fire: " + id);
        }
    }

    private void postNotification(Context ctx, JSONObject item) {
        String id = item.optString("id", "");
        String taskName = item.optString("taskName", "任务");
        String accName = item.optString("accName", "");
        String time = item.optString("time", "");
        String repeatLabel = repeatLabel(item);

        StringBuilder text = new StringBuilder();
        if (!accName.isEmpty()) text.append(accName).append(" · ");
        text.append(time);
        if (!repeatLabel.isEmpty()) text.append(" · ").append(repeatLabel);

        // 点击通知 → 打开主界面并弹出对应提醒
        Intent open = new Intent(ctx, MainActivity.class)
                .setAction(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LAUNCHER)
                .putExtra(MainActivity.EXTRA_REMIND_ID, id);
        PendingIntent contentPi = PendingIntent.getActivity(ctx, notifyId(id), open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        // 稍后提醒
        Intent snooze = new Intent(ctx, ReminderReceiver.class)
                .setAction(ReminderScheduler.ACTION_SNOOZE)
                .putExtra(ReminderScheduler.EXTRA_ID, id);
        PendingIntent snoozePi = PendingIntent.getBroadcast(ctx, notifyId(id) + 1, snooze,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        // 关闭
        Intent dismiss = new Intent(ctx, ReminderReceiver.class)
                .setAction(ReminderScheduler.ACTION_DISMISS)
                .putExtra(ReminderScheduler.EXTRA_ID, id);
        PendingIntent dismissPi = PendingIntent.getBroadcast(ctx, notifyId(id) + 2, dismiss,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Notification n = new NotificationCompat.Builder(ctx, App.CHANNEL_REMIND)
                .setSmallIcon(R.drawable.ic_stat_remind)
                .setContentTitle("该完成任务了")
                .setContentText(taskName + (text.length() > 0 ? "\n" + text : ""))
                .setStyle(new NotificationCompat.BigTextStyle().bigText(taskName + "\n" + text))
                .setCategory(NotificationCompat.CATEGORY_ALARM)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setDefaults(Notification.DEFAULT_ALL)
                .setAutoCancel(true)
                .setContentIntent(contentPi)
                .addAction(0, "10分钟后再提醒", snoozePi)
                .addAction(0, "关闭", dismissPi)
                .build();

        try {
            NotificationManagerCompat.from(ctx).notify(notifyId(id), n);
        } catch (SecurityException e) {
            Log.w(TAG, "notify denied: " + e.getMessage());
        }
    }

    private String repeatLabel(JSONObject item) {
        String repeat = item.optString("repeat", "daily");
        switch (repeat) {
            case "daily":
                return "每天";
            case "cycle":
                return "每 " + Math.max(1, item.optInt("cycleDays", 1)) + " 日";
            case "date":
                return "仅一次";
            case "weekly": {
                org.json.JSONArray days = item.optJSONArray("days");
                if (days == null || days.length() == 0) return "每周";
                String[] wk = {"日", "一", "二", "三", "四", "五", "六"};
                StringBuilder sb = new StringBuilder("每周");
                for (int i = 0; i < days.length(); i++) {
                    int d = days.optInt(i, -1);
                    if (d >= 0 && d < 7) sb.append(wk[d]);
                }
                return sb.toString();
            }
            default:
                return "";
        }
    }

    static int notifyId(String id) {
        return NOTIFY_ID_BASE + (id.hashCode() & 0x0FFFFFFF);
    }
}
