package com.dshmobile.app;

import java.io.File;

/**
 * Description of one of the large files required for bootstrapping (rootfs, proot,
 * libtalloc, libandroid-shmem, Node.js). Each AssetKind corresponds exactly to one of
 * the current BootstrapInstaller downloads; this class only keeps "state + source"
 * on the same destination file (dl/xxx), leaving the extraction logic untouched.
 */
public final class FileAsset {

    public enum Kind {
        ROOTFS("rootfs", "Ubuntu 22.04 root filesystem", "The base of the entire container; without this file nothing else runs.", "ubuntu-base.tar.gz"),
        PROOT("proot", "proot (container runner)", "Runs Linux programs inside the app folder without requiring root.", "proot.deb"),
        LIBTALLOC("libtalloc", "libtalloc library", "proot dependency for memory management.", "libtalloc.deb"),
        LIBSHMEM("libshmem", "libandroid-shmem library", "proot dependency for shared memory on Android.", "libandroid-shmem.deb"),
        NODE("node", "Node.js (version 22, ARM64)", "DeepSeek Harness runtime engine.", "node.tar.xz");

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
        /** Neither a local file selected nor a download started yet. */
        NOT_READY,
        /** Previously saved version found in dsh-shared/bootstrap-cache, awaiting user confirmation. */
        FOUND_IN_CACHE,
        /** User selected the file from phone storage and internal copy completed. */
        READY_LOCAL,
        /** Download in progress. */
        DOWNLOADING,
        /** Download paused due to network disconnection/server error; resume possible. */
        PAUSED_ERROR,
        /** Download completed successfully. */
        READY_DOWNLOADED,
        /** Unrecoverable error (e.g., insufficient disk space). */
        FAILED;
    }

    public final Kind kind;
    public State state = State.NOT_READY;
    /** Custom URL explicitly entered by the user for this file (if empty, default/mirror URL is used). */
    public String customUrl;
    public long downloadedBytes;
    public long totalBytes;
    public String lastError;

    public FileAsset(Kind kind) {
        this.kind = kind;
    }

    /** Final destination file inside the dl/ folder (the same path that BootstrapInstaller already expects). */
    public File destFile(File dlDir) {
        return new File(dlDir, kind.fileName);
    }

    public File partFile(File dlDir) {
        return new File(dlDir, kind.fileName + ".part");
    }

    /** Public cache file that persists across app uninstall/reinstall. */
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
