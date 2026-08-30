package com.dshmobile.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/** Modern bootstrap/provisioning screen. Native Views only; no AndroidX/Kotlin. */
public class FileProvisionActivity extends Activity {
    private static final int REQ_PICK_BASE = 100;
    private static final int REQ_IMPORT_PACK = 900;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final List<FileAsset> assets = new ArrayList<>();
    private final Map<FileAsset.Kind, CardHolder> holders = new EnumMap<>(FileAsset.Kind.class);
    private final Map<FileAsset.Kind, ResumableDownloader> active = new EnumMap<>(FileAsset.Kind.class);
    private File dlDir, cacheDir;
    private LinearLayout list, footer;
    private TextView overall, storage;

    private static final class CardHolder {
        LinearLayout card, actions;
        TextView status, meta;
        ProgressBar progress;
    }

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        dlDir = new File(ProotRunner.baseDir(this), "dl"); dlDir.mkdirs();
        File downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
        cacheDir = new File(new File(downloads, "dsh-shared"), "bootstrap-cache");
        for (FileAsset.Kind k : FileAsset.Kind.values()) {
            if (alreadyExtracted(k)) continue;
            FileAsset a = new FileAsset(k);
            if (a.destFile(dlDir).isFile()) a.state = FileAsset.State.READY_DOWNLOADED;
            else if (a.cacheFile(cacheDir).isFile()) a.state = FileAsset.State.FOUND_IN_CACHE;
            assets.add(a);
        }
        if (assets.isEmpty()) { startActivity(new Intent(this, SetupActivity.class)); finish(); return; }
        buildUi();
    }

    private boolean alreadyExtracted(FileAsset.Kind k) {
        File root = ProotRunner.rootfsDir(this);
        switch (k) {
            case ROOTFS: return new File(root, "bin/bash").isFile();
            case PROOT: return ProotRunner.prootBin(this).isFile();
            case LIBTALLOC: return new File(ProotRunner.libDir(this), "libtalloc.so.2").isFile();
            case LIBSHMEM: return new File(ProotRunner.libDir(this), "libandroid-shmem.so").isFile();
            case NODE: return new File(root, "opt/node/bin/node").isFile();
            default: return false;
        }
    }

    private TextView tv(String s, float size, int color, boolean bold) {
        TextView v = new TextView(this); v.setText(s); v.setTextSize(size); v.setTextColor(color);
        if (bold) v.setTypeface(Typeface.DEFAULT, Typeface.BOLD); return v;
    }
    private LinearLayout box(int bg, int pad) {
        LinearLayout v = new LinearLayout(this); v.setOrientation(LinearLayout.VERTICAL);
        v.setBackgroundColor(bg); v.setPadding(dp(pad), dp(pad), dp(pad), dp(pad)); return v;
    }
    private int dp(int n) { return Ui.dp(this, n); }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL); root.setBackgroundColor(Ui.bgSoft(this));
        ScrollView scroll = new ScrollView(this); scroll.setFillViewport(true);
        list = new LinearLayout(this); list.setOrientation(LinearLayout.VERTICAL); list.setPadding(dp(18), dp(18), dp(18), dp(18));

        LinearLayout hero = box(Ui.bg(this), 18);
        TextView brand = tv("DeepSeek Harness", 13, Ui.PRIMARY, true); hero.addView(brand);
        hero.addView(tv("آماده‌سازی محیط اجرا", 27, Ui.text(this), true), lp(0, 6));
        hero.addView(tv("فایل‌های لازم را آماده می‌کنیم تا محیط Ubuntu و Harness روی گوشی اجرا شود.", 14, Ui.textSecondary(this), false), lp(0, 5));
        LinearLayout summary = new LinearLayout(this); summary.setGravity(Gravity.CENTER_VERTICAL); summary.setPadding(0, dp(16), 0, 0);
        overall = tv("در حال بررسی…", 14, Ui.text(this), true); summary.addView(overall, new LinearLayout.LayoutParams(0, -2, 1));
        Button advanced = ghost("پیشرفته"); advanced.setOnClickListener(v -> showAdvanced()); summary.addView(advanced, new LinearLayout.LayoutParams(-2, -2)); hero.addView(summary);
        list.addView(hero, lp(0, 0));

        LinearLayout actions = box(Ui.bg(this), 14);
        Button all = primary("دانلود همه"); all.setOnClickListener(v -> downloadAll()); actions.addView(all, lp(0, 0));
        Button importPack = ghost("وارد کردن Bootstrap Pack"); importPack.setOnClickListener(v -> importPack()); actions.addView(importPack, lp(0, 8));
        Button exportPack = ghost("ساخت Bootstrap Pack از فایل‌های آماده"); exportPack.setOnClickListener(v -> exportPack()); actions.addView(exportPack, lp(0, 8));
        list.addView(actions, lp(0, 12));

        storage = tv("فضای موردنیاز در حال محاسبه…", 13, Ui.textSecondary(this), false);
        list.addView(storage, lp(0, 12));
        list.addView(tv("اجزای محیط", 16, Ui.text(this), true), lp(0, 20));
        for (FileAsset a : assets) list.addView(buildCard(a), lp(0, 10));
        scroll.addView(list);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));

        footer = new LinearLayout(this); footer.setOrientation(LinearLayout.VERTICAL); footer.setPadding(dp(16), dp(10), dp(16), dp(16)); footer.setBackgroundColor(Ui.bg(this));
        Button start = primary("شروع نصب"); start.setOnClickListener(v -> { if (allReady()) { startActivity(new Intent(this, SetupActivity.class)); finish(); } });
        footer.addView(start); root.addView(footer, lp(0, 0));
        setContentView(root); refreshSummary();
    }

    private LinearLayout.LayoutParams lp(int top, int ignored) { LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2); p.topMargin = dp(top); return p; }
    private Button primary(String s) { Button b = Ui.primaryButton(this, s); return b; }
    private Button ghost(String s) { Button b = Ui.outlineButton(this, s); b.setTextSize(13); return b; }

    private LinearLayout buildCard(FileAsset a) {
        LinearLayout c = box(Ui.bg(this), 15); CardHolder h = new CardHolder(); h.card = c; holders.put(a.kind, h);
        LinearLayout head = new LinearLayout(this); head.setGravity(Gravity.CENTER_VERTICAL);
        head.addView(tv(a.kind.displayName, 16, Ui.text(this), true), new LinearLayout.LayoutParams(0, -2, 1));
        TextView badge = tv("", 12, Ui.PRIMARY, true); head.addView(badge); c.addView(head);
        c.addView(tv(a.kind.purpose, 13, Ui.textSecondary(this), false), lp(7, 0));
        h.status = tv("", 13, Ui.textSecondary(this), false); c.addView(h.status, lp(10, 0));
        h.meta = tv("", 12, Ui.textSecondary(this), false); c.addView(h.meta, lp(4, 0));
        h.progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal); h.progress.setMax(100); h.progress.setVisibility(View.GONE); c.addView(h.progress, lp(9, 0));
        h.actions = new LinearLayout(this); h.actions.setOrientation(LinearLayout.HORIZONTAL); c.addView(h.actions, lp(10, 0));
        render(a); return c;
    }

    private void render(FileAsset a) {
        CardHolder h = holders.get(a.kind); if (h == null) return; h.actions.removeAllViews(); h.progress.setVisibility(View.GONE);
        String badge = "";
        switch (a.state) {
            case NOT_READY: h.status.setText("نیاز به فایل دارد"); add(h, "دانلود", v -> startDownload(a)); add(h, "انتخاب فایل", v -> pickLocal(a)); break;
            case FOUND_IN_CACHE: h.status.setText("نسخه آماده در Cache پیدا شد"); add(h, "استفاده", v -> useCached(a)); add(h, "دانلود جدید", v -> startDownload(a)); badge="CACHE"; break;
            case DOWNLOADING: h.status.setText("در حال دانلود  •  " + a.progressPercent() + "%"); h.progress.setVisibility(View.VISIBLE); h.progress.setProgress(a.progressPercent()); add(h, "توقف", v -> pause(a)); badge="DOWNLOADING"; break;
            case PAUSED_ERROR: h.status.setText("متوقف شد  •  " + safe(a.lastError)); h.progress.setVisibility(View.VISIBLE); h.progress.setProgress(a.progressPercent()); add(h, "ادامه", v -> startDownload(a)); add(h, "آدرس سفارشی", v -> customUrl(a)); break;
            case READY_LOCAL: h.status.setText("فایل محلی آماده است ✓"); add(h, "تغییر فایل", v -> pickLocal(a)); badge="READY"; break;
            case READY_DOWNLOADED: h.status.setText("دانلود کامل و آماده بررسی است ✓"); add(h, "Verify", v -> verify(a)); add(h, "دوباره", v -> { a.destFile(dlDir).delete(); a.state=FileAsset.State.NOT_READY; render(a); refreshSummary(); }); badge="READY"; break;
            case FAILED: h.status.setText("خطا  •  " + safe(a.lastError)); add(h, "تلاش مجدد", v -> startDownload(a)); add(h, "انتخاب فایل", v -> pickLocal(a)); badge="ERROR"; break;
        }
        h.status.setTextColor(a.state == FileAsset.State.FAILED ? Color.rgb(210,50,50) : Ui.textSecondary(this));
        h.meta.setText(meta(a));
        ((TextView)((LinearLayout)h.card.getChildAt(0)).getChildAt(1)).setText(badge);
    }

    private String meta(FileAsset a) { long n = a.totalBytes > 0 ? a.totalBytes : (a.destFile(dlDir).isFile() ? a.destFile(dlDir).length() : 0); return n > 0 ? format(n) : "حجم: در حال شناسایی…"; }
    private String format(long n) { if (n >= 1073741824L) return String.format(java.util.Locale.US, "حجم: %.2f GB", n/1073741824.0); return String.format(java.util.Locale.US, "حجم: %.1f MB", n/1048576.0); }
    private String safe(String s) { return s == null ? "خطای ناشناخته" : s; }
    private void add(CardHolder h, String s, View.OnClickListener l) { Button b=ghost(s); LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(0,-2,1); p.rightMargin=dp(7); b.setOnClickListener(l); h.actions.addView(b,p); }

    private void refreshSummary() {
        int ready=0; long bytes=0;
        for(FileAsset a:assets){ if(a.isReady())ready++; long n=a.totalBytes; if(n<=0 && a.destFile(dlDir).isFile())n=a.destFile(dlDir).length(); bytes+=Math.max(0,n); }
        overall.setText(ready+" از "+assets.size()+" جزء آماده  •  "+(ready==assets.size()?"آماده نصب":"نیاز به آماده‌سازی"));
        storage.setText(bytes>0?"حجم شناخته‌شده: "+format(bytes)+"  •  فضای اضافی برای استخراج و نصب نیز لازم است":"فضای موردنیاز: در حال شناسایی اندازه فایل‌ها");
        if(footer!=null && footer.getChildCount()>0){ Button b=(Button)footer.getChildAt(0); b.setEnabled(allReady()); b.setAlpha(allReady()?1f:.45f); }
    }
    private boolean allReady(){for(FileAsset a:assets)if(!a.isReady())return false;return true;}

    private void downloadAll(){ for(FileAsset a:assets) if(!a.isReady() && a.state!=FileAsset.State.DOWNLOADING) startDownload(a); }
    private void pauseAll(){ for(FileAsset a:assets) pause(a); }
    private void pause(FileAsset a){ ResumableDownloader d=active.get(a.kind); if(d!=null)d.cancel(); a.state=FileAsset.State.PAUSED_ERROR; a.lastError="توقف توسط کاربر؛ ادامه از همان نقطه ممکن است"; render(a); refreshSummary(); }

    private void startDownload(FileAsset a){ a.state=FileAsset.State.DOWNLOADING; render(a); new Thread(()->{
        try{ String url=resolveUrl(a); ResumableDownloader d=new ResumableDownloader(); active.put(a.kind,d); d.start(url,a.partFile(dlDir),a.destFile(dlDir),new ResumableDownloader.Listener(){
            public void onProgress(long done,long total){handler.post(()->{a.downloadedBytes=done;a.totalBytes=total;if(a.state==FileAsset.State.DOWNLOADING)render(a);refreshSummary();});}
            public void onError(Exception e){handler.post(()->{a.state=FileAsset.State.PAUSED_ERROR;a.lastError=e.getMessage();render(a);refreshSummary();});}
            public void onComplete(){handler.post(()->{a.state=FileAsset.State.READY_DOWNLOADED;render(a);refreshSummary();});}
        }); }catch(Exception e){handler.post(()->{a.state=FileAsset.State.PAUSED_ERROR;a.lastError=e.getMessage();render(a);refreshSummary();});}
    },"download-"+a.kind.id).start(); }

    private String resolveUrl(FileAsset a)throws IOException{ if(a.customUrl!=null&&!a.customUrl.isEmpty())return a.customUrl; Prefs p=Prefs.of(this); switch(a.kind){case ROOTFS:return p.getRootfsUrl();case NODE:return BootstrapInstaller.resolveNodeUrl(p);case PROOT:return BootstrapInstaller.resolveTermuxDeb(BootstrapInstaller.PROOT_POOL,"proot_");case LIBTALLOC:return BootstrapInstaller.resolveTermuxDeb(BootstrapInstaller.LIBTALLOC_POOL,"libtalloc_");case LIBSHMEM:return BootstrapInstaller.resolveTermuxDeb(BootstrapInstaller.LIBANDROID_SHMEM_POOL,"libandroid-shmem_");default:throw new IOException("نوع فایل ناشناخته");} }

    private void pickLocal(FileAsset a){Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT);i.addCategory(Intent.CATEGORY_OPENABLE);i.setType("*/*");startActivityForResult(i,REQ_PICK_BASE+a.kind.ordinal());}
    @Override protected void onActivityResult(int r,int result,Intent data){super.onActivityResult(r,result,data);if(result!=RESULT_OK||data==null||data.getData()==null)return;if(r==REQ_IMPORT_PACK){importZip(data.getData());return;}int ord=r-REQ_PICK_BASE;if(ord<0||ord>=FileAsset.Kind.values().length)return;FileAsset a=find(FileAsset.Kind.values()[ord]);new Thread(()->copy(a,data.getData())).start();}
    private void copy(FileAsset a,Uri uri){File tmp=new File(dlDir,a.kind.fileName+".selecting");try(InputStream in=getContentResolver().openInputStream(uri);OutputStream out=new FileOutputStream(tmp)){byte[]b=new byte[65536];int n;while((n=in.read(b))!=-1)out.write(b,0,n);Files.move(tmp.toPath(),a.destFile(dlDir).toPath(),java.nio.file.StandardCopyOption.REPLACE_EXISTING);handler.post(()->{a.state=FileAsset.State.READY_LOCAL;a.totalBytes=a.destFile(dlDir).length();render(a);refreshSummary();});}catch(Exception e){tmp.delete();handler.post(()->{a.state=FileAsset.State.FAILED;a.lastError="کپی فایل ممکن نشد: "+e.getMessage();render(a);});}}
    private void useCached(FileAsset a){new Thread(()->{try{Files.copy(a.cacheFile(cacheDir).toPath(),a.destFile(dlDir).toPath(),java.nio.file.StandardCopyOption.REPLACE_EXISTING);a.totalBytes=a.destFile(dlDir).length();handler.post(()->{a.state=FileAsset.State.READY_DOWNLOADED;render(a);refreshSummary();});}catch(Exception e){handler.post(()->{a.state=FileAsset.State.FAILED;a.lastError=e.getMessage();render(a);});}}).start();}
    private FileAsset find(FileAsset.Kind k){for(FileAsset a:assets)if(a.kind==k)return a;return null;}

    private void verify(FileAsset a){new Thread(()->{boolean ok=false;String err=null;try{long n=a.destFile(dlDir).length();if(n<1024)throw new IOException("فایل خالی یا ناقص است");try(FileInputStream in=new FileInputStream(a.destFile(dlDir))){byte[] h=new byte[8];int r=in.read(h);if(r<2)throw new IOException("header ناقص است");}ok=true;}catch(Exception e){err=e.getMessage();}boolean good=ok;handler.post(()->{if(good){Toast.makeText(this,"فایل سالم به نظر می‌رسد ✓",Toast.LENGTH_SHORT).show();}else{a.state=FileAsset.State.FAILED;a.lastError="Verify ناموفق: "+err;render(a);refreshSummary();new AlertDialog.Builder(this).setTitle("فایل خراب یا ناقص است").setMessage("این فایل اعتبارسنجی اولیه را رد کرد. پیشنهاد می‌شود دوباره دانلود شود.").setPositiveButton("دانلود مجدد",(d,w)->{a.destFile(dlDir).delete();a.state=FileAsset.State.NOT_READY;startDownload(a);}).setNegativeButton("بعداً",null).show();}});}).start();}

    private void customUrl(FileAsset a){EditText e=new EditText(this);e.setHint("https://...");if(a.customUrl!=null)e.setText(a.customUrl);new AlertDialog.Builder(this).setTitle("منبع سفارشی").setMessage("برای این فایل یک URL مستقیم وارد کنید.").setView(e).setPositiveButton("ذخیره و دانلود",(d,w)->{a.customUrl=e.getText().toString().trim();startDownload(a);}).setNegativeButton("انصراف",null).show();}

    private void showAdvanced(){new AlertDialog.Builder(this).setTitle("Advanced Setup").setMessage("حالت پیشرفته برای کنترل منبع فایل‌ها و عملیات Bootstrap است. Mirrorها در این بخش قابل مدیریت‌اند؛ منبع سفارشی هر فایل نیز از کارت همان فایل در دسترس است.").setMultiChoiceItems(new String[]{"نمایش گزینه‌های تخصصی روی کارت‌ها","دانلود موازی فایل‌ها","تلاش مجدد خودکار پس از خطای شبکه"},null,null).setPositiveButton("انجام شد",null).show();}

    private void exportPack(){new Thread(()->{try{File out=new File(getExternalFilesDir(null),"dsh-bootstrap-pack.zip");try(ZipOutputStream z=new ZipOutputStream(new FileOutputStream(out))){for(FileAsset a:assets){if(!a.isReady())continue;File f=a.destFile(dlDir);z.putNextEntry(new ZipEntry(a.kind.fileName));try(InputStream in=new FileInputStream(f)){byte[]b=new byte[65536];int n;while((n=in.read(b))!=-1)z.write(b,0,n);}z.closeEntry();}}handler.post(()->Toast.makeText(this,"Bootstrap Pack ساخته شد: "+out.getAbsolutePath(),Toast.LENGTH_LONG).show());}catch(Exception e){handler.post(()->Toast.makeText(this,"ساخت Pack ناموفق: "+e.getMessage(),Toast.LENGTH_LONG).show());}}).start();}
    private void importPack(){Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT);i.addCategory(Intent.CATEGORY_OPENABLE);i.setType("application/zip");startActivityForResult(i,REQ_IMPORT_PACK);}
    private void importZip(Uri uri){new Thread(()->{try(ZipInputStream z=new ZipInputStream(getContentResolver().openInputStream(uri))){ZipEntry e;while((e=z.getNextEntry())!=null){for(FileAsset a:assets)if(a.kind.fileName.equals(e.getName())){File f=a.destFile(dlDir);try(OutputStream out=new FileOutputStream(f)){byte[]b=new byte[65536];int n;while((n=z.read(b))!=-1)out.write(b,0,n);}a.state=FileAsset.State.READY_LOCAL;a.totalBytes=f.length();}z.closeEntry();}handler.post(()->{for(FileAsset a:assets)render(a);refreshSummary();Toast.makeText(this,"Bootstrap Pack وارد شد",Toast.LENGTH_SHORT).show();});}catch(Exception e){handler.post(()->Toast.makeText(this,"وارد کردن Pack ناموفق: "+e.getMessage(),Toast.LENGTH_LONG).show());}}).start();}
}