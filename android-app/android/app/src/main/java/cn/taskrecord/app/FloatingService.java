package cn.taskrecord.app;

import android.animation.ValueAnimator;
import android.app.Notification;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.IBinder;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.LinearLayout;

import androidx.core.app.NotificationCompat;

/**
 * 悬浮球：前台服务承载，可拖拽、松手贴边，点击打开悬浮面板。
 * 系统提醒到点时系统通知独立于此服务（AlarmManager 直接触发）。
 */
public class FloatingService extends android.app.Service {

    private WindowManager windowManager;
    private View ballView;
    private WindowManager.LayoutParams params;

    private float startX, startY, touchX, touchY;
    private boolean moved;
    private static final int CLICK_SLOP = 12;

    @Override
    public void onCreate() {
        super.onCreate();
        startForeground(1, buildNotification());
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (Build.VERSION.SDK_INT >= 23 && !android.provider.Settings.canDrawOverlays(this)) {
            stopSelf();
            return START_NOT_STICKY;
        }
        showBall();
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
        params.x = 0;
        params.y = windowManager.getDefaultDisplay().getHeight() / 2;

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
        } catch (Exception e) {
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
        anim.start();
    }

    private void openPanel() {
        try {
            Intent i = new Intent(this, PanelActivity.class);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
        } catch (Exception ignore) {
        }
    }

    @Override
    public void onDestroy() {
        if (ballView != null && windowManager != null) {
            try {
                windowManager.removeView(ballView);
            } catch (Exception ignore) {
            }
            ballView = null;
        }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
