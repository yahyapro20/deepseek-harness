package com.dshmobile.app;

import java.io.File;

/**
 * توصیف یکی از فایل‌های حجیم مورد نیاز برای راه‌اندازی (rootfs، proot،
 * libtalloc، libandroid-shmem، Node.js). هر AssetKind دقیقاً معادل یکی از
 * دانلودهای فعلی BootstrapInstaller است؛ این کلاس فقط «وضعیت + منبع» را
 * روی همان فایل مقصد (dl/xxx) نگه می‌دارد، منطق استخراج دست‌نخورده می‌ماند.
 */
public final class FileAsset {

    public enum Kind {
        ROOTFS("rootfs", "ریشهٔ سیستم اوبونتو 22.04", "پایهٔ کل کانتینر؛ بدون این فایل هیچ‌چیز دیگری اجرا نمی‌شود.", "ubuntu-base.tar.gz"),
        PROOT("proot", "proot (اجراکنندهٔ کانتینر)", "بدون نیاز به روت، برنامه‌های لینوکسی را داخل پوشهٔ اپ اجرا می‌کند.", "proot.deb"),
        LIBTALLOC("libtalloc", "کتابخانهٔ libtalloc", "وابستگی proot برای مدیریت حافظه.", "libtalloc.deb"),
        LIBSHMEM("libshmem", "کتابخانهٔ libandroid-shmem", "وابستگی proot برای حافظهٔ مشترک روی اندروید.", "libandroid-shmem.deb"),
        NODE("node", "Node.js (نسخهٔ 22، ARM64)", "موتور اجرای DeepSeek Harness.", "node.tar.xz");

        public final String id;
        public final String displayName;
        public final String purpose;
        public final String fileName;

        Kind(String id, String displayName, String purpose, String fileName) {
            this.id = id;
            this.displayName = displayName;
            this.purpose = purpose;
            this.fileName = fileName;
        }
    }

    public enum State {
        /** هنوز نه فایل محلی انتخاب شده نه دانلودی شروع شده. */
        NOT_READY,
        /** نسخهٔ قبلاً ذخیره‌شده در dsh-shared/bootstrap-cache پیدا شده، منتظر تأیید کاربر. */
        FOUND_IN_CACHE,
        /** کاربر فایل را از حافظهٔ گوشی انتخاب کرد و کپی داخلی تمام شد. */
        READY_LOCAL,
        /** دانلود در حال انجام است. */
        DOWNLOADING,
        /** دانلود به‌خاطر قطعی شبکه/خطای سرور متوقف شده؛ رزوم ممکن است. */
        PAUSED_ERROR,
        /** دانلود با موفقیت کامل شد. */
        READY_DOWNLOADED,
        /** خطای غیرقابل‌رفع (مثلاً فضای دیسک کافی نیست). */
        FAILED;
    }

    public final Kind kind;
    public State state = State.NOT_READY;
    /** آدرس سفارشی که کاربر برای این فایل مشخصاً وارد کرده (اگر خالی، آدرس پیش‌فرض/میرور استفاده می‌شود). */
    public String customUrl;
    public long downloadedBytes;
    public long totalBytes;
    public String lastError;

    public FileAsset(Kind kind) {
        this.kind = kind;
    }

    /** فایل مقصد نهایی داخل پوشهٔ dl/ (همان مسیری که BootstrapInstaller از قبل انتظارش را دارد). */
    public File destFile(File dlDir) {
        return new File(dlDir, kind.fileName);
    }

    public File partFile(File dlDir) {
        return new File(dlDir, kind.fileName + ".part");
    }

    /** فایل کش عمومی که با حذف/نصب مجدد اپ از بین نمی‌رود. */
    public File cacheFile(File publicCacheDir) {
        return new File(publicCacheDir, kind.fileName);
    }

    public boolean isReady() {
        return state == State.READY_LOCAL || state == State.READY_DOWNLOADED;
    }

    public int progressPercent() {
        if (totalBytes <= 0) return 0;
        return (int) Math.min(100, (downloadedBytes * 100) / totalBytes);
    }
}
