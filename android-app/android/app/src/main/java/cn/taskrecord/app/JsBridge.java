package cn.taskrecord.app;

import android.Manifest;
import android.app.Activity;
import android.app.AlarmManager;
import android.app.NotificationManager;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
import android.webkit.JavascriptInterface;
import android.widget.Toast;

import androidx.core.app.NotificationManagerCompat;
import androidx.core.content.ContextCompat;

/**
 * WebView ↔ 原生桥：页面里以 window.AndroidHost.* 调用。
 * 所有方法都运行在 WebView 的 JS 线程上，涉及 UI / Activity 的操作必须 post 到主线程。
 */
public class JsBridge {

    private final Context ctx;
    private final Activity activity;
    private Runnable closePanelRunnable;

    public JsBridge(Activity activity) {
        this.activity = activity;
        this.ctx = activity;
    }

    /** 供前台服务中的悬浮面板浮层使用（无 Activity） */
    public JsBridge(Context context) {
        this.activity = null;
        this.ctx = context;
    }

    public void setClosePanelCallback(Runnable r) {
        this.closePanelRunnable = r;
    }

    private Context ctx() {
        return ctx;
    }

    private void ui(Runnable r) {
        if (activity != null) {
            activity.runOnUiThread(r);
        } else {
            new Handler(Looper.getMainLooper()).post(r);
        }
    }

    private void showToast(final String msg) {
        ui(() -> Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show());
    }

    /* ================= 剪贴板 ================= */

    @JavascriptInterface
    public String readClipboard() {
        try {
            ClipboardManager cm = (ClipboardManager) ctx.getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm == null || !cm.hasPrimaryClip()) return "";
            ClipData cd = cm.getPrimaryClip();
            if (cd == null || cd.getItemCount() == 0) return "";
            CharSequence t = cd.getItemAt(0).getText();
            return t == null ? "" : t.toString();
        } catch (Exception e) {
            return "";
        }
    }

    /** 悬浮面板用：与 readClipboard 相同，单独命名便于面板语义 */
    @JavascriptInterface
    public String getClipSnapshot() {
        return readClipboard();
    }

    @JavascriptInterface
    public boolean copyText(String text) {
        try {
            ClipboardManager cm = (ClipboardManager) ctx.getSystemService(Context.CLIPBOARD_SERVICE);
            cm.setPrimaryClip(ClipData.newPlainText("task_record", text == null ? "" : text));
            showToast("已复制");
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /* ================= 悬浮窗 ================= */

    @JavascriptInterface
    public boolean setFloating(boolean on) {
        final Context c = ctx();
        if (c == null) return false;
        if (on) {
            if (Build.VERSION.SDK_INT >= 23 && !Settings.canDrawOverlays(c)) {
                showToast("请先授予「悬浮窗」权限");
                ui(() -> {
                    try {
                        Intent i = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                Uri.parse("package:" + c.getPackageName()));
                        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        c.startActivity(i);
                    } catch (Exception e) {
                        try {
                            c.startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                        } catch (Exception ignore) {
                        }
                    }
                });
                return false;
            }
            FloatingPrefs.setEnabled(c, true);
            try {
                ContextCompat.startForegroundService(c, new Intent(c, FloatingService.class));
            } catch (Exception e) {
                FloatingPrefs.setEnabled(c, false);
                showToast("悬浮窗启动失败");
                return false;
            }
            /* 开启悬浮窗时一并引导稳定性设置：电池优化豁免 + 精确闹钟权限，
               这是华为 / 鸿蒙 / Doze 下悬浮球不被回收、心跳准点的前提 */
            guideStabilityIfNeeded();
            return true;
        } else {
            FloatingPrefs.setEnabled(c, false);
            /* 用户主动关闭：连同心跳/重启闹钟一起取消，避免无谓唤醒 */
            try {
                FloatingService.cancelHeartbeat(c);
            } catch (Exception ignore) {
            }
            try {
                c.stopService(new Intent(c, FloatingService.class));
            } catch (Exception ignore) {
            }
            return true;
        }
    }

    @JavascriptInterface
    public boolean floatingEnabled() {
        Context c = ctx();
        return c != null && FloatingPrefs.isEnabled(c);
    }

    /* ================= 稳定性 / 保活引导 ================= */

    /** 是否已关闭电池优化（豁免 Doze 对前台服务与心跳闹钟的节流） */
    @JavascriptInterface
    public boolean batteryOptimizationIgnored() {
        Context c = ctx();
        if (c == null) return true;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PowerManager pm = (PowerManager) c.getSystemService(Context.POWER_SERVICE);
            return pm != null && pm.isIgnoringBatteryOptimizations(c.getPackageName());
        }
        return true;
    }

    /** 跳转系统「忽略电池优化」设置页（用户确认后，Doze 不再节流本应用） */
    @JavascriptInterface
    public boolean requestIgnoreBatteryOptimizations() {
        Context c = ctx();
        if (c == null) return false;
        ui(() -> {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    Intent i = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                            Uri.parse("package:" + c.getPackageName()));
                    i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    c.startActivity(i);
                }
            } catch (Exception e) {
                try {
                    c.startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.parse("package:" + c.getPackageName()))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                } catch (Exception ignore) {
                }
            }
        });
        return true;
    }

    /**
     * 开启悬浮窗时依次补齐最关键的保活条件：先引导关闭电池优化（最影响 Doze 节流），
     * 若已豁免再引导授予「精确闹钟」权限（保证 60 秒心跳准点）。两者都不强制，用户可跳过。
     */
    private void guideStabilityIfNeeded() {
        final Context c = ctx();
        if (c == null) return;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !batteryOptimizationIgnored()) {
            showToast("建议关闭电池优化，避免悬浮球被系统回收");
            requestIgnoreBatteryOptimizations();
            return;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !exactAlarmAllowed()) {
            showToast("建议允许「精确闹钟」，保证心跳准点");
            openExactAlarmSettings();
        }
    }

    /* ================= 系统提醒 ================= */

    /**
     * 全量下发提醒规则（JSON 数组）。JS 为事实源：每项含 nextAt（下次触发时刻），
     * 原生只负责持久化 + AlarmManager 排程 + 到点通知。
     */
    @JavascriptInterface
    public void syncReminders(String json) {
        Context c = ctx();
        if (c == null) return;
        ReminderScheduler.sync(c, json);
    }

    @JavascriptInterface
    public boolean exactAlarmAllowed() {
        Context c = ctx();
        if (c == null) return true;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            AlarmManager am = c.getSystemService(AlarmManager.class);
            return am != null && am.canScheduleExactAlarms();
        }
        return true;
    }

    @JavascriptInterface
    public boolean openExactAlarmSettings() {
        Context c = ctx();
        if (c == null) return false;
        ui(() -> {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    Intent i = new Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                            Uri.parse("package:" + c.getPackageName()));
                    i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    c.startActivity(i);
                } else {
                    c.startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.parse("package:" + c.getPackageName()))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                }
            } catch (Exception e) {
                try {
                    c.startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.parse("package:" + c.getPackageName()))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                } catch (Exception ignore) {
                }
            }
        });
        return true;
    }

    @JavascriptInterface
    public boolean notificationsAllowed() {
        Context c = ctx();
        return c != null && NotificationManagerCompat.from(c).areNotificationsEnabled();
    }

    @JavascriptInterface
    public boolean openNotificationSettings() {
        Context c = ctx();
        if (c == null) return false;
        ui(() -> {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    Intent i = new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                            .putExtra(Settings.EXTRA_APP_PACKAGE, c.getPackageName());
                    i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    c.startActivity(i);
                } else {
                    c.startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.parse("package:" + c.getPackageName()))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                }
            } catch (Exception e) {
                try {
                    c.startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.parse("package:" + c.getPackageName()))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                } catch (Exception ignore) {
                }
            }
        });
        return true;
    }

    /** Android 13+ 运行时申请通知权限 */
    @JavascriptInterface
    public boolean requestNotifications() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return false;
        if (activity == null) return false;
        if (ContextCompat.checkSelfPermission(activity, Manifest.permission.POST_NOTIFICATIONS)
                == PackageManager.PERMISSION_GRANTED) {
            return true;
        }
        ui(() -> {
            try {
                activity.requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1001);
            } catch (Exception ignore) {
            }
        });
        return true;
    }

    /* ================= 面板 / 其他 ================= */

    @JavascriptInterface
    public boolean closePanel() {
        ui(() -> {
            if (closePanelRunnable != null) {
                closePanelRunnable.run();
            }
        });
        return true;
    }

    @JavascriptInterface
    public boolean openMain() {
        final Context c = ctx();
        if (c == null) return false;
        ui(() -> {
            try {
                Intent i = new Intent(c, MainActivity.class);
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
                c.startActivity(i);
            } catch (Exception ignore) {
            }
        });
        return true;
    }

    @JavascriptInterface
    public void toast(String msg) {
        if (msg != null && !msg.isEmpty()) showToast(msg);
    }

    /** 设置状态栏颜色（跟随页面主题色）；按亮度自动切换状态栏图标深浅 */
    @JavascriptInterface
    public void setStatusBarColor(final String color) {
        final Activity a = activity;
        if (a == null || color == null) return;
        ui(() -> {
            try {
                int c = android.graphics.Color.parseColor(color.trim());
                android.view.Window w = a.getWindow();
                w.setStatusBarColor(c);
                double lum = (0.299 * android.graphics.Color.red(c)
                        + 0.587 * android.graphics.Color.green(c)
                        + 0.114 * android.graphics.Color.blue(c)) / 255.0;
                android.view.View decor = w.getDecorView();
                int flags = decor.getSystemUiVisibility();
                if (lum > 0.6) {
                    flags |= android.view.View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
                } else {
                    flags &= ~android.view.View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
                }
                decor.setSystemUiVisibility(flags);
            } catch (Exception ignore) {
            }
        });
    }

    @JavascriptInterface
    public String appVersion() {
        return BuildConfig.VERSION_NAME;
    }

    /** 应用内提醒：播放系统闹铃音 + 震动（弹窗时调用；WebView 的 WebAudio 常被自动播放策略拦截而无声） */
    @JavascriptInterface
    public void playAlarm() {
        final Context c = ctx();
        if (c == null) return;
        ui(() -> {
            /* 响铃：优先系统闹铃音，退到通知音 */
            try {
                android.net.Uri uri = android.provider.Settings.System.DEFAULT_ALARM_ALERT_URI;
                if (uri == null) uri = android.provider.Settings.System.DEFAULT_NOTIFICATION_URI;
                final android.media.Ringtone rt = android.media.RingtoneManager.getRingtone(c, uri);
                if (rt != null) {
                    try { rt.setStreamType(android.media.AudioManager.STREAM_ALARM); } catch (Exception ignore) {}
                    rt.play();
                    new Handler(Looper.getMainLooper()).postDelayed(() -> {
                        try { if (rt.isPlaying()) rt.stop(); } catch (Exception ignore) {}
                    }, 8000);
                }
            } catch (Exception ignore) {
            }
            /* 震动：多段波形，比默认单次更明显 */
            try {
                android.os.Vibrator v = (android.os.Vibrator) c.getSystemService(Context.VIBRATOR_SERVICE);
                if (v != null) {
                    long[] pattern = {0, 500, 250, 500, 250, 800};
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        v.vibrate(android.os.VibrationEffect.createWaveform(pattern, -1));
                    } else {
                        v.vibrate(pattern, -1);
                    }
                }
            } catch (Exception ignore) {
            }
        });
    }

    /** 供页面探测桥是否可用（JS 侧用 Native.available() 亦可） */
    @JavascriptInterface
    public boolean ping() {
        return true;
    }
}
