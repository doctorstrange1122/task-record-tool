package cn.taskrecord.app;

import android.animation.ValueAnimator;
import android.annotation.SuppressLint;
import android.app.AlarmManager;
import android.app.Notification;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.webkit.SslErrorHandler;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.widget.LinearLayout;

import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;
import androidx.webkit.WebResourceErrorCompat;
import androidx.webkit.WebViewAssetLoader;
import androidx.webkit.WebViewClientCompat;

/**
 * 悬浮球：前台服务承载，可拖拽、松手贴边，点击打开悬浮面板。
 * 系统提醒到点时系统通知独立于此服务（AlarmManager 直接触发）。
 */
public class FloatingService extends android.app.Service {

    private WindowManager windowManager;
    private View ballView;
    private WindowManager.LayoutParams params;

    /* 悬浮面板：WindowManager 浮层（绕开 Activity 窗口，兼容鸿蒙透明背景） */
    private View panelView;
    private WebView panelWebView;
    private WindowManager.LayoutParams panelParams;
    private boolean panelShown = false;
    private int panelCardW = 0;
    private int panelScreenW = 0;
    private View panelCardView;
    private static final String PREFS_NAME = "taskrecord_panel";
    private static final String KEY_SIDE_RIGHT = "panel_side_right";

    private boolean panelSideRight() {
        return getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getBoolean(KEY_SIDE_RIGHT, true);
    }

    /** 面板布局完成后按记忆的左右偏好贴边摆放（必须在 addView 之后调用，否则 setX 会与 layout_gravity 叠加导致卡片被推出屏幕） */
    private void applyCardSide() {
        if (panelCardView == null || panelScreenW <= 0 || panelCardW <= 0) return;
        int maxX = Math.max(0, panelScreenW - panelCardW);
        panelCardView.setX(panelSideRight() ? maxX : 0);
    }

    private float startX, startY, touchX, touchY;
    private boolean moved;
    private static final int CLICK_SLOP = 12;

    /* ===== 保活/自愈：部分机型（尤其鸿蒙/EMUI）会回收前台服务的悬浮窗 ===== */
    private static final long WATCH_INTERVAL_MS = 15_000L;   /* 看门狗巡检间隔 */
    private static final int RC_RESTART = 10086;
    /* 进程外心跳：闹钟是系统级的，即使本进程被冻结 / 被杀，也能把服务唤回来 */
    public static final String ACTION_HEARTBEAT = "cn.taskrecord.app.FLOATING_HEARTBEAT";
    public static final String ACTION_CHECK = "cn.taskrecord.app.FLOATING_CHECK";
    private static final int RC_HEARTBEAT = 10087;
    private static final long HEARTBEAT_INTERVAL_MS = 60_000L; /* 心跳间隔 60 秒 */
    /** 服务是否在运行（供心跳接收器判断是否需要重新拉起） */
    private static volatile boolean sRunning = false;
    private static final String KEY_BALL_X = "ball_x";
    private static final String KEY_BALL_Y = "ball_y";
    private Handler watchdog;
    private final Runnable watchRunnable = new Runnable() {
        @Override
        public void run() {
            try {
                ensureOverlayAlive();
            } catch (Exception ignore) {
            }
            if (watchdog != null) watchdog.postDelayed(this, WATCH_INTERVAL_MS);
        }
    };

    /**
     * 自愈检查：恢复被系统移除的悬浮球 / 被遗留为隐藏态的悬浮球。
     * 触发场景：
     *  1) 系统回收了 overlay 窗口（球“凭空消失”）；
     *  2) 打开面板时把球设为 GONE，但面板未正常关闭就被系统移除 → 球一直隐藏。
     */
    private void ensureOverlayAlive() {
        if (!FloatingPrefs.isEnabled(this)) return;
        if (Build.VERSION.SDK_INT >= 23 && !android.provider.Settings.canDrawOverlays(this)) return;
        if (windowManager == null) {
            windowManager = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
            if (windowManager == null) return;
        }
        /* 面板已不在窗口上却仍被标记为「已打开」：复位标记，别让球一直被隐藏 */
        if (panelShown && (panelView == null || panelView.getWindowToken() == null)) {
            panelShown = false;
        }
        /* 球丢失（被系统回收）：重新挂上 */
        if (ballView == null || ballView.getWindowToken() == null) {
            if (ballView != null) {
                try { windowManager.removeView(ballView); } catch (Exception ignore) {}
            }
            ballView = null;
            showBall();
            return;
        }
        /* 球还在、面板也没开，但被留在隐藏态：恢复显示 */
        if (!panelShown && ballView.getVisibility() != View.VISIBLE) {
            ballView.setVisibility(View.VISIBLE);
        }
    }

    /** 延迟拉起本服务（用于被划掉/被回收后自愈）；用户已关闭悬浮窗时不重启 */
    private void scheduleRestart(long delayMs) {
        if (!FloatingPrefs.isEnabled(this)) return;
        try {
            Intent i = new Intent(this, FloatingService.class);
            i.setPackage(getPackageName());
            int flags = PendingIntent.FLAG_ONE_SHOT | PendingIntent.FLAG_IMMUTABLE;
            PendingIntent pi = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                    ? PendingIntent.getForegroundService(this, RC_RESTART, i, flags)
                    : PendingIntent.getService(this, RC_RESTART, i, flags);
            AlarmManager am = (AlarmManager) getSystemService(Context.ALARM_SERVICE);
            if (am == null) return;
            long at = System.currentTimeMillis() + Math.max(300L, delayMs);
            try {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
            } catch (Exception e) {
                am.set(AlarmManager.RTC_WAKEUP, at, pi);
            }
        } catch (Exception ignore) {
        }
    }

    /* ===== 进程外心跳（关键保活）：由 FloatingWatchdog 接收闹钟后调用 ===== */

    public static boolean isRunning() {
        return sRunning;
    }

    /** 确保前台服务在运行（心跳 / 亮屏 / 开机时调用）；已在运行或用户已关闭则不动 */
    public static void ensureRunning(Context ctx) {
        if (ctx == null) return;
        Context app = ctx.getApplicationContext();
        if (!FloatingPrefs.isEnabled(app) || sRunning) return;
        if (Build.VERSION.SDK_INT >= 23 && !android.provider.Settings.canDrawOverlays(app)) return;
        try {
            Intent i = new Intent(app, FloatingService.class);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) app.startForegroundService(i);
            else app.startService(i);
        } catch (Exception e) {
            /* Android 12+ 后台启动前台服务受限时降级尝试普通启动 */
            try {
                app.startService(new Intent(app, FloatingService.class));
            } catch (Exception ignore) {
            }
        }
    }

    /** 让正在运行的服务立刻巡检一次悬浮球；服务不在则顺手拉起 */
    public static void requestCheck(Context ctx) {
        if (ctx == null) return;
        Context app = ctx.getApplicationContext();
        if (!sRunning) {
            ensureRunning(app);
            return;
        }
        try {
            app.startService(new Intent(app, FloatingService.class).setAction(ACTION_CHECK));
        } catch (Exception ignore) {
        }
    }

    /** 排下一次心跳闹钟（显式广播，进程被杀也能唤回服务） */
    public static void scheduleHeartbeat(Context ctx) {
        if (ctx == null) return;
        Context app = ctx.getApplicationContext();
        if (!FloatingPrefs.isEnabled(app)) return;
        try {
            Intent i = new Intent(app, FloatingWatchdog.class).setAction(ACTION_HEARTBEAT);
            int flags = PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE;
            PendingIntent pi = PendingIntent.getBroadcast(app, RC_HEARTBEAT, i, flags);
            AlarmManager am = (AlarmManager) app.getSystemService(Context.ALARM_SERVICE);
            if (am == null) return;
            long at = System.currentTimeMillis() + HEARTBEAT_INTERVAL_MS;
            try {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
            } catch (Exception e) {
                am.set(AlarmManager.RTC_WAKEUP, at, pi);
            }
        } catch (Exception ignore) {
        }
    }

    /** 取消心跳与重启闹钟（用户主动关闭悬浮窗时调用，避免无谓唤醒） */
    public static void cancelHeartbeat(Context ctx) {
        if (ctx == null) return;
        Context app = ctx.getApplicationContext();
        try {
            AlarmManager am = (AlarmManager) app.getSystemService(Context.ALARM_SERVICE);
            if (am == null) return;
            int flags = PendingIntent.FLAG_NO_CREATE | PendingIntent.FLAG_IMMUTABLE;
            PendingIntent hb = PendingIntent.getBroadcast(app, RC_HEARTBEAT,
                    new Intent(app, FloatingWatchdog.class).setAction(ACTION_HEARTBEAT), flags);
            if (hb != null) {
                am.cancel(hb);
                hb.cancel();
            }
            Intent ri = new Intent(app, FloatingService.class);
            ri.setPackage(app.getPackageName());
            PendingIntent rs = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                    ? PendingIntent.getForegroundService(app, RC_RESTART, ri, flags)
                    : PendingIntent.getService(app, RC_RESTART, ri, flags);
            if (rs != null) {
                am.cancel(rs);
                rs.cancel();
            }
        } catch (Exception ignore) {
        }
    }

    /** 记录悬浮球位置，供恢复时还原 */
    private void saveBallPos() {
        if (params == null) return;
        try {
            getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
                    .putInt(KEY_BALL_X, params.x)
                    .putInt(KEY_BALL_Y, params.y)
                    .apply();
        } catch (Exception ignore) {
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        startForeground(1, buildNotification());
        sRunning = true;
        scheduleHeartbeat(this); /* 进程外心跳：服务本身被系统回收后仍能被闹钟唤回 */
        watchdog = new Handler(Looper.getMainLooper());
        watchdog.postDelayed(watchRunnable, 4000);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (Build.VERSION.SDK_INT >= 23 && !android.provider.Settings.canDrawOverlays(this)) {
            /* 权限被回收：关闭开关并停止，避免「自愈重启」反复空转 */
            FloatingPrefs.setEnabled(this, false);
            cancelHeartbeat(this);
            stopSelf();
            return START_NOT_STICKY;
        }
        /* 用自愈逻辑：既能在首次启动挂上悬浮球，也能在服务被系统重建后补挂已丢失的球 */
        ensureOverlayAlive();
        scheduleHeartbeat(this); /* 每次被拉起都续上下一次心跳，避免链断 */
        return START_STICKY;
    }

    private Notification buildNotification() {
        PendingIntent pi = PendingIntent.getActivity(this, 10,
                new Intent(this, MainActivity.class),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new NotificationCompat.Builder(this, App.CHANNEL_FLOATING)
                .setSmallIcon(R.drawable.ic_stat_remind)
                .setContentTitle("任务记录工具")
                .setContentText("悬浮窗运行中，点按悬浮球快速记录")
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setContentIntent(pi)
                .setOngoing(true)
                .build();
    }

    private void showBall() {
        if (ballView != null) return;
        windowManager = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
        if (windowManager == null) return;
        ballView = LayoutInflater.from(this).inflate(R.layout.view_floating_ball, null);

        int type = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;

        params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.TOP | Gravity.START;
        /* 还原上次位置（被系统回收后恢复时不跳位） */
        android.content.SharedPreferences sp = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        params.x = sp.getInt(KEY_BALL_X, 0);
        int savedY = sp.getInt(KEY_BALL_Y, -1);
        params.y = savedY >= 0 ? savedY : (windowManager.getDefaultDisplay().getHeight() / 2);

        ballView.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        startX = event.getRawX();
                        startY = event.getRawY();
                        touchX = startX;
                        touchY = startY;
                        moved = false;
                        return true;
                    case MotionEvent.ACTION_MOVE: {
                        float dx = event.getRawX() - touchX;
                        float dy = event.getRawY() - touchY;
                        if (!moved && (Math.abs(event.getRawX() - startX) > CLICK_SLOP
                                || Math.abs(event.getRawY() - startY) > CLICK_SLOP)) {
                            moved = true;
                        }
                        if (moved) {
                            params.x = (int) (params.x + dx);
                            params.y = (int) (params.y + dy);
                            windowManager.updateViewLayout(ballView, params);
                        }
                        touchX = event.getRawX();
                        touchY = event.getRawY();
                        return true;
                    }
                    case MotionEvent.ACTION_UP:
                        if (!moved) {
                            openPanel();
                        } else {
                            snapToEdge();
                        }
                        return true;
                    default:
                        return false;
                }
            }
        });

        try {
            windowManager.addView(ballView, params);
            saveBallPos();
        } catch (Exception e) {
            /* 添加失败（权限被回收等）：关闭开关并停止，避免自愈重启空转 */
            FloatingPrefs.setEnabled(this, false);
            ballView = null;
            stopSelf();
        }
    }

    private void snapToEdge() {
        if (ballView == null || params == null) return;
        final int screenW = windowManager.getDefaultDisplay().getWidth();
        final int ballW = ballView.getWidth();
        final int targetX = (params.x + ballW / 2 < screenW / 2) ? 0 : screenW - ballW;
        final int fromX = params.x;
        ValueAnimator anim = ValueAnimator.ofInt(fromX, targetX);
        anim.setDuration(160);
        anim.addUpdateListener(a -> {
            params.x = (int) a.getAnimatedValue();
            try {
                windowManager.updateViewLayout(ballView, params);
            } catch (Exception ignore) {
            }
        });
        anim.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(android.animation.Animator animation) {
                saveBallPos(); /* 记住贴边后的位置 */
            }
        });
        anim.start();
    }

    private void openPanel() {
        if (panelShown) {
            closePanel();
        } else {
            showPanel();
        }
    }

    private void showPanel() {
        if (windowManager == null) return;
        if (panelView == null) {
            setupPanel();
        }
        if (panelView == null) return;

        int type = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;

        panelParams = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                type,
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        panelParams.gravity = Gravity.TOP | Gravity.START;
        panelParams.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE;

        try {
            windowManager.addView(panelView, panelParams);
            panelShown = true;
            /* 面板打开时隐藏悬浮球（点面板外任意处即可收起面板） */
            if (ballView != null) {
                ballView.setVisibility(View.GONE);
            }
            /* 布局完成后按记忆偏好贴边摆放卡片 */
            panelView.post(this::applyCardSide);
            /* 主动重读主题：面板 WebView 已缓存，主界面改色后需每次打开补一次 */
            if (panelWebView != null) {
                panelWebView.post(() -> {
                    try {
                        panelWebView.evaluateJavascript(
                                "if(window.__applyPanelTheme)window.__applyPanelTheme();", null);
                    } catch (Exception ignore) {
                    }
                });
            }
        } catch (Exception ignore) {
        }
    }

    private void closePanel() {
        if (windowManager != null && panelView != null && panelView.getWindowToken() != null) {
            try {
                windowManager.removeView(panelView);
            } catch (Exception ignore) {
            }
        }
        panelShown = false;
        /* 关闭面板后恢复悬浮球 */
        if (ballView != null) {
            ballView.setVisibility(View.VISIBLE);
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private void setupPanel() {
        LayoutInflater inf = LayoutInflater.from(this);
        panelView = inf.inflate(R.layout.activity_panel, null);
        if (panelView == null) return;

        View card = panelView.findViewById(R.id.panelCard);
        View handle = panelView.findViewById(R.id.panelHandle);
        View root = panelView.findViewById(R.id.panelRoot);

        /* 卡片尺寸：宽 45% 屏宽、高 80% 屏高，垂直居中。
           必须用 WindowManager 的显示度量（与悬浮球一致）；Service 里
           getResources().getDisplayMetrics() 偶尔返回 0，会让卡片缩成不可见 */
        android.util.DisplayMetrics dm = new android.util.DisplayMetrics();
        if (windowManager != null && windowManager.getDefaultDisplay() != null) {
            windowManager.getDefaultDisplay().getMetrics(dm);
        } else {
            dm = getResources().getDisplayMetrics();
        }
        final int screenW = dm.widthPixels;
        final int screenH = dm.heightPixels;
        panelScreenW = screenW;
        panelCardView = card;
        if (card != null && card.getLayoutParams() != null) {
            ViewGroup.LayoutParams lp = card.getLayoutParams();
            lp.width = (int) (screenW * 0.45);
            lp.height = (int) (screenH * 0.80);
            card.setLayoutParams(lp);
            panelCardW = lp.width;
        }
        /* 左右定位延后到 addView 之后（applyCardSide），避免未布局时 setX 与 gravity 叠加 */

        /* 点透明区域（卡片外）关闭面板 */
        if (root != null) {
            root.setOnClickListener(v -> closePanel());
        }
        /* 消费卡片内点击事件，避免冒泡到透明根触发误关闭 */
        if (card != null) {
            card.setOnClickListener(v -> { /* no-op: keep panel open */ });
        }

        /* 拖拽条：顶部居中，左右拖动使面板贴边吸附（记忆左右） */
        if (handle != null && card != null) {
            final float[] start = {0, 0};
            final float[] originX = {0};
            final boolean[] moved = {false};
            handle.setOnTouchListener(new View.OnTouchListener() {
                @Override
                public boolean onTouch(View v, MotionEvent event) {
                    switch (event.getActionMasked()) {
                        case MotionEvent.ACTION_DOWN:
                            start[0] = event.getRawX();
                            start[1] = event.getRawY();
                            originX[0] = card.getX();
                            moved[0] = false;
                            return true;
                        case MotionEvent.ACTION_MOVE: {
                            float dx = event.getRawX() - start[0];
                            float dy = event.getRawY() - start[1];
                            if (!moved[0] && (Math.abs(dx) > CLICK_SLOP || Math.abs(dy) > CLICK_SLOP)) {
                                moved[0] = true;
                            }
                            if (moved[0]) {
                                float nx = originX[0] + dx;
                                int maxX = Math.max(0, screenW - panelCardW);
                                nx = Math.max(0, Math.min(maxX, nx));
                                card.setX(nx);
                            }
                            return true;
                        }
                        case MotionEvent.ACTION_UP:
                        case MotionEvent.ACTION_CANCEL:
                            if (moved[0]) {
                                int maxX = Math.max(0, screenW - panelCardW);
                                float cx = card.getX() + panelCardW / 2f;
                                final boolean toRight = cx >= screenW / 2f;
                                final int targetX = toRight ? maxX : 0;
                                getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                                        .edit().putBoolean(KEY_SIDE_RIGHT, toRight).apply();
                                ValueAnimator anim = ValueAnimator.ofFloat(card.getX(), targetX);
                                anim.setDuration(160);
                                anim.addUpdateListener(a -> card.setX((float) a.getAnimatedValue()));
                                anim.start();
                            }
                            return true;
                        default:
                            return false;
                    }
                }
            });
        }

        /* WebView：与主界面同 origin（WebViewAssetLoader），共享 localStorage */
        WebView wv = panelView.findViewById(R.id.panelWeb);
        if (wv == null) return;
        panelWebView = wv;
        wv.setBackgroundColor(Color.TRANSPARENT);
        WebSettings s = wv.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setTextZoom(100);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);

        final WebViewAssetLoader assetLoader = new WebViewAssetLoader.Builder()
                .addPathHandler("/", new WebViewAssetLoader.AssetsPathHandler(this))
                .build();

        final boolean[] triedLocal = {false};
        wv.setWebViewClient(new WebViewClientCompat() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri uri = request.getUrl();
                if (!"https".equals(uri.getScheme()) && !"http".equals(uri.getScheme())) return false;
                if (uri.getHost() != null && uri.getHost().endsWith("app.local")) return false;
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, uri));
                } catch (Exception ignore) {
                }
                return true;
            }

            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                return assetLoader.shouldInterceptRequest(request.getUrl());
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request, WebResourceErrorCompat error) {
                if (!request.isForMainFrame() || triedLocal[0]) return;
                triedLocal[0] = true;
                AppConfig.useLocal = true;
                wv.loadUrl(AppConfig.panelUrl());
            }

            @Override
            public void onReceivedSslError(WebView view, SslErrorHandler handler, android.net.http.SslError error) {
                handler.cancel();
            }
        });

        JsBridge bridge = new JsBridge((Context) this);
        bridge.setClosePanelCallback(() -> closePanel());
        wv.addJavascriptInterface(bridge, "AndroidHost");
        wv.loadUrl(AppConfig.panelUrl());
    }

    @Override
    public void onDestroy() {
        if (watchdog != null) {
            watchdog.removeCallbacksAndMessages(null);
            watchdog = null;
        }
        if (windowManager != null) {
            if (panelView != null && panelView.getWindowToken() != null) {
                try {
                    windowManager.removeView(panelView);
                } catch (Exception ignore) {
                }
            }
            if (ballView != null && ballView.getWindowToken() != null) {
                try {
                    windowManager.removeView(ballView);
                } catch (Exception ignore) {
                }
            }
        }
        if (panelWebView != null) {
            try {
                panelWebView.removeJavascriptInterface("AndroidHost");
                panelWebView.destroy();
            } catch (Exception ignore) {
            }
            panelWebView = null;
        }
        ballView = null;
        panelView = null;
        sRunning = false;
        super.onDestroy();
        /* 自愈：若悬浮窗仍处于「开启」状态（说明是被系统回收，而非用户关闭）→ 稍后自动拉起 */
        scheduleRestart(1200);
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        super.onTaskRemoved(rootIntent);
        /* 从最近任务划掉：多数机型会连带杀掉服务，这里延迟自愈拉起悬浮球 */
        scheduleRestart(800);
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
