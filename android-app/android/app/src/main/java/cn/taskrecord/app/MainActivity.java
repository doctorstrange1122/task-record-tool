package cn.taskrecord.app;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.http.SslError;
import android.os.Build;
import android.os.Bundle;
import android.view.ViewGroup;
import android.webkit.SslErrorHandler;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.webkit.WebResourceErrorCompat;
import androidx.webkit.WebViewAssetLoader;
import androidx.webkit.WebViewClientCompat;

import java.lang.ref.WeakReference;

/**
 * 主界面：WebView 加载线上 app.html（远程优先），失败回退 assets 快照。
 * 系统提醒到点且本界面在前台时，由 ReminderReceiver 回拨 window.onNativeReminder。
 */
public class MainActivity extends Activity {

    public static final String EXTRA_REMIND_ID = "remindId";

    private static volatile boolean resumed = false;
    private static MainActivity instance;

    private WebView webView;
    private boolean triedLocal = false;

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        instance = this;

        FrameLayout root = new FrameLayout(this);
        root.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        setContentView(root);

        webView = new WebView(this);
        root.addView(webView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);          /* localStorage：任务 / 剪贴板 / 登录态 */
        s.setDatabaseEnabled(true);
        s.setTextZoom(100);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        s.setMediaPlaybackRequiresUserGesture(true);
        s.setCacheMode(WebSettings.LOAD_DEFAULT);
        /* 深浅色由页面自身跟随系统（媒体查询 + JS），无需 WebView 强制深色 */
        /* UA 附加 TaskReminderApp 标记：与 ?app=1 一起供页面识别 App 内运行 */
        String ua = s.getUserAgentString();
        if (ua != null && !ua.contains("TaskReminderApp")) {
            s.setUserAgentString(ua + " TaskReminderApp/1.0");
        }

        /* 本地回退：https://app.local/ → assets/（与线上同源，悬浮面板可共享 localStorage） */
        final WebViewAssetLoader assetLoader = new WebViewAssetLoader.Builder()
                .addPathHandler("/", new WebViewAssetLoader.AssetsPathHandler(this))
                .build();

        webView.setWebViewClient(new WebViewClientCompat() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                /* 页面内的 http(s) 链接（如下载 / 云同步）交给系统处理 */
                android.net.Uri uri = request.getUrl();
                if (!"https".equals(uri.getScheme()) && !"http".equals(uri.getScheme())) {
                    return false;
                }
                if (uri.getHost() != null && uri.getHost().endsWith("app.local")) {
                    return false;
                }
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, uri));
                } catch (Exception ignore) {
                }
                return true;
            }

            @Override
            public android.webkit.WebResourceResponse shouldInterceptRequest(
                    WebView view, WebResourceRequest request) {
                return assetLoader.shouldInterceptRequest(request.getUrl());
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request,
                                        WebResourceErrorCompat error) {
                if (!request.isForMainFrame()) return;
                if (!triedLocal) {
                    triedLocal = true;
                    AppConfig.useLocal = true;
                    String url = AppConfig.withRemind(AppConfig.appUrl(), remindIdFrom(getIntent()));
                    webView.loadUrl(url);
                }
            }

            @Override
            public void onReceivedSslError(WebView view, SslErrorHandler handler,
                                           SslError error) {
                /* 线上文档为 GitHub Pages 有效证书；异常时不阻断加载流程 */
                handler.cancel();
            }
        });

        webView.addJavascriptInterface(new JsBridge(this), "AndroidHost");

        String remindId = remindIdFrom(getIntent());
        webView.loadUrl(AppConfig.withRemind(AppConfig.appUrl(), remindId));

        requestNotificationPermissionIfNeeded();
    }

    private void requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                try {
                    requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1002);
                } catch (Exception ignore) {
                }
            }
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
    }

    private static String remindIdFrom(Intent intent) {
        if (intent == null) return null;
        return intent.getStringExtra(EXTRA_REMIND_ID);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        String remindId = remindIdFrom(intent);
        if (remindId != null && !remindId.isEmpty()) {
            webView.loadUrl(AppConfig.withRemind(AppConfig.appUrl(), remindId));
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
    }

    @Override
    protected void onPause() {
        resumed = false;
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        resumed = false;
        if (webView != null) {
            try {
                webView.removeJavascriptInterface("AndroidHost");
            } catch (Exception ignore) {
            }
            webView.destroy();
            webView = null;
        }
        if (instance == this) instance = null;
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        if (webView != null && webView.canGoBack()) {
            webView.goBack();
            return;
        }
        super.onBackPressed();
    }

    /* ================= 系统提醒回拨 ================= */

    static boolean isResumed() {
        return resumed && instance != null;
    }

    /** ReminderReceiver 在前台时调用：弹应用内提醒（与网页版同款弹窗） */
    static void fireReminderFromNative(final String json) {
        final MainActivity a = instance;
        if (a == null || a.webView == null) return;
        a.runOnUiThread(() -> {
            if (a.webView != null) {
                try {
                    a.webView.evaluateJavascript(
                            "window.onNativeReminder && window.onNativeReminder(" + json + ")", null);
                } catch (Exception ignore) {
                }
            }
        });
    }
}
