package com.yahyapro20.dshmobile

object BootConfig {
    const val PROOT_URL =
        "https://skirsten.github.io/proot-portable-android-binaries/aarch64/proot"
    
    const val ROOTFS_URL =
        "https://cloud.debian.org/images/cloud/bookworm/latest/debian-12-generic-arm64.tar.xz"
    
    const val NODE_URL =
        "https://nodejs.org/dist/v22.19.0/node-v22.19.0-linux-arm64.tar.xz"
    
    const val NODE_DIR_NAME = "node-v22.19.0-linux-arm64"
    
    // SHA256 Checksums for integrity verification
    const val PROOT_SHA256 = "" // Optional - fill if you know it
    const val ROOTFS_SHA256 = "" // Optional - fill if you know it
    const val NODE_SHA256 = "" // Optional - fill if you know it
    
    const val LOCAL_CACHE_SUBDIR = "bootstrap-cache"
    const val LOCAL_PROOT_FILENAME = "proot"
    const val LOCAL_ROOTFS_FILENAME = "rootfs.tar.xz"
    const val LOCAL_NODE_FILENAME = "node.tar.xz"
    
    const val WEB_PORT = "3000"
}
