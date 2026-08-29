package com.yahyapro20.dshmobile

import android.content.Context
import android.util.Log
import java.io.File

/**
 * Wraps commands with `proot`, presenting the extracted Debian rootfs as `/`.
 *
 * NOTE on sandboxing: DSH's own filesystem sandbox (bwrap/Landlock on Linux) does
 * not work inside proot, the same limitation the Ajwyunsx/deepseek-harness-mobile
 * project ran into. For this MVP we do the same thing they do and run with
 * DSH_PERMISSION_MODE=danger-full-access — meaning dsh will run shell/file-write
 * tool calls WITHOUT asking for per-command approval. This is a real, deliberate
 * trade-off, not an oversight, and should be surfaced to the user in the UI before
 * we consider it "done" (tracked as a known MVP limitation, not fixed here).
 */
object ProotRunner {
    private const val TAG = "ProotRunner"

    private fun prootBinary(context: Context) = File(context.filesDir, "bin/proot")
    private fun rootfsDir(context: Context) = File(context.filesDir, "dsh-root")
    private fun nodeDir(context: Context) = File(rootfsDir(context), "opt/node")

    /**
     * Builds the full proot argv for running [command] with [rootfsDir] as `/`.
     * `-0` makes proot pretend every uid/gid is 0 (root) inside the guest, which is
     * required for apt/npm-style installs to behave. `-b` binds real device paths in;
     * `-w` sets the working directory inside the guest.
     */
    private fun buildCommand(
        context: Context,
        command: List<String>,
        workdirInGuest: String = "/root"
    ): List<String> {
        val root = rootfsDir(context)
        val proot = prootBinary(context)
        return listOf(
            proot.absolutePath,
            "-0",
            "-r", root.absolutePath,
            "-b", "/dev",
            "-b", "/proc",
            "-b", "/sys",
            "-b", "${File(context.cacheDir, "tmp").absolutePath}:/tmp",
            "-w", workdirInGuest
        ) + command
    }

    private fun baseEnv(extra: Map<String, String> = emptyMap()): Map<String, String> {
        val env = HashMap<String, String>()
        env["HOME"] = "/root"
        env["PATH"] = "/opt/node/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"
        env["TERM"] = "xterm-256color"
        // See the class-level note above: proot cannot enforce dsh's normal sandbox,
        // so we run in full-access mode and must be explicit about it.
        env["DSH_PERMISSION_MODE"] = "danger-full-access"
        env["PROOT_TMP_DIR"] = File(extra["_cacheDir"] ?: "/data/local/tmp", "proot-tmp").absolutePath
        env.putAll(extra.filterKeys { it != "_cacheDir" })
        return env
    }

    /** Runs [command] under proot and blocks until it exits, streaming stdout/stderr to Logcat. */
    fun runBlocking(
        context: Context,
        command: List<String>,
        workdirInGuest: String = "/root",
        extraEnv: Map<String, String> = emptyMap()
    ): Int {
        File(context.cacheDir, "tmp").mkdirs()
        val fullEnv = baseEnv(extraEnv + ("_cacheDir" to context.cacheDir.absolutePath))
        val pb = ProcessBuilder(buildCommand(context, command, workdirInGuest))
        pb.environment().putAll(fullEnv)
        pb.redirectErrorStream(true)
        Log.i(TAG, "Running (blocking): $command")
        val process = pb.start()
        process.inputStream.bufferedReader().forEachLine { Log.i(TAG, "[guest] $it") }
        val exit = process.waitFor()
        Log.i(TAG, "Command exited with code $exit: $command")
        return exit
    }

    /** Starts `dsh web` as a long-running, non-blocking process. Caller owns its lifecycle. */
    fun startWebServer(context: Context): Process {
        File(context.cacheDir, "tmp").mkdirs()
        val fullEnv = baseEnv(mapOf("_cacheDir" to context.cacheDir.absolutePath))
        // `npm install -g` with node's own bundled npm places the `dsh` bin symlink
        // directly under the node prefix's bin/ (i.e. /opt/node/bin/dsh), since we
        // never repointed npm's global prefix elsewhere. No need to go through `npm
        // exec` at runtime — just invoke the installed binary.
        val command = buildCommand(
            context,
            listOf("/opt/node/bin/dsh", "web", "--port", BootConfig.WEB_PORT.toString()),
            workdirInGuest = "/root"
        )
        val pb = ProcessBuilder(command)
        pb.environment().putAll(fullEnv)
        pb.redirectErrorStream(true)
        Log.i(TAG, "Starting dsh web: $command")
        return pb.start()
    }

    fun isProotReady(context: Context) = prootBinary(context).canExecute()
    fun isRootfsReady(context: Context) = File(rootfsDir(context), "bin/sh").exists()
    fun isNodeReady(context: Context) = File(nodeDir(context), "bin/node").exists()
}
