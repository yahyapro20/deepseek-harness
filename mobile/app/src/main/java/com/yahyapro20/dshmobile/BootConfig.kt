package com.yahyapro20.dshmobile

/**
 * Every external download / pinned version used by the bootstrap process lives here,
 * in one place, so it can be audited and bumped deliberately instead of silently
 * drifting to "latest".
 *
 * Sources (verified at the time this was written — re-check before relying on them
 * long-term, since mirrors and releases move):
 *
 * - PROOT_URL: skirsten/proot-portable-android-binaries, a static PRoot build made
 *   specifically for Android, itself based on the Termux `proot` package.
 *   https://github.com/skirsten/proot-portable-android-binaries
 *
 * - ROOTFS_URL: official debuerreotype-generated Debian rootfs tarball (the same
 *   tool/pipeline that builds the official `debian` Docker Hub image), arm64 branch.
 *   We use Debian (glibc) rather than Alpine (musl) because DSH requires a Node.js
 *   build (^22.19.0 or >=24.0.0) that we install ourselves from the official glibc
 *   Node.js release — mixing musl + official Node.js binaries is a known source of
 *   "not found" / dynamic-linker failures.
 *   https://github.com/debuerreotype/docker-debian-artifacts (dist-arm64 branch)
 *
 * - NODE_URL: official Node.js release tarball, linux-arm64, matching the exact
 *   version dsh's own docs give as the minimum supported (22.19.0).
 *   https://nodejs.org/dist/v22.19.0/
 *
 * - DSH_NPM_SPEC: dsh has not reached 1.0 and only ever publishes prereleases, so a
 *   caret/tilde range (e.g. ^0.1.1) will fail to resolve. The exact version string
 *   must be pinned and bumped on purpose.
 */
object BootConfig {

    const val PROOT_URL =
        "https://skirsten.github.io/proot-portable-android-binaries/aarch64/proot"

    const val ROOTFS_URL =
        "https://cloud.debian.org/images/cloud/bookworm/latest/debian-12-generic-arm64.tar.xz"

    const val NODE_URL =
        "https://nodejs.org/dist/v22.19.0/node-v22.19.0-linux-arm64.tar.xz"
    const val NODE_DIR_NAME = "node-v22.19.0-linux-arm64"

    const val DSH_NPM_SPEC = "@deepseek-ai/dsh@0.1.1-rc.2"

    const val WEB_PORT = 3080
    const val HEALTH_CHECK_TIMEOUT_MS = 180_000L
    const val HEALTH_CHECK_INTERVAL_MS = 1_000L

    // Optional escape hatch: if the user already has these three files sitting
    // somewhere from a previous download (e.g. another proot-based app), they can
    // drop them here (exact file names below) and BootstrapInstaller will use them
    // instead of downloading again. This directory needs no special permission
    // because it's the app's own external-files directory.
    // getExternalFilesDir(null)/bootstrap-cache/{proot, rootfs.tar.xz, node.tar.xz}
    const val LOCAL_CACHE_SUBDIR = "bootstrap-cache"
    const val LOCAL_PROOT_FILENAME = "proot"
    const val LOCAL_ROOTFS_FILENAME = "rootfs.tar.xz"
    const val LOCAL_NODE_FILENAME = "node.tar.xz"
}
