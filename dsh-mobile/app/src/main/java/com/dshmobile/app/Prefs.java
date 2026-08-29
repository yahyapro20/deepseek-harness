package com.dshmobile.app;

import android.content.Context;
import android.content.SharedPreferences;

/** 应用设置集中读写。 */
public final class Prefs {
    private static final String NAME = "dsh_settings";

    public static final String KEY_SD_PATH = "sd_path";
    public static final String KEY_PORT = "port";
    public static final String KEY_ROOTFS_URL = "rootfs_url";
    public static final String KEY_NODE_MIRROR = "node_mirror";
    public static final String KEY_NPM_REGISTRY = "npm_registry";
    public static final String KEY_SSH_PORT = "ssh_port";
    public static final String KEY_SSH_PASSWORD = "ssh_password";
    public static final String KEY_SETUP_DONE = "setup_done";

    public static final int DEFAULT_SSH_PORT = 8022;

    // 默认走国内镜像（中科大 USTC）：rootfs/Node/Termux 池都有对应目录。
    // npm registry USTC 没有镜像，用 npmmirror（原淘宝源）。
    public static final String DEFAULT_ROOTFS_URL =
            "https://mirrors.ustc.edu.cn/ubuntu-cdimage/ubuntu-base/releases/22.04/release/ubuntu-base-22.04.5-base-arm64.tar.gz";
    public static final String DEFAULT_NODE_MIRROR = "https://mirrors.ustc.edu.cn/node";
    public static final String DEFAULT_NPM_REGISTRY = "https://registry.npmmirror.com";
    public static final int DEFAULT_PORT = 3080;

    private final SharedPreferences sp;

    private Prefs(Context ctx) {
        sp = ctx.getApplicationContext().getSharedPreferences(NAME, Context.MODE_PRIVATE);
    }

    public static Prefs of(Context ctx) {
        return new Prefs(ctx);
    }

    public String getSdPath() {
        return sp.getString(KEY_SD_PATH, null);
    }

    public void setSdPath(String path) {
        sp.edit().putString(KEY_SD_PATH, path).apply();
    }

    public int getPort() {
        return sp.getInt(KEY_PORT, DEFAULT_PORT);
    }

    public void setPort(int port) {
        sp.edit().putInt(KEY_PORT, port).apply();
    }

    public String getRootfsUrl() {
        return sp.getString(KEY_ROOTFS_URL, DEFAULT_ROOTFS_URL);
    }

    public void setRootfsUrl(String url) {
        sp.edit().putString(KEY_ROOTFS_URL, url).apply();
    }

    public String getNodeMirror() {
        return sp.getString(KEY_NODE_MIRROR, DEFAULT_NODE_MIRROR);
    }

    public void setNodeMirror(String url) {
        sp.edit().putString(KEY_NODE_MIRROR, url).apply();
    }

    public String getNpmRegistry() {
        return sp.getString(KEY_NPM_REGISTRY, DEFAULT_NPM_REGISTRY);
    }

    public void setNpmRegistry(String url) {
        sp.edit().putString(KEY_NPM_REGISTRY, url).apply();
    }

    public boolean isSetupDone() {
        return sp.getBoolean(KEY_SETUP_DONE, false);
    }

    public void setSetupDone(boolean done) {
        sp.edit().putBoolean(KEY_SETUP_DONE, done).apply();
    }

    public int getSshPort() {
        return sp.getInt(KEY_SSH_PORT, DEFAULT_SSH_PORT);
    }

    public void setSshPort(int port) {
        sp.edit().putInt(KEY_SSH_PORT, port).apply();
    }

    /** SSH 的 root 密码：首次读取时生成并持久化（换密码=重置后重装生成新的）。 */
    public String getSshPassword() {
        String pw = sp.getString(KEY_SSH_PASSWORD, null);
        if (pw == null || pw.isEmpty()) {
            String chars = "abcdefghjkmnpqrstuvwxyzABCDEFGHJKMNPQRSTUVWXYZ23456789";
            java.security.SecureRandom r = new java.security.SecureRandom();
            StringBuilder sb = new StringBuilder(10);
            for (int i = 0; i < 10; i++) {
                sb.append(chars.charAt(r.nextInt(chars.length())));
            }
            pw = sb.toString();
            sp.edit().putString(KEY_SSH_PASSWORD, pw).apply();
        }
        return pw;
    }
}
