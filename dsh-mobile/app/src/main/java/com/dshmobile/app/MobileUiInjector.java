package com.dshmobile.app;

import android.content.Context;
import android.webkit.WebView;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/** 向 dsh Web UI 注入移动端适配 CSS/JS。 */
public final class MobileUiInjector {

    private final String css;
    private final String js;
    private final String earlyPolyfillJs;

    public MobileUiInjector(Context ctx) {
        css = readAsset(ctx, "mobile.css");
        js = readAsset(ctx, "inject.js");
        earlyPolyfillJs = readAsset(ctx, "early-polyfill.js");
    }

    /** 页面开始加载时注入老 WebView 兼容 polyfill（须在 dsh 自身脚本运行前）。 */
    public void injectEarlyPolyfill(WebView webView) {
        webView.evaluateJavascript(earlyPolyfillJs, null);
    }

    /** 页面加载完成后注入。幂等，可重复调用。 */
    public void inject(WebView webView) {
        String cssJson = toJsString(css);
        String script =
                "(function(){" +
                "if(!document.getElementById('dsh-mobile-style')){" +
                "var st=document.createElement('style');st.id='dsh-mobile-style';" +
                "st.textContent=" + cssJson + ";document.head.appendChild(st);}" +
                "var v=document.querySelector('meta[name=viewport]');" +
                "if(v){v.setAttribute('content','width=device-width, initial-scale=1, maximum-scale=1, user-scalable=no, viewport-fit=cover, interactive-widget=resizes-content');}" +
                "else{var m=document.createElement('meta');m.name='viewport';" +
                "m.content='width=device-width, initial-scale=1, maximum-scale=1, user-scalable=no, viewport-fit=cover, interactive-widget=resizes-content';" +
                "document.head.appendChild(m);}" +
                "})();";
        webView.evaluateJavascript(script, null);
        webView.evaluateJavascript(js, null);
    }

    private static String readAsset(Context ctx, String name) {
        try (InputStream in = ctx.getAssets().open(name)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) {
                out.write(buf, 0, n);
            }
            return out.toString(StandardCharsets.UTF_8.name());
        } catch (IOException e) {
            return "";
        }
    }

    private static String toJsString(String s) {
        StringBuilder sb = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
            }
        }
        return sb.append("\"").toString();
    }
}
