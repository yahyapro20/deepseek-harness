package com.dshmobile.app;

import android.app.Activity;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient.FileChooserParams;
import android.net.Uri;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;

/** Main activity: Full-screen WebView loading dsh Web UI, showing DeepSeek-style splash screen until ready. */
public class MainActivity extends Activity {
    private WebView webView;
    private FrameLayout splash;
    private TextView splashStatus;
    private MobileUiInjector injector;
    private Handler handler;
    private volatile boolean stopPolling;
    private volatile boolean pageFailed;
    private int loadAttempts;
    private int port;
    private static final int REQ_FILE_CHOOSER = 42;
    private ValueCallback<Uri[]> fileCallback;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        Prefs prefs = Prefs.of(this);
        if (!prefs.isSetupDone()) {
            startActivity(new Intent(this, FileProvisionActivity.class));
            finish();
            return;
        }
        port = prefs.getPort();
        handler = new Handler(Looper.getMainLooper());
        injector = new MobileUiInjector(this);
        
        // Permission adaptation: Request storage permission for Android 12 and below (fallback mapping for /sdcard/dsh-shared);
        // For Android 13+ (including 16), foreground service notification changed to actively requesting POST_NOTIFICATIONS —
        // Although targetSdk 28 has a system fallback dialog, the timing is uncontrollable, explicit request is more reliable.
        java.util.List<String> perms = new java.util.ArrayList<>();
        if (android.os.Build.VERSION.SDK_INT < 33) {
            if (checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
                    != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                perms.add(android.Manifest.permission.WRITE_EXTERNAL_STORAGE);
            }
        } else if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            perms.add(android.Manifest.permission.POST_NOTIFICATIONS);
        }
        if (!perms.isEmpty()) {
            requestPermissions(perms.toArray(new String[0]), 1);
        }
        
        buildUi();
        warnIfWebViewTooOld();
        checkUpdate();
        HarnessService.startService(this);
        waitForServerAndLoad();
    }

    // ---------- Version update check ----------

    /** Check GitHub Releases for the latest version when entering the App, show update prompt if a new version is available. Fails silently. */
    private void checkUpdate() {
        new Thread(() -> {
            try {
                java.net.URL url = new java.net.URL(
                        "https://api.github.com/repos/Ajwyunsx/deepseek-harness-mobile/releases/latest");
                java.net.HttpURLConnection c = (java.net.HttpURLConnection) url.openConnection();
                c.setConnectTimeout(6000);
                c.setReadTimeout(6000);
                c.setRequestProperty("Accept", "application/vnd.github+json");
                c.setRequestProperty("User-Agent", "dsh-mobile/" + currentVersion());
                java.io.InputStream in = c.getInputStream();
                java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream();
                byte[] chunk = new byte[4096];
                int n;
                while ((n = in.read(chunk)) != -1) buf.write(chunk, 0, n);
                in.close();
                c.disconnect();
                org.json.JSONObject j = new org.json.JSONObject(buf.toString("UTF-8"));
                String tag = j.optString("tag_name", "");
                String notes = j.optString("body", "");
                handler.post(() -> maybeShowUpdate(tag, notes));
            } catch (Exception ignored) {
                // Offline/GitHub unreachable: Skip silently, does not affect usage
            }
        }, "dsh-update-check").start();
    }

    private void maybeShowUpdate(String tag, String notes) {
        if (isFinishing() || tag.isEmpty()) return;
        String latest = tag.startsWith("v") ? tag.substring(1) : tag;
        if (compareVersion(latest, currentVersion()) <= 0) return;
        String body = notes == null ? "" : notes.trim();
        if (body.length() > 500) body = body.substring(0, 500) + "…";
        new android.app.AlertDialog.Builder(this)
                .setTitle("New version found " + tag)
                .setMessage(body.isEmpty() ? "Current v" + currentVersion() + ", update to " + tag + "." : body)
                .setPositiveButton("Update Now", (d, w) -> {
                    try {
                        // Fixed-name asset direct link, user manually installs after browser download
                        startActivity(new android.content.Intent(android.content.Intent.ACTION_VIEW,
                                android.net.Uri.parse("https://github.com/Ajwyunsx/deepseek-harness-mobile/releases/latest/download/dsh-mobile.apk")));
                    } catch (Exception e) {
                        android.widget.Toast.makeText(this, "Cannot open download link", android.widget.Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("Later", null)
                .show();
    }

    /** Read own versionName at runtime (AGP 9 does not generate BuildConfig by default). */
    private String currentVersion() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "0";
        }
    }

    /** Semantic version comparison: returns positive if a>b, negative if a<b, 0 if equal. */
    private static int compareVersion(String a, String b) {
        String[] pa = a.split("\\."), pb = b.split("\\.");
        int n = Math.max(pa.length, pb.length);
        for (int i = 0; i < n; i++) {
            int x = i < pa.length ? parseIntSafe(pa[i]) : 0;
            int y = i < pb.length ? parseIntSafe(pb[i]) : 0;
            if (x != y) return x - y;
        }
        return 0;
    }

    private static int parseIntSafe(String s) {
        try {
            return Integer.parseInt(s.replaceAll("[^0-9].*$", ""));
        } catch (Exception e) {
            return 0;
        }
    }

    /** Old WebView (Chrome < 116, common in EMUI/old systems) lacks a bunch of modern APIs,
     *  dsh UI will report errors or even crash the rendering process (issue #2/#4). Polyfills try their best to patch,
     *  still prompt user to update the kernel — this is the root cure. */
    private void warnIfWebViewTooOld() {
        try {
            android.content.pm.PackageInfo pi = android.webkit.WebView.getCurrentWebViewPackage();
            String v = pi == null ? null : pi.versionName;
            if (v == null) return;
            int dot = v.indexOf('.');
            int major = Integer.parseInt(dot > 0 ? v.substring(0, dot) : v);
            if (major < 116) {
                android.widget.Toast.makeText(this,
                        "Browser kernel too old (" + v + "), dsh UI may error or crash, please update Android System WebView or Chrome",
                        android.widget.Toast.LENGTH_LONG).show();
            }
        } catch (Exception ignored) {
            // If version number cannot be parsed, do not prompt, do not block the main flow
        }
    }

    private void buildUi() {
        FrameLayout root = new FrameLayout(this);
        webView = new WebView(this);
        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setUseWideViewPort(true);
        s.setLoadWithOverviewMode(true);
        s.setBuiltInZoomControls(false);
        s.setDisplayZoomControls(false);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        s.setJavaScriptCanOpenWindowsAutomatically(true);
        
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) {
                pageFailed = false;
                injector.injectEarlyPolyfill(view);
            }
            @Override
            public void onPageFinished(WebView view, String url) {
                if (pageFailed) return;
                injector.inject(view);
                dismissSplash();
            }
            @Override
            public void onReceivedError(WebView view, WebResourceRequest request,
                                        WebResourceError error) {
                // Only care about main frame: sub-resource failure does not affect page usability
                if (request.isForMainFrame()) {
                    pageFailed = true;
                    scheduleReload();
                }
            }
        });
        
        webView.setWebChromeClient(new WebChromeClient() {
            /**
             * <input type="file"> in the web page (dsh attachments/upload plugins all go through this) is
             * silently swallowed by default in Android WebView — must invoke the system file
             * picker here, otherwise clicking upload has no response.
             */
            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback,
                                             FileChooserParams params) {
                if (fileCallback != null) fileCallback.onReceiveValue(null);
                fileCallback = callback;
                try {
                    // createIntent() comes with built-in accept types and multi-select flags
                    startActivityForResult(params.createIntent(), REQ_FILE_CHOOSER);
                    return true;
                } catch (Exception e) {
                    // No app available to handle selection, etc.: return callback to avoid web side getting stuck
                    fileCallback = null;
                    callback.onReceiveValue(null);
                    return true;
                }
            }
        });
        
        webView.addJavascriptInterface(new DshBridge(), "DshNative");
        root.addView(webView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        root.addView(buildFabs());
        splash = buildSplash();
        root.addView(splash, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        setContentView(root);
    }

    /**
     * Web -> Native bridge (called after inject.js intercepts web buttons).
     * Note: @JavascriptInterface methods run on the WebView background thread, UI operations must be posted back to the main thread.
     */
    private final class DshBridge {
        /** Settings popup "Open config file": Desktop uses xdg-open, container has none so it will definitely fail -> Native editor. */
        @android.webkit.JavascriptInterface
        public void openConfig() {
            handler.post(() -> {
                if (!isFinishing()) {
                    startActivity(new Intent(MainActivity.this, ConfigEditorActivity.class));
                }
            });
        }
        /** Settings-Plugin page "Add plugin": Run dsh plugin --profile web add in background thread, callback to JS when done. */
        @android.webkit.JavascriptInterface
        public void installPlugin(final String spec) {
            new Thread(() -> {
                final PluginInstaller.Result r = PluginInstaller.install(MainActivity.this, spec);
                final String json = "{\"ok\":" + r.ok + ",\"output\":"
                        + org.json.JSONObject.quote(r.output) + "}";
                handler.post(() -> {
                    if (webView != null) {
                        webView.evaluateJavascript(
                                "window.__dshOnPluginInstallResult && window.__dshOnPluginInstallResult(" + json + ")",
                                null);
                    }
                });
            }, "dsh-plugin-install").start();
        }
    }

    /** Right-edge floating button group: File manager (top) + Container terminal (bottom), semi-transparent to avoid blocking dsh UI. */
    private View buildFabs() {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.addView(fab("📁", v ->
                startActivity(new Intent(this, FileManagerActivity.class))));
        View term = fab(">_", v ->
                startActivity(new Intent(this, TerminalActivity.class)));
        LinearLayout.LayoutParams tlp = (LinearLayout.LayoutParams) term.getLayoutParams();
        tlp.topMargin = Ui.dp(this, 12);
        col.addView(term, tlp);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.gravity = Gravity.END | Gravity.CENTER_VERTICAL;
        lp.rightMargin = Ui.dp(this, 6);
        col.setLayoutParams(lp);
        return col;
    }

    private View fab(String text, View.OnClickListener click) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextColor(Color.WHITE);
        tv.setTextSize(15);
        tv.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        tv.setGravity(Gravity.CENTER);
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor(Ui.PRIMARY);
        tv.setBackground(g);
        tv.setAlpha(0.85f);
        int size = Ui.dp(this, 44);
        tv.setLayoutParams(new LinearLayout.LayoutParams(size, size));
        tv.setOnClickListener(click);
        return tv;
    }

    private FrameLayout buildSplash() {
        FrameLayout fl = new FrameLayout(this);
        fl.setBackgroundColor(Ui.bg(this));
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER);
        int pad = Ui.dp(this, 36);
        box.setPadding(pad, pad, pad, pad);
        
        // DeepSeek Harness official whale logo (the icon itself contains brand recognition, no text configured)
        // The vector body is black filled, colored by text color in dark mode, otherwise black whale on black background is invisible
        ImageView logo = new ImageView(this);
        Drawable d = getResources().getDrawable(R.drawable.ic_dsh_brand, null);
        logo.setImageDrawable(d);
        logo.setImageTintList(ColorStateList.valueOf(Ui.text(this)));
        int logoSize = Ui.dp(this, 96);
        LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(logoSize, logoSize);
        box.addView(logo, llp);
        
        ProgressBar pb = new ProgressBar(this);
        pb.setIndeterminateTintList(ColorStateList.valueOf(Ui.PRIMARY));
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        plp.topMargin = Ui.dp(this, 32);
        box.addView(pb, plp);
        
        splashStatus = Ui.hint(this, "Starting container...");
        splashStatus.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams stlp = Ui.matchWrap();
        stlp.topMargin = Ui.dp(this, 16);
        box.addView(splashStatus, stlp);
        
        LinearLayout btns = new LinearLayout(this);
        btns.setGravity(Gravity.CENTER);
        btns.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        blp.topMargin = Ui.dp(this, 40);
        
        Button settings = Ui.outlineButton(this, "Settings");
        settings.setOnClickListener(v ->
                startActivity(new Intent(this, SettingsActivity.class)));
        btns.addView(settings);
        
        // Can also enter terminal to troubleshoot container when startup fails (especially useful when Web service fails to start)
        Button term = Ui.outlineButton(this, "Terminal");
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, Ui.dp(this, 48));
        tlp.leftMargin = Ui.dp(this, 16);
        term.setLayoutParams(tlp);
        term.setOnClickListener(v ->
                startActivity(new Intent(this, TerminalActivity.class)));
        btns.addView(term);
        
        Button reinstall = Ui.primaryButton(this, "Reinstall");
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, Ui.dp(this, 48));
        rlp.leftMargin = Ui.dp(this, 16);
        reinstall.setLayoutParams(rlp);
        reinstall.setOnClickListener(v ->
                startActivity(new Intent(this, FileProvisionActivity.class)));
        btns.addView(reinstall);
        
        box.addView(btns, blp);
        FrameLayout.LayoutParams flp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        flp.gravity = Gravity.CENTER;
        fl.addView(box, flp);
        return fl;
    }

    /** Poll Web service in background (real HTTP request), load page when ready; keep splash screen until page rendering is complete. */
    private void waitForServerAndLoad() {
        stopPolling = false;
        new Thread(() -> {
            long deadline = System.currentTimeMillis() + 120_000;
            boolean up = false;
            while (!stopPolling && System.currentTimeMillis() < deadline) {
                if (httpReady(port)) {
                    up = true;
                    break;
                }
                final long left = (deadline - System.currentTimeMillis()) / 1000;
                handler.post(() -> splashStatus.setText(
                        HarnessService.isRunning()
                                ? "Container started, waiting for Web service ready... (" + left + "s)"
                                : "Starting container... (" + left + "s)"));
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                    return;
                }
            }
            final boolean ok = up;
            handler.post(() -> {
                if (isFinishing()) return;
                if (ok) {
                    splashStatus.setText("Loading UI...");
                    loadMainUrl();
                } else {
                    splashStatus.setText("Timeout. Check logs in settings, or tap 'Reinstall'.");
                }
            });
        }, "dsh-port-poll").start();
    }

    private void loadMainUrl() {
        loadAttempts++;
        webView.loadUrl("http://127.0.0.1:" + port + "/");
    }

    /** Main frame loading failed: Restore splash screen prompt and bounded automatic retry (container might still be warming up or just restarted). */
    private void scheduleReload() {
        handler.post(() -> {
            if (isFinishing()) return;
            if (loadAttempts >= 15) {
                showSplash("UI load failed. Check logs in settings, or tap 'Reinstall'.");
                return;
            }
            showSplash("Web service connection failed, retrying...");
            handler.postDelayed(() -> {
                if (!isFinishing()) loadMainUrl();
            }, 2000);
        });
    }

    private void showSplash(String text) {
        splash.animate().cancel();
        splash.setAlpha(1f);
        splash.setVisibility(View.VISIBLE);
        splashStatus.setText(text);
    }

    private void dismissSplash() {
        if (splash.getVisibility() != View.VISIBLE) return;
        splash.animate().alpha(0f).setDuration(300).withEndAction(() -> {
            splash.setVisibility(View.GONE);
            splash.setAlpha(1f);
        }).start();
    }

    /** TCP connection does not mean HTTP service is ready (proot/service warm-up period will occupy the port but not respond),
     *  must get a real HTTP response to be considered ready — otherwise loadUrl will result in a white screen. */
    private static boolean httpReady(int port) {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL("http://127.0.0.1:" + port + "/").openConnection();
            conn.setConnectTimeout(800);
            conn.setReadTimeout(1500);
            conn.setInstanceFollowRedirects(false);
            conn.getResponseCode();
            return true; // Any HTTP response (including 404/302) means Web service is responding
        } catch (IOException e) {
            return false;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (webView != null) webView.onResume();
    }

    @Override
    protected void onPause() {
        if (webView != null) webView.onPause();
        super.onPause();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        // Never loaded successfully before (url is null or last load failed) and splash screen is still visible -> Re-poll and load
        if (webView != null && splash.getVisibility() == View.VISIBLE
                && (webView.getUrl() == null || pageFailed)) {
            loadAttempts = 0;
            waitForServerAndLoad();
        }
    }

    @Override
    public void onBackPressed() {
        if (webView != null && webView.canGoBack()) {
            webView.goBack();
        } else {
            super.onBackPressed();
        }
    }

    @Override
    protected void onDestroy() {
        stopPolling = true;
        if (webView != null) {
            webView.destroy();
        }
        super.onDestroy();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == REQ_FILE_CHOOSER) {
            if (fileCallback != null) {
                Uri[] results = null;
                if (resultCode == RESULT_OK && data != null) {
                    // Multi-select results in clipData, single select in data
                    if (data.getClipData() != null) {
                        int n = data.getClipData().getItemCount();
                        results = new Uri[n];
                        for (int i = 0; i < n; i++) {
                            results[i] = data.getClipData().getItemAt(i).getUri();
                        }
                    } else if (data.getData() != null) {
                        results = new Uri[]{data.getData()};
                    }
                }
                fileCallback.onReceiveValue(results);
                fileCallback = null;
            }
            return;
        }
        super.onActivityResult(requestCode, resultCode, data);
    }
}
