package cn.taskrecord.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/**
 * 开机 / 应用升级后：重新排程系统闹钟；若悬浮窗此前开启则恢复悬浮球。
 */
public class BootReceiver extends BroadcastReceiver {

    private static final String TAG = "BootReceiver";

    @Override
    public void onReceive(Context ctx, Intent intent) {
        if (intent == null) return;
        String action = intent.getAction();
        if (Intent.ACTION_BOOT_COMPLETED.equals(action)
                || Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)
                || "android.intent.action.QUICKBOOT_POWERON".equals(action)) {
            try {
                ReminderScheduler.rescheduleAll(ctx);
            } catch (Exception e) {
                Log.w(TAG, "reschedule failed: " + e.getMessage());
            }
            if (FloatingPrefs.isEnabled(ctx)) {
                try {
                    FloatingService.ensureRunning(ctx);
                    FloatingService.scheduleHeartbeat(ctx); /* 开机即续上进程外心跳链 */
                } catch (Exception ignore) {
                }
            }
        }
    }
}
