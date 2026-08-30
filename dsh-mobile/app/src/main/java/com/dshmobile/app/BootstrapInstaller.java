package com.dshmobile.app;

import android.content.Context;
import org.apache.commons.compress.archivers.ar.ArArchiveEntry;
import org.apache.commons.compress.archivers.ar.ArArchiveInputStream;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream;
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream;
import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class BootstrapInstaller {
    public interface Listener {
        void onStage(String stage, int percent);
        void onLog(String line);
        void onDone(boolean success, String error);
    }

    private static final String TERMUX_POOL = "https://mirrors.ustc.edu.cn/termux/apt/termux-main/pool/main/";
    static final String PROOT_POOL = TERMUX_POOL + "p/proot/";
    static final String LIBTALLOC_POOL = TERMUX_POOL + "libt/libtalloc/";
    static final String LIBANDROID_SHMEM_POOL = TERMUX_POOL + "liba/libandroid-shmem/";
    private static final String NODE_SERIES = "latest-v22.x";
    private static final int CONNECT_TIMEOUT = 15000;
    private static final int READ_TIMEOUT = 60000;

    private final Context ctx;
    private final Prefs prefs;
    private final Listener listener;
    private volatile boolean cancelled;

    public BootstrapInstaller(Context ctx, Listener listener) {
        this.ctx = ctx.getApplicationContext();
        this.prefs = Prefs.of(ctx);
        this.listener = listener;
    }

    public void cancel() { cancelled = true; }
    private void stage(String s, int p) { listener.onStage(s, p); }
    private void log(String s) { listener.onLog(s); }
    private void checkCancelled() throws IOException { if (cancelled) throw new IOException("Cancelled"); }

    public void run() {
        try {
            File base = ProotRunner.baseDir(ctx);
            File dl = new File(base, "dl");
            dl.mkdirs();
            File rootfs = ProotRunner.rootfsDir(ctx);

            long pageSize = android.system.Os.sysconf(android.system.OsConstants._SC_PAGESIZE);
            log("Host kernel page size: " + pageSize + " bytes");
            if (pageSize == 16384) {
                log("⚠ 16KB page device: Ubuntu rootfs & Node.js binaries are 4KB aligned, may fail to exec; proot uses Termux new builds (16KB aligned) and is unaffected.");
            }

            // 1. rootfs
            File rootfsTar = new File(dl, "ubuntu-base.tar.gz");
            if (!new File(rootfs, "bin/bash").isFile()) {
                if (rootfsTar.isFile()) {
                    log("rootfs archive ready (local/cache), skipping download");
                    stage("Download Ubuntu rootfs", 25);
                } else {
                    stage("Download Ubuntu rootfs", 2);
                    download(prefs.getRootfsUrl(), rootfsTar, 2, 25);
                }
                checkCancelled();
                stage("Extract rootfs", 26);
                deleteRecursively(rootfs);
                rootfs.mkdirs();
                extractTar(rootfsTar, rootfs, true, 0);
                log("rootfs extraction complete");
            } else {
                log("rootfs exists, skipping");
            }

            // 2. proot
            File proot = ProotRunner.prootBin(ctx);
            File loader = new File(base, "loader");
            if (!proot.isFile() || !loader.isFile()) {
                File deb = new File(dl, "proot.deb");
                if (deb.isFile()) {
                    log("proot archive ready (local/cache), skipping download");
                    stage("Download proot", 40);
                } else {
                    stage("Download proot", 32);
                    String debUrl = resolveTermuxDeb(PROOT_POOL, "proot_");
                    log("proot package: " + debUrl);
                    download(debUrl, deb, 32, 40);
                }
                checkCancelled();
                stage("Extract proot", 41);
                extractProotFromDeb(deb, base);
                proot.setExecutable(true, true);
                loader.setExecutable(true, true);
                new File(base, "loader32").setExecutable(true, true);
                log("proot and loader ready");
            } else {
                log("proot exists, skipping");
            }

            if (pageSize == 16384) {
                long align = elfMaxAlign(proot);
                log("proot ELF alignment: " + align);
                if (align >= 0 && align < 16384) {
                    log("⚠ proot binary not 16KB aligned, cannot run on this device, please report this log.");
                }
            }

            // 2b. proot dependency libraries
            File libDir = new File(base, "lib");
            File tallocSo = new File(libDir, "libtalloc.so.2");
            if (!tallocSo.isFile()) {
                stage("Download proot dependencies", 42);
                libDir.mkdirs();
                
                File tallocDeb = new File(dl, "libtalloc.deb");
                if (tallocDeb.isFile()) {
                    log("libtalloc archive ready (local/cache), skipping download");
                    stage("Download proot dependencies", 43);
                } else {
                    String tallocUrl = resolveTermuxDeb(LIBTALLOC_POOL, "libtalloc_");
                    log("libtalloc package: " + tallocUrl);
                    download(tallocUrl, tallocDeb, 42, 43);
                }
                extractLibsFromDeb(tallocDeb, libDir);
                checkCancelled();
                
                File shmemDeb = new File(dl, "libandroid-shmem.deb");
                if (shmemDeb.isFile()) {
                    log("libandroid-shmem archive ready (local/cache), skipping download");
                    stage("Download proot dependencies", 44);
                } else {
                    String shmemUrl = resolveTermuxDeb(LIBANDROID_SHMEM_POOL, "libandroid-shmem_");
                    log("libandroid-shmem package: " + shmemUrl);
                    download(shmemUrl, shmemDeb, 43, 44);
                }
                extractLibsFromDeb(shmemDeb, libDir);
                log("proot dependencies ready");
            } else {
                log("proot dependencies exist, skipping");
            }

            // 3. Node.js
            File nodeBin = new File(rootfs, "opt/node/bin/node");
            if (!nodeBin.isFile()) {
                File nodeTar = new File(dl, "node.tar.xz");
                if (nodeTar.isFile()) {
                    log("Node.js archive ready (local/cache), skipping download");
                    stage("Download Node.js", 65);
                } else {
                    stage("Download Node.js", 45);
                    String nodeUrl = resolveNodeUrl(prefs);
                    log("Node package: " + nodeUrl);
                    download(nodeUrl, nodeTar, 45, 65);
                }
                checkCancelled();
                stage("Extract Node.js", 66);
                deleteRecursively(new File(rootfs, "opt/node"));
                new File(rootfs, "opt/node").mkdirs();
                extractTar(nodeTar, new File(rootfs, "opt/node"), false, 1);
                log("Node.js ready");
            } else {
                log("Node.js exists, skipping");
            }

            // 4. Basic configuration inside container
            stage("Configure container", 72);
            writeContainerConfig(rootfs);

            // 4b. Compilation toolchain
            File gxx = new File(rootfs, "usr/bin/g++");
            if (!gxx.isFile()) {
                stage("Install compilation toolchain", 74);
                boolean toolchainOk = false;
                for (int attempt = 1; attempt <= 2 && !toolchainOk && !cancelled; attempt++) {
                    try {
                        killStaleAptProcesses();
                        runInContainer(Arrays.asList("/usr/bin/apt-get", "-o", "DPkg::Lock::Timeout=180", "update"), 74, 78);
                        checkCancelled();
                        try {
                            runInContainer(Arrays.asList("/usr/bin/dpkg", "--configure", "-a"), 78, 78);
                        } catch (Exception e) {
                            log("dpkg state fix incomplete (ignored, continuing installation)");
                        }
                        runInContainer(Arrays.asList("/usr/bin/apt-get", "-o", "DPkg::Lock::Timeout=180", "install", "-y", "--no-install-recommends", "python3", "make", "g++", "ca-certificates", "git"), 78, 82);
                        toolchainOk = gxx.isFile();
                    } catch (Exception e) {
                        if (cancelled) throw e;
                        log("Toolchain install failed (attempt " + attempt + "): " + e.getMessage());
                    }
                }
                if (!toolchainOk) {
                    log("⚠ Toolchain install failed, skipped. node-pty will auto-retry on service startup.");
                }
            } else {
                log("Toolchain exists, skipping");
            }

            // 5. Install dsh inside container
            File dshBin = new File(rootfs, "opt/node/bin/dsh");
            if (!dshBin.isFile()) {
                stage("Install DeepSeek Harness", 83);
                File scopeDir = new File(rootfs, "opt/node/lib/node_modules/@deepseek-ai");
                File[] stale = scopeDir.listFiles();
                if (stale != null) {
                    for (File f : stale) {
                        if (f.getName().equals("dsh") || f.getName().startsWith(".dsh-")) {
                            deleteRecursively(f);
                        }
                    }
                }
                runInContainer(Arrays.asList("/opt/node/bin/npm", "config", "set", "registry", prefs.getNpmRegistry()), 83, 85);
                checkCancelled();
                runInContainer(Arrays.asList("/opt/node/bin/npm", "install", "-g", "@deepseek-ai/dsh"), 85, 96);
            } else {
                log("dsh installed, skipping");
            }

            // 5b. Verify node-pty native module
            if (NodePtyFixer.needsFix(rootfs)) {
                if (!new File(rootfs, "usr/bin/g++").isFile()) {
                    log("⚠ No toolchain, skipping node-pty compile (will auto-retry on service startup)");
                } else {
                    stage("Compile node-pty native module", 97);
                    log("node-pty lacks pty.node, rebuilding in container...");
                    File installLog = new File(ProotRunner.baseDir(ctx), "install.log");
                    if (!NodePtyFixer.fix(ctx, installLog)) {
                        throw new IOException("node-pty native module rebuild failed, check logs in settings");
                    }
                    log("node-pty native module ready");
                }
            }
            checkCancelled();

            // 6. SSH service
            ensureSshServer(rootfs);
            checkCancelled();
            stage("Complete", 100);
            prefs.setSetupDone(true);
            listener.onDone(true, null);
        } catch (Exception e) {
            log("Install failed: " + e.getMessage());
            listener.onDone(false, e.getMessage());
        }
    }

    private void ensureSshServer(File rootfs) throws IOException, InterruptedException {
        if (new File(rootfs, "usr/sbin/sshd").isFile()) {
            log("SSH service installed, skipping");
            return;
        }
        stage("Install SSH service", 97);
        for (int attempt = 1; attempt <= 2 && !cancelled; attempt++) {
            try {
                killStaleAptProcesses();
                runInContainer(Arrays.asList("/usr/bin/apt-get", "-o", "DPkg::Lock::Timeout=180", "update"), 97, 97);
                try { runInContainer(Arrays.asList("/usr/bin/dpkg", "--configure", "-a"), 97, 97); } catch (Exception e) { log("dpkg state fix incomplete (ignored)"); }
                runInContainer(Arrays.asList("/usr/bin/apt-get", "-o", "DPkg::Lock::Timeout=180", "install", "-y", "--no-install-recommends", "openssh-server"), 97, 99);
                return;
            } catch (Exception e) {
                if (cancelled) throw e;
                log("SSH service install failed (attempt " + attempt + "): " + e.getMessage());
            }
        }
        log("⚠ SSH service install failed, skipped (will auto-retry on service startup)");
    }

    public static void ensureSshServerInstalled(Context ctx, File logFile) {
        if (new File(ProotRunner.rootfsDir(ctx), "usr/sbin/sshd").isFile()) return;
        BootstrapInstaller installer = new BootstrapInstaller(ctx, new Listener() {
            @Override public void onStage(String stage, int percent) { appendLog(logFile, "[ssh] " + stage); }
            @Override public void onLog(String line) { appendLog(logFile, "[ssh] " + line); }
            @Override public void onDone(boolean success, String error) { }
        });
        try {
            installer.ensureSshServer(ProotRunner.rootfsDir(ctx));
            appendLog(logFile, "[ssh] openssh-server fallback install complete");
        } catch (Exception e) {
            appendLog(logFile, "[ssh] Install failed (does not affect Web service): " + e.getMessage());
        }
    }

    private static void appendLog(File f, String line) {
        try {
            java.io.FileOutputStream out = new java.io.FileOutputStream(f, true);
            out.write((line + "\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
            out.close();
        } catch (Exception ignored) { }
    }

    private void writeContainerConfig(File rootfs) throws IOException {
        File etc = new File(rootfs, "etc");
        etc.mkdirs();
        File resolv = new File(etc, "resolv.conf");
        String dns = "nameserver 223.5.5.5\nnameserver 8.8.8.8\n";
        Files.write(resolv.toPath(), dns.getBytes(StandardCharsets.UTF_8));

        File sources = new File(etc, "apt");
        sources.mkdirs();
        String list = "deb http://mirrors.ustc.edu.cn/ubuntu-ports jammy main restricted universe multiverse\n"
                + "deb http://mirrors.ustc.edu.cn/ubuntu-ports jammy-updates main restricted universe multiverse\n"
                + "deb http://mirrors.ustc.edu.cn/ubuntu-ports jammy-security main restricted universe multiverse\n";
        Files.write(new File(sources, "sources.list").toPath(), list.getBytes(StandardCharsets.UTF_8));

        new File(rootfs, "mnt/sd").mkdirs();
        new File(rootfs, "mnt/shared").mkdirs();
        new File(rootfs, "root").mkdirs();
        log("Written resolv.conf, apt sources, and mount points");
    }

    private void runInContainer(List<String> inner, int pStart, int pEnd) throws IOException, InterruptedException {
        File logFile = new File(ProotRunner.baseDir(ctx), "install.log");
        ProcessBuilder pb = new ProcessBuilder(ProotRunner.buildCommand(ctx, inner));
        ProotRunner.applyEnv(ctx, pb);
        pb.redirectErrorStream(true);
        Process p = pb.start();
        long lastDot = 0;
        int shown = pStart;
        try (InputStream in = p.getInputStream(); OutputStream lf = new FileOutputStream(logFile, true)) {
            byte[] buf = new byte[4096];
            StringBuilder line = new StringBuilder();
            int n;
            while ((n = in.read(buf)) != -1) {
                lf.write(buf, 0, n);
                lf.flush();
                for (int i = 0; i < n; i++) {
                    char c = (char) buf[i];
                    if (c == '\n' || c == '\r') {
                        if (line.length() > 0) { log(line.toString()); line.setLength(0); }
                    } else if (line.length() < 300) {
                        line.append(c);
                    }
                }
                long now = System.currentTimeMillis();
                if (now - lastDot > 2000 && shown < pEnd) {
                    shown++;
                    lastDot = now;
                    stage(null, shown);
                }
            }
        }
        int code = p.waitFor();
        if (code != 0) {
            throw new IOException("Container command failed (" + code + "): " + inner);
        }
    }

    private void killStaleAptProcesses() {
        File[] entries = new File("/proc").listFiles();
        if (entries == null) return;
        int myUid = android.os.Process.myUid();
        int myPid = android.os.Process.myPid();
        boolean killed = false;
        for (File entry : entries) {
            int pid;
            try { pid = Integer.parseInt(entry.getName()); } catch (NumberFormatException e) { continue; }
            if (pid == myPid) continue;
            try {
                String status = readProcFile(new File(entry, "status"));
                if (status == null || parseUid(status) != myUid) continue;
                String cmdline = readProcFile(new File(entry, "cmdline"));
                if (cmdline == null) continue;
                if (cmdline.contains("apt-get") || cmdline.contains("/dpkg") || cmdline.contains("unattended-upgrade")) {
                    log("Cleaning residual package manager process (pid " + pid + ")");
                    android.os.Process.killProcess(pid);
                    killed = true;
                }
            } catch (Exception ignored) { }
        }
        if (killed) {
            try { Thread.sleep(800); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
    }

    private static String readProcFile(File f) {
        try (InputStream in = new FileInputStream(f)) {
            return new String(readAll(in), StandardCharsets.UTF_8).replace('\0', ' ');
        } catch (IOException e) { return null; }
    }

    private static int parseUid(String status) {
        for (String line : status.split("\n")) {
            if (line.startsWith("Uid:")) {
                String[] parts = line.trim().split("\\s+");
                if (parts.length >= 2) {
                    try { return Integer.parseInt(parts[1]); } catch (NumberFormatException e) { return -1; }
                }
            }
        }
        return -1;
    }

    private void download(String url, File dest, int pStart, int pEnd) throws IOException {
        checkCancelled();
        File tmp = new File(dest.getParentFile(), dest.getName() + ".part");
        HttpURLConnection conn = open(url);
        long total = conn.getContentLengthLong();
        try (InputStream in = new BufferedInputStream(conn.getInputStream()); OutputStream out = new FileOutputStream(tmp)) {
            byte[] buf = new byte[64 * 1024];
            long done = 0;
            int n;
            long lastReport = 0;
            while ((n = in.read(buf)) != -1) {
                checkCancelled();
                out.write(buf, 0, n);
                done += n;
                long now = System.currentTimeMillis();
                if (now - lastReport > 500) {
                    lastReport = now;
                    int p = total > 0 ? pStart + (int) ((pEnd - pStart) * done / total) : pStart;
                    stage(null, Math.min(p, pEnd));
                    log(String.format("Downloaded %.1f MB", done / 1048576.0));
                }
            }
        } finally {
            conn.disconnect();
        }
        if (!tmp.renameTo(dest)) {
            throw new IOException("Cannot write " + dest);
        }
    }

    private static HttpURLConnection open(String url) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setConnectTimeout(CONNECT_TIMEOUT);
        conn.setReadTimeout(READ_TIMEOUT);
        conn.setInstanceFollowRedirects(true);
        conn.setRequestProperty("User-Agent", "dsh-mobile/1.0");
        int code = conn.getResponseCode();
        if (code >= 400) {
            throw new IOException("HTTP " + code + ": " + url);
        }
        return conn;
    }

    private static String fetchText(String url) throws IOException {
        HttpURLConnection conn = open(url);
        StringBuilder sb = new StringBuilder();
        try (InputStream in = conn.getInputStream()) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) {
                sb.append(new String(buf, 0, n, StandardCharsets.UTF_8));
            }
        } finally {
            conn.disconnect();
        }
        return sb.toString();
    }

    static String resolveTermuxDeb(String pool, String prefix) throws IOException {
        String html = fetchText(pool);
        Matcher m = Pattern.compile("href=\"(?:[^\"]/)?(" + Pattern.quote(prefix) + "[^\"]+_aarch64\\.deb)\"").matcher(html);
        String latest = null;
        while (m.find()) { latest = m.group(1); }
        if (latest == null) { throw new IOException("Not found: " + prefix + " aarch64 package"); }
        return pool + latest;
    }

    static String resolveNodeUrl(Prefs prefs) throws IOException {
        String base = prefs.getNodeMirror();
        if (base.endsWith("/")) base = base.substring(0, base.length() - 1);
        String html = fetchText(base + "/" + NODE_SERIES + "/");
        Matcher m = Pattern.compile("(node-v22\\.\\d+\\.\\d+)-linux-arm64\\.tar\\.xz").matcher(html);
        String latest = null;
        while (m.find()) { latest = m.group(1); }
        if (latest == null) { throw new IOException("Node linux-arm64 package not found"); }
        return base + "/" + NODE_SERIES + "/" + latest + "-linux-arm64.tar.xz";
    }

    private void extractTar(File archive, File dest, boolean gz, int stripComps) throws IOException {
        InputStream fis = new BufferedInputStream(new FileInputStream(archive));
        InputStream cis = gz ? new GzipCompressorInputStream(fis) : new XZCompressorInputStream(fis);
        try (TarArchiveInputStream tar = new TarArchiveInputStream(cis)) {
            TarArchiveEntry e;
            while ((e = tar.getNextEntry()) != null) {
                checkCancelled();
                String name = e.getName();
                String[] parts = name.split("/");
                List<String> kept = new ArrayList<>();
                for (String part : parts) { if (!part.isEmpty() && !part.equals(".")) kept.add(part); }
                if (kept.size() <= stripComps) continue;
                String rel = String.join("/", kept.subList(stripComps, kept.size()));
                if (rel.isEmpty() || rel.contains("..")) continue;
                File out = new File(dest, rel);
                if (e.isDirectory()) {
                    out.mkdirs();
                } else if (e.isSymbolicLink()) {
                    String target = e.getLinkName();
                    File parent = out.getParentFile();
                    parent.mkdirs();
                    out.delete();
                    try { Files.createSymbolicLink(out.toPath(), new File(target).toPath()); } catch (Exception ex) { log("Skipping symlink: " + rel); }
                } else if (e.isLink()) {
                    File parent = out.getParentFile();
                    parent.mkdirs();
                    File src = new File(dest, e.getLinkName());
                    out.delete();
                    try { Files.createLink(out.toPath(), src.toPath()); } catch (Exception ex) { log("Skipping hardlink: " + rel); }
                } else {
                    File parent = out.getParentFile();
                    parent.mkdirs();
                    try (OutputStream fos = new FileOutputStream(out)) {
                        byte[] buf = new byte[64 * 1024];
                        int n;
                        while ((n = tar.read(buf)) != -1) { fos.write(buf, 0, n); }
                    }
                    int mode = e.getMode();
                    if ((mode & 0100) != 0) { out.setExecutable(true, true); }
                }
            }
        }
    }

    private void extractProotFromDeb(File deb, File destDir) throws IOException {
        try (ArArchiveInputStream ar = new ArArchiveInputStream(new BufferedInputStream(new FileInputStream(deb)))) {
            ArArchiveEntry e;
            byte[] dataTar = null;
            boolean xz = true;
            while ((e = ar.getNextEntry()) != null) {
                String name = e.getName();
                if (name.startsWith("data.tar")) {
                    xz = name.endsWith(".xz");
                    dataTar = readAll(ar);
                    break;
                }
            }
            if (dataTar == null) { throw new IOException("data.tar not found in deb"); }
            InputStream cis = xz ? new XZCompressorInputStream(new java.io.ByteArrayInputStream(dataTar)) : new GzipCompressorInputStream(new java.io.ByteArrayInputStream(dataTar));
            int found = 0;
            try (TarArchiveInputStream tar = new TarArchiveInputStream(cis)) {
                TarArchiveEntry te;
                while ((te = tar.getNextEntry()) != null) {
                    String name = te.getName();
                    if (te.isDirectory()) continue;
                    String outName = null;
                    if (name.endsWith("/bin/proot")) outName = "proot";
                    else if (name.endsWith("/libexec/proot/loader")) outName = "loader";
                    else if (name.endsWith("/libexec/proot/loader32")) outName = "loader32";
                    if (outName == null) continue;
                    try (OutputStream fos = new FileOutputStream(new File(destDir, outName))) {
                        byte[] buf = new byte[64 * 1024];
                        int n;
                        while ((n = tar.read(buf)) != -1) { fos.write(buf, 0, n); }
                    }
                    found++;
                }
            }
            if (found == 0) { throw new IOException("proot binary not found in deb"); }
        }
    }

    private void extractLibsFromDeb(File deb, File libDir) throws IOException {
        java.util.Map<String, byte[]> files = new java.util.HashMap<>();
        java.util.Map<String, String> symlinks = new java.util.HashMap<>();
        try (ArArchiveInputStream ar = new ArArchiveInputStream(new BufferedInputStream(new FileInputStream(deb)))) {
            ArArchiveEntry e;
            byte[] dataTar = null;
            boolean xz = true;
            while ((e = ar.getNextEntry()) != null) {
                String name = e.getName();
                if (name.startsWith("data.tar")) {
                    xz = name.endsWith(".xz");
                    dataTar = readAll(ar);
                    break;
                }
            }
            if (dataTar == null) { throw new IOException("data.tar not found in deb"); }
            InputStream cis = xz ? new XZCompressorInputStream(new java.io.ByteArrayInputStream(dataTar)) : new GzipCompressorInputStream(new java.io.ByteArrayInputStream(dataTar));
            try (TarArchiveInputStream tar = new TarArchiveInputStream(cis)) {
                TarArchiveEntry te;
                while ((te = tar.getNextEntry()) != null) {
                    String name = te.getName();
                    String base = name.substring(name.lastIndexOf('/') + 1);
                    if (!base.contains(".so")) continue;
                    if (te.isSymbolicLink()) {
                        String tgt = te.getLinkName();
                        symlinks.put(base, tgt.substring(tgt.lastIndexOf('/') + 1));
                    } else if (!te.isDirectory()) {
                        files.put(base, readAll(tar));
                    }
                }
            }
        }
        if (files.isEmpty()) { throw new IOException(".so libraries not found in deb"); }
        for (java.util.Map.Entry<String, byte[]> en : files.entrySet()) {
            File out = new File(libDir, en.getKey());
            try (OutputStream fos = new FileOutputStream(out)) { fos.write(en.getValue()); }
        }
        for (java.util.Map.Entry<String, String> en : symlinks.entrySet()) {
            byte[] target = files.get(en.getValue());
            if (target != null && !files.containsKey(en.getKey())) {
                File out = new File(libDir, en.getKey());
                try (OutputStream fos = new FileOutputStream(out)) { fos.write(target); }
            }
        }
        log("Extracted libraries: " + files.keySet());
    }

    private static byte[] readAll(InputStream in) throws IOException {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[64 * 1024];
        int n;
        while ((n = in.read(buf)) != -1) { out.write(buf, 0, n); }
        return out.toByteArray();
    }

    static long elfMaxAlign(File f) {
        try (java.io.RandomAccessFile raf = new java.io.RandomAccessFile(f, "r")) {
            byte[] ident = new byte[6];
            raf.readFully(ident);
            if (ident[0] != 0x7F || ident[1] != 'E' || ident[2] != 'L' || ident[3] != 'F' || ident[4] != 2 || ident[5] != 1) { return -1; }
            long phoff = readLeLong(raf, 0x20);
            int phentsize = readLeShort(raf, 0x36);
            int phnum = readLeShort(raf, 0x38);
            long max = 0;
            for (int i = 0; i < phnum; i++) {
                long align = readLeLong(raf, phoff + (long) i * phentsize + 0x30);
                if (align > max) max = align;
            }
            return max;
        } catch (Exception e) { return -1; }
    }

    private static long readLeLong(java.io.RandomAccessFile raf, long off) throws IOException {
        raf.seek(off);
        long v = 0;
        for (int i = 0; i < 8; i++) { v |= ((long) raf.read() & 0xFF) << (8 * i); }
        return v;
    }

    private static int readLeShort(java.io.RandomAccessFile raf, long off) throws IOException {
        raf.seek(off);
        return (raf.read() & 0xFF) | ((raf.read() & 0xFF) << 8);
    }

    static void deleteRecursively(File f) {
        if (f == null || !f.exists()) return;
        if (f.isDirectory()) {
            File[] kids = f.listFiles();
            if (kids != null) { for (File k : kids) deleteRecursively(k); }
        }
        f.delete();
    }
}
