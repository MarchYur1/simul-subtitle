package io.github.korenkrita.simulsub

import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * One app at a time: the app stays in the menu bar / notification area when its window is closed, so the exe can be
 * clicked again while it runs there. The new copy must not run a second app (another tray icon, another capture of the
 * same sound): it asks the copy that runs to show its window and stops itself.
 */
class SingleInstanceTest {
    private lateinit var dir: File
    private val shown = CountDownLatch(1)

    @Before fun setUp() {
        dir = File.createTempFile("simulsub-single", "").also { it.delete(); it.mkdirs() }
        SingleInstance.showWindow = { shown.countDown() }
    }

    @After fun tearDown() {
        SingleInstance.stop()
        SingleInstance.showWindow = null
        dir.deleteRecursively()
    }

    /** the first copy is the app: it holds the lock and listens for the copies that come later */
    @Test fun theFirstCopyIsTheApp() {
        assertTrue(SingleInstance.start(dir))
    }

    /** a copy that starts later does not run: it asks the one that runs to show its window */
    @Test fun aLaterCopyAsksTheRunningOneToShowItsWindow() {
        assertTrue(SingleInstance.start(dir))
        // the later copy: the lock is taken, so it asks this one and stops
        assertFalse(SingleInstance.start(dir))
        assertTrue(shown.await(10, TimeUnit.SECONDS))
    }
}
