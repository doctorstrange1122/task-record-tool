package cn.taskrecord.app;

/**
 * 加载策略：只加载 GitHub Pages 上的在线文档（app.html / floating.html）。
 * 已按需求取消 assets 离线快照：改网页内容无需重新打包 APK，推送即生效。
 * 代价是断网时页面无法打开（预期行为）。
 */
public final class AppConfig {

    private AppConfig() {}

    /** GitHub Pages 上的仓库目录（与仓库 android-app/ 对应） */
    public static final String REMOTE_BASE =
            "https://doctorstrange1122.github.io/task-record-tool/android-app/";

    /** 本地回退域名（由 WebViewAssetLoader 映射到 assets） */
    public static final String LOCAL_ORIGIN = "https://app.local/";

    /** 是否已回退到本地快照 */
    public static volatile boolean useLocal = false;

    /* app=1 标记让页面识别为 App 内运行（IN_APP），从而启用原生桥与系统提醒同步 */
    private static final String APP_PARAM = "?app=1";

    public static String appUrl() {
        return REMOTE_BASE + "app.html" + APP_PARAM;
    }

    public static String panelUrl() {
        return REMOTE_BASE + "floating.html" + APP_PARAM;
    }

    /** 附带提醒参数（从系统通知点击进入时弹出对应提醒） */
    public static String withRemind(String url, String remindId) {
        if (remindId == null || remindId.isEmpty()) return url;
        return url + (url.contains("?") ? "&" : "?") + "remind=" + remindId;
    }
}
