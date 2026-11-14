package dev.xiaoman.hands

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.ColorSpace
import android.hardware.HardwareBuffer
import android.os.Handler
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import dev.xiaoman.api.Action
import dev.xiaoman.api.NodeOnlyObservation
import dev.xiaoman.api.Point
import dev.xiaoman.api.ScreenState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowAccessibilityService
import org.robolectric.shadows.ShadowWindowManagerImpl
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class, NodeOnlyObservation::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], shadows = [OverlayGestureTest.GestureShadow::class])
class OverlayGestureTest {
    @Test fun coordinateGesturesDoNotHitTheStopOverlay() = checkGestures(cancel = false)
    @Test fun cancelledGesturesRestoreTheStopOverlay() = checkGestures(cancel = true)

    @Test fun nodeEnumerationAlsoHidesTheStopOverlay() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val controller = Robolectric.buildService(XiaomanAccessibilityService::class.java).create()
        val service = controller.get()
        val overlay = StatusOverlay(service)
        try {
            overlay.show()
            val view = Shadow.extract<ShadowWindowManagerImpl>(service.getSystemService(WindowManager::class.java)).views.single()
            var reads = 0
            GestureShadow.readRoot = {
                assertEquals(View.GONE, view.visibility)
                reads++
                null
            }
            // A secure screenshot avoids hardware bitmap dependencies; node enumeration still runs.
            Shadow.extract<GestureShadow>(service).setTakeScreenshotErrorCode(AccessibilityService.ERROR_TAKE_SCREENSHOT_SECURE_WINDOW)
            AccessibilityDevice({ service }, service, settleDelayMs = 0, overlay = overlay).observe()
            assertEquals(1, reads)
            assertEquals(View.VISIBLE, view.visibility)
        } finally {
            GestureShadow.reset()
            overlay.hide()
            controller.destroy()
            Dispatchers.resetMain()
        }
    }

    @Test fun observeReportsStageTimings() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val controller = Robolectric.buildService(XiaomanAccessibilityService::class.java).create()
        val service = controller.get()
        val reports = mutableListOf<ObserveMetrics>()
        try {
            Shadow.extract<GestureShadow>(service).setTakeScreenshotErrorCode(AccessibilityService.ERROR_TAKE_SCREENSHOT_SECURE_WINDOW)
            val screen = AccessibilityDevice({ service }, service, settleDelayMs = 0, metrics = { reports += it }).observe()
            assertTrue(screen.secure)
            val metrics = reports.single()
            assertTrue(metrics.withScreenshot)
            assertEquals(0, metrics.nodeCount)
            assertTrue(metrics.widthPx > 0 && metrics.heightPx > 0)
            assertTrue(metrics.totalMs >= 0 && metrics.screenshotMs >= 0 && metrics.nodesMs >= 0)
            assertTrue(metrics.nodesStartOffsetMs >= 0)
        } finally {
            GestureShadow.reset()
            controller.destroy()
            Dispatchers.resetMain()
        }
    }

    @Test fun throwingMetricsSinkDoesNotAffectObserve() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val controller = Robolectric.buildService(XiaomanAccessibilityService::class.java).create()
        val service = controller.get()
        try {
            Shadow.extract<GestureShadow>(service).setTakeScreenshotErrorCode(AccessibilityService.ERROR_TAKE_SCREENSHOT_SECURE_WINDOW)
            val screen = AccessibilityDevice({ service }, service, settleDelayMs = 0, metrics = { error("boom") }).observe()
            assertTrue(screen.secure)
        } finally {
            GestureShadow.reset()
            controller.destroy()
            Dispatchers.resetMain()
        }
    }

    @Test fun observeNodesSkipsTheScreenshot() = runBlocking {
        val controller = Robolectric.buildService(XiaomanAccessibilityService::class.java).create()
        val service = controller.get()
        try {
            val screen = AccessibilityDevice({ service }, service, settleDelayMs = 0).observeNodesUnsafeForSecurity()
            assertEquals(0, GestureShadow.screenshotCalls)
            // Documented consequence of skipping the screenshot: a node-only screen never looks secure.
            assertFalse(screen.secure)
        } finally {
            GestureShadow.reset()
            controller.destroy()
        }
    }

    /**
     * 1a: the custom shadow really replaces [AccessibilityService.takeScreenshot] and a delivered
     * frame reaches the pipeline. The HardwareBuffer itself is faked away because Robolectric has no
     * implementation of [Bitmap.wrapHardwareBuffer]; everything after it (encoding, secure flag) is
     * the production code path.
     */
    @Test fun screenshotCallbackDeliversANonEmptyBitmap() = runBlocking {
        val buffer = runCatching {
            HardwareBuffer.create(4, 4, HardwareBuffer.RGBA_8888, 1, HardwareBuffer.USAGE_CPU_READ_OFTEN)
        }.getOrNull()
        // Reflective construction: the platform class is @SystemApi on some SDK levels and the test
        // must still compile; when Robolectric cannot provide it the case is skipped, not failed.
        val newResult = runCatching {
            val ctor = AccessibilityService.ScreenshotResult::class.java
                .getConstructor(HardwareBuffer::class.java, ColorSpace::class.java)
            val colorSpace = ColorSpace.get(ColorSpace.Named.SRGB)
            ctor.newInstance(buffer, colorSpace) as AccessibilityService.ScreenshotResult
        }.getOrNull()
        assumeTrue(
            "Robolectric 无法构造 HardwareBuffer/ScreenshotResult，此项无法在本环境验证",
            buffer != null && newResult != null,
        )
        // assumeTrue 为假时会直接跳过本用例，走到这里 newResult 一定非空。
        val screenshotResult = requireNotNull(newResult)
        val controller = Robolectric.buildService(XiaomanAccessibilityService::class.java).create()
        val service = controller.get()
        var decoded = 0
        try {
            GestureShadow.screenshotSuccess = { screenshotResult }
            val screen = AccessibilityDevice(
                { service }, service, settleDelayMs = 0,
                decodeFrame = { _, _ ->
                    decoded++
                    Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
                },
            ).observe()
            assertEquals(1, GestureShadow.screenshotCalls)
            assertEquals(1, decoded)
            assertFalse(screen.secure)
            assertTrue((screen.screenshotPng?.size ?: 0) > 0, "成功回调应产出非空 PNG")
        } finally {
            GestureShadow.reset()
            controller.destroy()
        }
    }

    @Test fun nodesAreReadBeforeTheScreenshotCallbackReturns() = runBlocking {
        val controller = Robolectric.buildService(XiaomanAccessibilityService::class.java).create()
        val service = controller.get()
        val hold = CompletableDeferred<Unit>()
        var nodesRead = false
        var observed: ScreenState? = null
        try {
            GestureShadow.holdScreenshot = hold
            GestureShadow.readRoot = { nodesRead = true; null }
            val job = launch {
                observed = AccessibilityDevice({ service }, service, settleDelayMs = 0).observe()
            }
            withTimeout(10_000) { while (!nodesRead) delay(10) }
            assertEquals(1, GestureShadow.screenshotCalls)
            assertFalse(job.isCompleted)
            // A UI change during the (still pending) screenshot must be flagged, not acted upon.
            service.onAccessibilityEvent(AccessibilityEvent.obtain(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED))
            hold.complete(Unit)
            withTimeout(10_000) { job.join() }
            assertTrue(observed!!.secure)
            assertTrue(observed!!.changedDuringCapture)
        } finally {
            GestureShadow.reset()
            controller.destroy()
        }
    }

    @Test fun serialObserveStillRunsTheOldOrder() = runBlocking {
        val controller = Robolectric.buildService(XiaomanAccessibilityService::class.java).create()
        val service = controller.get()
        try {
            val screen = AccessibilityDevice(
                { service }, service, settleDelayMs = 0, serialObserve = true,
            ).observe()
            assertEquals(1, GestureShadow.screenshotCalls)
            assertTrue(screen.secure)
        } finally {
            GestureShadow.reset()
            controller.destroy()
        }
    }

    /** 1c: the node read is issued only after the screenshot request, never before. */
    @Test fun nodesAreReadAfterTheScreenshotRequestIsIssued() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val controller = Robolectric.buildService(XiaomanAccessibilityService::class.java).create()
        val service = controller.get()
        try {
            Shadow.extract<GestureShadow>(service).setTakeScreenshotErrorCode(AccessibilityService.ERROR_TAKE_SCREENSHOT_SECURE_WINDOW)
            GestureShadow.readRoot = { GestureShadow.events += "nodes"; null }
            AccessibilityDevice({ service }, service, settleDelayMs = 0).observe()
            assertEquals(listOf("screenshot", "nodes"), GestureShadow.events)
        } finally {
            GestureShadow.reset()
            controller.destroy()
            Dispatchers.resetMain()
        }
    }

    /** 1c: a nodes-only check takes no screenshot screenshots and therefore pays no 1.05 s throttle. */
    @Test fun nodesOnlyObservationSkipsTheScreenshotThrottle() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val controller = Robolectric.buildService(XiaomanAccessibilityService::class.java).create()
        val service = controller.get()
        val reports = mutableListOf<ObserveMetrics>()
        try {
            Shadow.extract<GestureShadow>(service).setTakeScreenshotErrorCode(AccessibilityService.ERROR_TAKE_SCREENSHOT_SECURE_WINDOW)
            val device = AccessibilityDevice({ service }, service, settleDelayMs = 0, metrics = { reports += it })
            device.observe()
            val shotsAfterFirst = GestureShadow.screenshotCalls
            device.observeNodesUnsafeForSecurity()
            assertEquals(shotsAfterFirst, GestureShadow.screenshotCalls)
            assertFalse(reports.last().withScreenshot)
            assertEquals(0L, reports.last().screenshotThrottleMs)
            assertTrue(reports.first().screenshotThrottleMs > 0, "截图路径应记录节流等待")
        } finally {
            GestureShadow.reset()
            controller.destroy()
            Dispatchers.resetMain()
        }
    }

    /**
     * 1b: a long loop of node reads must not throw when the root is empty (the only "empty root"
     * Robolectric can produce). Real binder death / transient null roots cannot be simulated here;
     * see the stage 1 report.
     */
    @Test fun nodesOnlyObservationSurvivesOneHundredEmptyRoots() = runBlocking {
        val controller = Robolectric.buildService(XiaomanAccessibilityService::class.java).create()
        val service = controller.get()
        var failures = 0
        var empty = 0
        try {
            val device = AccessibilityDevice({ service }, service, settleDelayMs = 0)
            repeat(100) { i ->
                try {
                    val screen = device.observeNodesUnsafeForSecurity()
                    if (screen.packageName == null && screen.nodes.isEmpty()) empty++
                } catch (e: Exception) {
                    failures++
                    println("observeNodes #$i failed: $e")
                }
            }
            println("empty-root loop: runs=100 failures=$failures empty=$empty")
            assertEquals(0, failures)
            assertEquals(100, empty)
        } finally {
            GestureShadow.reset()
            controller.destroy()
        }
    }

    /**
     * 1c: the parallel path must read the node tree while the screenshot callback is still pending,
     * the serial escape hatch must finish the screenshot before it starts reading. Asserted on the
     * observable ordering, not on wall-clock time: Robolectric's frozen clock and the one-second
     * screenshot throttle make millisecond comparisons meaningless here.
     */
    @Test fun parallelObserveReadsNodesWhileTheScreenshotCallbackIsPending() = runBlocking {
        val controller = Robolectric.buildService(XiaomanAccessibilityService::class.java).create()
        val service = controller.get()
        try {
            val parallelPending = screenshotPendingDuringNodeRead(service, serial = false)
            val serialPending = screenshotPendingDuringNodeRead(service, serial = true)
            println("screenshot pending during node read: parallel=$parallelPending serial=$serialPending")
            assertTrue("并发路径读节点时截图回调应仍在进行中", parallelPending)
            assertFalse("串行路径读节点时截图回调应已完成", serialPending)
        } finally {
            GestureShadow.reset()
            controller.destroy()
        }
    }

    /** Runs one observation and returns whether a screenshot callback was still pending at node read. */
    private suspend fun screenshotPendingDuringNodeRead(
        service: XiaomanAccessibilityService,
        serial: Boolean,
    ): Boolean = coroutineScope {
        val hold = CompletableDeferred<Unit>()
        var pendingAtNodeRead = false
        GestureShadow.holdScreenshot = hold
        GestureShadow.readRoot = {
            pendingAtNodeRead = GestureShadow.pendingScreenshots.get() > 0
            Thread.sleep(NODE_READ_MS)
            null
        }
        val job = launch {
            AccessibilityDevice({ service }, service, settleDelayMs = 0, serialObserve = serial).observe()
        }
        // Hold the callback open long enough for the node read to happen while it is in flight.
        delay(CALLBACK_HOLD_MS)
        hold.complete(Unit)
        job.join()
        GestureShadow.holdScreenshot = null
        pendingAtNodeRead
    }

    private fun checkGestures(cancel: Boolean) = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val controller = Robolectric.buildService(XiaomanAccessibilityService::class.java).create()
        val service = controller.get()
        val overlay = StatusOverlay(service)
        try {
            overlay.show()
            val windows = Shadow.extract<ShadowWindowManagerImpl>(service.getSystemService(WindowManager::class.java))
            val view = windows.views.single()
            assertEquals(View.VISIBLE, view.visibility)
            val originalFlags = (view.layoutParams as WindowManager.LayoutParams).flags
            var dispatched = 0
            GestureShadow.checkAndCancel = {
                // The model sees a screenshot without the pill. Its gesture must reach that same UI.
                assertEquals(View.GONE, view.visibility)
                assertEquals(
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                    (view.layoutParams as WindowManager.LayoutParams).flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                )
                dispatched++
                cancel
            }
            val device = AccessibilityDevice({ service }, service, settleDelayMs = 0, overlay = overlay)
            for (action in listOf(
                Action.Tap(point = Point(620, 78)),
                Action.LongPress(point = Point(620, 78)),
                Action.Swipe(Point(620, 78), Point(620, 300)),
            )) {
                assertEquals(!cancel, device.perform(action).ok)
                assertEquals(View.VISIBLE, view.visibility)
                assertEquals(originalFlags, (view.layoutParams as WindowManager.LayoutParams).flags)
            }
            assertEquals(3, dispatched)
        } finally {
            GestureShadow.reset()
            overlay.hide()
            controller.destroy()
            Dispatchers.resetMain()
        }
    }

    @Implements(AccessibilityService::class)
    class GestureShadow : ShadowAccessibilityService() {
        @Implementation
        fun getRootInActiveWindow(): AccessibilityNodeInfo? = readRoot()

        @Implementation
        override fun takeScreenshot(displayId: Int, executor: Executor, callback: AccessibilityService.TakeScreenshotCallback) {
            screenshotCalls++
            pendingScreenshots.incrementAndGet()
            events += "screenshot"
            val success = screenshotSuccess
            val hold = holdScreenshot
            val deliver = Runnable {
                try {
                    if (success != null) {
                        callback.onSuccess(success())
                    } else {
                        callback.onFailure(AccessibilityService.ERROR_TAKE_SCREENSHOT_SECURE_WINDOW)
                    }
                } finally {
                    pendingScreenshots.decrementAndGet()
                }
            }
            if (hold == null) executor.execute(deliver) else hold.invokeOnCompletion { executor.execute(deliver) }
        }

        @Implementation
        override fun dispatchGesture(
            gesture: GestureDescription,
            callback: AccessibilityService.GestureResultCallback?,
            handler: Handler?,
        ): Boolean {
            if (checkAndCancel()) callback?.onCancelled(gesture) else callback?.onCompleted(gesture)
            return true
        }

        companion object {
            var checkAndCancel: () -> Boolean = { false }
            var readRoot: () -> AccessibilityNodeInfo? = { null }
            var screenshotCalls = 0
            /** Screenshots requested but whose callback has not returned yet. */
            val pendingScreenshots = AtomicInteger(0)
            /** When set, the screenshot callback fires only after this completes. */
            var holdScreenshot: CompletableDeferred<Unit>? = null
            /** When set, the shadow answers onSuccess instead of onFailure. */
            var screenshotSuccess: (() -> AccessibilityService.ScreenshotResult)? = null
            /** Order of calls, used to check that the screenshot request precedes the node read. */
            var events = mutableListOf<String>()

            fun reset() {
                checkAndCancel = { false }
                readRoot = { null }
                holdScreenshot = null
                screenshotSuccess = null
                screenshotCalls = 0
                pendingScreenshots.set(0)
                events = mutableListOf()
            }
        }
    }

    private companion object {
        const val CALLBACK_HOLD_MS = 250L
        const val NODE_READ_MS = 250L
    }
}
