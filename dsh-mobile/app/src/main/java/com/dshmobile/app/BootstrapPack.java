package com.dshmobile.app;

import android.content.ContentResolver;
import android.net.Uri;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/** Portable Bootstrap Pack with a manifest and SHA-256 verification. */
public final class BootstrapPack {
    public static final String MANIFEST = "dsh-bootstrap-manifest.txt";
    public static final String FORMAT = "DSH-BOOTSTRAP-PACK/1";

    public static final class Result {
        public final boolean ok; public final String message; public final int imported;
        Result(boolean ok, String message, int imported) { this.ok=ok; this.message=message; this.imported=imported; }
    }

    private BootstrapPack() {}

    public static void exportPack(ContentResolver resolver, Uri target, File dlDir, List<FileAsset> assets) throws Exception {
        OutputStream raw = resolver.openOutputStream(target);
        if (raw == null) throw new IllegalStateException("امکان نوشتن فایل مقصد وجود ندارد");
        try (OutputStream out = raw; ZipOutputStream zip = new ZipOutputStream(out)) {
            StringBuilder manifest = new StringBuilder();
            manifest.append(FORMAT).append('\n');
            manifest.append("version=1\n");
            for (FileAsset a : assets) {
                File f = a.destFile(dlDir);
                if (!f.isFile()) continue;
                String hash = ProvisionVerifier.sha256(f);
                manifest.append(a.kind.id).append('|').append(a.kind.fileName).append('|').append(f.length()).append('|').append(hash).append('\n');
            }
            putBytes(zip, MANIFEST, manifest.toString().getBytes(StandardCharsets.UTF_8));
            for (FileAsset a : assets) {
                File f = a.destFile(dlDir); if (!f.isFile()) continue;
                zip.putNextEntry(new ZipEntry(a.kind.fileName));
                try (InputStream in = new FileInputStream(f)) { copy(in, zip); }
                zip.closeEntry();
            }
        }
    }

    public static Result importPack(ContentResolver resolver, Uri source, File dlDir, List<FileAsset> assets) {
        File temp = new File(dlDir, ".bootstrap-import");
        if (!temp.exists() && !temp.mkdirs()) return new Result(false, "پوشه موقت ساخته نشد", 0);
        Map<String, File> extracted = new HashMap<>();
        InputStream raw = resolver.openInputStream(source);
        if (raw == null) return new Result(false, "فایل قابل خواندن نیست", 0);
        try (InputStream in = raw; ZipInputStream zip = new ZipInputStream(in)) {
            ZipEntry e; String manifest = null;
            while ((e = zip.getNextEntry()) != null) {
                if (e.isDirectory()) continue;
                if (MANIFEST.equals(e.getName())) { manifest = readText(zip); }
                else {
                    FileAsset a = findByName(assets, e.getName());
                    if (a != null) {
                        File f = new File(temp, e.getName());
                        try (OutputStream out = new FileOutputStream(f)) { copy(zip, out); }
                        extracted.put(a.kind.id, f);
                    }
                }
                zip.closeEntry();
            }
            if (manifest == null || !manifest.startsWith(FORMAT)) return new Result(false, "این فایل یک Bootstrap Pack معتبر نیست", 0);
            int count = 0;
            String[] lines = manifest.split("\\n");
            for (String line : lines) {
                if (!line.contains("|")) continue;
                String[] p = line.trim().split("\\|"); if (p.length != 4) continue;
                FileAsset a = findById(assets, p[0]); File f = extracted.get(p[0]);
                if (a == null || f == null) continue;
                long size = Long.parseLong(p[2]);
                if (f.length() != size) throw new IllegalStateException(a.kind.displayName + ": اندازه فایل با manifest یکی نیست");
                ProvisionVerifier.Result vr = ProvisionVerifier.verify(a.kind, f, p[3]);
                if (!vr.ok) throw new IllegalStateException(a.kind.displayName + ": " + vr.message);
                File dest = a.destFile(dlDir);
                if (!f.renameTo(dest)) {
                    java.nio.file.Files.move(f.toPath(), dest.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                }
                count++;
            }
            delete(temp);
            return new Result(true, "Bootstrap Pack با موفقیت بررسی و وارد شد", count);
        } catch (Exception e) {
            delete(temp); return new Result(false, "وارد کردن Pack ناموفق: " + e.getMessage(), 0);
        }
    }

    private static FileAsset findByName(List<FileAsset> assets, String n) { for (FileAsset a: assets) if (a.kind.fileName.equals(n)) return a; return null; }
    private static FileAsset findById(List<FileAsset> assets, String id) { for (FileAsset a: assets) if (a.kind.id.equals(id)) return a; return null; }
    private static void putBytes(ZipOutputStream z, String name, byte[] data) throws Exception { z.putNextEntry(new ZipEntry(name)); z.write(data); z.closeEntry(); }
    private static String readText(InputStream in) throws Exception { java.io.ByteArrayOutputStream o=new java.io.ByteArrayOutputStream(); copy(in,o); return o.toString("UTF-8"); }
    private static void copy(InputStream in, OutputStream out) throws Exception { byte[] b=new byte[1024*1024]; int n; while((n=in.read(b))!=-1) out.write(b,0,n); }
    private static void delete(File f) { if(!f.exists())return; if(f.isDirectory()){File[] c=f.listFiles();if(c!=null)for(File x:c)delete(x);}f.delete(); }
}
