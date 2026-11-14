package dev.xiaoman.agent

import dev.xiaoman.api.Action
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Stage 0 instrumentation must be observable and must never change what the loop does. */
class MetricsTest {
    @Test
    fun loopAndExecuteEmitOneTimingLineEachWithoutChangingSteps() = runTest {
        val lines = mutableListOf<String>()
        val h = Harness(metrics = { lines += it })
        h.operator
            .reply(op("""{"type": "tap", "nodeId": 0}"""))
            .reply(op("""{"type": "finish", "success": true, "summary": "ok"}"""))

        val outcome = h.agent.runPhoneTask("打开设置", null, h.events, h.stop)

        assertTrue(outcome.success)
        assertEquals(2, h.operator.requests.size)
        assertEquals(listOf<Action>(Action.Tap(nodeId = 0)), h.device.performed)
        assertEquals(2, lines.count { it.startsWith("op step=") })
        assertEquals(1, lines.count { it.startsWith("exec step=") })
        val firstOp = lines.first { it.startsWith("op step=") }
        assertTrue("observe=" in firstOp && "model=" in firstOp && "nodes=" in firstOp && "pkg=com.android.launcher" in firstOp)
        val exec = lines.first { it.startsWith("exec step=") }
        assertTrue("auth=" in exec && "approval=" in exec && "perform=" in exec && "ok=true" in exec && "action=Tap" in exec)
    }

    @Test
    fun throwingMetricsSinkDoesNotAffectTheTask() = runTest {
        val h = Harness(metrics = { error("boom") })
        h.operator
            .reply(op("""{"type": "tap", "nodeId": 0}"""))
            .reply(op("""{"type": "finish", "success": true, "summary": "ok"}"""))

        val outcome = h.agent.runPhoneTask("打开设置", null, h.events, h.stop)

        assertTrue(outcome.success)
        assertEquals(1, h.device.performed.size)
    }
}
