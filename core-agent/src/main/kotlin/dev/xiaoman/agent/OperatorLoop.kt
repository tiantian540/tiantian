package dev.xiaoman.agent

import dev.xiaoman.api.Action
import dev.xiaoman.api.ActionResult
import dev.xiaoman.api.AgentConfig
import dev.xiaoman.api.AgentEvent
import dev.xiaoman.api.ChatMessage
import dev.xiaoman.api.ChatRequest
import dev.xiaoman.api.ChatResponse
import dev.xiaoman.api.ChatRole
import dev.xiaoman.api.ContentPart
import dev.xiaoman.api.Decision
import dev.xiaoman.api.Device
import dev.xiaoman.api.GuardContext
import dev.xiaoman.api.LlmClient
import dev.xiaoman.api.LlmException
import dev.xiaoman.api.NodeOnlyObservation
import dev.xiaoman.api.ScreenState
import dev.xiaoman.api.Sentinel
import dev.xiaoman.api.UiNode
import dev.xiaoman.api.UserChannel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

/**
 * The operator loop of docs/ARCHITECTURE.md: observe, prompt the operator model, parse, gate
 * through Sentinel, perform, detect stuck screens. Also hosts single-step and screen checks used by flows.
 *
 * @param metrics optional receiver of one timing line per loop iteration and per executed action.
 *   Diagnostic only: it never changes what the loop does.
 */
internal class OperatorLoop(
    private val operator: LlmClient,
    private val device: Device,
    private val sentinel: Sentinel,
    private val users: UserChannel,
    private val config: AgentConfig,
    private val metrics: ((String) -> Unit)? = null,
) {
    /**
     * Runs until finish, failure or [maxSteps] iterations. [subGoal] replaces the task goal as the
     * immediate objective (flow `ai` steps); [constraint] makes it a single constrained step.
     */
    suspend fun run(
        task: TaskRun,
        subGoal: String? = null,
        constraint: StepConstraint? = null,
        maxSteps: Int = config.maxSteps,
    ): LoopOutcome {
        val history = mutableListOf<String>()
        val feedback = mutableListOf<String>()
        var parseFailures = 0
        var actedOn: String? = null // fingerprint of the screen the last action ran on
        var acted = false
        var sameAfterActing = 0
        var warnedStuck = false

        /**
         * One local attempt for a constrained step: [LocalPicker] chooses a node, then the normal
         * `execute` (Sentinel, approval, perform) runs it. Returns the finished outcome on success,
         * or null plus the reason to print on the model line. Never throws: any doubt falls back.
         */
        suspend fun tryFastStep(
            iteration: Int,
            observeMs: Long,
            constraint: StepConstraint,
            screen: ScreenState,
            fingerprint: String,
        ): Pair<LoopOutcome?, String> {
            val pick = LocalPicker.pick(constraint.kind, constraint.target, screen)
                ?: return null to "no_unique_match"
            val action = fastAction(constraint, pick.node) ?: return null to "no_unique_match"
            report(
                "op step=$iteration observe=${observeMs}ms select=local reason=${pick.reason} " +
                    "nodes=${screen.nodes.size} pkg=${screen.packageName} secure=${screen.secure}",
            )
            task.emit(AgentEvent.StepPlanned(task.taskId, task.steps, action))
            val index = task.steps
            return when (val executed = execute(task, action, screen)) {
                is Executed.Blocked -> {
                    feedback += Prompts.blocked(executed.decision)
                    history += Prompts.stepLine(index, ScreenText.describe(action), "被拦截：${executed.decision.reason}")
                    null to "local_blocked"
                }
                is Executed.Done -> {
                    val result = executed.result
                    history += Prompts.stepLine(
                        index, ScreenText.describe(action),
                        if (result.ok) "成功" else "失败：${result.message ?: "未知原因"}",
                    )
                    acted = true
                    actedOn = fingerprint
                    if (result.ok) {
                        LoopOutcome(true, ScreenText.describe(action)) to pick.reason
                    } else {
                        feedback += "上一步执行失败：${result.message ?: "未知原因"}"
                        null to "local_failed"
                    }
                }
            }
        }

        for (iteration in 0 until maxSteps) {
            if (task.stop.stopped) return LoopOutcome(false, Prompts.STOPPED)
            val observeStartedAt = System.nanoTime()
            val screen = device.observe()
            val observeMs = elapsedMs(observeStartedAt)
            screen.packageName?.let { task.lastPackage = it }

            if (screen.secure) {
                task.emit(AgentEvent.NeedsUser(task.taskId, Prompts.SECURE_SCREEN, takeover = true))
                val answer = users.ask(task.taskId, Prompts.SECURE_SCREEN, true)
                history += Prompts.takeoverDone(answer)
                acted = false
                continue
            }

            val fp = screen.fingerprint()
            if (acted) {
                sameAfterActing = if (fp == actedOn) sameAfterActing + 1 else 0
                acted = false
            }
            if (sameAfterActing > 0 && sameAfterActing + 1 >= config.stuckRepeats) {
                if (warnedStuck) return LoopOutcome(false, Prompts.STUCK_FAILED)
                warnedStuck = true
                sameAfterActing = 0
                feedback += Prompts.STUCK_WARNING
            }

            // Local fast path: only for flow steps whose target is already written down. It picks a
            // node; Sentinel, approval and perform below are exactly the normal path. Any doubt or
            // failure falls through to the model on this same screen.
            var selectReason = when {
                constraint == null -> "no_constraint"
                !config.localFastPath -> "disabled"
                else -> "no_unique_match"
            }
            if (config.localFastPath && constraint != null) {
                val (done, reason) = tryFastStep(iteration, observeMs, constraint, screen, fp)
                selectReason = reason
                if (done != null) return done
            }

            val request = operatorRequest(task, subGoal, constraint, screen, history, feedback)
            val modelStartedAt = System.nanoTime()
            val reply = try {
                completeWithRetry(request)
            } catch (e: LlmException) {
                val message = Prompts.operatorLlmFailed(e)
                task.emit(AgentEvent.Failed(task.taskId, message))
                return LoopOutcome(false, message)
            }
            feedback.clear()
            report(
                "op step=$iteration observe=${observeMs}ms select=model reason=$selectReason " +
                    "model=${elapsedMs(modelStartedAt)}ms " +
                    "nodes=${screen.nodes.size} pkg=${screen.packageName} secure=${screen.secure}",
            )

            var action = when (val parsed = OperatorReplyParser.parse(reply.text)) {
                is ParsedReply.Error -> {
                    if (++parseFailures >= PARSE_FAILURE_LIMIT) return LoopOutcome(false, Prompts.PARSE_FAILED)
                    feedback += Prompts.parseError(parsed.message)
                    continue
                }
                is ParsedReply.Ok -> parsed.action.also { parseFailures = 0 }
            }

            if (constraint != null && action !is Action.Finish) {
                val matches = when (constraint.kind) {
                    "tap" -> action is Action.Tap
                    "type" -> action is Action.TypeText
                    else -> true
                }
                if (!matches) {
                    feedback += Prompts.wrongSingleStepKind(constraint.kind)
                    continue
                }
                if (action is Action.TypeText && constraint.text != null) action = action.copy(text = constraint.text)
            }

            val nodeId = when (action) {
                is Action.Tap -> action.nodeId
                is Action.LongPress -> action.nodeId
                is Action.TypeText -> action.nodeId
                else -> null
            }
            if (nodeId != null && screen.nodes.none { it.id == nodeId }) {
                feedback += Prompts.unknownNode(nodeId)
                continue
            }

            task.emit(AgentEvent.StepPlanned(task.taskId, task.steps, action))
            when (action) {
                is Action.Finish -> return LoopOutcome(action.success, action.summary)
                is Action.AskUser -> {
                    task.emit(AgentEvent.NeedsUser(task.taskId, action.question, action.takeover))
                    val answer = users.ask(task.taskId, action.question, action.takeover)
                    history += Prompts.askedUser(action.question, answer)
                    continue
                }
                else -> Unit
            }

            val index = task.steps
            when (val executed = execute(task, action, screen)) {
                is Executed.Blocked -> {
                    feedback += Prompts.blocked(executed.decision)
                    history += Prompts.stepLine(index, ScreenText.describe(action), "被拦截：${executed.decision.reason}")
                }
                is Executed.Done -> {
                    val r = executed.result
                    history += Prompts.stepLine(index, ScreenText.describe(action), if (r.ok) "成功" else "失败：${r.message ?: "未知原因"}")
                    if (!r.ok) feedback += "上一步执行失败：${r.message ?: "未知原因"}"
                    acted = true
                    actedOn = fp
                    if (constraint != null) {
                        return LoopOutcome(r.ok, if (r.ok) ScreenText.describe(action) else (r.message ?: "执行失败"))
                    }
                }
            }
        }
        return LoopOutcome(false, if (constraint != null) "没能完成：${constraint.target}" else Prompts.MAX_STEPS)
    }

    /** Gates [action] through Sentinel and performs it; emits StepBlocked or StepExecuted. */
    suspend fun execute(task: TaskRun, action: Action, screen: ScreenState?): Executed {
        val startedAt = System.nanoTime()
        var authMs = -1L
        var approvalMs = -1L
        var performMs = -1L
        fun reportExec(ok: Boolean?) {
            report(
                "exec step=${task.steps} auth=${if (authMs >= 0) "${authMs}ms" else "?"} " +
                    "approval=${if (approvalMs >= 0) "${approvalMs}ms" else "?"} " +
                    "perform=${if (performMs >= 0) "${performMs}ms" else "?"} " +
                    "total=${elapsedMs(startedAt)}ms ok=${ok ?: "?"} action=${action::class.simpleName}",
            )
        }

        val context = GuardContext(task.taskId, task.goal, task.skill, screen, task.steps)
        val needsConfirmation = sentinel.guard(context, action) is Decision.Ask
        if (!sentinel.authorize(context, action)) {
            authMs = elapsedMs(startedAt)
            val decision = sentinel.guard(context, action).let { if (it is Decision.Allow) Decision.Deny("未获授权") else it }
            task.emit(AgentEvent.StepBlocked(task.taskId, task.steps, action, decision))
            reportExec(false)
            return Executed.Blocked(decision)
        }
        authMs = elapsedMs(startedAt)
        if (task.stop.stopped) {
            val decision = Decision.Deny(Prompts.STOPPED)
            task.emit(AgentEvent.StepBlocked(task.taskId, task.steps, action, decision))
            reportExec(false)
            return Executed.Blocked(decision)
        }
        if (needsConfirmation && !awaitApprovalScreen(task, screen)) {
            approvalMs = elapsedMs(startedAt) - authMs
            val decision = Decision.Deny("确认期间画面发生变化，未执行原动作；请返回目标应用，重新观察和定位后再确认。")
            task.emit(AgentEvent.StepBlocked(task.taskId, task.steps, action, decision))
            reportExec(false)
            return Executed.Blocked(decision)
        }
        approvalMs = if (needsConfirmation) elapsedMs(startedAt) - authMs else 0L
        if (task.stop.stopped) {
            val decision = Decision.Deny(Prompts.STOPPED)
            task.emit(AgentEvent.StepBlocked(task.taskId, task.steps, action, decision))
            reportExec(false)
            return Executed.Blocked(decision)
        }
        val performStartedAt = System.nanoTime()
        val result = try {
            device.perform(action)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ActionResult(false, e.message ?: e::class.simpleName)
        }
        performMs = elapsedMs(performStartedAt)
        task.emit(AgentEvent.StepExecuted(task.taskId, task.steps, action, result))
        task.steps++
        if (result.ok) {
            if (action is Action.LaunchApp && task.launchedPackage == null) task.launchedPackage = action.packageName
            task.addPath(ScreenText.pathLabel(action, screen))
        }
        reportExec(result.ok)
        return Executed.Done(result)
    }

    /** App-switch animations can outlast the approval card. Never tap until the original UI returns. */
    @OptIn(NodeOnlyObservation::class)
    private suspend fun awaitApprovalScreen(task: TaskRun, before: ScreenState?): Boolean {
        repeat(5) { attempt ->
            if (task.stop.stopped) return false
            // The first check keeps a real screenshot so FLAG_SECURE is still detected. Retries only
            // compare the node tree, which lets them skip the one-screenshot-per-second limit.
            // The opt-in above is deliberate: a nodes-only retry cannot see FLAG_SECURE, so this
            // function must never treat it as a secure-screen check.
            val after = if (attempt == 0) device.observe() else device.observeNodesUnsafeForSecurity()
            if (after.secure) return false
            if (ScreenText.matchesApprovalScreen(before, after)) return true
            if (attempt < 4) delay(300)
        }
        return false
    }

    /** Flow `assert`: asks the operator model whether the screen shows [expectation]. */
    suspend fun check(task: TaskRun, expectation: String): LoopOutcome {
        repeat(PARSE_FAILURE_LIMIT) {
            if (task.stop.stopped) return LoopOutcome(false, Prompts.STOPPED)
            val screen = device.observe()
            if (screen.secure) {
                task.emit(AgentEvent.NeedsUser(task.taskId, Prompts.SECURE_SCREEN, takeover = true))
                users.ask(task.taskId, Prompts.SECURE_SCREEN, true)
                return@repeat
            }
            val text = Prompts.assertRequest(expectation) + "\n\n" + ScreenText.section(screen, config.maxNodes)
            val request = ChatRequest(listOf(ChatMessage.system(Prompts.ASSERT_SYSTEM), userMessage(text, screen)), jsonMode = true)
            val reply = try {
                completeWithRetry(request)
            } catch (e: LlmException) {
                val message = Prompts.operatorLlmFailed(e)
                task.emit(AgentEvent.Failed(task.taskId, message))
                return LoopOutcome(false, message)
            }
            val obj = JsonText.firstObject(reply.text) ?: return@repeat
            val ok = JsonText.bool(obj, "ok") ?: return@repeat
            val reason = JsonText.string(obj, "reason")
            return LoopOutcome(ok, if (ok) (reason ?: expectation) else Prompts.assertFailed(expectation, reason))
        }
        return LoopOutcome(false, Prompts.assertFailed(expectation, null))
    }

    /** Flow constraints are `tap`/`type`; a `type` step without text stays with the model. */
    private fun fastAction(constraint: StepConstraint, node: UiNode): Action? = when (constraint.kind) {
        "tap" -> Action.Tap(nodeId = node.id, target = constraint.target, reason = FAST_REASON)
        "type" -> constraint.text?.let {
            Action.TypeText(text = it, nodeId = node.id, target = constraint.target, reason = FAST_REASON)
        }
        else -> null
    }

    /** Retry a transient model failure once; account/configuration failures need intervention. */
    suspend fun completeWithRetry(request: ChatRequest): ChatResponse = try {
        operator.complete(request)
    } catch (e: LlmException) {
        if (!e.retryable) throw e
        operator.complete(request)
    }

    private fun operatorRequest(
        task: TaskRun,
        subGoal: String?,
        constraint: StepConstraint?,
        screen: ScreenState,
        history: List<String>,
        feedback: List<String>,
    ): ChatRequest {
        val sections = buildList {
            add(if (subGoal != null) Prompts.subGoalSection(subGoal, task.goal) else Prompts.goalSection(task.goal))
            task.skill?.let { s -> s.instructions.takeIf { it.isNotBlank() }?.let { add(Prompts.skillSection(s.name, it)) } }
            if (task.references.isNotBlank()) add(Prompts.MEMORY_REFS_HEADER + "\n" + task.references)
            if (task.howto.isNotBlank()) add(Prompts.HOWTO_HEADER + "\n" + task.howto)
            if (history.isNotEmpty()) add(Prompts.HISTORY_HEADER + "\n" + history.takeLast(config.historyWindow).joinToString("\n"))
            if (feedback.isNotEmpty()) add(Prompts.FEEDBACK_HEADER + "\n" + feedback.joinToString("\n"))
            constraint?.let { add(Prompts.singleStep(it.kind, it.target, it.text)) }
            add(ScreenText.section(screen, config.maxNodes))
            add(Prompts.NEXT_STEP)
        }
        return ChatRequest(
            messages = listOf(ChatMessage.system(Prompts.operatorSystem(config.operatorNotes)), userMessage(sections.joinToString("\n\n"), screen)),
            jsonMode = true,
        )
    }

    private fun userMessage(text: String, screen: ScreenState) = ChatMessage(
        ChatRole.USER,
        listOfNotNull(ContentPart.Text(text), screen.screenshotPng?.let { ContentPart.ImagePng(it) }),
    )

    /** Diagnostic only: a throwing receiver must never change what the loop does. */
    private fun report(line: String) {
        val sink = metrics ?: return
        try {
            sink(line)
        } catch (_: Exception) {
        }
    }

    private fun elapsedMs(startNanos: Long): Long = (System.nanoTime() - startNanos) / 1_000_000

    companion object {
        const val PARSE_FAILURE_LIMIT = 3
        private const val FAST_REASON = "本地快路径：唯一匹配"
    }
}
