package com.dshmobile.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;

import java.io.File;
import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Foreground service: holds the proot container process to ensure background survival. */
public class HarnessService extends Service {

    public static final String ACTION_START = "com.dshmobile.app.action.START";
    public static final String ACTION_STOP = "com.dshmobile.app.action.STOP";
    private static final String CHANNEL_ID = "harness";
    private static final int NOTIF_ID = 1001;
    private static final int MAX_RESTART = 5;

    private static Process process;
    private static Process sshdProcess;
    private static boolean running;

    private ExecutorService executor;
    private PowerManager.WakeLock wakeLock;
    private volatile boolean wantRun;

    public static boolean isRunning() {
        return running && process != null && process.isAlive();
    }

    public static void startService(Context ctx) {
        Intent i = new Intent(ctx, HarnessService.class);
        i.setAction(ACTION_START);
        ctx.startForegroundService(i);
    }

    public static void stopService(Context ctx) {
        Intent i = new Intent(ctx, HarnessService.class);
        i.setAction(ACTION_STOP);
        ctx.startService(i);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        executor = Executors.newSingleThreadExecutor();
        createChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? ACTION_START : intent.getAction();
        if (ACTION_STOP.equals(action)) {
            wantRun = false;
            // Stopping must truly kill the container and all services (notification bar "Stop" = shutdown semantics).
            // Must never put into executor: runLoop permanently occupies the single thread (while loop blocks on
            // process.waitFor()), stop task would be queued behind it and never execute—
            // notification bar stop and settings page stop/restart would all fail. Execute stop in independent thread.
            new Thread(() -> {
                stopContainer();
                stopForeground(true);
                stopSelf();
            }, "dsh-harness-stop").start();
            return START_NOT_STICKY;
        }
        startForeground(NOTIF_ID, buildNotification("Starting container..."));
        acquireWakeLock();
        wantRun = true;
        if (!isRunning()) {
            executor.execute(this::runLoop);
        }
        return START_STICKY;
    }

    private void runLoop() {
        Prefs prefs = Prefs.of(this);
        int restarts = 0;
        while (wantRun) {
            File log = new File(ProotRunner.baseDir(this), "dsh-web.log");
            // Pre-start self-check for node-pty: dsh web inevitably crashes when pty.node is missing (plugin tree
            // failed to load), fix in-place before starting to avoid meaningless crash-restart loops
            if (NodePtyFixer.needsFix(ProotRunner.rootfsDir(this))) {
                updateNotification("Repairing node-pty native module...");
                boolean fixed = NodePtyFixer.fix(this, log);
                updateNotification(fixed
                        ? "node-pty repaired, starting..."
                        : "node-pty repair failed, check logs in settings");
            }
            try {
                updateNotification("DeepSeek Harness Running · Port " + prefs.getPort());
                startSshd(prefs, log);
                process = ProotRunner.startWeb(this, prefs.getPort(), log);
                running = true;
                int code = process.waitFor();
                running = false;
                if (!wantRun) break;
                restarts++;
                if (restarts > MAX_RESTART) {
                    updateNotification("Container exited multiple times, stopped (see logs)");
                    break;
                }
                updateNotification("Container exited (" + code + "), restarting in " + 3 + " seconds...");
                Thread.sleep(3000);
            } catch (IOException e) {
                running = false;
                updateNotification("Start failed: " + e.getMessage());
                break;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        running = false;
        releaseWakeLock();
        if (!wantRun) {
            stopForeground(true);
            stopSelf();
        }
    }

    /** Start sshd inside container: install via network if missing in old containers (failure does not affect Web service). */
    private void startSshd(Prefs prefs, File log) {
        BootstrapInstaller.ensureSshServerInstalled(this, log);
        if (!new File(ProotRunner.rootfsDir(this), "usr/sbin/sshd").isFile()) return;
        if (sshdProcess != null && sshdProcess.isAlive()) return;
        try {
            sshdProcess = ProotRunner.startSshd(this, prefs.getSshPort(), log);
        } catch (IOException e) {
            updateNotification("SSH start failed: " + e.getMessage());
        }
    }

    private void stopContainer() {
        // Set state to false first: settings page/button immediately reflects "stopped",
        // no need to wait for force-kill fallback (max 3s) to complete
        running = false;
        Process p = process;
        if (p != null) {
            p.destroy();
            try {
                // Bounded wait: cannot block indefinitely when container hangs (caller may be on main thread)
                if (!p.waitFor(3, java.util.concurrent.TimeUnit.SECONDS)) {
                    p.destroyForcibly();
                }
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
                p.destroyForcibly();
            }
        }
        Process s = sshdProcess;
        sshdProcess = null;
        if (s != null) {
            s.destroy();
            s.destroyForcibly();
        }
        running = false;
    }

    private void acquireWakeLock() {
        if (wakeLock == null) {
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "dshmobile:harness");
            wakeLock.setReferenceCounted(false);
        }
        if (!wakeLock.isHeld()) {
            wakeLock.acquire(24 * 60 * 60 * 1000L);
        }
    }

    private void releaseWakeLock() {
        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
        }
    }

    private void createChannel() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        NotificationChannel ch = new NotificationChannel(CHANNEL_ID, "Harness Service",
                NotificationManager.IMPORTANCE_LOW);
        ch.setDescription("DeepSeek Harness container running status");
        nm.createNotificationChannel(ch);
    }

    private Notification buildNotification(String text) {
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(this, 0, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Intent stop = new Intent(this, HarnessService.class);
        stop.setAction(ACTION_STOP);
        PendingIntent stopPi = PendingIntent.getService(this, 1, stop,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder b = Build.VERSION.SDK_INT >= 31
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this, CHANNEL_ID);
        return b.setContentTitle(getString(R.string.app_name))
                .setContentText(text)
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentIntent(pi)
                .addAction(new Notification.Action.Builder(null, "Stop", stopPi).build())
                .setOngoing(true)
                .build();
    }

    private void updateNotification(String text) {
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.notify(NOTIF_ID, buildNotification(text));
    }

    @Override
    public void onDestroy() {
        wantRun = false;
        stopContainer();
        releaseWakeLock();
        if (executor != null) executor.shutdownNow();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        // Last resort when swiping away task: try to restart service before process is killed.
        // Android 12+ may throw exceptions when starting foreground service from background (ForegroundServiceStartNotAllowed
        // etc.), just catch them; for ROMs like Honor/MagicOS that kill entire process on swipe-away, ultimately relies on user
        // enabling "Auto-start/Allow background activity" in system settings (settings page has entry).
        if (wantRun) {
            try {
                Intent restart = new Intent(this, HarnessService.class);
                restart.setAction(ACTION_START);
                startForegroundService(restart);
            } catch (Exception e) {
                // Background start restriction: cannot bypass without system-side authorization, rely on START_STICKY and user authorization as fallback
            }
        }
        super.onTaskRemoved(rootIntent);
    }
}
