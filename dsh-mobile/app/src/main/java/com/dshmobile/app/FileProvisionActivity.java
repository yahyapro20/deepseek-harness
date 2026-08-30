package com.dshmobile.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ColorStateList;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.IOException;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * صفحهٔ جدید قبل از SetupActivity: توضیح می‌دهد چرا این فایل‌ها لازم‌اند،
 * بعد برای هر فایل یک کارت جدا نشان می‌دهد که کاربر می‌تواند «از دستگاه
 * انتخاب کند» یا «دانلود کند» (با Resume واقعی). وقتی همهٔ فایل‌ها آماده
 * شدند، دکمهٔ پایینی SetupActivity را باز می‌کند که از این به بعد فقط
 * استخراج/نصب کانتینر را انجام می‌دهد (چون دانلودها از قبل در dl/ هستند).
 */
public class FileProvisionActivity extends Activity {

    private static final int REQ_PICK_BASE = 100; // + ordinal آسِت

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final List<FileAsset> assets = new ArrayList<>();
    private final Map<FileAsset.Kind, CardHolder> holders = new EnumMap<>(FileAsset.Kind.class);
    private final Map<FileAsset.Kind, ResumableDownloader> activeDownloaders = new EnumMap<>(FileAsset.Kind.class);

    private File dlDir;
    private File cacheDir;
    private LinearLayout continueBtnHolder;

    /** رفرنس‌های زندهٔ ویجت‌های هر کارت، برای آپدیت بدون بازساخت کل UI. */
    private static final class CardHolder {
        LinearLayout card;
        TextView statusText;
        ProgressBar progressBar;
        LinearLayout buttonRow;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        dlDir = new File(ProotRunner.baseDir(this), "dl");
        //noinspection ResultOfMethodCallIgnored
        dlDir.mkdirs();
        cacheDir = new File(ProotRunner.sharedDir(), "bootstrap-cache");

        for (FileAsset.Kind k : FileAsset.Kind.values()) {
            // اگر مرحلهٔ نهایی همان asset از قبل روی دستگاه استخراج/نصب شده،
            // اصلاً کارتش را نشان نده (سازگار با منطق فعلی BootstrapInstaller
            // که هر مرحله را جدا skip می‌کند).
            if (alreadyExtracted(k)) continue;
            FileAsset a = new FileAsset(k);
            File dest = a.destFile(dlDir);
            if (dest.isFile()) {
                a.state = FileAsset.State.READY_DOWNLOADED; // از اجرای قبلی باقی مانده
            } else if (a.cacheFile(cacheDir).isFile()) {
                a.state = FileAsset.State.FOUND_IN_CACHE;
            }
            assets.add(a);
        }

        if (assets.isEmpty()) {
            // همه‌چیز از قبل آماده است؛ مستقیم برو مرحلهٔ نصب کانتینر
            startActivity(new Intent(this, SetupActivity.class));
            finish();
            return;
        }

        buildUi();
    }

    /** آیا این asset از قبل به‌صورت نهایی نصب/استخراج شده (منطق مشابه BootstrapInstaller.run()). */
    private boolean alreadyExtracted(FileAsset.Kind k) {
        File rootfs = ProotRunner.rootfsDir(this);
        switch (k) {
            case ROOTFS:
                return new File(rootfs, "bin/bash").isFile();
            case PROOT:
                return ProotRunner.prootBin(this).isFile();
            case LIBTALLOC:
                return new File(ProotRunner.libDir(this), "libtalloc.so.2").isFile();
            case LIBSHMEM:
                return new File(ProotRunner.libDir(this), "libandroid-shmem.so").isFile();
            case NODE:
                return new File(rootfs, "opt/node/bin/node").isFile();
            default:
                return false;
        }
    }

    // ---------------------------------------------------------------- UI

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Ui.bgSoft(this));

        ScrollView scroll = new ScrollView(this);
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        int pad = Ui.dp(this, 20);
        list.setPadding(pad, pad, pad, pad);

        // --- کارت توضیح (Onboarding) ---
        LinearLayout intro = Ui.card(this);
        intro.addView(Ui.title(this, "آماده‌سازی فایل‌های موتور آفلاین"));
        TextView introBody = Ui.hint(this,
                "برای اجرای DeepSeek Harness روی گوشی، به چند فایل پایه نیاز داریم "
                        + "(سیستم لینوکسی سبک، Node.js و ابزارهای وابسته). می‌توانید هرکدام را "
                        + "خودتان از حافظهٔ گوشی انتخاب کنید (اگر قبلاً جایی دارید) یا از اینترنت "
                        + "دانلود کنید — می‌توانید این دو حالت را برای فایل‌های مختلف مخلوط کنید.");
        LinearLayout.LayoutParams ibp = Ui.matchWrap();
        ibp.topMargin = Ui.dp(this, 8);
        intro.addView(introBody, ibp);
        list.addView(intro, Ui.matchWrap());

        // --- یک کارت به‌ازای هر فایل ---
        for (FileAsset a : assets) {
            LinearLayout.LayoutParams clp = Ui.matchWrap();
            clp.topMargin = Ui.dp(this, 12);
            list.addView(buildCard(a), clp);
        }

        scroll.addView(list, Ui.matchWrap());
        LinearLayout.LayoutParams slp = Ui.matchWrap();
        slp.weight = 1;
        slp.height = 0;
        root.addView(scroll, slp);

        // --- نوار پایین: دکمهٔ ادامه ---
        continueBtnHolder = new LinearLayout(this);
        continueBtnHolder.setOrientation(LinearLayout.VERTICAL);
        int cp = Ui.dp(this, 16);
        continueBtnHolder.setPadding(cp, cp, cp, cp);
        continueBtnHolder.setBackgroundColor(Ui.bg(this));
        root.addView(continueBtnHolder, Ui.matchWrap());
        refreshContinueButton();

        setContentView(root);
    }

    private LinearLayout buildCard(FileAsset a) {
        LinearLayout card = Ui.card(this);
        CardHolder h = new CardHolder();
        h.card = card;
        holders.put(a.kind, h);

        TextView name = Ui.body(this, a.kind.displayName);
        name.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        card.addView(name);

        TextView purpose = Ui.hint(this, a.kind.purpose);
        LinearLayout.LayoutParams plp = Ui.matchWrap();
        plp.topMargin = Ui.dp(this, 4);
        card.addView(purpose, plp);

        h.statusText = Ui.hint(this, "");
        h.statusText.setTextColor(Ui.PRIMARY);
        LinearLayout.LayoutParams stlp = Ui.matchWrap();
        stlp.topMargin = Ui.dp(this, 8);
        card.addView(h.statusText, stlp);

        h.progressBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        h.progressBar.setMax(100);
        h.progressBar.setProgressTintList(ColorStateList.valueOf(Ui.PRIMARY));
        h.progressBar.setVisibility(View.GONE);
        LinearLayout.LayoutParams pblp = Ui.matchWrap();
        pblp.topMargin = Ui.dp(this, 8);
        card.addView(h.progressBar, pblp);

        h.buttonRow = new LinearLayout(this);
        h.buttonRow.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams brlp = Ui.matchWrap();
        brlp.topMargin = Ui.dp(this, 10);
        card.addView(h.buttonRow, brlp);

        renderCardState(a);
        return card;
    }

    /** بازسازی متن وضعیت + پراگرس‌بار + دکمه‌های هر کارت بر اساس state فعلی. */
    private void renderCardState(FileAsset a) {
        CardHolder h = holders.get(a.kind);
        h.buttonRow.removeAllViews();
        h.progressBar.setVisibility(View.GONE);

        switch (a.state) {
            case NOT_READY:
                h.statusText.setText("آماده نیست");
                h.statusText.setTextColor(Ui.textSecondary(this));
                addBtn(h, "انتخاب از دستگاه", v -> pickLocal(a));
                addBtn(h, "دانلود", v -> startDownload(a));
                break;

            case FOUND_IN_CACHE:
                h.statusText.setText("نسخهٔ قبلی روی گوشی پیدا شد");
                addBtn(h, "استفاده از نسخهٔ قبلی", v -> useCached(a));
                addBtn(h, "دانلود دوباره", v -> startDownload(a));
                break;

            case DOWNLOADING:
                h.statusText.setText("در حال دانلود… " + a.progressPercent() + "%");
                h.progressBar.setVisibility(View.VISIBLE);
                h.progressBar.setProgress(a.progressPercent());
                addBtn(h, "توقف", v -> pauseDownload(a));
                break;

            case PAUSED_ERROR:
                h.statusText.setText("متوقف شد: " + (a.lastError != null ? a.lastError : "خطای ناشناخته"));
                h.statusText.setTextColor(0xFFE54545);
                h.progressBar.setVisibility(View.VISIBLE);
                h.progressBar.setProgress(a.progressPercent());
                addBtn(h, "ادامهٔ دانلود", v -> startDownload(a));
                addBtn(h, "آدرس سفارشی", v -> promptCustomUrl(a));
                break;

            case READY_LOCAL:
                h.statusText.setText("از حافظهٔ گوشی انتخاب شد ✓");
                h.statusText.setTextColor(Ui.PRIMARY);
                addBtn(h, "تغییر فایل", v -> pickLocal(a));
                break;

            case READY_DOWNLOADED:
                h.statusText.setText("دانلود کامل شد ✓");
                h.statusText.setTextColor(Ui.PRIMARY);
                addBtn(h, "ذخیره برای دفعهٔ بعد", v -> exportToCache(a));
                addBtn(h, "دانلود دوباره", v -> {
                    a.destFile(dlDir).delete();
                    a.state = FileAsset.State.NOT_READY;
                    renderCardState(a);
                    refreshContinueButton();
                });
                break;

            case FAILED:
                h.statusText.setText("خطا: " + a.lastError);
                h.statusText.setTextColor(0xFFE54545);
                addBtn(h, "تلاش مجدد", v -> startDownload(a));
                addBtn(h, "انتخاب از دستگاه", v -> pickLocal(a));
                break;
        }
        refreshContinueButton();
    }

    private void addBtn(CardHolder h, String text, View.OnClickListener l) {
        android.widget.Button b = Ui.outlineButton(this, text);
        b.setTextSize(13);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        lp.marginEnd = Ui.dp(this, 8);
        b.setOnClickListener(l);
        h.buttonRow.addView(b, lp);
    }

    private void refreshContinueButton() {
        if (continueBtnHolder == null) return;
        continueBtnHolder.removeAllViews();
        boolean allReady = true;
        for (FileAsset a : assets) {
            if (!a.isReady()) {
                allReady = false;
                break;
            }
        }
        android.widget.Button btn = Ui.primaryButton(this,
                allReady ? "شروع نصب" : "منتظر تکمیل فایل‌های بالا…");
        btn.setEnabled(allReady);
        btn.setAlpha(allReady ? 1f : 0.5f);
        btn.setOnClickListener(v -> {
            startActivity(new Intent(this, SetupActivity.class));
            finish();
        });
        continueBtnHolder.addView(btn, Ui.matchWrap());
    }

    // ------------------------------------------------------ انتخاب محلی

    private void pickLocal(FileAsset a) {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        // شناسایی این‌که کدام asset درخواست شده از طریق requestCode
        startActivityForResult(i, REQ_PICK_BASE + a.kind.ordinal());
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != Activity.RESULT_OK || data == null || data.getData() == null) return;
        int ord = requestCode - REQ_PICK_BASE;
        if (ord < 0 || ord >= FileAsset.Kind.values().length) return;
        FileAsset.Kind kind = FileAsset.Kind.values()[ord];
        FileAsset a = findAsset(kind);
        if (a == null) return;
        Uri uri = data.getData();
        new Thread(() -> copyLocalFile(a, uri)).start();
    }

    private void copyLocalFile(FileAsset a, Uri uri) {
        File dest = a.destFile(dlDir);
        try (InputStream in = getContentResolver().openInputStream(uri);
             OutputStream out = new FileOutputStream(dest)) {
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
            handler.post(() -> {
                a.state = FileAsset.State.READY_LOCAL;
                renderCardState(a);
                Toast.makeText(this, a.kind.displayName + " انتخاب شد", Toast.LENGTH_SHORT).show();
            });
        } catch (Exception e) {
            handler.post(() -> {
                a.state = FileAsset.State.FAILED;
                a.lastError = "کپی فایل انتخابی ممکن نشد: " + e.getMessage();
                renderCardState(a);
            });
        }
    }

    // ----------------------------------------------------------- کش عمومی

    private void useCached(FileAsset a) {
        new Thread(() -> {
            try {
                Files.copy(a.cacheFile(cacheDir).toPath(), a.destFile(dlDir).toPath());
                handler.post(() -> {
                    a.state = FileAsset.State.READY_DOWNLOADED;
                    renderCardState(a);
                });
            } catch (Exception e) {
                handler.post(() -> {
                    a.state = FileAsset.State.FAILED;
                    a.lastError = "کپی از کش ممکن نشد: " + e.getMessage();
                    renderCardState(a);
                });
            }
        }).start();
    }

    private void exportToCache(FileAsset a) {
        new Thread(() -> {
            try {
                //noinspection ResultOfMethodCallIgnored
                cacheDir.mkdirs();
                File src = a.destFile(dlDir);
                File dst = a.cacheFile(cacheDir);
                Files.copy(src.toPath(), dst.toPath(),
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                handler.post(() -> Toast.makeText(this,
                        "ذخیره شد؛ دفعهٔ بعد نیازی به دانلود دوباره نیست", Toast.LENGTH_LONG).show());
            } catch (Exception e) {
                handler.post(() -> Toast.makeText(this,
                        "ذخیره ممکن نشد: " + e.getMessage(), Toast.LENGTH_LONG).show());
            }
        }).start();
    }

    // ------------------------------------------------------------- دانلود

    private void startDownload(FileAsset a) {
        a.state = FileAsset.State.DOWNLOADING;
        renderCardState(a);
        new Thread(() -> {
            String url;
            try {
                url = resolveUrl(a);
            } catch (IOException e) {
                handler.post(() -> {
                    a.state = FileAsset.State.PAUSED_ERROR;
                    a.lastError = "Download address not found: " + e.getMessage();
                    renderCardState(a);
                });
                return;
            }
            ResumableDownloader d = new ResumableDownloader();
            activeDownloaders.put(a.kind, d);
            d.start(url, a.partFile(dlDir), a.destFile(dlDir), new ResumableDownloader.Listener() {
                @Override
                public void onProgress(long downloadedBytes, long totalBytes) {
                    handler.post(() -> {
                        a.downloadedBytes = downloadedBytes;
                        a.totalBytes = totalBytes;
                        if (a.state == FileAsset.State.DOWNLOADING) {
                            holders.get(a.kind).progressBar.setProgress(a.progressPercent());
                            holders.get(a.kind).statusText.setText("در حال دانلود… " + a.progressPercent() + "%");
                        }
                    });
                }

                @Override
                public void onError(Exception e) {
                    handler.post(() -> {
                        a.state = FileAsset.State.PAUSED_ERROR;
                        a.lastError = e.getMessage();
                        renderCardState(a);
                    });
                }

                @Override
                public void onComplete() {
                    handler.post(() -> {
                        a.state = FileAsset.State.READY_DOWNLOADED;
                        renderCardState(a);
                    });
                }
            });
        }, "download-" + a.kind.id).start();
    }
    private void pauseDownload(FileAsset a) {
        ResumableDownloader d = activeDownloaders.get(a.kind);
        if (d != null) d.cancel();
        a.state = FileAsset.State.PAUSED_ERROR;
        a.lastError = "متوقف‌شده توسط کاربر";
        renderCardState(a);
    }

        private String resolveUrl(FileAsset a) throws IOException {
        if (a.customUrl != null && !a.customUrl.isEmpty()) return a.customUrl;
        Prefs p = Prefs.of(this);
        switch (a.kind) {
            case ROOTFS:
                return p.getRootfsUrl();
            case NODE:
                return BootstrapInstaller.resolveNodeUrl(p);
            case PROOT:
                return BootstrapInstaller.resolveTermuxDeb(BootstrapInstaller.PROOT_POOL, "proot_");
            case LIBTALLOC:
                return BootstrapInstaller.resolveTermuxDeb(BootstrapInstaller.LIBTALLOC_POOL, "libtalloc_");
            case LIBSHMEM:
                return BootstrapInstaller.resolveTermuxDeb(BootstrapInstaller.LIBANDROID_SHMEM_POOL, "libandroid-shmem_");
            default:
                throw new IOException("Unknown file type");
        }
        }

    private void promptCustomUrl(FileAsset a) {
        EditText input = new EditText(this);
        input.setHint("https://...");
        if (a.customUrl != null) input.setText(a.customUrl);
        new AlertDialog.Builder(this)
                .setTitle("آدرس دانلود سفارشی برای " + a.kind.displayName)
                .setView(input)
                .setPositiveButton("ذخیره و تلاش مجدد", (dialog, which) -> {
                    a.customUrl = input.getText().toString().trim();
                    startDownload(a);
                })
                .setNegativeButton("انصراف", null)
                .show();
    }

    private FileAsset findAsset(FileAsset.Kind kind) {
        for (FileAsset a : assets) if (a.kind == kind) return a;
        return null;
    }
}
