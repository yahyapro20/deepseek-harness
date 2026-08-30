package com.dshmobile.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.Build;
import android.os.IBinder;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/** Persistent foreground downloader. Activity lifecycle is intentionally irrelevant to downloads. */
public final class BootstrapDownloadService extends Service {
    public static final String ACTION_START = "com.dshmobile.app.PROVISION_START";
    public static final String ACTION_PAUSE = "com.dshmobile.app.PROVISION_PAUSE";
    public static final String ACTION_RESUME = "com.dshmobile.app.PROVISION_RESUME";
    public static final String ACTION_PAUSE_ONE = "com.dshmobile.app.PROVISION_PAUSE_ONE";
    public static final String EXTRA_KINDS = "kinds";
    private static final int NOTIFICATION_ID = 4102;
    private static final String CHANNEL = "bootstrap_downloads";

    private ProvisionStore store;
    private volatile boolean stopRequested;
    private volatile ResumableDownloader current;
    private volatile FileAsset.Kind currentKind;
    private Thread worker;
    private File dlDir;

    @Override public void onCreate() {
        super.onCreate();
        store = ProvisionStore.of(this);
        dlDir = new File(ProotRunner.baseDir(this), "dl"); dlDir.mkdirs();
        createChannel();
        startForeground(NOTIFICATION_ID, notification("آماده‌سازی محیط", "دانلودها در پس‌زمینه ادامه پیدا می‌کنند", 0));
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null) {
            String action = intent.getAction();
            if (ACTION_PAUSE.equals(action)) { stopRequested = true; if (current != null) current.cancel(); store.clearQueue(); stopSelf(); return START_NOT_STICKY; }
            if (ACTION_PAUSE_ONE.equals(action)) { String id=intent.getStringExtra(EXTRA_KINDS); if(id!=null){ for(FileAsset.Kind k:FileAsset.Kind.values()) if(k.id.equals(id)) store.status(k, ProvisionStore.Status.PAUSED); } if(current!=null && currentKind!=null && currentKind.id.equals(id)) current.cancel(); removeQueuedId(id); if(readQueue().isEmpty()) { stopSelf(); return START_NOT_STICKY; } }
            if (ACTION_START.equals(action)) {
                String ids = intent.getStringExtra(EXTRA_KINDS);
                if (ids != null) store.setQueue(ids);
                stopRequested = false;
            } else if (ACTION_RESUME.equals(action)) stopRequested = false;
        }
        if (worker == null || !worker.isAlive()) {
            worker = new Thread(this::runQueue, "dsh-bootstrap-download"); worker.start();
        }
        return START_STICKY;
    }

    private void runQueue() {
        try {
            while (!stopRequested) {
                List<FileAsset.Kind> queue = readQueue();
                if (queue.isEmpty()) break;
                boolean progressed = false;
                for (FileAsset.Kind k : queue) {
                    if (stopRequested) break;
                    File dest = new File(dlDir, k.fileName);
                    File part = new File(dlDir, k.fileName + ".part");
                    ProvisionStore.Status s = store.status(k);
                    if (dest.isFile()) {
                        store.status(k, ProvisionStore.Status.VERIFYING); broadcast(k);
                        ProvisionVerifier.Result vr = ProvisionVerifier.verify(k, dest, "");
                        if (vr.ok) { store.sha256(k, vr.sha256); store.status(k, ProvisionStore.Status.READY); removeFromQueue(k); broadcast(k); continue; }
                        dest.delete(); store.error(k, vr.message);
                    }
                    if (s == ProvisionStore.Status.PAUSED && !stopRequested) store.status(k, ProvisionStore.Status.QUEUED);
                    store.status(k, ProvisionStore.Status.DOWNLOADING); store.error(k, ""); broadcast(k);
                    boolean ok = downloadWithRetry(k, part, dest);
                    if (!ok) { if (stopRequested) break; if (store.status(k) != ProvisionStore.Status.PAUSED) { store.status(k, ProvisionStore.Status.FAILED); removeFromQueue(k); } broadcast(k); }
                    else { progressed = true; }
                }
                if (!progressed && !stopRequested) {
                    // If every remaining item is waiting for the network, keep the service alive.
                    sleep(5000);
                }
            }
        } finally {
            if (!stopRequested) stopSelf();
        }
    }

    private boolean downloadWithRetry(FileAsset.Kind kind, File part, File dest) {
        int failures = 0;
        while (!stopRequested) {
            if (!hasNetwork()) { store.status(kind, ProvisionStore.Status.PAUSED); store.error(kind, "اینترنت قطع است؛ پس از اتصال دوباره ادامه می‌دهیم"); broadcast(kind); sleep(3000); continue; }
            try {
                ProvisionMirrors.Mirror mirror = ProvisionMirrors.byId(store.mirror(kind));
                String custom = store.customUrl(kind);
                String url = custom == null || custom.isEmpty() ? ProvisionMirrors.resolveUrl(kind, mirror, Prefs.of(this)) : custom;
                currentKind = kind;
                current = new ResumableDownloader();
                final long[] lastBytes = { part.isFile() ? part.length() : 0 };
                final long[] lastTime = { System.currentTimeMillis() };
                final long[] speed = { 0 };
                final Exception[] downloadError = { null };
                current.start(url, part, dest, new ResumableDownloader.Listener() {
                    @Override public void onProgress(long done, long total) {
                        long now = System.currentTimeMillis(); long dt = now - lastTime[0];
                        if (dt >= 250) { speed[0] = Math.max(0, (done-lastBytes[0])*1000L/dt); lastBytes[0]=done; lastTime[0]=now; }
                        long eta = speed[0] > 0 && total > done ? (total-done)/speed[0] : -1;
                        store.updateProgress(kind, done, total, speed[0], eta); broadcast(kind);
                        updateNotification(kind, done, total, speed[0]);
                    }
                    @Override public void onError(Exception e) { downloadError[0] = e; store.error(kind, e.getMessage()); }
                    @Override public void onComplete() { }
                });
                current = null;
                currentKind = null;
                if (downloadError[0] != null) { throw downloadError[0]; }
                ProvisionVerifier.Result vr = ProvisionVerifier.verify(kind, dest, "");
                if (!vr.ok) { store.status(kind, ProvisionStore.Status.FAILED); store.error(kind, vr.message); broadcast(kind); return false; }
                store.updateProgress(kind, vr.size, vr.size, 0, 0); store.sha256(kind, vr.sha256); store.status(kind, ProvisionStore.Status.READY); store.error(kind, ""); broadcast(kind); return true;
            } catch (Exception e) {
                current = null; currentKind = null; failures++;
                store.status(kind, ProvisionStore.Status.PAUSED);
                store.error(kind, e.getMessage()); broadcast(kind);
                if (e.getMessage()!=null && (e.getMessage().contains("Cancelled") || e.getMessage().contains("Canceled")) && store.status(kind)==ProvisionStore.Status.PAUSED) return false;
                if (!hasNetwork() || isNetworkError(e)) { sleep(Math.min(15000, 2000L * failures)); continue; }
                if (failures >= 3) return false;
                sleep(3000);
            }
        }
        return false;
    }

    private boolean isNetworkError(Exception e) {
        String m=e.getMessage(); if(m==null)return true; return m.contains("HTTP 5")||m.contains("timeout")||m.contains("timed out")||m.contains("Connection")||m.contains("network")||m.contains("لغو");
    }
    private boolean hasNetwork() { try { ConnectivityManager cm=(ConnectivityManager)getSystemService(CONNECTIVITY_SERVICE); NetworkInfo n=cm.getActiveNetworkInfo(); return n!=null&&n.isConnected(); } catch(Exception e){return true;} }
    private List<FileAsset.Kind> readQueue() { List<FileAsset.Kind> out=new ArrayList<>();String q=store.queue();if(q==null||q.isEmpty())return out;for(String id:q.split(","))for(FileAsset.Kind k:FileAsset.Kind.values())if(k.id.equals(id))out.add(k);return out; }
    private void removeQueuedId(String id) { if(id==null)return; List<FileAsset.Kind> q=readQueue(); StringBuilder b=new StringBuilder(); for(FileAsset.Kind k:q) if(!k.id.equals(id)){if(b.length()>0)b.append(',');b.append(k.id);} store.setQueue(b.toString()); }
    private void removeFromQueue(FileAsset.Kind kind) { List<FileAsset.Kind> q=readQueue();StringBuilder b=new StringBuilder();for(FileAsset.Kind k:q)if(k!=kind){if(b.length()>0)b.append(',');b.append(k.id);}store.setQueue(b.toString()); }
    private void broadcast(FileAsset.Kind k) { Intent i=new Intent(FileProvisionActivity.ACTION_STATE_CHANGED);i.setPackage(getPackageName());i.putExtra(FileProvisionActivity.EXTRA_KIND,k.id);sendBroadcast(i); }
    private void updateNotification(FileAsset.Kind k,long done,long total,long speed){int p=total>0?(int)Math.min(100,done*100/total):0;NotificationManager nm=(NotificationManager)getSystemService(NOTIFICATION_SERVICE);nm.notify(NOTIFICATION_ID,notification(k.displayName,"در حال دانلود  •  "+p+"%  •  "+format(speed)+"/s",p));}
    private Notification notification(String title,String text,int progress){Intent i=new Intent(this,FileProvisionActivity.class);PendingIntent pi=PendingIntent.getActivity(this,0,i,Build.VERSION.SDK_INT>=23?PendingIntent.FLAG_IMMUTABLE:0);Notification.Builder b=Build.VERSION.SDK_INT>=26?new Notification.Builder(this,CHANNEL):new Notification.Builder(this);b.setSmallIcon(com.dshmobile.app.R.drawable.ic_launcher).setContentTitle(title).setContentText(text).setContentIntent(pi).setOngoing(true);if(progress>0)b.setProgress(100,progress,false);return b.build();}
    private void createChannel(){if(Build.VERSION.SDK_INT>=26){NotificationManager nm=(NotificationManager)getSystemService(NOTIFICATION_SERVICE);nm.createNotificationChannel(new NotificationChannel(CHANNEL,"Bootstrap downloads",NotificationManager.IMPORTANCE_LOW));}}
    private String format(long b){if(b<=0)return "0 B";if(b>=1048576)return String.format(java.util.Locale.US,"%.1f MB",b/1048576.0);if(b>=1024)return String.format(java.util.Locale.US,"%.0f KB",b/1024.0);return b+" B";}
    private void sleep(long ms){try{Thread.sleep(ms);}catch(InterruptedException e){Thread.currentThread().interrupt();}}
    @Override public void onDestroy(){stopRequested=true;if(current!=null)current.cancel(); currentKind=null; super.onDestroy();}
    @Override public IBinder onBind(Intent intent){return null;}
}
