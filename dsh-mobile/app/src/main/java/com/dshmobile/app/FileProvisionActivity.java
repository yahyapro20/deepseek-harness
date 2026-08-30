package com.dshmobile.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Bootstrap setup center. Native Java Views only; intentionally independent from AndroidX/Compose.
 * The Activity is a UI/controller only. Long-running downloads live in BootstrapDownloadService.
 */
public final class FileProvisionActivity extends Activity {
    public static final String ACTION_STATE_CHANGED = "com.dshmobile.app.PROVISION_STATE_CHANGED";
    public static final String EXTRA_KIND = "kind";
    private static final int REQ_PICK_BASE = 100;
    private static final int REQ_IMPORT_PACK = 700;
    private static final int REQ_EXPORT_PACK = 701;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final List<FileAsset> assets = new ArrayList<>();
    private final Map<FileAsset.Kind, CardHolder> holders = new EnumMap<>(FileAsset.Kind.class);
    private final Map<FileAsset.Kind, Long> remoteSizes = new ConcurrentHashMap<>();
    private final Map<FileAsset.Kind, List<ProvisionMirrors.Health>> mirrorHealth = new EnumMap<>(FileAsset.Kind.class);
    private File dlDir;
    private LinearLayout content;
    private TextView overall, overallSub, storage, network;
    private DshReadinessView readinessRing;
    private Button mainAction, pauseResume;
    private boolean advanced;

    private static final class CardHolder {
        LinearLayout card, actionRow;
        TextView status, meta, speed;
        ProgressBar progress;
    }

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (ACTION_STATE_CHANGED.equals(intent.getAction())) refreshAll(false);
        }
    };

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        dlDir = new File(ProotRunner.baseDir(this), "dl"); dlDir.mkdirs();
        loadAssets();
        if (assets.isEmpty()) { startSetup(); return; }
        buildUi();
        refreshAll(true);
        refreshRemoteSizes();
    }

    @Override protected void onStart() {
        super.onStart();
        IntentFilter f = new IntentFilter(ACTION_STATE_CHANGED);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(receiver, f, Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(receiver, f);
        refreshAll(false);
    }

    @Override protected void onStop() { try { unregisterReceiver(receiver); } catch (Exception ignored) {} super.onStop(); }

    private void loadAssets() {
        for (FileAsset.Kind k : FileAsset.Kind.values()) {
            if (alreadyExtracted(k)) continue;
            FileAsset a = new FileAsset(k);
            File dest = a.destFile(dlDir);
            if (dest.isFile()) a.state = FileAsset.State.READY_DOWNLOADED;
            else if (a.partFile(dlDir).isFile()) a.state = FileAsset.State.PAUSED_ERROR;
            else {
                File cache = a.cacheFile(new File(new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "dsh-shared"), "bootstrap-cache"));
                if (cache.isFile()) a.state = FileAsset.State.FOUND_IN_CACHE;
            }
            assets.add(a);
        }
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

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Ui.bgSoft(this));

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setClipToPadding(false);
        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(18);
        content.setPadding(pad, dp(14), pad, dp(22));

        LinearLayout hero = surface(dp(24));
        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        TextView eyebrow = text("DEEPSEEK HARNESS", 11, Ui.PRIMARY, true);
        top.addView(eyebrow, new LinearLayout.LayoutParams(0, -2, 1));
        TextView shield = pill("ENVIRONMENT", Ui.PRIMARY, Ui.PRIMARY_SOFT);
        top.addView(shield);
        hero.addView(top);
        hero.addView(text("محیط اجرا آماده می‌شود", 28, Ui.text(this), true), margin(9));
        hero.addView(text("همه چیز برای اجرای محیط Linux و Harness روی این دستگاه بررسی و آماده می‌شود.", 14, Ui.textSecondary(this), false), margin(5));

        LinearLayout readiness = new LinearLayout(this);
        readiness.setGravity(Gravity.CENTER_VERTICAL);
        readiness.setPadding(0, dp(18), 0, 0);
        LinearLayout readinessText = new LinearLayout(this);
        readinessText.setOrientation(LinearLayout.VERTICAL);
        overall = text("در حال بررسی…", 17, Ui.text(this), true);
        overallSub = text("وضعیت اجزا در حال همگام‌سازی است", 12, Ui.textSecondary(this), false);
        readinessText.addView(overall);
        readinessText.addView(overallSub, margin(3));
        readiness.addView(readinessText, new LinearLayout.LayoutParams(0, -2, 1));
        readinessRing = new DshReadinessView(this);
        readiness.addView(readinessRing);
        hero.addView(readiness);
        content.addView(hero);

        LinearLayout action = surface(dp(20));
        mainAction = primary("آماده‌سازی سریع");
        mainAction.setTextSize(15);
        action.addView(mainAction);
        pauseResume = secondary("توقف همه");
        action.addView(pauseResume, margin(8));

        LinearLayout utilityRow = new LinearLayout(this);
        utilityRow.setGravity(Gravity.CENTER_VERTICAL);
        Button advancedBtn = tertiary("⚙  پیشرفته");
        advancedBtn.setOnClickListener(v -> {
            advanced = !advanced;
            advancedBtn.setText(advanced ? "‹  حالت سریع" : "⚙  پیشرفته");
            refreshAll(false);
        });
        utilityRow.addView(advancedBtn, new LinearLayout.LayoutParams(0, -2, 1));
        Button pack = tertiary("⇄  Bootstrap Pack");
        pack.setOnClickListener(v -> packDialog());
        utilityRow.addView(pack, new LinearLayout.LayoutParams(0, -2, 1));
        action.addView(utilityRow, margin(8));
        content.addView(action, margin(12));

        LinearLayout device = surface(dp(18));
        device.addView(text("آمادگی دستگاه", 14, Ui.text(this), true));
        LinearLayout statusRow = new LinearLayout(this);
        statusRow.setGravity(Gravity.CENTER_VERTICAL);
        TextView dot = circleIcon("", Ui.PRIMARY, Ui.PRIMARY_SOFT, 30);
        statusRow.addView(dot);
        network = text("اتصال اینترنت: در حال بررسی…", 12, Ui.textSecondary(this), false);
        statusRow.addView(network, marginStart(8));
        device.addView(statusRow, margin(9));
        storage = text("فضا: در حال محاسبه…", 12, Ui.textSecondary(this), false);
        device.addView(storage, margin(5));
        content.addView(device, margin(12));

        content.addView(text("اجزای محیط", 18, Ui.text(this), true), margin(24));
        content.addView(text("فایل‌های لازم را ببینید؛ جزئیات تخصصی فقط وقتی لازم باشد نمایش داده می‌شوند.", 12, Ui.textSecondary(this), false), margin(4));
        for (FileAsset a : assets) content.addView(buildCard(a), margin(10));

        scroll.addView(content, new ScrollView.LayoutParams(-1, -2));
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));

        LinearLayout footer = new LinearLayout(this);
        footer.setOrientation(LinearLayout.VERTICAL);
        footer.setPadding(dp(16), dp(9), dp(16), dp(16));
        footer.setBackgroundColor(Ui.bg(this));
        Button start = primary("بررسی نهایی و شروع نصب  →");
        start.setTextSize(15);
        start.setOnClickListener(v -> { if (allReady()) verifyAllThenStart(); });
        footer.addView(start);
        root.addView(footer);
        setContentView(root);
    }

    private LinearLayout buildCard(FileAsset a) {
        CardHolder h = new CardHolder();
        h.card = surface(dp(20));
        holders.put(a.kind, h);
        LinearLayout head = new LinearLayout(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        TextView icon = assetIcon(a.kind);
        head.addView(icon);
        LinearLayout names = new LinearLayout(this);
        names.setOrientation(LinearLayout.VERTICAL);
        names.addView(text(a.kind.displayName, 16, Ui.text(this), true));
        names.addView(text(a.kind.id, 11, Ui.textSecondary(this), false), margin(2));
        head.addView(names, marginStart(10));
        h.card.addView(head);
        h.status = text("", 13, Ui.textSecondary(this), true);
        h.card.addView(h.status, margin(12));
        h.meta = text("", 12, Ui.textSecondary(this), false);
        h.card.addView(h.meta, margin(4));
        h.speed = text("", 11, Ui.textSecondary(this), false);
        h.card.addView(h.speed, margin(3));
        h.progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        h.progress.setMax(1000);
        h.progress.setProgressDrawable(progressDrawable());
        h.progress.setVisibility(View.GONE);
        h.card.addView(h.progress, margin(8));
        h.actionRow = new LinearLayout(this);
        h.actionRow.setGravity(Gravity.CENTER_VERTICAL);
        h.card.addView(h.actionRow, margin(10));
        return h.card;
    }

    private void refreshAll(boolean first) {
        ProvisionStore st = ProvisionStore.of(this);
        int ready = 0;
        for (FileAsset a : assets) {
            if (st.status(a.kind) == ProvisionStore.Status.READY) ready++;
            render(a, st);
        }
        int total = assets.size();
        overall.setText(total == 0 ? "Inga filer" : ready + " av " + total + " komponenter klara");
        overallSub.setText(ready == total ? "Miljön är redo för installation" : "Filerna verifieras och förbereds säkert");
        readinessRing.setProgress(total == 0 ? 0 : (float) ready / total);
        network.setText("Internet: " + (hasNetwork() ? "ansluten ✓" : "offline — återupptas automatiskt"));
        long required = 0;
        for (FileAsset a : assets) required += Math.max(0, remoteSizes.containsKey(a.kind) ? remoteSizes.get(a.kind) : a.destFile(dlDir).length());
        required += estimateInstallExtra();
        long free = dlDir.getFreeSpace();
        storage.setText("فضا: " + formatBytes(free) + " آزاد  •  حدود " + formatBytes(required) + " لازم");
        boolean readyToInstall = allReady();
        mainAction.setText(readyToInstall ? "بررسی نهایی و نصب" : "آماده‌سازی سریع");
        mainAction.setOnClickListener(v -> { if (readyToInstall) verifyAllThenStart(); else fastSetup(); });
        pauseResume.setText(hasActiveDownloads() ? "توقف همه" : "ادامه دانلودها");
        pauseResume.setOnClickListener(v -> togglePause());
    }

    private boolean hasActiveDownloads(){ProvisionStore st=ProvisionStore.of(this);for(FileAsset a:assets){ProvisionStore.Status s=st.status(a.kind);if(s==ProvisionStore.Status.DOWNLOADING||s==ProvisionStore.Status.QUEUED)return true;}return false;}

    private void render(FileAsset a, ProvisionStore st) {
        CardHolder h = holders.get(a.kind); if (h == null) return;
        h.actionRow.removeAllViews();
        ProvisionStore.Status s = st.status(a.kind);
        long size = remoteSizes.containsKey(a.kind) ? remoteSizes.get(a.kind) : a.destFile(dlDir).length();
        h.meta.setText((size > 0 ? "حجم: " + formatBytes(size) : "حجم: در حال شناسایی…") + "  •  " + a.kind.purpose);
        h.speed.setText("");
        h.progress.setVisibility(View.GONE);
        switch (s) {
            case READY:
                h.status.setText("✓ آماده و Verify شده");
                add(h,"Verify مجدد",v->verify(a));
                add(h,"Mirror",v->mirrorDialog(a.kind));
                break;
            case DOWNLOADING:
                h.status.setText("در حال دانلود");
                h.progress.setVisibility(View.VISIBLE);
                h.progress.setProgress((int)Math.round(a.progressPercent()*10));
                h.speed.setText(a.speedBytesPerSecond>0 ? formatBytes(a.speedBytesPerSecond)+"/s  •  ETA " + formatEta(a.etaSeconds) : "در حال محاسبه سرعت…");
                add(h,"توقف",v->stopKind(a.kind));
                add(h,"Mirror",v->mirrorDialog(a.kind));
                break;
            case QUEUED:
                h.status.setText("در صف دانلود"); add(h,"توقف",v->stopKind(a.kind)); break;
            case VERIFYING:
                h.status.setText("در حال Verify…"); break;
            case FAILED:
                h.status.setText("✕ خطا: "+safe(st.error(a.kind)));
                add(h,"دانلود مجدد",v->redownload(a)); add(h,"انتخاب فایل",v->pickLocal(a)); break;
            case PAUSED:
                h.status.setText("⏸ متوقف شده — قابل ادامه"); add(h,"ادامه",v->startKinds(a.kind)); add(h,"Mirror",v->mirrorDialog(a.kind)); break;
            case CACHE:
                h.status.setText("نسخه آماده در Cache پیدا شد"); add(h,"استفاده",v->useCache(a)); add(h,"دانلود",v->startKinds(a.kind)); break;
            case NOT_READY:
            default:
                h.status.setText("نیاز به فایل دارد"); add(h,"دانلود",v->startKinds(a.kind)); add(h,"انتخاب فایل",v->pickLocal(a)); break;
        }
        if (advanced) { add(h,"چرا لازم است؟",v->whyDialog(a.kind)); add(h,"URL سفارشی",v->customUrlDialog(a.kind)); }
    }

    private String safe(String s){return s==null?"خطای ناشناخته":s;}
    private void add(CardHolder h,String s,View.OnClickListener l){Button b=tertiary(s);b.setOnClickListener(l);LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(0,-2,1);p.rightMargin=dp(6);h.actionRow.addView(b,p);}

    private void refreshRemoteSizes(){new Thread(()->{for(FileAsset a:assets){try{ProvisionMirrors.Mirror m=ProvisionMirrors.byId(ProvisionStore.of(this).mirror(a.kind));String custom=ProvisionStore.of(this).customUrl(a.kind);String u=(custom==null||custom.isEmpty())?ProvisionMirrors.resolveUrl(a.kind,m,Prefs.of(this)):custom;long n=ProvisionMetadata.contentLength(u);if(n>0){remoteSizes.put(a.kind,n);handler.post(()->refreshAll(false));}}catch(Exception ignored){}}}).start();}
    private void fastSetup(){List<FileAsset.Kind> q=new ArrayList<>();ProvisionStore st=ProvisionStore.of(this);for(FileAsset a:assets)if(st.status(a.kind)!=ProvisionStore.Status.READY)q.add(a.kind);if(q.isEmpty()){verifyAllThenStart();return;}startKinds(q.toArray(new FileAsset.Kind[0]));}
    private void startKinds(FileAsset.Kind... kinds){ProvisionStore st=ProvisionStore.of(this);StringBuilder q=new StringBuilder(st.queue());for(FileAsset.Kind k:kinds){if(q.indexOf(k.id)<0){if(q.length()>0)q.append(',');q.append(k.id);}st.status(k,ProvisionStore.Status.QUEUED);}st.setQueue(q.toString());Intent i=new Intent(this,BootstrapDownloadService.class);i.setAction(BootstrapDownloadService.ACTION_START);i.putExtra(BootstrapDownloadService.EXTRA_KINDS,q.toString());if(Build.VERSION.SDK_INT>=26)startForegroundService(i);else startService(i);refreshAll(false);}
    private void stopKind(FileAsset.Kind k){ProvisionStore st=ProvisionStore.of(this);st.status(k,ProvisionStore.Status.PAUSED);Intent i=new Intent(this,BootstrapDownloadService.class);i.setAction(BootstrapDownloadService.ACTION_PAUSE_ONE);i.putExtra(BootstrapDownloadService.EXTRA_KINDS,k.id);startService(i);refreshAll(false);}
    private void togglePause(){ProvisionStore st=ProvisionStore.of(this);boolean active=hasActiveDownloads();if(active){for(FileAsset a:assets){ProvisionStore.Status s=st.status(a.kind);if(s==ProvisionStore.Status.DOWNLOADING||s==ProvisionStore.Status.QUEUED)st.status(a.kind,ProvisionStore.Status.PAUSED);}Intent i=new Intent(this,BootstrapDownloadService.class);i.setAction(BootstrapDownloadService.ACTION_PAUSE);startService(i);}else fastSetup();refreshAll(false);}

    private void verify(FileAsset a){ProvisionStore st=ProvisionStore.of(this);st.status(a.kind,ProvisionStore.Status.VERIFYING);render(a,st);new Thread(()->{ProvisionVerifier.Result r=ProvisionVerifier.verify(a.kind,a.destFile(dlDir),st.sha256(a.kind));handler.post(()->{if(r.ok){st.sha256(a.kind,r.sha256);st.status(a.kind,ProvisionStore.Status.READY);Toast.makeText(this,"فایل سالم است ✓",Toast.LENGTH_SHORT).show();}else{st.status(a.kind,ProvisionStore.Status.FAILED);st.error(a.kind,r.message);new AlertDialog.Builder(this).setTitle("فایل معتبر نیست").setMessage(r.message+"\n\nپیشنهاد: دانلود مجدد از یک Mirror سالم.").setPositiveButton("دانلود مجدد",(d,w)->redownload(a)).setNegativeButton("بعداً",null).show();}refreshAll(false);});}).start();}
    private void redownload(FileAsset a){a.destFile(dlDir).delete();a.partFile(dlDir).delete();ProvisionStore.of(this).reset(a.kind);startKinds(a.kind);}
    private void useCache(FileAsset a){File cache=new File(new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),"dsh-shared"),"bootstrap-cache");File src=a.cacheFile(cache);new Thread(()->{try{java.nio.file.Files.copy(src.toPath(),a.destFile(dlDir).toPath(),java.nio.file.StandardCopyOption.REPLACE_EXISTING);handler.post(()->verify(a));}catch(Exception e){handler.post(()->Toast.makeText(this,"استفاده از Cache ناموفق بود: "+e.getMessage(),Toast.LENGTH_LONG).show());}}).start();}
    private void pickLocal(FileAsset a){Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT);i.addCategory(Intent.CATEGORY_OPENABLE);i.setType("*/*");startActivityForResult(i,REQ_PICK_BASE+a.kind.ordinal());}
    private void packDialog(){new AlertDialog.Builder(this).setTitle("Bootstrap Pack").setItems(new String[]{"ساخت Pack از فایل‌های آماده","وارد کردن Pack"},(d,w)->{if(w==0){Intent i=new Intent(Intent.ACTION_CREATE_DOCUMENT);i.setType("application/zip");i.putExtra(Intent.EXTRA_TITLE,"dsh-bootstrap-pack.zip");startActivityForResult(i,REQ_EXPORT_PACK);}else{Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT);i.addCategory(Intent.CATEGORY_OPENABLE);i.setType("application/zip");startActivityForResult(i,REQ_IMPORT_PACK);}}).show();}
    @Override protected void onActivityResult(int requestCode,int resultCode,Intent data){super.onActivityResult(requestCode,resultCode,data);if(resultCode!=RESULT_OK||data==null||data.getData()==null)return;if(requestCode==REQ_IMPORT_PACK){new Thread(()->{BootstrapPack.Result r=BootstrapPack.importPack(getContentResolver(),data.getData(),dlDir,assets);handler.post(()->{Toast.makeText(this,r.message,Toast.LENGTH_LONG).show();refreshAll(false);});}).start();return;}if(requestCode==REQ_EXPORT_PACK){new Thread(()->{try{BootstrapPack.exportPack(getContentResolver(),data.getData(),dlDir,assets);handler.post(()->Toast.makeText(this,"Bootstrap Pack ساخته شد ✓",Toast.LENGTH_LONG).show());}catch(Exception e){handler.post(()->Toast.makeText(this,"ساخت Pack ناموفق بود: "+e.getMessage(),Toast.LENGTH_LONG).show());}}).start();return;}int ord=requestCode-REQ_PICK_BASE;if(ord<0||ord>=FileAsset.Kind.values().length)return;FileAsset a=find(FileAsset.Kind.values()[ord]);if(a==null)return;new Thread(()->{try{File tmp=new File(dlDir,a.kind.fileName+".selecting");try(java.io.InputStream in=getContentResolver().openInputStream(data.getData());java.io.OutputStream out=new java.io.FileOutputStream(tmp)){if(in==null)throw new IllegalStateException("فایل قابل خواندن نیست");byte[]b=new byte[1024*1024];int n;while((n=in.read(b))!=-1)out.write(b,0,n);}java.nio.file.Files.move(tmp.toPath(),a.destFile(dlDir).toPath(),java.nio.file.StandardCopyOption.REPLACE_EXISTING);ProvisionStore.of(this).reset(a.kind);handler.post(()->verify(a));}catch(Exception e){handler.post(()->Toast.makeText(this,"انتخاب فایل ناموفق بود: "+e.getMessage(),Toast.LENGTH_LONG).show());}}).start();}
    private void mirrorDialog(FileAsset.Kind kind){ProvisionStore st=ProvisionStore.of(this);List<ProvisionMirrors.Mirror> ms=ProvisionMirrors.forAsset(kind);LinearLayout box=new LinearLayout(this);box.setOrientation(LinearLayout.VERTICAL);box.setPadding(dp(8),dp(4),dp(8),dp(2));TextView info=text("وضعیت Mirrorها با تست واقعی HTTP مشخص می‌شود. Mirror سبز در حال حاضر پاسخ می‌دهد؛ بهترین گزینه بر اساس latency پیشنهاد می‌شود.",12,Ui.textSecondary(this),false);box.addView(info);LinearLayout list=new LinearLayout(this);list.setOrientation(LinearLayout.VERTICAL);box.addView(list,margin(8));AlertDialog dialog=new AlertDialog.Builder(this).setTitle("انتخاب Mirror  •  "+kind.displayName).setView(box).setNegativeButton("بستن",null).create();for(ProvisionMirrors.Mirror m:ms)addMirrorRow(list,dialog,kind,m,st);dialog.show();}
    private void addMirrorRow(LinearLayout list,AlertDialog dialog,FileAsset.Kind kind,ProvisionMirrors.Mirror m,ProvisionStore st){LinearLayout row=new LinearLayout(this);row.setGravity(Gravity.CENTER_VERTICAL);row.setPadding(0,dp(8),0,dp(8));LinearLayout texts=new LinearLayout(this);texts.setOrientation(LinearLayout.VERTICAL);TextView name=text(m.name,14,Ui.text(this),true);TextView sub=text(m.host+"  •  در حال تست…",11,Ui.textSecondary(this),false);texts.addView(name);texts.addView(sub,margin(2));row.addView(texts,new LinearLayout.LayoutParams(0,-2,1));Button use=Ui.outlineButton(this,"انتخاب");use.setEnabled(false);use.setOnClickListener(v->{st.setMirror(kind,m.id);dialog.dismiss();refreshRemoteSizes();refreshAll(false);});row.addView(use);list.addView(row);new Thread(()->{ProvisionMirrors.Health h=ProvisionMirrors.check(this,m,kind);handler.post(()->{sub.setText(h.ok?"✓ در دسترس  •  "+h.latencyMs+" ms":"✕ در دسترس نیست  •  "+(h.error==null?"خطای شبکه":h.error));sub.setTextColor(h.ok?Ui.PRIMARY:Color.rgb(210,55,55));use.setEnabled(h.ok);if(h.ok&&m.id.equals(st.mirror(kind)))use.setText("✓ انتخاب شده");});}).start();}
    private void customUrlDialog(FileAsset.Kind k){ProvisionStore st=ProvisionStore.of(this);EditText e=new EditText(this);e.setSingleLine(true);e.setHint("https://...");e.setText(st.customUrl(k));new AlertDialog.Builder(this).setTitle("URL سفارشی • "+k.displayName).setMessage("اگر یک لینک مستقیم و قابل اعتماد دارید، این منبع برای همین فایل استفاده می‌شود و Mirror انتخاب‌شده نادیده گرفته می‌شود.").setView(e).setPositiveButton("ذخیره",(d,w)->{st.setCustomUrl(k,e.getText().toString().trim());refreshRemoteSizes();}).setNeutralButton("پاک کردن",(d,w)->{st.setCustomUrl(k,"");refreshRemoteSizes();}).setNegativeButton("انصراف",null).show();}
    private void whyDialog(FileAsset.Kind k){new AlertDialog.Builder(this).setTitle("چرا این فایل لازم است؟").setMessage(k.purpose+"\n\nاین برنامه فقط برای دستگاه‌های ARM64/AArch64 ساخته شده و این جزء بخشی از زنجیره اجرای محیط لینوکسی مستقل برنامه است.").setPositiveButton("متوجه شدم",null).show();}
    private void verifyAllThenStart(){new Thread(()->{ProvisionStore st=ProvisionStore.of(this);FileAsset bad=null;ProvisionVerifier.Result badResult=null;for(FileAsset a:assets){if(st.status(a.kind)!=ProvisionStore.Status.READY)continue;ProvisionVerifier.Result r=ProvisionVerifier.verify(a.kind,a.destFile(dlDir),st.sha256(a.kind));if(!r.ok){bad=a;badResult=r;break;}st.sha256(a.kind,r.sha256);}FileAsset finalBad=bad;ProvisionVerifier.Result finalResult=badResult;handler.post(()->{if(finalBad!=null){st.status(finalBad.kind,ProvisionStore.Status.FAILED);st.error(finalBad.kind,finalResult.message);refreshAll(false);new AlertDialog.Builder(this).setTitle("فایل خراب یا ناقص است").setMessage(finalBad.kind.displayName+"\n\n"+finalResult.message).setPositiveButton("دانلود مجدد",(d,w)->redownload(finalBad)).setNegativeButton("انصراف",null).show();}else startSetup();});}).start();}
    private void startSetup(){startActivity(new Intent(this,SetupActivity.class));finish();}
    private boolean allReady(){ProvisionStore st=ProvisionStore.of(this);for(FileAsset a:assets)if(st.status(a.kind)!=ProvisionStore.Status.READY)return false;return true;}
    private FileAsset find(FileAsset.Kind k){for(FileAsset a:assets)if(a.kind==k)return a;return null;}
    private boolean hasNetwork(){try{ConnectivityManager cm=(ConnectivityManager)getSystemService(CONNECTIVITY_SERVICE);NetworkInfo n=cm.getActiveNetworkInfo();return n!=null&&n.isConnected();}catch(Exception e){return true;}}
    private long estimateInstallExtra(){long total=0;for(FileAsset a:assets){if(a.kind==FileAsset.Kind.ROOTFS)total+=120L*1024*1024;else if(a.kind==FileAsset.Kind.NODE)total+=120L*1024*1024;else total+=8L*1024*1024;}return total;}
    private String formatBytes(long n){if(n<0)return "—";if(n>=1073741824L)return String.format(java.util.Locale.US,"%.2f GB",n/1073741824.0);if(n>=1048576L)return String.format(java.util.Locale.US,"%.1f MB",n/1048576.0);if(n>=1024L)return String.format(java.util.Locale.US,"%.0f KB",n/1024.0);return n+" B";}
    private String formatEta(long sec){if(sec<60)return sec+" ثانیه";long m=sec/60;if(m<60)return m+" دقیقه";return (m/60)+" ساعت و "+(m%60)+" دقیقه";}
    private LinearLayout surface(int radius){LinearLayout v=new LinearLayout(this);v.setOrientation(LinearLayout.VERTICAL);GradientDrawable g=new GradientDrawable();g.setColor(Ui.bg(this));g.setCornerRadius(radius);g.setStroke(Math.max(1,dp(1)),Ui.border(this));v.setBackground(g);v.setPadding(dp(17),dp(17),dp(17),dp(17));v.setElevation(dp(2));return v;}
    private TextView text(String s,float size,int color,boolean bold){TextView v=new TextView(this);v.setText(s);v.setTextSize(size);v.setTextColor(color);v.setIncludeFontPadding(false);if(bold)v.setTypeface(Typeface.DEFAULT,Typeface.BOLD);return v;}
    private TextView pill(String s,int fg,int bg){TextView v=text(s,10,fg,true);v.setGravity(Gravity.CENTER);v.setPadding(dp(10),dp(6),dp(10),dp(6));GradientDrawable g=new GradientDrawable();g.setColor(bg);g.setCornerRadius(dp(30));v.setBackground(g);return v;}
    private TextView circleIcon(String s,int fg,int bg,int sizeDp){TextView v=text(s,16,fg,true);v.setGravity(Gravity.CENTER);GradientDrawable g=new GradientDrawable();g.setColor(bg);g.setShape(GradientDrawable.OVAL);v.setBackground(g);v.setLayoutParams(new LinearLayout.LayoutParams(dp(sizeDp),dp(sizeDp)));return v;}
    private TextView assetIcon(FileAsset.Kind k){String glyph;switch(k){case ROOTFS:glyph="L";break;case PROOT:glyph=">_";break;case LIBTALLOC:glyph="◇";break;case LIBSHMEM:glyph="↔";break;case NODE:glyph="JS";break;default:glyph="•";}return circleIcon(glyph,Ui.PRIMARY,Ui.PRIMARY_SOFT,44);}
    private GradientDrawable progressDrawable(){GradientDrawable g=new GradientDrawable();g.setColor(Ui.PRIMARY);g.setCornerRadius(dp(20));return g;}
    private Button primary(String s){Button b=Ui.primaryButton(this,s);b.setAllCaps(false);b.setTextSize(15);b.setMinHeight(dp(52));b.setPadding(dp(14),0,dp(14),0);b.setStateListAnimator(null);return b;}
    private Button secondary(String s){Button b=Ui.outlineButton(this,s);b.setAllCaps(false);b.setTextSize(13);b.setMinHeight(dp(48));return b;}
    private Button tertiary(String s){Button b=Ui.outlineButton(this,s);b.setAllCaps(false);b.setTextSize(12);b.setMinHeight(dp(44));b.setPadding(dp(8),0,dp(8),0);return b;}
    private LinearLayout.LayoutParams margin(int top){LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.topMargin=dp(top);return p;}
    private LinearLayout.LayoutParams marginStart(int start){LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(0,-2,1f);p.leftMargin=dp(start);return p;}
    private int dp(int n){return Ui.dp(this,n);}
}