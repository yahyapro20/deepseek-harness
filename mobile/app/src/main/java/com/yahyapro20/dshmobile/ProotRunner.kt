package com.yahyapro20.dshmobile

import android.content.Context
import android.util.Log
import java.io.File

object ProotRunner {
    private const val TAG = "ProotRunner"

    // IMPORTANT: On Android 10+, filesDir blocks execution of ELF binaries.
    // We MUST use cacheDir for the proot executable.
    private fun prootBinary(context: Context) = File(context.cacheDir, "proot")
    
    // rootfs can stay in filesDir as it's just data, not an executable
    private fun rootfsDir(context: Context) = File(context.filesDir, "dsh-root")
    private fun nodeDir(context: Context) = File(rootfsDir(context), "opt/node")

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
        env["DSH_PERMISSION_MODE"] = "danger-full-access"
        env["PROOT_TMP_DIR"] = File(extra["_cacheDir"] ?: "/data/local/tmp", "proot-tmp").absolutePath
        env.putAll(extra.filterKeys { it != "_cacheDir" })
        return env
    }

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

    fun startWebServer(context: Context): Process {
        File(context.cacheDir, "tmp").mkdirs()
        val fullEnv = baseEnv(mapOf("_cacheDir" to context.cacheDir.absolutePath))
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
