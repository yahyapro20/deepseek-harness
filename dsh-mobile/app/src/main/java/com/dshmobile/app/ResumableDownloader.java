package com.dshmobile.app;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * دانلودگر تک‌فایلی با پشتیبانی واقعی از Resume (هدر Range).
 * برخلاف متد download() فعلی در BootstrapInstaller، این کلاس:
 *   - اگر partFile از قبل داده دارد، با "Range: bytes=<len>-" ادامه می‌دهد.
 *   - اگر سرور 206 برنگرداند (یعنی Range را نادیده گرفت)، partFile را خالی
 *     کرده و از صفر شروع می‌کند (نه خطا، بلکه افت درجه‌ی امن).
 *   - قابل لغو/توقف است (cancel())؛ چون بایت‌های نوشته‌شده در partFile
 *     می‌مانند، فراخوانی دوبارهٔ start() دقیقاً از همان نقطه ادامه می‌دهد.
 *
 * یک نمونه فقط برای یک دانلود استفاده شود (state داخلی ندارد جز cancelled).
 */
public final class ResumableDownloader {

    public interface Listener {
        /** روی ترد فراخوانی می‌شود که start() را صدا زده؛ خودتان به UI-thread پست کنید. */
        void onProgress(long downloadedBytes, long totalBytes);

        void onError(Exception e);

        void onComplete();
    }

    private static final int CONNECT_TIMEOUT = 15_000;
    private static final int READ_TIMEOUT = 30_000;

    private volatile boolean cancelled;

    public void cancel() {
        cancelled = true;
    }

    /**
     * دانلود را (یا رزوم آن را) شروع می‌کند. متد بلاک‌کننده است؛ روی ترد پس‌زمینه صدا بزنید.
     *
     * @param url      آدرس منبع (پیش‌فرض یا سفارشی کاربر)
     * @param partFile فایل موقت؛ اگر از قبل وجود داشته باشد رزوم تلاش می‌شود
     * @param finalFile مقصد نهایی؛ فقط پس از اتمام موفق partFile به این rename می‌شود
     */
    public void start(String url, File partFile, File finalFile, Listener listener) {
        long existingLength = partFile.isFile() ? partFile.length() : 0L;
        HttpURLConnection conn = null;
        try {
            conn = open(url);
            if (existingLength > 0) {
                conn.setRequestProperty("Range", "bytes=" + existingLength + "-");
            }
            int code = conn.getResponseCode();
            boolean resumed;
            if (code == HttpURLConnection.HTTP_PARTIAL) {
                resumed = true;
            } else if (code == HttpURLConnection.HTTP_OK) {
                // سرور از Range پشتیبانی نکرد؛ کل بدنه از صفر می‌آید - باید از نو بنویسیم
                resumed = false;
                existingLength = 0;
            } else {
                throw new IOException("HTTP " + code + " برای " + url);
            }

            long remaining = conn.getContentLengthLong();
            long total = remaining >= 0 ? existingLength + remaining : -1;

            try (InputStream in = conn.getInputStream();
                 OutputStream out = new FileOutputStream(partFile, resumed)) {
                byte[] buf = new byte[64 * 1024];
                long done = existingLength;
                int n;
                long lastReport = 0;
                while ((n = in.read(buf)) != -1) {
                    if (cancelled) {
                        listener.onError(new IOException("لغو شد توسط کاربر"));
                        return;
                    }
                    out.write(buf, 0, n);
                    done += n;
                    long now = System.currentTimeMillis();
                    if (now - lastReport > 200) {
                        lastReport = now;
                        listener.onProgress(done, total);
                    }
                }
                listener.onProgress(done, total < 0 ? done : total);
            }

            if (!partFile.renameTo(finalFile)) {
                throw new IOException("نوشتن در " + finalFile + " ممکن نشد");
            }
            listener.onComplete();
        } catch (IOException e) {
            // partFile عمداً پاک نمی‌شود: دفعهٔ بعد از همینجا رزوم می‌شود
            listener.onError(e);
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private HttpURLConnection open(String url) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setConnectTimeout(CONNECT_TIMEOUT);
        conn.setReadTimeout(READ_TIMEOUT);
        conn.setInstanceFollowRedirects(true);
        conn.setRequestProperty("User-Agent", "dsh-mobile/1.0");
        return conn;
    }
}
