package cn.taskrecord.app;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Intent;
import android.net.http.SslError;
import android.os.Build;
import android.os.Bundle;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.webkit.SslErrorHandler;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.ImageButton;

import androidx.webkit.WebResourceErrorCompat;
import androidx.webkit.WebViewAssetLoader;
import androidx.webkit.WebViewClientCompat;

/**
 * 悬浮面板：透明背景的底部卡片，顶部为原生拖拽条（下拉关闭）+ 关闭按钮，
 * 卡片内 WebView 加载 floating.html（与主界面同 origin，共享 localStorage）。
 */
public class PanelActivity extends Activity {

    private WebView webView;
    private View card;
    private View handle;
    private boolean triedLocal = false;

    private float dragStartY;
    private float dragDy;

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_panel);

        card = findViewById(R.id.panelCard);
        handle = findViewById(R.id.panelHandle);
        ImageButton closeBtn = findViewById(R.id.panelClose);

        /* 卡片尺寸：宽约 66% 屏宽、高约 92% 屏高，靠右居中（华为中转站风格） */
        android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
        ViewGroup.LayoutParams lp = card.getLayoutParams();
        lp.width = (int) (dm.widthPixels * 0.66);
        lp.height = (int) (dm.heightPixels * 0.92);
        card.setLayoutParams(lp);

        closeBtn.setOnClickListener(v -> finishWithAnim());

        /* 拖拽条：下拉超过阈值关闭，否则回弹 */
        handle.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        dragStartY = event.getRawY();
                        dragDy = 0;
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        dragDy = Math.max(0, event.getRawY() - dragStartY);
                        card.setTranslationY(dragDy);
                        float alpha = 1f - Math.min(0.6f, dragDy / (card.getHeight() == 0 ? 600f : card.getHeight()) * 0.6f);
                        card.setAlpha(alpha);
                        return true;
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        if (dragDy > 140) {
                            finishWithAnim();
                        } else {
                            card.animate().translationY(0).alpha(1f)
                                    .setDuration(160)
                                    .setInterpolator(new DecelerateInterpolator())
                                    .start();
                        }
                        return true;
                    default:
                        return false;
                }
            }
        });

        webView = findViewById(R.id.panelWeb);
        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setTextZoom(100);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);

        final WebViewAssetLoader assetLoader = new WebViewAssetLoader.Builder()
                .addPathHandler("/", new WebViewAssetLoader.AssetsPathHandler(this))
                .build();

        webView.setWebViewClient(new WebViewClientCompat() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
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
                if (!request.isForMainFrame() || triedLocal) return;
                triedLocal = true;
                AppConfig.useLocal = true;
                webView.loadUrl(AppConfig.panelUrl());
            }

            @Override
            public void onReceivedSslError(WebView view, SslErrorHandler handler,
                                           SslError error) {
                handler.cancel();
            }
        });

        webView.addJavascriptInterface(new JsBridge(this), "AndroidHost");
        webView.loadUrl(AppConfig.panelUrl());
    }

    private void finishWithAnim() {
        finish();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            overrideActivityTransition(OVERRIDE_TRANSITION_CLOSE, 0, 0);
        } else {
            overridePendingTransition(0, 0);
        }
    }

    @Override
    public void finish() {
        super.finish();
        try {
            overridePendingTransition(0, 0);
        } catch (Exception ignore) {
        }
    }

    @Override
    protected void onDestroy() {
        if (webView != null) {
            try {
                webView.removeJavascriptInterface("AndroidHost");
            } catch (Exception ignore) {
            }
            webView.destroy();
            webView = null;
        }
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        finishWithAnim();
    }
}
