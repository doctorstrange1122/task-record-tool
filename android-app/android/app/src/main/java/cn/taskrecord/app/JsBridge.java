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

    private final Activity activity;

    public JsBridge(Activity activity) {
        this.activity = activity;
    }

    private Context ctx() {
        return activity == null ? null : activity.getApplicationContext();
    }

    private void ui(Runnable r) {
        if (activity != null) activity.runOnUiThread(r);
    }

    private void showToast(final String msg) {
        ui(() -> Toast.makeText(activity, msg, Toast.LENGTH_SHORT).show());
    }

    /* ================= 剪贴板 ================= */

    @JavascriptInterface
    public String readClipboard() {
        try {
            ClipboardManager cm = (ClipboardManager) activity.getSystemService(Context.CLIPBOARD_SERVICE);
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
            ClipboardManager cm = (ClipboardManager) activity.getSystemService(Context.CLIPBOARD_SERVICE);
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
            return true;
        } else {
            FloatingPrefs.setEnabled(c, false);
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
            if (activity instanceof PanelActivity) {
                activity.finish();
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

    @JavascriptInterface
    public String appVersion() {
        return BuildConfig.VERSION_NAME;
    }

    /** 供页面探测桥是否可用（JS 侧用 Native.available() 亦可） */
    @JavascriptInterface
    public boolean ping() {
        return true;
    }
}
