package com.dshmobile.app;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Mirror catalog and lightweight health checks for bootstrap assets. */
public final class ProvisionMirrors {
    public enum Kind { ROOTFS, TERMUX, NODE }

    public static final class Mirror {
        public final String id;
        public final String name;
        public final String host;
        public final Kind kind;
        public final String rootfsBase;
        public final String termuxBase;
        public final String nodeBase;

        Mirror(String id, String name, String host, Kind kind, String rootfsBase, String termuxBase, String nodeBase) {
            this.id = id;
            this.name = name;
            this.host = host;
            this.kind = kind;
            this.rootfsBase = rootfsBase;
            this.termuxBase = termuxBase;
            this.nodeBase = nodeBase;
        }

        public String displayUrl(FileAsset.Kind asset) {
            if (asset == FileAsset.Kind.ROOTFS) {
                return rootfsBase + "/ubuntu-base-22.04.5-base-arm64.tar.gz";
            }
            if (asset == FileAsset.Kind.NODE) return nodeBase + "/latest-v22.x/";
            return termuxBase + "/pool/main/p/proot/";
        }
    }

    public static final class Health {
        public final Mirror mirror;
        public final boolean ok;
        public final long latencyMs;
        public final String error;

        Health(Mirror mirror, boolean ok, long latencyMs, String error) {
            this.mirror = mirror; this.ok = ok; this.latencyMs = latencyMs; this.error = error;
        }
    }

    private static final List<Mirror> ALL = new ArrayList<>();
    static {
        ALL.add(new Mirror("official", "Official", "Ubuntu / Termux / Node.js", Kind.ROOTFS,
                "https://cdimage.ubuntu.com/ubuntu-base/releases/22.04/release",
                "https://packages.termux.dev/apt/termux-main",
                "https://nodejs.org/dist"));
        ALL.add(new Mirror("ustc", "USTC", "mirrors.ustc.edu.cn", Kind.ROOTFS,
                "https://mirrors.ustc.edu.cn/ubuntu-cdimage/ubuntu-base/releases/22.04/release",
                "https://mirrors.ustc.edu.cn/termux/apt/termux-main",
                "https://mirrors.ustc.edu.cn/node"));
        ALL.add(new Mirror("aliyun", "Alibaba Cloud", "mirrors.aliyun.com", Kind.ROOTFS,
                "https://mirrors.aliyun.com/ubuntu-cdimage/ubuntu-base/releases/22.04/release",
                "https://mirrors.aliyun.com/termux/termux-main",
                "https://mirrors.aliyun.com/nodejs-release"));
        ALL.add(new Mirror("tuna", "Tsinghua TUNA", "mirrors.tuna.tsinghua.edu.cn", Kind.ROOTFS,
                "https://mirrors.tuna.tsinghua.edu.cn/ubuntu-cdimage/ubuntu-base/releases/22.04/release",
                "https://mirrors.tuna.tsinghua.edu.cn/termux/apt/termux-main",
                "https://mirrors.tuna.tsinghua.edu.cn/nodejs-release"));
        ALL.add(new Mirror("pku", "Peking University", "mirrors.pku.edu.cn", Kind.ROOTFS,
                "https://mirrors.pku.edu.cn/ubuntu-cdimage/ubuntu-base/releases/22.04/release",
                "https://mirrors.pku.edu.cn/termux/termux-main",
                "https://mirrors.pku.edu.cn/nodejs-release"));
        ALL.add(new Mirror("sjtu", "Shanghai Jiao Tong", "mirror.sjtu.edu.cn", Kind.ROOTFS,
                "https://mirror.sjtu.edu.cn/ubuntu-cdimage/ubuntu-base/releases/22.04/release",
                "https://mirror.sjtu.edu.cn/termux/termux-main",
                "https://mirror.sjtu.edu.cn/nodejs-release"));
        ALL.add(new Mirror("iscas", "ISCAS", "mirror.iscas.ac.cn", Kind.ROOTFS,
                "https://mirror.iscas.ac.cn/ubuntu-cdimage/ubuntu-base/releases/22.04/release",
                "https://mirror.iscas.ac.cn/termux/apt/termux-main",
                "https://mirror.iscas.ac.cn/nodejs-release"));
    }

    private ProvisionMirrors() {}

    public static List<Mirror> forAsset(FileAsset.Kind asset) {
        Kind kind = asset == FileAsset.Kind.ROOTFS ? Kind.ROOTFS : asset == FileAsset.Kind.NODE ? Kind.NODE : Kind.TERMUX;
        List<Mirror> out = new ArrayList<>();
        for (Mirror m : ALL) out.add(m);
        return out;
    }

    public static Mirror byId(String id) {
        for (Mirror m : ALL) if (m.id.equals(id)) return m;
        return ALL.get(0);
    }

    public static String resolveUrl(FileAsset.Kind asset, Mirror mirror, Prefs prefs) throws IOException {
        if (asset == FileAsset.Kind.ROOTFS) {
            String configured = prefs.getRootfsUrl();
            if (mirror.id.equals("ustc") && configured != null && !configured.isEmpty() && !configured.equals(Prefs.DEFAULT_ROOTFS_URL)) return configured;
            return mirror.rootfsBase + "/ubuntu-base-22.04.5-base-arm64.tar.gz";
        }
        if (asset == FileAsset.Kind.NODE) {
            if (mirror.id.equals("ustc") && prefs.getNodeMirror() != null && !prefs.getNodeMirror().isEmpty()) {
                return BootstrapInstaller.resolveNodeUrl(prefs);
            }
            String base = mirror.nodeBase;
            if (base.endsWith("/")) base = base.substring(0, base.length() - 1);
            String html = fetchText(base + "/latest-v22.x/");
            Matcher m = Pattern.compile("(node-v22\\.\\d+\\.\\d+)-linux-arm64\\.tar\\.xz").matcher(html);
            String latest = null;
            while (m.find()) latest = m.group(1);
            if (latest == null) throw new IOException("Node.js ARM64 package not found");
            return base + "/latest-v22.x/" + latest + "-linux-arm64.tar.xz";
        }
        String prefixDir = asset == FileAsset.Kind.PROOT ? "p" : asset == FileAsset.Kind.LIBTALLOC ? "libt" : "liba"; String packageDir = asset == FileAsset.Kind.PROOT ? "proot" : asset == FileAsset.Kind.LIBTALLOC ? "libtalloc" : "libandroid-shmem"; String pool = mirror.termuxBase + "/pool/main/" + prefixDir + "/" + packageDir + "/";
        String prefix = asset == FileAsset.Kind.PROOT ? "proot_" : asset == FileAsset.Kind.LIBTALLOC ? "libtalloc_" : "libandroid-shmem_";
        return BootstrapInstaller.resolveTermuxDeb(pool, prefix);
    }

    public static Health check(Mirror mirror, FileAsset.Kind asset) {
        long start = System.currentTimeMillis();
        HttpURLConnection c = null;
        try {
            URL u = new URL(mirror.displayUrl(asset));
            c = (HttpURLConnection) u.openConnection();
            c.setConnectTimeout(5000); c.setReadTimeout(7000); c.setInstanceFollowRedirects(true);
            c.setRequestProperty("User-Agent", "dsh-mobile/1.0");
            c.setRequestProperty("Range", "bytes=0-0");
            int code = c.getResponseCode();
            boolean ok = code == 200 || code == 206 || code == 301 || code == 302 || code == 307 || code == 308;
            return new Health(mirror, ok, System.currentTimeMillis() - start, ok ? null : "HTTP " + code);
        } catch (Exception e) {
            return new Health(mirror, false, System.currentTimeMillis() - start, e.getMessage());
        } finally { if (c != null) c.disconnect(); }
    }

    private static String fetchText(String url) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(10000); c.setReadTimeout(15000); c.setInstanceFollowRedirects(true);
        c.setRequestProperty("User-Agent", "dsh-mobile/1.0");
        try (java.io.InputStream in = c.getInputStream()) {
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] b = new byte[8192]; int n;
            while ((n = in.read(b)) != -1) out.write(b, 0, n);
            return out.toString("UTF-8");
        } finally { c.disconnect(); }
    }
}
