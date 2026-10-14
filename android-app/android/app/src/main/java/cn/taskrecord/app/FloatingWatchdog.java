package cn.taskrecord.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * 悬浮球「进程外」心跳看门狗。
 *
 * 背景：Service 内的 Handler 看门狗、以及 onDestroy / onTaskRemoved 里排的重启闹钟，
 * 都要求「进程还活着，并且那段代码真的被执行到」。华为 / 鸿蒙在内存紧张或开启
 * 「应用速冻」后，会直接把进程冻结甚至 SIGKILL：
 *   · 进程被冻结 → Handler 停摆，overlay 窗口被系统回收后没人修复；
 *   · 进程被直接杀掉 → 不走 onDestroy，那些「延迟重启」根本不会被排上。
 * 结果就是悬浮球一直消失、再也回不来。
 *
 * 本接收器由 AlarmManager 的显式闹钟（约 60 秒一次）唤醒，亮屏 / 解锁时也会被触发。
 * 闹钟是系统级能力，不依赖本进程存活：到点就把进程拉起，这里立刻检查并恢复悬浮球，
 * 然后重排下一次心跳，形成持续的进程外看护。
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
