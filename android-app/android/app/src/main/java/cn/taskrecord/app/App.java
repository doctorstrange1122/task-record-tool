package cn.taskrecord.app;

import android.app.Application;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.os.Build;

/**
 * 应用入口：创建通知渠道（任务提醒 / 悬浮窗常驻）。
 */
public class App extends Application {

    public static final String CHANNEL_REMIND = "task_reminder";
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
        remind.setDescription("任务到点提醒（系统闹钟触发）");
        remind.enableVibration(true);
        remind.enableLights(true);
        remind.setSound(android.provider.Settings.System.DEFAULT_NOTIFICATION_URI,
                new android.media.AudioAttributes.Builder()
                        .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .setUsage(android.media.AudioAttributes.USAGE_NOTIFICATION_EVENT)
                        .build());
        nm.createNotificationChannel(remind);

        NotificationChannel floating = new NotificationChannel(
                CHANNEL_FLOATING, "悬浮窗服务", NotificationManager.IMPORTANCE_LOW);
        floating.setDescription("悬浮球运行中（可手动关闭）");
        floating.setShowBadge(false);
        nm.createNotificationChannel(floating);
    }
}
