package dev.xiaoman.agent

import dev.xiaoman.api.Action
import dev.xiaoman.api.AgentConfig
import dev.xiaoman.api.Bounds
import dev.xiaoman.api.Decision
import dev.xiaoman.api.GuardContext
import dev.xiaoman.api.ModelRole
import dev.xiaoman.api.ScreenState
import dev.xiaoman.api.Sentinel
import dev.xiaoman.api.UiNode
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LocalFastPathTest {
    @Test fun uniqueTapNeedsNoModelButStillGoesThroughSentinel() = runTest {
        val sentinel = FakeSentinel()
        val run = runStep(
            constraint = StepConstraint("tap", "确认呼叫"),
            screen = screenOf(node(0, text = "确认呼叫"), node(1, text = "返回")),
            sentinel = sentinel,
        )
        assertTrue(run.outcome.success)
        assertTrue(run.operator.requests.isEmpty())
        val tap = assertIs<Action.Tap>(run.device.performed.single())
        assertEquals(0, tap.nodeId)
        assertNull(tap.point)
        assertEquals("确认呼叫", tap.target)
        assertEquals(1, sentinel.authorized.size)
    }

    @Test fun uniqueTypeUsesTheConstraintText() = runTest {
        val run = runStep(
            constraint = StepConstraint("type", "目的地", "公司"),
            screen = screenOf(node(0, desc = "目的地", editable = true)),
        )
        assertTrue(run.outcome.success)
        assertTrue(run.operator.requests.isEmpty())
        val typed = assertIs<Action.TypeText>(run.device.performed.single())
        assertEquals("公司", typed.text)
        assertEquals(0, typed.nodeId)
    }

    @Test fun ambiguousTargetFallsBackToTheModel() = runTest {
        val operator = ScriptedLlm(ModelRole.OPERATOR).reply(op("""{"type":"tap","nodeId":0}"""))
        val run = runStep(
            constraint = StepConstraint("tap", "确认呼叫"),
            screen = screenOf(node(0, text = "确认呼叫"), node(1, text = "确认呼叫")),
            operator = operator,
        )
        assertTrue(run.outcome.success)
        assertEquals(1, operator.requests.size)
        assertEquals(0, assertIs<Action.Tap>(run.device.performed.single()).nodeId)
    }

    @Test fun noMatchFallsBackToTheModel() = runTest {
        val operator = ScriptedLlm(ModelRole.OPERATOR).reply(op("""{"type":"tap","nodeId":1}"""))
        val run = runStep(
            constraint = StepConstraint("tap", "确认呼叫"),
            screen = screenOf(node(0, text = "返回"), node(1, text = "取消")),
            operator = operator,
        )
        assertTrue(run.outcome.success)
        assertEquals(1, operator.requests.size)
    }

    @Test fun disabledFastPathUsesTheModel() = runTest {
        val operator = ScriptedLlm(ModelRole.OPERATOR).reply(op("""{"type":"tap","nodeId":0}"""))
        val run = runStep(
            constraint = StepConstraint("tap", "确认呼叫"),
            screen = screenOf(node(0, text = "确认呼叫")),
            config = AgentConfig(localFastPath = false),
            operator = operator,
        )
        assertTrue(run.outcome.success)
        assertEquals(1, operator.requests.size)
    }

    @Test fun sensitivePickStillGoesThroughSentinelAsking() = runTest {
        val gate = AskGate()
        val run = runStep(
            constraint = StepConstraint("tap", "确认呼叫"),
            screen = screenOf(node(0, text = "确认呼叫")),
            sentinel = gate,
        )
        assertTrue(run.outcome.success)
        assertTrue(run.operator.requests.isEmpty())
        assertEquals(1, run.device.performed.size)
        // Initial observation + approval-screen check; the fast path cannot skip that.
        assertEquals(2, run.device.observations)
        assertTrue(gate.asked)
    }

    @Test fun blockedLocalPickFallsBackToTheModel() = runTest {
        val operator = ScriptedLlm(ModelRole.OPERATOR).reply(op("""{"type":"tap","nodeId":1}"""))
        val run = runStep(
            constraint = StepConstraint("tap", "确认呼叫"),
            screen = screenOf(node(0, text = "确认呼叫"), node(1, text = "返回")),
            sentinel = FakeSentinel(policy = { Decision.Deny("不允许") }),
            operator = operator,
            maxSteps = 1,
        )
        assertFalse(run.outcome.success)
        assertEquals(1, operator.requests.size)
        assertTrue(run.device.performed.isEmpty())
    }

    @Test fun passwordFieldIsNeverPickedAndFallsBackToTheModel() = runTest {
        val operator = ScriptedLlm(ModelRole.OPERATOR).reply(op("""{"type":"type","nodeId":0,"text":"hunter2"}"""))
        val run = runStep(
            constraint = StepConstraint("type", "密码", "hunter2"),
            screen = screenOf(node(0, desc = "密码", editable = true, password = true)),
            operator = operator,
        )
        assertEquals(1, operator.requests.size)
        assertEquals("hunter2", assertIs<Action.TypeText>(run.device.performed.single()).text)
    }

    @Test fun timingLineSaysLocalAndWhy() = runTest {
        val lines = mutableListOf<String>()
        runStep(
            constraint = StepConstraint("tap", "确认呼叫"),
            screen = screenOf(node(0, text = "确认呼叫")),
            metrics = { lines += it },
        )
        assertTrue(
            lines.any { "select=local" in it && "reason=exact" in it },
            "未记录本地命中：$lines",
        )
    }

    private class AskGate : Sentinel {
        var asked = false
        override suspend fun guard(context: GuardContext, action: Action): Decision {
            asked = true
            return Decision.Ask("确认呼叫快车？", "呼叫")
        }
        override suspend fun authorize(context: GuardContext, action: Action): Boolean = true
    }

    private class Run(
        val outcome: LoopOutcome,
        val device: FakeDevice,
        val operator: ScriptedLlm,
    )

    private suspend fun runStep(
        constraint: StepConstraint,
        screen: ScreenState,
        config: AgentConfig = AgentConfig(localFastPath = true),
        sentinel: Sentinel = FakeSentinel(),
        operator: ScriptedLlm = ScriptedLlm(ModelRole.OPERATOR),
        maxSteps: Int = 1,
        metrics: ((String) -> Unit)? = null,
    ): Run {
        val device = FakeDevice(screen)
        val task = TaskRun("t1", "叫车", skill = null, listener = Events(), stop = Stop())
        val loop = OperatorLoop(operator, device, sentinel, FakeUsers(), config, metrics)
        val outcome = loop.run(task, subGoal = "叫车", constraint = constraint, maxSteps = maxSteps)
        return Run(outcome, device, operator)
    }

    private fun node(
        id: Int,
        text: String? = null,
        desc: String? = null,
        editable: Boolean = false,
        password: Boolean = false,
    ) = UiNode(
        id = id,
        text = text,
        description = desc,
        bounds = Bounds(0, id * 100, 1000, id * 100 + 90),
        clickable = true,
        editable = editable,
        password = password,
    )

    private fun screenOf(vararg nodes: UiNode) = ScreenState(
        screenshotPng = byteArrayOf(1),
        widthPx = 1080,
        heightPx = 2400,
        packageName = "com.example.app",
        nodes = nodes.toList(),
    )
}
