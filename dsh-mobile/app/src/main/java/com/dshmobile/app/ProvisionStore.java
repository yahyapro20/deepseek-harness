package com.dshmobile.app;

import android.content.Context;
import android.content.SharedPreferences;

/** Persistent state shared by FileProvisionActivity and the background download service. */
public final class ProvisionStore {
    public enum Status { NOT_READY, CACHE, QUEUED, DOWNLOADING, PAUSED, VERIFYING, READY, FAILED }

    private static final String PREF = "dsh_provision_state";
    private static final String QUEUE = "queue";
    private final SharedPreferences sp;

    private ProvisionStore(Context c) { sp = c.getApplicationContext().getSharedPreferences(PREF, Context.MODE_PRIVATE); }
    public static ProvisionStore of(Context c) { return new ProvisionStore(c); }

    private String p(FileAsset.Kind k, String key) { return k.id + "." + key; }
    public Status status(FileAsset.Kind k) { try { return Status.valueOf(sp.getString(p(k,"status"), Status.NOT_READY.name())); } catch (Exception e) { return Status.NOT_READY; } }
    public void status(FileAsset.Kind k, Status s) { sp.edit().putString(p(k,"status"), s.name()).apply(); }
    public long downloaded(FileAsset.Kind k) { return sp.getLong(p(k,"downloaded"), 0); }
    public long total(FileAsset.Kind k) { return sp.getLong(p(k,"total"), 0); }
    public long speed(FileAsset.Kind k) { return sp.getLong(p(k,"speed"), 0); }
    public long eta(FileAsset.Kind k) { return sp.getLong(p(k,"eta"), -1); }
    public String error(FileAsset.Kind k) { return sp.getString(p(k,"error"), ""); }
    public String mirror(FileAsset.Kind k) { return sp.getString(p(k,"mirror"), "ustc"); }
    public String sha256(FileAsset.Kind k) { return sp.getString(p(k,"sha256"), ""); }
    public String customUrl(FileAsset.Kind k) { return sp.getString(p(k,"url"), ""); }
    public void setMirror(FileAsset.Kind k, String id) { sp.edit().putString(p(k,"mirror"), id).apply(); }
    public void setCustomUrl(FileAsset.Kind k, String url) { sp.edit().putString(p(k,"url"), url).apply(); }
    public void updateProgress(FileAsset.Kind k, long done, long total, long speed, long eta) {
        sp.edit().putLong(p(k,"downloaded"), done).putLong(p(k,"total"), total).putLong(p(k,"speed"), speed).putLong(p(k,"eta"), eta).apply();
    }
    public void error(FileAsset.Kind k, String e) { sp.edit().putString(p(k,"error"), e == null ? "" : e).apply(); }
    public void sha256(FileAsset.Kind k, String hash) { sp.edit().putString(p(k,"sha256"), hash == null ? "" : hash).apply(); }

    public synchronized String queue() { return sp.getString(QUEUE, ""); }
    public synchronized void setQueue(String csv) { sp.edit().putString(QUEUE, csv == null ? "" : csv).apply(); }
    public synchronized void queue(FileAsset.Kind[] kinds) {
        StringBuilder b = new StringBuilder();
        for (FileAsset.Kind k : kinds) { if (b.length() > 0) b.append(','); b.append(k.id); }
        setQueue(b.toString());
    }
    public synchronized void clearQueue() { setQueue(""); }

    public void reset(FileAsset.Kind k) {
        sp.edit().remove(p(k,"downloaded")).remove(p(k,"total")).remove(p(k,"speed")).remove(p(k,"eta"))
                .remove(p(k,"error")).remove(p(k,"sha256")).putString(p(k,"status"), Status.NOT_READY.name()).apply();
    }
}
