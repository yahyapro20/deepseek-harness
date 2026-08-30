package com.dshmobile.app;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.util.Locale;
import java.io.BufferedInputStream;
import org.apache.commons.compress.archivers.ar.ArArchiveInputStream;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream;
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream;

/** Integrity and format checks for bootstrap archives. */
public final class ProvisionVerifier {
    public static final class Result {
        public final boolean ok;
        public final String message;
        public final String sha256;
        public final long size;
        Result(boolean ok, String message, String sha256, long size) { this.ok=ok; this.message=message; this.sha256=sha256; this.size=size; }
    }

    private ProvisionVerifier() {}

    public static Result verify(FileAsset.Kind kind, File f, String expectedSha256) {
        if (f == null || !f.isFile()) return new Result(false, "فایل وجود ندارد", "", 0);
        if (f.length() < 1024) return new Result(false, "فایل بیش از حد کوچک است و احتمالاً ناقص است", "", f.length());
        try {
            String magicError = checkMagic(kind, f);
            if (magicError != null) return new Result(false, magicError, sha256(f), f.length());
            String archiveError = checkArchive(kind, f);
            if (archiveError != null) return new Result(false, archiveError, sha256(f), f.length());
            String hash = sha256(f);
            if (expectedSha256 != null && !expectedSha256.isEmpty() && !expectedSha256.equalsIgnoreCase(hash)) {
                return new Result(false, "SHA-256 با مقدار مورد انتظار تطبیق ندارد", hash, f.length());
            }
            return new Result(true, "ساختار فایل و SHA-256 معتبر است", hash, f.length());
        } catch (Exception e) {
            return new Result(false, "Verify ناموفق: " + e.getMessage(), "", f.length());
        }
    }

    private static String checkMagic(FileAsset.Kind kind, File f) throws IOException {
        try (FileInputStream in = new FileInputStream(f)) {
            byte[] h = new byte[8];
            int n = in.read(h);
            if (n < 4) return "Header فایل ناقص است";
            if (kind == FileAsset.Kind.ROOTFS) {
                if ((h[0]&255)!=0x1f || (h[1]&255)!=0x8b) return "Ubuntu rootfs یک gzip معتبر نیست";
            } else if (kind == FileAsset.Kind.NODE) {
                if ((h[0]&255)!=0xfd || (h[1]&255)!=0x37 || (h[2]&255)!=0x7a || (h[3]&255)!=0x58 || (h[4]&255)!=0x5a || (h[5]&255)!=0x00) return "Node.js archive یک XZ معتبر نیست";
            } else {
                String s = new String(h, 0, Math.min(n, 8), java.nio.charset.StandardCharsets.US_ASCII);
                if (!s.startsWith("!<arch>")) return kind.displayName + " یک Debian package معتبر نیست";
            }
            return null;
        }
    }

    private static String checkArchive(FileAsset.Kind kind, File f) throws Exception {
        if (kind == FileAsset.Kind.ROOTFS) {
            try (InputStream in = new GzipCompressorInputStream(new BufferedInputStream(new FileInputStream(f))); TarArchiveInputStream tar = new TarArchiveInputStream(in)) {
                int entries=0; while(tar.getNextTarEntry()!=null){entries++; if(entries>200000)break;}
                return entries>0?null:"Ubuntu rootfs archive خالی است";
            }
        }
        if (kind == FileAsset.Kind.NODE) {
            try (InputStream in = new XZCompressorInputStream(new BufferedInputStream(new FileInputStream(f))); TarArchiveInputStream tar = new TarArchiveInputStream(in)) {
                int entries=0; while(tar.getNextTarEntry()!=null){entries++; if(entries>200000)break;}
                return entries>0?null:"Node.js archive خالی است";
            }
        }
        try (ArArchiveInputStream ar = new ArArchiveInputStream(new BufferedInputStream(new FileInputStream(f)))) {
            return ar.getNextArEntry()!=null?null:"Debian package خالی است";
        }
    }

    public static String sha256(File f) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        try (FileInputStream in = new FileInputStream(f)) {
            byte[] b = new byte[1024 * 1024]; int n;
            while ((n = in.read(b)) != -1) md.update(b, 0, n);
        }
        byte[] d = md.digest(); StringBuilder s = new StringBuilder(64);
        for (byte x : d) s.append(String.format(Locale.US, "%02x", x & 0xff));
        return s.toString();
    }
}
