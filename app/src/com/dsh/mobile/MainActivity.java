package com.dsh.mobile;

import android.app.Activity;
import android.content.res.Configuration;
import android.app.AlertDialog;
import android.app.DownloadManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.webkit.CookieManager;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.webkit.WebChromeClient;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URL;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * DSH Mobile —— 纯 Android Framework 的 WebView 壳 (v1.5 开源通用版)。
 * 经 lan-gate / dsh-bridge 网关连接电脑上的 DSH (DeepSeek Harness) Web UI。
 * v1.1: 401 自动重新认证 / 官方鲸鱼图标 / 内网版本订阅自动更新 / 全文可选中复制
 * v1.2: 加载失败不再静默黑屏——主框架 onReceivedError 回设置页带原因、
 *       onRenderProcessGone 自动重载一次、顶栏显示连接态、设置页新增「自动搜索网关」
 *       (扫本机网段常见网关口,命中自动填入并保留 ?token= 查询串)
 * v1.3: 主题跟随系统修复——manifest 换 DayNight(API29+,低版本回退亮色),App 色板
 *       按 uiMode 解析;WebView 关 FORCE_DARK 算法反色(页面自带主题 CSS 说了算,
 *       网页里设亮色不再被染黑);返回键在网页根转后台保活(不再拆会话回入口页),
 *       系统切深浅色不重建页面(configChanges+uiMode,页面媒体查询即时跟随)
 * v1.4: 内置 127.0.0.1 TCP 转发器——WebView 恒用 http://127.0.0.1:<本地口>/ 访问,
 *       DSH 前端按 location.hostname 判 loopback(127/8|localhost)→设置走 host 持久化:
 *       「内测公告」只确认一次、主题/界面设置跨重进保留(此前远端一律 memory 模式,
 *       每次冷启动重置,公告反复弹是同一根因);真实目标=保存的 网关host:port,
 *       电脑换 IP 后主框架失败自动重扫网段改指转发器、页面原址重载,全程免重输;
 *       更新器加 REQUEST_INSTALL_PACKAGES 并用 ACTION_INSTALL_PACKAGE,
 *       下载完成自动拉起系统安装器(此前仅 DownloadManager 完成通知)
 * v1.5: 开源通用版——去本机化:
 *       - 网关探测签名补 "DeepSeek Harness"/"dsh-bridge"(官方预设桥插件的
 *         401 认证页此前会被漏判);端口表改为"已存端口优先,再扫官方/社区常见口"
 *       - 更新源双通道:同网段 :3093 订阅源优先,公网 GitHub Releases 兜底,
 *         无入口地址也能手动检查更新
 *       - 迁移/文案不再含单机端口约定,任何装了 lan-gate/dsh-bridge 的
 *         官方桌面端或 `dsh web` 实例都能以同一方式接入
 */
public class MainActivity extends Activity {
    private static final String PREFS = "dsh_mobile";
    private static final String KEY_URL = "url";
    private static final String KEY_TARGET = "target"; // "host:port" 真实网关
    private static final String KEY_QUERY = "query";   // "?token=..." 可空
    private static final String KEY_HIST = "hist";
    private static final int HIST_MAX = 8;
    private static final int FEED_PORT = 3093;          // 同网段订阅源约定口(tools/start-apk-feed)
    private static final String PUBLIC_FEED =           // 公网兜底更新源(GitHub Releases 最新版资产)
        "https://github.com/zhuyoujie-2025/dsh-mobile/releases/latest/download/";
    private static final int LOCAL_PORT_BASE = 3090;   // WebView 恒用 127.0.0.1:<LPORT>,冲突顺延
    private static final int LOCAL_PORT_MAX = 3099;

    private SharedPreferences prefs;
    private WebView web;
    private boolean webMode = false;
    private TcpForwarder fwd;
    private int rescanFails = 0;   // 主框架失败后已自动重扫次数,防死循环
    private long dlId = -1;
    private BroadcastReceiver dlReceiver;
    private TextView titleView;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private Runnable loadWatchdog;
    private boolean renderDiedOnce = false;

    private static int dp(Context c, int v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                c.getResources().getDisplayMetrics());
    }

    // ---------- 主题色板(跟随系统 uiMode;v1.3 前全硬编码暗色) ----------

    private boolean isNight() {
        return (getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
    }
    private int bgMain()   { return isNight() ? Color.rgb(0x0f,0x11,0x15) : Color.rgb(0xf4,0xf5,0xf8); }
    private int bgBar()    { return isNight() ? Color.rgb(0x16,0x1a,0x22) : Color.rgb(0xff,0xff,0xff); }
    private int fgText()   { return isNight() ? Color.WHITE : Color.rgb(0x1a,0x1d,0x24); }
    private int fgSub()    { return isNight() ? Color.rgb(0x9a,0xa3,0xb2) : Color.rgb(0x5a,0x64,0x72); }
    private int fgFaint()  { return isNight() ? Color.rgb(0x55,0x5e,0x6e) : Color.rgb(0x8a,0x92,0xa2); }
    private int fgAccent() { return isNight() ? Color.rgb(0x9c,0xc0,0xff) : Color.rgb(0x33,0x5f,0xc4); }
    private GradientDrawable cardSoft() {
        return isNight() ? card(0x0b,0x0e,0x14, 0x23,0x2a,0x37, 10)
                         : card(0xff,0xff,0xff, 0xd6,0xdb,0xe5, 10);
    }
    private GradientDrawable cardBtn() {
        return isNight() ? card(0x1b,0x22,0x33, 0x2a,0x2f,0x3a, 12)
                         : card(0xe9,0xee,0xfa, 0xc7,0xd3,0xea, 12);
    }
    private GradientDrawable barBtn() {
        return isNight() ? card(0x1b,0x22,0x33, 0x2a,0x2f,0x3a, 8)
                         : card(0xe9,0xee,0xfa, 0xc7,0xd3,0xea, 8);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        CookieManager.getInstance().setAcceptCookie(true);
        migrateSavedUrl();
        String target = prefs.getString(KEY_TARGET, null);
        if (target != null && ensureForwarder(target)) {
            showWeb(localUrl());
        } else {
            showSetup();
        }
        checkUpdate(false);
    }

    /** v1.3- 的 KEY_URL 是真实网关地址;v1.4 拆成 KEY_TARGET(host:port)+KEY_QUERY。 */
    private void migrateSavedUrl() {
        if (prefs.getString(KEY_TARGET, null) != null) return;
        String saved = prefs.getString(KEY_URL, null);
        if (saved == null || saved.length() == 0) return;
        if (saved.startsWith("http://127.0.0.1:") || saved.startsWith("http://localhost:")) return;
        Uri uri = Uri.parse(saved);
        String host = uri.getHost();
        if (host == null || host.length() == 0) return;
        int port = uri.getPort() > 0 ? uri.getPort() : ("https".equals(uri.getScheme()) ? 443 : 80);
        String q = uri.getEncodedQuery();
        prefs.edit()
                .putString(KEY_TARGET, host + ":" + port)
                .putString(KEY_QUERY, q != null && q.length() > 0 ? "?" + q : "")
                .apply();
    }

    /** WebView 恒用的 loopback 地址;转发器指向真实网关。 */
    private String localUrl() {
        int lp = (fwd != null) ? fwd.localPort() : LOCAL_PORT_BASE;
        String q = prefs.getString(KEY_QUERY, "");
        return "http://127.0.0.1:" + lp + "/" + (q != null ? q : "");
    }

    /** 启动/重指本地转发器;成功返回 true。target 形如 "host:port"。 */
    private boolean ensureForwarder(String target) {
        String[] hp = target.split(":");
        if (hp.length != 2) return false;
        int port;
        try { port = Integer.parseInt(hp[1]); } catch (Exception e) { return false; }
        if (fwd != null) { fwd.retarget(hp[0], port); return true; }
        for (int lp = LOCAL_PORT_BASE; lp <= LOCAL_PORT_MAX; lp++) {
            TcpForwarder f = TcpForwarder.tryStart(lp, hp[0], port);
            if (f != null) { fwd = f; return true; }
        }
        return false;
    }

    // ---------- 入口设置页 ----------

    private void showSetup() { showSetup(null); }

    private void setBarTitle(String s) {
        if (titleView != null) titleView.setText(s);
    }

    private void showSetup(String err) {
        webMode = false;
        titleView = null;
        if (loadWatchdog != null) { ui.removeCallbacks(loadWatchdog); loadWatchdog = null; }
        final WebView old = web;
        web = null;
        if (old != null) {
            // 可能在 WebView 回调里被调用:destroy 延后一拍,避免回调内销毁自身
            ui.post(new Runnable() { public void run() { try { old.destroy(); } catch (Exception ignored) { } } });
        }

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        int p = dp(this, 24);
        col.setPadding(p, dp(this, 48), p, p);
        col.setBackgroundColor(bgMain());
        scroll.addView(col, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView title = new TextView(this);
        title.setText("DSH Mobile");
        title.setTextColor(fgText());
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 26);
        title.getPaint().setFakeBoldText(true);
        col.addView(title);

        TextView sub = new TextView(this);
        sub.setText("输入电脑端 DSH 网关地址连接（手机与电脑同一局域网）。电脑端装 lan-gate / dsh-bridge 网关插件或运行独立网关后即可生成该地址；也可以点「自动搜索网关」让 App 自己在网段里找。");
        sub.setTextColor(fgSub());
        sub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        sub.setTextIsSelectable(true);
        sub.setPadding(0, dp(this, 8), 0, dp(this, 20));
        col.addView(sub);

        if (err != null) {
            TextView e = new TextView(this);
            e.setText(err);
            e.setTextColor(isNight() ? Color.rgb(0xff,0x8a,0x8a) : Color.rgb(0xc8,0x28,0x28));
            e.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            e.setTextIsSelectable(true);
            e.setPadding(0, 0, 0, dp(this, 14));
            col.addView(e);
        }

        final EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setHint("http://192.168.x.x:3088/  (或带 ?token=)");
        String saved = prefs.getString(KEY_URL, "");
        if (saved.length() > 0) input.setText(saved);
        input.setTextColor(fgText());
        input.setHintTextColor(fgFaint());
        input.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        input.setBackground(cardSoft());
        int ep = dp(this, 14);
        input.setPadding(ep, ep, ep, ep);
        col.addView(input);

        Button go = new Button(this);
        go.setText("连 接");
        go.setTextColor(Color.WHITE);
        go.setBackground(card(0x4c, 0x8d, 0xff, 0x4c, 0x8d, 0xff, 12));
        LinearLayout.LayoutParams glp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        glp.setMargins(0, dp(this, 16), 0, 0);
        col.addView(go, glp);
        go.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                String url = normalize(input.getText().toString());
                if (url == null) {
                    Toast.makeText(MainActivity.this, "地址为空或格式不对", Toast.LENGTH_SHORT).show();
                    return;
                }
                hideKeyboard(input);
                connect(url);
            }
        });

        final Button scan = new Button(this);
        scan.setText("自动搜索网关");
        scan.setTextColor(fgAccent());
        scan.setAllCaps(false);
        scan.setBackground(cardBtn());
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        slp.setMargins(0, dp(this, 10), 0, 0);
        col.addView(scan, slp);
        scan.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { scanForGate(input, scan); }
        });

        Button upd = new Button(this);
        upd.setText("检查更新");
        upd.setTextColor(fgAccent());
        upd.setAllCaps(false);
        upd.setBackground(cardBtn());
        LinearLayout.LayoutParams ulp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        ulp.setMargins(0, dp(this, 10), 0, 0);
        col.addView(upd, ulp);
        upd.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { checkUpdate(true); }
        });

        List<String> hist = loadHistory();
        if (!hist.isEmpty()) {
            TextView ht = new TextView(this);
            ht.setText("最近使用 (长按删除)");
            ht.setTextColor(fgSub());
            ht.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            ht.setPadding(0, dp(this, 28), 0, dp(this, 6));
            col.addView(ht);
            for (final String entry : hist) {
                Button b = new Button(this);
                b.setText(shorten(entry));
                b.setAllCaps(false);
                b.setTextColor(fgAccent());
                b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
                b.setBackground(cardSoft());
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                lp.setMargins(0, dp(this, 6), 0, 0);
                col.addView(b, lp);
                b.setOnClickListener(new View.OnClickListener() {
                    public void onClick(View v) { connect(entry); }
                });
                b.setOnLongClickListener(new View.OnLongClickListener() {
                    public boolean onLongClick(View v) {
                        removeHistory(entry);
                        showSetup();
                        return true;
                    }
                });
            }
        }

        TextView foot = new TextView(this);
        foot.setText("首次访问会停在「等待批准」页: 在电脑浏览器打开 <网关地址>/lan-gate/admin 批准本设备(选「手机」)后自动进入,已批准设备无需 token。若入口是 dsh web / dsh-bridge 的认证页,需要带 ?token= 的完整地址(在电脑端生成)。");
        foot.setTextColor(fgFaint());
        foot.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        foot.setTextIsSelectable(true);
        foot.setPadding(0, dp(this, 32), 0, 0);
        col.addView(foot);

        setContentView(scroll);
    }

    // ---------- WebView 页 ----------

    private void showWeb(String url) {
        webMode = true;

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(bgMain());

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setBackgroundColor(bgBar());
        int bp = dp(this, 6);
        bar.setPadding(bp, bp, bp, bp);

        Button menu = barButton("入口");
        menu.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { showSetup(); }
        });
        bar.addView(menu);

        TextView t = new TextView(this);
        t.setText("连接中…");
        titleView = t;
        t.setTextColor(fgSub());
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        t.setGravity(android.view.Gravity.CENTER);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.MATCH_PARENT, 1f);
        bar.addView(t, tlp);

        Button home = barButton("主页");
        home.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                if (web != null) web.loadUrl(localUrl());
            }
        });
        bar.addView(home);

        Button reload = barButton("刷新");
        reload.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { if (web != null) web.reload(); }
        });
        bar.addView(reload);

        root.addView(bar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(this, 46)));

        web = new WebView(this);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        s.setCacheMode(WebSettings.LOAD_DEFAULT);
        s.setUseWideViewPort(true);
        s.setLoadWithOverviewMode(true);
        s.setBuiltInZoomControls(false);
        s.setSupportMultipleWindows(false);
        // 关闭 WebView 算法反色:页面以自身 prefers-color-scheme/主题设置为准,
        // 不再出现系统或 DSH 设置调亮色却被 WebView 强制压黑的情况(API29+ 才有此开关)
        if (Build.VERSION.SDK_INT >= 29) s.setForceDark(WebSettings.FORCE_DARK_OFF);

        CookieManager cm = CookieManager.getInstance();
        cm.setAcceptCookie(true);
        cm.setAcceptThirdPartyCookies(web, true);

        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest req) {
                String scheme = req.getUrl().getScheme();
                return !("http".equals(scheme) || "https".equals(scheme));
            }

            @Override
            public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) {
                setBarTitle("连接中…");
                if (loadWatchdog != null) ui.removeCallbacks(loadWatchdog);
                loadWatchdog = new Runnable() {
                    public void run() { setBarTitle("仍在连接… 地址过期可回「入口」搜索"); }
                };
                ui.postDelayed(loadWatchdog, 18000);
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest req, WebResourceError err) {
                // 主框架网络层失败:先自动重扫网段找网关(电脑换 IP 场景),找到则改指转发器重载;
                // 找不到或重扫过仍失败,再回设置页说明
                if (!req.isForMainFrame()) return;
                String desc = String.valueOf(err.getDescription());
                autoRescan(desc);
            }

            @Override
            public void onReceivedHttpError(WebView view, WebResourceRequest req, WebResourceResponse resp) {
                // 401:尚有未消费的 ?token= 则重载一次再认证;否则回设置页引导重取地址
                if (!req.isForMainFrame() || resp.getStatusCode() != 401) return;
                if (prefs.getString(KEY_QUERY, "").contains("token=")) {
                    view.loadUrl(localUrl());
                } else {
                    showSetup("登录态失效,且当前地址不带 ?token=。\n回电脑端重新获取带 token 的完整地址再连一次。");
                }
            }

            @Override
            public boolean onRenderProcessGone(WebView view, RenderProcessGoneDetail detail) {
                // 渲染进程崩溃(页面默认永远黑屏):先自动重载一次,再崩则回设置页
                if (!renderDiedOnce && web != null) {
                    renderDiedOnce = true;
                    String savedU = prefs.getString(KEY_URL, null);
                    web.loadUrl(savedU != null ? savedU : "about:blank");
                } else {
                    showSetup("页面渲染进程崩溃,已退回设置页。可重试或「自动搜索网关」。");
                }
                return true;
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                setBarTitle("DSH");
                renderDiedOnce = false;
                rescanFails = 0;
                if (loadWatchdog != null) ui.removeCallbacks(loadWatchdog);
                CookieManager.getInstance().flush();
                // ?token= 属一次性引导:首个页面落地后即弃,之后靠 cookie(防过期 token 拖累后续加载)
                if (prefs.getString(KEY_QUERY, "").length() > 0) {
                    prefs.edit().putString(KEY_QUERY, "").apply();
                }
                // 解除网页 user-select:none,正文可选中复制
                view.evaluateJavascript(
                        "(function(){if(document.getElementById('dsh-usel'))return;var s=document.createElement('style');s.id='dsh-usel';s.textContent='*{-webkit-user-select:text!important;user-select:text!important}';(document.head||document.documentElement).appendChild(s);})()",
                        null);
            }
        });
        web.setWebChromeClient(new WebChromeClient());

        root.addView(web, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        setContentView(root);
        web.loadUrl(url);
    }

    private Button barButton(String label) {
        Button b = new Button(this);
        b.setText(label);
        b.setTextColor(fgAccent());
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        b.setAllCaps(false);
        b.setMinWidth(0);
        b.setMinimumWidth(0);
        int hp = dp(this, 12);
        b.setPadding(hp, 0, hp, 0);
        b.setBackground(barBtn());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT);
        lp.setMargins(dp(this, 2), 0, dp(this, 2), 0);
        b.setLayoutParams(lp);
        return b;
    }

    private GradientDrawable card(int r, int g, int b, int sr, int sg, int sb, int radiusDp) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(Color.rgb(r, g, b));
        d.setCornerRadius(dp(this, radiusDp));
        d.setStroke(Math.max(1, dp(this, 1) / 2), Color.rgb(sr, sg, sb));
        return d;
    }

    // ---------- 自动更新 ----------

    private long myVersionCode() {
        try {
            PackageInfo pi = getPackageManager().getPackageInfo(getPackageName(), 0);
            if (Build.VERSION.SDK_INT >= 28) return pi.getLongVersionCode();
            return pi.versionCode;
        } catch (Exception e) {
            return 0;
        }
    }

    private String feedBase() {
        String t = prefs.getString(KEY_TARGET, null);
        if (t == null) return null;
        int ci = t.lastIndexOf(':');
        if (ci <= 0) return null;
        return "http://" + t.substring(0, ci) + ":" + FEED_PORT + "/dshmobile/";
    }

    /** 拉 version.json;成功返回 JSONObject,失败抛异常。 */
    private static JSONObject fetchJson(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(5000);
        c.setReadTimeout(5000);
        if (c.getResponseCode() != 200) throw new Exception("http " + c.getResponseCode());
        BufferedReader rd = new BufferedReader(new InputStreamReader(c.getInputStream(), "UTF-8"));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = rd.readLine()) != null) sb.append(line);
        rd.close();
        return new JSONObject(sb.toString());
    }

    /** 双通道检查更新:同网段 :3093 订阅源优先,不通再试公网 GitHub Releases。 */
    private void checkUpdate(final boolean manual) {
        new Thread(new Runnable() {
            public void run() {
                List<String> bases = new ArrayList<String>();
                String lb = feedBase();
                if (lb != null) bases.add(lb);
                bases.add(PUBLIC_FEED);
                JSONObject j = null;
                String usedBase = null;
                String lastErr = "no feed";
                for (String b : bases) {
                    try { j = fetchJson(b + "version.json"); usedBase = b; break; }
                    catch (Exception e) { lastErr = e.getMessage(); }
                }
                final JSONObject fj = j;
                final String ub = usedBase;
                final String err = lastErr;
                runOnUiThread(new Runnable() {
                    public void run() {
                        if (fj != null) onUpdateInfo(fj, ub, manual);
                        else if (manual) Toast.makeText(MainActivity.this,
                                "检查更新失败: " + err, Toast.LENGTH_LONG).show();
                    }
                });
            }
        }).start();
    }

    private void onUpdateInfo(JSONObject j, String base, boolean manual) {
        try {
            long vc = j.getLong("versionCode");
            String vn = j.optString("versionName", "");
            if (vc <= myVersionCode()) {
                if (manual) Toast.makeText(this, "已是最新 (" + vn + ")", Toast.LENGTH_SHORT).show();
                return;
            }
            final String apkUrl = base + j.optString("url", "DSHMobile.apk");
            new AlertDialog.Builder(this)
                    .setTitle("发现新版本 " + vn)
                    .setMessage("当前 v" + currentVersionName() + " → v" + vn + "\n下载后系统会拉起安装器,覆盖安装保留入口配置。")
                    .setPositiveButton("下载更新", (d, w) -> startDownload(apkUrl, vn))
                    .setNegativeButton("暂不", null)
                    .show();
        } catch (Exception ignored) {
        }
    }

    private String currentVersionName() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "?";
        }
    }

    private void startDownload(String apkUrl, String vn) {
        try {
            DownloadManager dm = (DownloadManager) getSystemService(DOWNLOAD_SERVICE);
            DownloadManager.Request r = new DownloadManager.Request(Uri.parse(apkUrl));
            r.setTitle("DSH Mobile " + vn);
            r.setMimeType("application/vnd.android.package-archive");
            r.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, "DSHMobile-" + vn + ".apk");
            r.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            dlId = dm.enqueue(r);
            Toast.makeText(this, "开始下载,完成后点通知安装", Toast.LENGTH_LONG).show();
            registerDownloadReceiver();
        } catch (Exception e) {
            Toast.makeText(this, "下载失败: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void registerDownloadReceiver() {
        if (dlReceiver != null) return;
        dlReceiver = new BroadcastReceiver() {
            public void onReceive(Context c, Intent i) {
                long id = i.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1);
                if (id != dlId) return;
                DownloadManager dm = (DownloadManager) getSystemService(DOWNLOAD_SERVICE);
                Uri uri = dm.getUriForDownloadedFile(id);
                if (uri == null) return;
                // ACTION_INSTALL_PACKAGE 直达系统安装器;配合 manifest 的
                // REQUEST_INSTALL_PACKAGES 才有权限。失败/被后台限制时,
                // 下载完成通知仍在,点它同样进入安装。
                Intent in = new Intent(Intent.ACTION_INSTALL_PACKAGE);
                in.setData(uri);
                in.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
                try {
                    startActivity(in);
                } catch (Exception e) {
                    try {
                        Intent v = new Intent(Intent.ACTION_VIEW);
                        v.setDataAndType(uri, "application/vnd.android.package-archive");
                        v.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
                        startActivity(v);
                    } catch (Exception e2) {
                        Toast.makeText(MainActivity.this,
                                "下载完成:请到系统下载里点 DSHMobile 安装",
                                Toast.LENGTH_LONG).show();
                    }
                }
            }
        };
        IntentFilter f = new IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE);
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(dlReceiver, f, Context.RECEIVER_EXPORTED);
        } else {
            registerReceiver(dlReceiver, f);
        }
    }

    // ---------- 数据与杂项 ----------

    private void connect(String url) {
        // url = 真实网关地址(用户输入/历史/扫描结果);WebView 走 127.0.0.1 转发器
        Uri uri = Uri.parse(url);
        String host = uri.getHost();
        if (host == null || host.length() == 0) {
            Toast.makeText(this, "地址无法解析", Toast.LENGTH_SHORT).show();
            return;
        }
        int port = uri.getPort() > 0 ? uri.getPort() : ("https".equals(uri.getScheme()) ? 443 : 80);
        String q = uri.getEncodedQuery();
        if (!ensureForwarder(host + ":" + port)) {
            showSetup("本地转发端口全部占用(3090-3099),重启 App 再试");
            return;
        }
        prefs.edit()
                .putString(KEY_URL, url)
                .putString(KEY_TARGET, host + ":" + port)
                .putString(KEY_QUERY, q != null && q.length() > 0 ? "?" + q : "")
                .apply();
        pushHistory(url);
        rescanFails = 0;
        showWeb(localUrl());
    }

    // ---------- 局域网自动搜索网关 ----------

    /** 主框架失败后的自动重扫:换 IP 场景改指转发器并原页重载;扫不到/反复失败回设置页。 */
    private void autoRescan(final String desc) {
        if (rescanFails >= 2) {
            showSetup("连接失败: " + desc + "\n电脑可能关机或不在同一网络;也可点「自动搜索网关」手动找回。");
            return;
        }
        rescanFails++;
        setBarTitle("连接中断,自动重找网关…");
        final String cur = prefs.getString(KEY_URL, "");
        new Thread(new Runnable() {
            public void run() {
                final String[] found = scanSubnet(cur);
                runOnUiThread(new Runnable() {
                    public void run() {
                        if (found != null) {
                            ensureForwarder(found[0] + ":" + found[1]);
                            prefs.edit().putString(KEY_TARGET, found[0] + ":" + found[1]).apply();
                            setBarTitle("已找回网关 " + found[0] + ":" + found[1]);
                            if (web != null) web.loadUrl(localUrl());
                        } else {
                            showSetup("连接失败: " + desc + "\n本网段没找到网关——确认电脑端 DSH 已打开、手机与电脑在同一网络。");
                        }
                    }
                });
            }
        }).start();
    }

    private void scanForGate(final EditText input, final Button btn) {
        btn.setEnabled(false);
        btn.setText("搜索中…");
        new Thread(new Runnable() {
            public void run() {
                final String[] found = scanSubnet(input.getText().toString());
                runOnUiThread(new Runnable() {
                    public void run() {
                        btn.setEnabled(true);
                        btn.setText("自动搜索网关");
                        if (found == null) {
                            Toast.makeText(MainActivity.this,
                                    "本机网段里没找到网关。确认电脑网关已启动、手机和电脑在同一网络。",
                                    Toast.LENGTH_LONG).show();
                            return;
                        }
                        // 保留已填地址的 ?token= 等查询串,只换主机与端口
                        String old = normalize(input.getText().toString());
                        String query = "";
                        if (old != null) {
                            int q = old.indexOf('?');
                            if (q >= 0) query = old.substring(q);
                        }
                        input.setText("http://" + found[0] + ":" + found[1] + "/" + query);
                        Toast.makeText(MainActivity.this,
                                "找到网关 " + found[0] + ":" + found[1] + " ,已填入,请点「连 接」",
                                Toast.LENGTH_LONG).show();
                    }
                });
            }
        }).start();
    }

    /** 扫手机所在网段的网关端口;多口命中时按 preferredPorts 顺序择优,找不到返回 null。 */
    private String[] scanSubnet(String currentUrl) {
        int[] ports = preferredPorts(currentUrl);
        List<String> hosts = subnetHosts();
        final AtomicBoolean stop = new AtomicBoolean(false);
        final List<String[]> hits = new ArrayList<String[]>();
        ExecutorService pool = Executors.newFixedThreadPool(48);
        for (final String host : hosts) {
            for (final int port : ports) {
                if (stop.get()) break;
                pool.execute(new Runnable() {
                    public void run() {
                        if (stop.get()) return;
                        if (probeGate(host, port)) {
                            synchronized (hits) {
                                hits.add(new String[]{host, String.valueOf(port)});
                            }
                            // 命中首选口即可提前收工
                            if (port == ports[0]) stop.set(true);
                        }
                    }
                });
            }
            if (stop.get()) break;
        }
        pool.shutdown();
        try { pool.awaitTermination(12, TimeUnit.SECONDS); } catch (InterruptedException ignored) { }
        synchronized (hits) {
            if (hits.isEmpty()) return null;
            String[] best = hits.get(0);
            for (String[] h : hits) {
                if (portRank(Integer.parseInt(h[1]), ports) < portRank(Integer.parseInt(best[1]), ports)) best = h;
            }
            return best;
        }
    }

    private static int portRank(int p, int[] pref) {
        for (int i = 0; i < pref.length; i++) if (pref[i] == p) return i;
        return pref.length;
    }

    /** 端口优先级:先用当前地址里的端口,再按常见网关口补扫。
     *  3088=lan-gate 插件/独立网关默认口(dsh plugin add dsh-mobile-gate 或
     *  node gate/lan-gate-server.cjs,EADDRINUSE 时最多顺延 +20 到 3108);
     *  3083/3084=dsh-bridge 常见代理口(官方预设桥插件);
     *  3089-3094=常见自管网关/订阅源区间;3105-3108 顺带兜 lan-gate 顺延尾段。 */
    private int[] preferredPorts(String url) {
        int first = 3088;
        try {
            if (url != null) {
                Uri u = Uri.parse(url.contains("://") ? url : "http://" + url);
                if (u.getPort() > 0) first = u.getPort();
            }
        } catch (Exception ignored) { }
        int[] rest = {3088, 3083, 3084, 3089, 3090, 3091, 3092, 3094, 3105, 3106, 3107, 3108};
        List<Integer> out = new ArrayList<Integer>();
        out.add(first);
        for (int p : rest) if (p != first && !out.contains(p)) out.add(p);
        int[] r = new int[out.size()];
        for (int i = 0; i < out.size(); i++) r[i] = out.get(i);
        return r;
    }

    /** 列出本机各 IPv4 网段的主机地址(排除自身),前缀长于 /24 时按 /24 扫。 */
    private List<String> subnetHosts() {
        List<String> hosts = new ArrayList<String>();
        try {
            Enumeration<NetworkInterface> ifs = NetworkInterface.getNetworkInterfaces();
            while (ifs != null && ifs.hasMoreElements()) {
                NetworkInterface ni = ifs.nextElement();
                try { if (!ni.isUp() || ni.isLoopback() || ni.isVirtual()) continue; } catch (Exception e) { continue; }
                for (InterfaceAddress ia : ni.getInterfaceAddresses()) {
                    InetAddress addr = ia.getAddress();
                    if (!(addr instanceof Inet4Address) || addr.isLoopbackAddress()) continue;
                    byte[] ip = addr.getAddress();
                    int prefix = ia.getNetworkPrefixLength();
                    if (prefix < 24 || prefix > 32) prefix = 24; // 比 /24 大的网络只扫自己所在 /24
                    int hostBits = 32 - prefix;
                    int base = ((ip[0] & 255) << 24) | ((ip[1] & 255) << 16) | ((ip[2] & 255) << 8) | (ip[3] & 255);
                    int network = base & (int) (0xFFFFFFFFL << hostBits);
                    int count = Math.min((1 << hostBits) - 1, 254);
                    for (int i = 1; i <= count; i++) {
                        int h = network + i;
                        if (h == base) continue; // 跳过自己
                        hosts.add(((h >>> 24) & 255) + "." + ((h >>> 16) & 255) + "." + ((h >>> 8) & 255) + "." + (h & 255));
                    }
                }
            }
        } catch (Exception ignored) { }
        return hosts;
    }

    /** 探测 host:port 是否是 DSH 入口:能 HTTP 应答且应答含已知签名——
     *  lan-gate 页("lan-gate"/"/?t=")、dsh web 内核 401("dsh web authentication")、
     *  dsh-bridge 认证页("DeepSeek Harness"/"dsh-bridge")、DSH SPA("DSH")。 */
    private boolean probeGate(String host, int port) {
        Socket s = new Socket();
        try {
            s.connect(new InetSocketAddress(host, port), 700);
            s.setSoTimeout(1200);
            String req = "GET / HTTP/1.0\r\nHost: " + host + ":" + port + "\r\n\r\n";
            s.getOutputStream().write(req.getBytes("UTF-8"));
            byte[] buf = new byte[3072];
            int n = s.getInputStream().read(buf);
            if (n <= 0) return false;
            String head = new String(buf, 0, n, "ISO-8859-1");
            return head.contains("lan-gate") || head.contains("/?t=")
                    || head.contains("dsh web authentication")
                    || head.contains("DeepSeek Harness") || head.contains("dsh-bridge")
                    || head.contains("DSH");
        } catch (Exception e) {
            return false;
        } finally {
            try { s.close(); } catch (Exception ignored) { }
        }
    }

    private static String normalize(String raw) {
        if (raw == null) return null;
        String u = raw.trim();
        if (u.length() == 0) return null;
        if (!u.contains("://")) u = "http://" + u;
        if (!u.startsWith("http://") && !u.startsWith("https://")) return null;
        String rest = u.substring(u.indexOf("://") + 3);
        if (!rest.contains("/")) u = u + "/";
        return u;
    }

    private List<String> loadHistory() {
        List<String> out = new ArrayList<String>();
        String raw = prefs.getString(KEY_HIST, "");
        for (String s : raw.split("\n")) {
            String t = s.trim();
            if (t.length() > 0) out.add(t);
        }
        return out;
    }

    private void pushHistory(String url) {
        List<String> h = loadHistory();
        h.remove(url);
        h.add(0, url);
        while (h.size() > HIST_MAX) h.remove(h.size() - 1);
        StringBuilder sb = new StringBuilder();
        for (String s : h) { if (sb.length() > 0) sb.append('\n'); sb.append(s); }
        prefs.edit().putString(KEY_HIST, sb.toString()).apply();
    }

    private void removeHistory(String url) {
        List<String> h = loadHistory();
        h.remove(url);
        StringBuilder sb = new StringBuilder();
        for (String s : h) { if (sb.length() > 0) sb.append('\n'); sb.append(s); }
        prefs.edit().putString(KEY_HIST, sb.toString()).apply();
    }

    private static String shorten(String url) {
        return url.length() > 42 ? url.substring(0, 42) + "…" : url;
    }

    private void hideKeyboard(View v) {
        InputMethodManager im = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (im != null) im.hideSoftInputFromWindow(v.getWindowToken(), 0);
    }

    // ---------- 127.0.0.1 本地 TCP 转发器 ----------
    // WebView 恒访问 http://127.0.0.1:<local>/ —— DSH 前端按 location.hostname 判 loopback,
    // 远端页从 memory 持久化升级为 host 持久化(内测公告只认一次、界面设置跨进保留)。
    // 转发器只搬字节,网关侧审批/限流照旧(对端仍见手机真实 IP)。

    private static class TcpForwarder {
        private final int localPort;
        private volatile String host;
        private volatile int port;
        private volatile boolean running = true;
        private ServerSocket ss;
        private Thread acceptThread;

        private TcpForwarder(int lp, String h, int p) { localPort = lp; host = h; port = p; }

        int localPort() { return localPort; }

        static TcpForwarder tryStart(int lp, String h, int p) {
            TcpForwarder f = new TcpForwarder(lp, h, p);
            try {
                f.ss = new ServerSocket(lp, 8, InetAddress.getByName("127.0.0.1"));
            } catch (Exception e) {
                return null;
            }
            f.acceptThread = new Thread(new Runnable() { public void run() { f.loop(); } }, "dsh-fwd-accept");
            f.acceptThread.setDaemon(true);
            f.acceptThread.start();
            return f;
        }

        synchronized void retarget(String h, int p) { host = h; port = p; }

        synchronized void stop() {
            running = false;
            try { if (ss != null) ss.close(); } catch (Exception ignored) { }
        }

        private void loop() {
            while (running) {
                final Socket c;
                try { c = ss.accept(); } catch (Exception e) { return; }
                Thread t = new Thread(new Runnable() { public void run() { relay(c); } }, "dsh-fwd-io");
                t.setDaemon(true);
                t.start();
            }
        }

        private void relay(Socket c) {
            Socket u = null;
            try {
                u = new Socket();
                u.connect(new InetSocketAddress(host, port), 5000);
                u.setTcpNoDelay(true);
                c.setTcpNoDelay(true);
                final Socket up = u;
                Thread a = new Thread(new Runnable() { public void run() { pump(c, up); } }, "dsh-fwd-up");
                a.setDaemon(true);
                a.start();
                pump(u, c);
            } catch (Exception ignored) {
            } finally {
                try { c.close(); } catch (Exception ignored) { }
                try { if (u != null) u.close(); } catch (Exception ignored) { }
            }
        }

        private void pump(Socket from, Socket to) {
            byte[] buf = new byte[32768];
            try {
                InputStream in = from.getInputStream();
                OutputStream out = to.getOutputStream();
                int n;
                while ((n = in.read(buf)) >= 0) { out.write(buf, 0, n); out.flush(); }
            } catch (Exception ignored) {
            } finally {
                try { to.shutdownOutput(); } catch (Exception ignored) { }
                try { from.shutdownInput(); } catch (Exception ignored) { }
            }
        }
    }

    @Override
    public void onBackPressed() {
        if (webMode && web != null) {
            if (web.canGoBack()) {
                web.goBack();
            } else {
                // 网页根页面:不再拆会话回入口页。转后台保活,重开秒回;
                // 想换地址仍走顶栏「入口」按钮。
                moveTaskToBack(true);
                Toast.makeText(this, "已转入后台,会话保持连接", Toast.LENGTH_SHORT).show();
            }
        } else {
            super.onBackPressed();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        CookieManager.getInstance().flush();
    }

    @Override
    protected void onDestroy() {
        if (dlReceiver != null) {
            try { unregisterReceiver(dlReceiver); } catch (Exception ignored) { }
            dlReceiver = null;
        }
        if (fwd != null) { fwd.stop(); fwd = null; }
        if (web != null) web.destroy();
        super.onDestroy();
    }
}
