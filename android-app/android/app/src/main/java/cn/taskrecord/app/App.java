package cn.taskrecord.app;

import android.app.Application;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.os.Build;

/**
 * 应用入口：创建通知渠道（任务提醒 / 悬浮窗常驻）。
 */
public class App extends Application {

    public static final String CHANNEL_REMIND = "task_reminder_v2";
    public static final String CHANNEL_FLOATING = "floating_service";

    @Override
    public void onCreate() {
        super.onCreate();
        createChannels();
    }

    private void createChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm == null) return;

        NotificationChannel remind = new NotificationChannel(
                CHANNEL_REMIND, "任务提醒", NotificationManager.IMPORTANCE_HIGH);
        remind.setDescription("任务到点提醒（系统闹钟触发，响铃 + 震动）");
        remind.enableVibration(true);
        remind.setVibrationPattern(new long[]{0, 500, 250, 500, 250, 800});
        remind.enableLights(true);
        remind.setLightColor(0xFFFF0000);
        /* 用系统「闹铃」音而非通知音：更响、更易被听到 */
        android.net.Uri sound = android.provider.Settings.System.DEFAULT_ALARM_ALERT_URI;
        if (sound == null) sound = android.provider.Settings.System.DEFAULT_NOTIFICATION_URI;
        remind.setSound(sound,
                new android.media.AudioAttributes.Builder()
                        .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .setUsage(android.media.AudioAttributes.USAGE_ALARM)
                        .build());
        remind.setLockscreenVisibility(android.app.Notification.VISIBILITY_PUBLIC);
        nm.createNotificationChannel(remind);

        NotificationChannel floating = new NotificationChannel(
                CHANNEL_FLOATING, "悬浮窗服务", NotificationManager.IMPORTANCE_LOW);
        floating.setDescription("悬浮球运行中（可手动关闭）");
        floating.setShowBadge(false);
        nm.createNotificationChannel(floating);
    }
}
