# 保持 WebView JS 接口与原生桥类名（混淆会破坏 addJavascriptInterface 绑定）
-keepclassmembers class cn.taskrecord.app.** {
    @android.webkit.JavascriptInterface <methods>;
}
-keep class cn.taskrecord.app.JsBridge { *; }
