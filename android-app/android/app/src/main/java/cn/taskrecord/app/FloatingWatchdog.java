package cn.taskrecord.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * 悬浮球进程外看护接收器（亮屏 / 解锁即时恢复）。
 *
 * 说明：周期性心跳闹钟现在由 AlarmManager 直接拉起 FloatingService（getForegroundService），
 * 不经由本接收器，以避免 Android 12+ 后台启动前台服务的限制。详见 FloatingService.scheduleHeartbeat。
 *
 * 本接收器只负责「用户点亮屏幕 / 解锁」这两个最贴近使用场景的时机，让已在运行（但可能
 * 因冻结导致 overlay 被回收、或球被留在隐藏态）的服务立刻巡检并恢复悬浮球；若服务进程
 * 已死，则顺手拉起。亮屏即恢复，无需等到下一个 60 秒心跳，体验上「想用时球就在」。
 */
public class FloatingWatchdog extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        if (context == null) return;
        Context app = context.getApplicationContext();

        /* 用户已主动关闭悬浮窗：不再自愈，并清掉心跳闹钟 */
        if (!FloatingPrefs.isEnabled(app)) {
            FloatingService.cancelHeartbeat(app);
            return;
        }

        /* 1) 服务不在（进程曾被回收）→ 重新拉起前台服务 */
        FloatingService.ensureRunning(app);

        /* 2) 服务在运行 → 让它立刻巡检一次悬浮球（窗口被移除 / 被留在隐藏态） */
        FloatingService.requestCheck(app);

        /* 3) 重排下一次心跳，保持进程外看护持续 */
        FloatingService.scheduleHeartbeat(app);
    }
}
