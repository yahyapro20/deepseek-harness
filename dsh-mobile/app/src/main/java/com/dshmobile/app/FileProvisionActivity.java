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
        LinearLayout root = new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL); root.setBackgroundColor(Ui.bgSoft(this));
        ScrollView scroll = new ScrollView(this); scroll.setFillViewport(true);
        content = new LinearLayout(this); content.setOrientation(LinearLayout.VERTICAL); int pad=dp(18); content.setPadding(pad,pad,pad,pad);

        LinearLayout hero = panel();
        TextView eyebrow = text("DEEPSEEK HARNESS", 12, Ui.PRIMARY, true); hero.addView(eyebrow);
        hero.addView(text("محیط اجرا را آماده کنیم", 27, Ui.text(this), true), margin(6));
        hero.addView(text("Ubuntu 22.04، proot، کتابخانه‌های لازم و Node.js روی همین گوشی آماده می‌شوند.",14,Ui.textSecondary(this),false),margin(6));

        LinearLayout summary = new LinearLayout(this); summary.setGravity(Gravity.CENTER_VERTICAL); summary.setPadding(0,dp(18),0,0);
        LinearLayout summaryText = new LinearLayout(this); summaryText.setOrientation(LinearLayout.VERTICAL);
        overall=text("در حال بررسی…",16,Ui.text(this),true); summaryText.addView(overall);
        overallSub=text("",12,Ui.textSecondary(this),false); summaryText.addView(overallSub,margin(3));
        summary.addView(summaryText,new LinearLayout.LayoutParams(0,-2,1));
        TextView check=text("✓",26,Ui.PRIMARY,true); summary.addView(check);
        hero.addView(summary);
        content.addView(hero);

        LinearLayout controls=panel();
        mainAction=primary("آماده‌سازی سریع"); mainAction.setOnClickListener(v->fastSetup()); controls.addView(mainAction);
        pauseResume=outline("توقف همه"); pauseResume.setOnClickListener(v->togglePause()); controls.addView(pauseResume,margin(8));
        LinearLayout tools=new LinearLayout(this); tools.setGravity(Gravity.CENTER_VERTICAL);
        Button advancedBtn=outline("حالت پیشرفته"); advancedBtn.setOnClickListener(v->{advanced=!advanced; advancedBtn.setText(advanced?"حالت سریع":"حالت پیشرفته"); refreshAll(false);});
        tools.addView(advancedBtn,new LinearLayout.LayoutParams(0,-2,1));
        Button pack=outline("Bootstrap Pack"); pack.setOnClickListener(v->packDialog()); tools.addView(pack,new LinearLayout.LayoutParams(0,-2,1));
        controls.addView(tools,margin(8));
        content.addView(controls,margin(12));

        LinearLayout readiness=panel();
        readiness.addView(text("وضعیت دستگاه",14,Ui.text(this),true));
        network=text("اتصال: در حال بررسی…",12,Ui.textSecondary(this),false); readiness.addView(network,margin(6));
        storage=text("فضای موردنیاز: در حال محاسبه…",12,Ui.textSecondary(this),false); readiness.addView(storage,margin(3));
        content.addView(readiness,margin(12));

        content.addView(text("اجزای محیط",17,Ui.text(this),true),margin(22));
        for(FileAsset a:assets) content.addView(buildCard(a),margin(10));
        scroll.addView(content,new ScrollView.LayoutParams(-1,-2)); root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));

        LinearLayout footer=new LinearLayout(this); footer.setOrientation(LinearLayout.VERTICAL); footer.setPadding(dp(16),dp(10),dp(16),dp(16)); footer.setBackgroundColor(Ui.bg(this));
        Button start=primary("شروع نصب"); start.setOnClickListener(v->{if(allReady())verifyAllThenStart();}); footer.addView(start); root.addView(footer);
        setContentView(root);
    }

    private LinearLayout buildCard(FileAsset a) {
        CardHolder h=new CardHolder(); h.card=panel(); holders.put(a.kind,h);
        LinearLayout head=new LinearLayout(this); head.setGravity(Gravity.CENTER_VERTICAL);
        TextView name=text(a.kind.displayName,16,Ui.text(this),true); head.addView(name,new LinearLayout.LayoutParams(0,-2,1));
        TextView badge=text("",11,Ui.PRIMARY,true); head.addView(badge); h.card.addView(head);
        h.card.addView(text(a.kind.purpose,12,Ui.textSecondary(this),false),margin(6));
        h.status=text("",13,Ui.textSecondary(this),false); h.card.addView(h.status,margin(10));
        h.meta=text("",12,Ui.textSecondary(this),false); h.card.addView(h.meta,margin(4));
        h.progress=new ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal); h.progress.setMax(100); h.progress.setVisibility(View.GONE); h.card.addView(h.progress,margin(9));
        h.speed=text("",12,Ui.textSecondary(this),false); h.card.addView(h.speed,margin(4));
        h.actionRow=new LinearLayout(this); h.actionRow.setOrientation(LinearLayout.HORIZONTAL); h.card.addView(h.actionRow,margin(9));
        render(a); return h.card;
    }

    private void render(FileAsset a) {
        CardHolder h=holders.get(a.kind); if(h==null)return;
        ProvisionStore st=ProvisionStore.of(this); ProvisionStore.Status s=st.status(a.kind);
        if(a.destFile(dlDir).isFile() && s!=ProvisionStore.Status.READY) { a.state=FileAsset.State.READY_DOWNLOADED; }
        h.actionRow.removeAllViews(); h.progress.setVisibility(View.GONE); h.speed.setText("");
        String badge="";
        long total=st.total(a.kind); long done=st.downloaded(a.kind); long speed=st.speed(a.kind); long eta=st.eta(a.kind);
        if(total<=0) total=remoteSizes.containsKey(a.kind)?remoteSizes.get(a.kind):0;
        if(a.destFile(dlDir).isFile()) { total=a.destFile(dlDir).length(); done=total; }
        h.meta.setText(total>0?"حجم واقعی: "+formatBytes(total):"حجم واقعی: در حال شناسایی…");

        switch(s) {
            case DOWNLOADING: badge="در حال دانلود"; h.status.setText("دانلود در حال انجام است"); h.progress.setVisibility(View.VISIBLE); h.progress.setProgress(total>0?(int)Math.min(100,done*100/total):0); h.speed.setText(formatBytes(speed)+"/s"+(eta>0?"  •  "+formatEta(eta)+" باقی مانده":"")); add(h,"توقف",v->stopKind(a.kind)); break;
            case PAUSED: badge="مکث"; h.status.setText(st.error(a.kind).isEmpty()?"متوقف شده؛ ادامه از همان نقطه ممکن است":st.error(a.kind)); if(done>0){h.progress.setVisibility(View.VISIBLE);h.progress.setProgress(total>0?(int)Math.min(100,done*100/total):0);} add(h,"ادامه",v->startKinds(a.kind)); add(h,"Mirror",v->mirrorDialog(a.kind)); break;
            case VERIFYING: badge="بررسی"; h.status.setText("در حال بررسی سلامت فایل…"); break;
            case READY: badge="آماده ✓"; h.status.setText("فایل سالم و آماده نصب است"); add(h,"Verify دوباره",v->verify(a)); if(advanced)add(h,"دانلود مجدد",v->redownload(a)); break;
            case FAILED: badge="نیاز به اقدام"; h.status.setText(st.error(a.kind).isEmpty()?"فایل خراب یا دانلود ناموفق است":st.error(a.kind)); h.status.setTextColor(Color.rgb(210,55,55)); add(h,"دانلود مجدد",v->redownload(a)); add(h,"Mirror",v->mirrorDialog(a.kind)); break;
            case CACHE: badge="Cache"; h.status.setText("یک نسخه از قبل روی گوشی پیدا شد"); add(h,"استفاده از Cache",v->useCache(a)); add(h,"دانلود تازه",v->redownload(a)); break;
            case QUEUED: badge="در صف"; h.status.setText("در صف دانلود پس‌زمینه"); break;
            default: badge="نیاز دارد"; h.status.setText("این فایل برای اجرای محیط لازم است"); add(h,"دانلود",v->startKinds(a.kind)); add(h,"انتخاب فایل",v->pickLocal(a));
        }
        if(advanced){ add(h,"Mirror",v->mirrorDialog(a.kind)); add(h,"URL سفارشی",v->customUrlDialog(a.kind)); add(h,"چرا لازم است؟",v->whyDialog(a.kind)); }
        if(a.state==FileAsset.State.FOUND_IN_CACHE && s==ProvisionStore.Status.NOT_READY) { add(h,"استفاده از Cache",v->useCache(a)); }
        if(h.card.getChildAt(0) instanceof LinearLayout) ((TextView)((LinearLayout)h.card.getChildAt(0)).getChildAt(1)).setText(badge);
        if(s!=ProvisionStore.Status.FAILED)h.status.setTextColor(Ui.textSecondary(this));
    }

    private void refreshAll(boolean initial){
        if(isFinishing())return; ProvisionStore st=ProvisionStore.of(this); int ready=0; int active=0; long download=0;
        for(FileAsset a:assets){syncState(a,st); if(st.status(a.kind)==ProvisionStore.Status.READY)ready++; if(st.status(a.kind)==ProvisionStore.Status.DOWNLOADING||st.status(a.kind)==ProvisionStore.Status.QUEUED)active++; if(st.status(a.kind)!=ProvisionStore.Status.READY){long n=st.total(a.kind);if(n<=0)n=remoteSizes.containsKey(a.kind)?remoteSizes.get(a.kind):0;download+=Math.max(0,n-st.downloaded(a.kind));} render(a);}
        overall.setText(ready+" از "+assets.size()+" جزء آماده است"); overallSub.setText(active>0?active+" مورد در حال آماده‌سازی در پس‌زمینه":"همه وضعیت‌ها آماده نمایش هستند");
        boolean readyAll=ready==assets.size(); mainAction.setText(readyAll?"ادامه و شروع نصب":"آماده‌سازی سریع"); mainAction.setOnClickListener(v->{if(readyAll)verifyAllThenStart();else fastSetup();});
        pauseResume.setText(active>0?"توقف همه":"ادامه همه");
        boolean net=hasNetwork(); network.setText(net?"✓ اتصال اینترنت برقرار است":"⚠ اینترنت قطع است؛ دانلودها بعد از اتصال دوباره ادامه می‌یابند"); network.setTextColor(net?Ui.textSecondary(this):Color.rgb(210,110,40));
        long free=dlDir.getUsableSpace(); long installExtra=estimateInstallExtra(); storage.setText("دانلود باقی‌مانده: "+formatBytes(download)+"  •  فضای آزاد: "+formatBytes(free)+"  •  فضای امن پیشنهادی: "+formatBytes(download+installExtra));
    }

    private void syncState(FileAsset a, ProvisionStore st){
        if(a.destFile(dlDir).isFile() && st.status(a.kind)!=ProvisionStore.Status.READY){ProvisionVerifier.Result r=ProvisionVerifier.verify(a.kind,a.destFile(dlDir),st.sha256(a.kind));if(r.ok){st.sha256(a.kind,r.sha256);st.status(a.kind,ProvisionStore.Status.READY);}else{st.status(a.kind,ProvisionStore.Status.FAILED);st.error(a.kind,r.message);}}
        if(st.status(a.kind)==ProvisionStore.Status.NOT_READY && a.state==FileAsset.State.FOUND_IN_CACHE)st.status(a.kind,ProvisionStore.Status.CACHE);
    }

    private void refreshRemoteSizes(){new Thread(()->{for(FileAsset a:assets){try{ProvisionMirrors.Mirror m=ProvisionMirrors.byId(ProvisionStore.of(this).mirror(a.kind));String custom=ProvisionStore.of(this).customUrl(a.kind); String u=(custom==null||custom.isEmpty())?ProvisionMirrors.resolveUrl(a.kind,m,Prefs.of(this)):custom; long n=ProvisionMetadata.contentLength(u);if(n>0){remoteSizes.put(a.kind,n);handler.post(()->refreshAll(false));}}catch(Exception ignored){}}}).start();}

    private void fastSetup(){List<FileAsset.Kind> q=new ArrayList<>();ProvisionStore st=ProvisionStore.of(this);for(FileAsset a:assets)if(st.status(a.kind)!=ProvisionStore.Status.READY)q.add(a.kind);if(q.isEmpty()){verifyAllThenStart();return;}startKinds(q.toArray(new FileAsset.Kind[0]));}
    private void startKinds(FileAsset.Kind... kinds){ProvisionStore st=ProvisionStore.of(this);StringBuilder q=new StringBuilder(st.queue());for(FileAsset.Kind k:kinds){if(q.indexOf(k.id)<0){if(q.length()>0)q.append(',');q.append(k.id);}st.status(k,ProvisionStore.Status.QUEUED);}st.setQueue(q.toString());Intent i=new Intent(this,BootstrapDownloadService.class);i.setAction(BootstrapDownloadService.ACTION_START);i.putExtra(BootstrapDownloadService.EXTRA_KINDS,q.toString());if(Build.VERSION.SDK_INT>=26)startForegroundService(i);else startService(i);refreshAll(false);}
    private void stopKind(FileAsset.Kind k){ProvisionStore st=ProvisionStore.of(this);st.status(k,ProvisionStore.Status.PAUSED);Intent i=new Intent(this,BootstrapDownloadService.class);i.setAction(BootstrapDownloadService.ACTION_PAUSE_ONE);i.putExtra(BootstrapDownloadService.EXTRA_KINDS,k.id);if(Build.VERSION.SDK_INT>=26)startService(i);else startService(i);refreshAll(false);}
    private void togglePause(){ProvisionStore st=ProvisionStore.of(this);boolean active=false;for(FileAsset a:assets){ProvisionStore.Status s=st.status(a.kind);if(s==ProvisionStore.Status.DOWNLOADING||s==ProvisionStore.Status.QUEUED){active=true;break;}}if(active){for(FileAsset a:assets){ProvisionStore.Status s=st.status(a.kind);if(s==ProvisionStore.Status.DOWNLOADING||s==ProvisionStore.Status.QUEUED)st.status(a.kind,ProvisionStore.Status.PAUSED);}Intent i=new Intent(this,BootstrapDownloadService.class);i.setAction(BootstrapDownloadService.ACTION_PAUSE);startService(i);}else{fastSetup();}refreshAll(false);}

    private void verify(FileAsset a){ProvisionStore st=ProvisionStore.of(this);st.status(a.kind,ProvisionStore.Status.VERIFYING);render(a);new Thread(()->{ProvisionVerifier.Result r=ProvisionVerifier.verify(a.kind,a.destFile(dlDir),st.sha256(a.kind));handler.post(()->{if(r.ok){st.sha256(a.kind,r.sha256);st.status(a.kind,ProvisionStore.Status.READY);Toast.makeText(this,"فایل سالم است ✓",Toast.LENGTH_SHORT).show();}else{st.status(a.kind,ProvisionStore.Status.FAILED);st.error(a.kind,r.message);new AlertDialog.Builder(this).setTitle("فایل معتبر نیست").setMessage(r.message+"\n\nپیشنهاد: دانلود مجدد از یک Mirror سالم.").setPositiveButton("دانلود مجدد",(d,w)->redownload(a)).setNegativeButton("بعداً",null).show();}refreshAll(false);});}).start();}
    private void redownload(FileAsset a){a.destFile(dlDir).delete();a.partFile(dlDir).delete();ProvisionStore.of(this).reset(a.kind);startKinds(a.kind);}
    private void useCache(FileAsset a){File cache=new File(new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),"dsh-shared"),"bootstrap-cache");File src=a.cacheFile(cache);new Thread(()->{try{java.nio.file.Files.copy(src.toPath(),a.destFile(dlDir).toPath(),java.nio.file.StandardCopyOption.REPLACE_EXISTING);handler.post(()->verify(a));}catch(Exception e){handler.post(()->Toast.makeText(this,"استفاده از Cache ناموفق بود: "+e.getMessage(),Toast.LENGTH_LONG).show());}}).start();}

    private void pickLocal(FileAsset a){Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT);i.addCategory(Intent.CATEGORY_OPENABLE);i.setType("*/*");startActivityForResult(i,REQ_PICK_BASE+a.kind.ordinal());}
    private void packDialog(){new AlertDialog.Builder(this).setTitle("Bootstrap Pack").setItems(new String[]{"ساخت Pack از فایل‌های آماده","وارد کردن Pack"},(d,w)->{if(w==0){Intent i=new Intent(Intent.ACTION_CREATE_DOCUMENT);i.setType("application/zip");i.putExtra(Intent.EXTRA_TITLE,"dsh-bootstrap-pack.zip");startActivityForResult(i,REQ_EXPORT_PACK);}else{Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT);i.addCategory(Intent.CATEGORY_OPENABLE);i.setType("application/zip");startActivityForResult(i,REQ_IMPORT_PACK);}}).show();}

    @Override protected void onActivityResult(int requestCode,int resultCode,Intent data){super.onActivityResult(requestCode,resultCode,data);if(resultCode!=RESULT_OK||data==null||data.getData()==null)return;if(requestCode==REQ_IMPORT_PACK){new Thread(()->{BootstrapPack.Result r=BootstrapPack.importPack(getContentResolver(),data.getData(),dlDir,assets);handler.post(()->{Toast.makeText(this,r.message,Toast.LENGTH_LONG).show();refreshAll(false);});}).start();return;}if(requestCode==REQ_EXPORT_PACK){new Thread(()->{try{BootstrapPack.exportPack(getContentResolver(),data.getData(),dlDir,assets);handler.post(()->Toast.makeText(this,"Bootstrap Pack ساخته شد ✓",Toast.LENGTH_LONG).show());}catch(Exception e){handler.post(()->Toast.makeText(this,"ساخت Pack ناموفق بود: "+e.getMessage(),Toast.LENGTH_LONG).show());}}).start();return;}int ord=requestCode-REQ_PICK_BASE;if(ord<0||ord>=FileAsset.Kind.values().length)return;FileAsset a=find(FileAsset.Kind.values()[ord]);if(a==null)return;new Thread(()->{try{File tmp=new File(dlDir,a.kind.fileName+".selecting");try(java.io.InputStream in=getContentResolver().openInputStream(data.getData());java.io.OutputStream out=new java.io.FileOutputStream(tmp)){if(in==null)throw new IllegalStateException("فایل قابل خواندن نیست");byte[]b=new byte[1024*1024];int n;while((n=in.read(b))!=-1)out.write(b,0,n);}java.nio.file.Files.move(tmp.toPath(),a.destFile(dlDir).toPath(),java.nio.file.StandardCopyOption.REPLACE_EXISTING);ProvisionStore.of(this).reset(a.kind);handler.post(()->verify(a));}catch(Exception e){handler.post(()->Toast.makeText(this,"انتخاب فایل ناموفق بود: "+e.getMessage(),Toast.LENGTH_LONG).show());}}).start();}

    private void mirrorDialog(FileAsset.Kind kind){ProvisionStore st=ProvisionStore.of(this);List<ProvisionMirrors.Mirror> ms=ProvisionMirrors.forAsset(kind);LinearLayout box=new LinearLayout(this);box.setOrientation(LinearLayout.VERTICAL);box.setPadding(dp(8),dp(4),dp(8),dp(2));TextView info=text("وضعیت Mirrorها با تست واقعی HTTP مشخص می‌شود. Mirror سبز در حال حاضر پاسخ می‌دهد؛ بهترین گزینه بر اساس latency پیشنهاد می‌شود.",12,Ui.textSecondary(this),false);box.addView(info);LinearLayout list=new LinearLayout(this);list.setOrientation(LinearLayout.VERTICAL);box.addView(list,margin(8));AlertDialog dialog=new AlertDialog.Builder(this).setTitle("انتخاب Mirror  •  "+kind.displayName).setView(box).setNegativeButton("بستن",null).create();for(ProvisionMirrors.Mirror m:ms)addMirrorRow(list,dialog,kind,m,st);dialog.show();}
    private void addMirrorRow(LinearLayout list,AlertDialog dialog,FileAsset.Kind kind,ProvisionMirrors.Mirror m,ProvisionStore st){LinearLayout row=new LinearLayout(this);row.setGravity(Gravity.CENTER_VERTICAL);row.setPadding(0,dp(8),0,dp(8));LinearLayout texts=new LinearLayout(this);texts.setOrientation(LinearLayout.VERTICAL);TextView name=text(m.name,14,Ui.text(this),true);TextView sub=text(m.host+"  •  در حال تست…",11,Ui.textSecondary(this),false);texts.addView(name);texts.addView(sub,margin(2));row.addView(texts,new LinearLayout.LayoutParams(0,-2,1));Button use=outline("انتخاب");use.setEnabled(false);use.setOnClickListener(v->{st.setMirror(kind,m.id);dialog.dismiss();refreshRemoteSizes();refreshAll(false);});row.addView(use);list.addView(row);new Thread(()->{ProvisionMirrors.Health h=ProvisionMirrors.check(m,kind);handler.post(()->{sub.setText(h.ok?"✓ در دسترس  •  "+h.latencyMs+" ms":"✕ در دسترس نیست  •  "+(h.error==null?"خطای شبکه":h.error));sub.setTextColor(h.ok?Ui.PRIMARY:Color.rgb(210,55,55));use.setEnabled(h.ok);if(h.ok&&m.id.equals(st.mirror(kind)))use.setText("✓ انتخاب شده");});}).start();}

    private void customUrlDialog(FileAsset.Kind k){
        ProvisionStore st=ProvisionStore.of(this); EditText e=new EditText(this); e.setSingleLine(true); e.setHint("https://..."); e.setText(st.customUrl(k));
        new AlertDialog.Builder(this).setTitle("URL سفارشی • "+k.displayName).setMessage("اگر یک لینک مستقیم و قابل اعتماد دارید، این منبع برای همین فایل استفاده می‌شود و Mirror انتخاب‌شده نادیده گرفته می‌شود.").setView(e).setPositiveButton("ذخیره",(d,w)->{st.setCustomUrl(k,e.getText().toString().trim());refreshRemoteSizes();}).setNeutralButton("پاک کردن",(d,w)->{st.setCustomUrl(k,"");refreshRemoteSizes();}).setNegativeButton("انصراف",null).show();
    }

    private void whyDialog(FileAsset.Kind k){new AlertDialog.Builder(this).setTitle("چرا این فایل لازم است؟").setMessage(k.purpose+"\n\nاین برنامه فقط برای دستگاه‌های ARM64/AArch64 ساخته شده و این جزء بخشی از زنجیره اجرای محیط لینوکسی مستقل برنامه است.").setPositiveButton("متوجه شدم",null).show();}
    private void verifyAllThenStart(){
        new Thread(()->{
            ProvisionStore st=ProvisionStore.of(this); FileAsset bad=null; ProvisionVerifier.Result badResult=null;
            for(FileAsset a:assets){ if(st.status(a.kind)!=ProvisionStore.Status.READY) continue; ProvisionVerifier.Result r=ProvisionVerifier.verify(a.kind,a.destFile(dlDir),st.sha256(a.kind)); if(!r.ok){bad=a;badResult=r;break;} st.sha256(a.kind,r.sha256); }
            FileAsset finalBad=bad; ProvisionVerifier.Result finalResult=badResult;
            handler.post(()->{ if(finalBad!=null){ st.status(finalBad.kind,ProvisionStore.Status.FAILED); st.error(finalBad.kind,finalResult.message); refreshAll(false); new AlertDialog.Builder(this).setTitle("فایل خراب یا ناقص است").setMessage(finalBad.kind.displayName+"\n\n"+finalResult.message).setPositiveButton("دانلود مجدد",(d,w)->redownload(finalBad)).setNegativeButton("انصراف",null).show(); } else startSetup(); });
        }).start();
    }
    private void startSetup(){startActivity(new Intent(this,SetupActivity.class));finish();}
    private boolean allReady(){ProvisionStore st=ProvisionStore.of(this);for(FileAsset a:assets)if(st.status(a.kind)!=ProvisionStore.Status.READY)return false;return true;}
    private FileAsset find(FileAsset.Kind k){for(FileAsset a:assets)if(a.kind==k)return a;return null;}
    private boolean hasNetwork(){try{ConnectivityManager cm=(ConnectivityManager)getSystemService(CONNECTIVITY_SERVICE);NetworkInfo n=cm.getActiveNetworkInfo();return n!=null&&n.isConnected();}catch(Exception e){return true;}}
    private long estimateInstallExtra(){long total=0;for(FileAsset a:assets){if(a.kind==FileAsset.Kind.ROOTFS)total+=120L*1024*1024;else if(a.kind==FileAsset.Kind.NODE)total+=120L*1024*1024;else total+=8L*1024*1024;}return total;}
    private String formatBytes(long n){if(n<0)return "—";if(n>=1073741824L)return String.format(java.util.Locale.US,"%.2f GB",n/1073741824.0);if(n>=1048576L)return String.format(java.util.Locale.US,"%.1f MB",n/1048576.0);if(n>=1024L)return String.format(java.util.Locale.US,"%.0f KB",n/1024.0);return n+" B";}
    private String formatEta(long sec){if(sec<60)return sec+" ثانیه";long m=sec/60;if(m<60)return m+" دقیقه";return (m/60)+" ساعت و "+(m%60)+" دقیقه";}
    private void add(CardHolder h,String s,View.OnClickListener l){Button b=outline(s);b.setOnClickListener(l);LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(0,-2,1);p.rightMargin=dp(6);h.actionRow.addView(b,p);}

    private LinearLayout panel(){LinearLayout v=new LinearLayout(this);v.setOrientation(LinearLayout.VERTICAL);GradientDrawable g=new GradientDrawable();g.setColor(Ui.bg(this));g.setCornerRadius(dp(18));g.setStroke(dp(1),Ui.border(this));v.setBackground(g);int p=dp(16);v.setPadding(p,p,p,p);return v;}
    private TextView text(String s,float size,int color,boolean bold){TextView v=new TextView(this);v.setText(s);v.setTextSize(size);v.setTextColor(color);if(bold)v.setTypeface(Typeface.DEFAULT,Typeface.BOLD);return v;}
    private Button primary(String s){Button b=Ui.primaryButton(this,s);return b;}
    private Button outline(String s){Button b=Ui.outlineButton(this,s);b.setTextSize(13);return b;}
    private LinearLayout.LayoutParams margin(int top){LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.topMargin=dp(top);return p;}
    private int dp(int n){return Ui.dp(this,n);}
}
