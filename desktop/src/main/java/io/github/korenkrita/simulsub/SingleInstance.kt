package io.github.korenkrita.simulsub

import java.awt.EventQueue
import java.io.File
import java.io.RandomAccessFile
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.channels.FileLock
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * One app at a time.
 *
 * The app stays in the menu bar / notification area when its window is closed (the captions go on), so the exe can be
 * clicked again while it runs there. That click must not start a second copy: the new copy asks the running one to
 * show its window and stops itself. Otherwise every click would leave another app behind - another tray icon, another
 * capture of the same sound.
 *
 * The running app holds a lock on a file in the data folder (the system takes the lock back when the app dies, so a
 * crash does not keep the next one out) and listens on a local socket; the next copy finds the port in a file next to
 * the lock and asks over it.
 */
object SingleInstance {
    private const val LOCK = "instance.lock"
    private const val PORT = "instance.port"
    /** what a copy that starts later says */
    private const val SHOW = "show"
    /** the running app is there but does not answer */
    private const val SILENT = ""
    /** how long a copy waits for the running app to answer (it may still be starting up) */
    private const val WAIT_MS = 10_000L
    /** how long the running app waits for its window to come up before it answers anyway */
    private const val ANSWER_MS = 3_000L

    /** The app sets this when it is ready: another copy asked for its window. */
    @Volatile var showWindow: (() -> Unit)? = null

    /** The app's main window (Windows: its handle, so the copy that asked can put it in front). */
    @Volatile var windowHandle: Long = 0

    private var lockFile: RandomAccessFile? = null
    private var lock: FileLock? = null
    private var server: ServerSocket? = null
    private val waiting = mutableListOf<CountDownLatch>()

    /**
     * Takes the app's place, or asks the copy that has it to show its window.
     * true: this copy is the app; false: another one is running, so this one stops.
     */
    fun start(dir: File = dataDir): Boolean {
        return try {
            dir.mkdirs()
            val raf = RandomAccessFile(File(dir, LOCK), "rw")
            // the system gives the lock back when the app dies: a crash does not keep the next one out
            val held = try { raf.channel.tryLock() } catch (e: Exception) { null }
            if (held == null) {
                raf.close()
                askTheRunningOne(dir)
                false
            } else {
                lockFile = raf
                lock = held
                // the port of a copy that died is of no use to anyone
                File(dir, PORT).delete()
                val s = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
                server = s
                File(dir, PORT).writeText(s.localPort.toString())
                Thread({ serve(s) }, "simulsub-instance").apply { isDaemon = true }.start()
                true
            }
        } catch (e: Exception) {
            // no lock, no second copy: the app runs anyway
            true
        }
    }

    /** The app calls this when its window is up: the copy that asked for it may go on. */
    fun windowShown() {
        val list = synchronized(waiting) { waiting.toList().also { waiting.clear() } }
        list.forEach { it.countDown() }
    }

    /** Releases the lock and the socket (the app is going). */
    fun stop() {
        runCatching { server?.close() }
        runCatching { lock?.release() }
        runCatching { lockFile?.close() }
        server = null
        lock = null
        lockFile = null
    }

    /**
     * Asks the copy that runs to show its window and puts it in front. The running app may still be starting up, so
     * this waits for its port to appear.
     */
    private fun askTheRunningOne(dir: File) {
        for (attempt in 0 until 40) {
            val port = readPort(dir)
            if (port != null) {
                when (val answer = tryAsk(port)) {
                    // the running app is there and answered: its window goes in front
                    null -> Unit
                    SILENT -> return          // it is there but busy: this copy stops anyway
                    else -> {
                        // this copy was started by the user, so Windows lets it put the running app's window in front
                        focusWindow(answer)
                        return
                    }
                }
                // the port was of a copy that died: the running one writes its own in a moment
            }
            Thread.sleep(250)
        }
    }

    /** Says "show" and waits for the answer; null when nobody is there, [SILENT] when nobody answered. */
    private fun tryAsk(port: Int): String? {
        val s = try { Socket(InetAddress.getByName("127.0.0.1"), port) } catch (e: Exception) { return null }
        return try {
            s.soTimeout = WAIT_MS.toInt()
            s.getOutputStream().write("$SHOW\n".toByteArray())
            s.getOutputStream().flush()
            s.getInputStream().bufferedReader().readLine() ?: SILENT
        } catch (e: Exception) {
            SILENT
        } finally {
            runCatching { s.close() }
        }
    }

    /** The port of the running app; null while it has not written it yet. */
    private fun readPort(dir: File): Int? =
        runCatching { File(dir, PORT).readText().trim().toInt() }.getOrNull()

    /** Windows: the running app's window in front of the others. */
    private fun focusWindow(answer: String) {
        if (!isWindows) return
        val handle = answer.substringAfter(' ').trim().toLongOrNull() ?: 0L
        if (handle == 0L) return
        runCatching {
            val u = com.sun.jna.platform.win32.User32.INSTANCE
            val h = com.sun.jna.platform.win32.WinDef.HWND(com.sun.jna.Pointer.createConstant(handle))
            u.ShowWindow(h, 5)          // SW_SHOW: the running app un-minimizes its own window
            u.SetForegroundWindow(h)
        }
    }

    /** Answers the copies that come later: one asks for the window, this one shows it. */
    private fun serve(s: ServerSocket) {
        while (!s.isClosed) {
            val client = try { s.accept() } catch (e: Exception) { break }
            Thread({
                val line = try { client.getInputStream().bufferedReader().readLine() } catch (e: Exception) { null }
                if (line != SHOW) {
                    runCatching { client.close() }
                    return@Thread
                }
                val up = CountDownLatch(1)
                synchronized(waiting) { waiting.add(up) }
                // the app may still be starting up: wait for it to be ready
                val handler = waitForTheApp()
                if (handler != null) EventQueue.invokeLater { handler() }
                up.await(ANSWER_MS, TimeUnit.MILLISECONDS)
                synchronized(waiting) { waiting.remove(up) }
                runCatching {
                    client.getOutputStream().write("ok $windowHandle\n".toByteArray())
                    client.getOutputStream().flush()
                }
                runCatching { client.close() }
            }, "simulsub-instance-client").apply { isDaemon = true }.start()
        }
    }

    /** The app, when it is ready to show its window; null when it does not come in time. */
    private fun waitForTheApp(): (() -> Unit)? {
        for (i in 0 until 50) {
            val h = showWindow
            if (h != null) return h
            Thread.sleep(100)
        }
        return null
    }
}
