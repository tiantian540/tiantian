package dev.xiaoman.agent

import dev.xiaoman.api.AgentEvent
import dev.xiaoman.api.SkillRepository
import dev.xiaoman.api.UserChannel
import dev.xiaoman.skills.SkillParser
import java.io.File

/** Raised when a drafted skill is refused; the message is shown to the model and the user. */
internal class SkillRejected(message: String) : Exception(message)

/**
 * Lets the assistant write a new skill into the user's skill folder (the same folder `cli push`
 * targets). Everything is validated with [SkillParser] before anything touches the disk, the user
 * must approve the exact files, and the repository is reloaded afterwards.
 *
 * The tool itself only writes text files: it never performs a device action, so it does not go
 * through Sentinel. Skills written here run through the normal `guard → authorize → perform`
 * path later, exactly like hand-written ones.
 */
internal class SkillAuthor(
    private val userDir: File,
    private val bundledDir: File?,
    private val repo: SkillRepository,
    private val users: UserChannel,
) {
    data class Draft(
        val name: String,
        val description: String,
        val apps: List<String>,
        val instructions: String,
        val steps: List<String>,
    )

    data class Authored(val name: String, val path: String, val hasFlow: Boolean)

    suspend fun author(taskId: String, draft: Draft, emit: (AgentEvent) -> Unit): Authored {
        val name = draft.name.trim()
        if (!SkillParser.NAME.matches(name) || name.length > MAX_NAME) {
            throw SkillRejected("技能名 '$name' 不合法：只能用 a-z、0-9 和 -，不超过 $MAX_NAME 个字符")
        }
        val markdown = render(name, draft)
        val flow = flowYaml(draft.steps)
        checkSize(markdown, flow)
        refuseBypass(listOf(draft.description, draft.instructions, draft.steps.joinToString("\n")).joinToString("\n"))
        val target = targetDir(name)
        val (parsed, problems) = SkillParser.parseFolder(name, markdown, flow, target.path)
        if (parsed == null || problems.isNotEmpty()) {
            throw SkillRejected("技能没有通过校验：" + problems.take(3).joinToString("；") { it.message })
        }
        refuseBuiltIn(name)
        confirm(taskId, draft, target, flow != null, emit)
        write(target, markdown, flow)
        val reloaded = repo.reload()
        if (reloaded.skills.none { it.name == name && inside(userDir, it.source) }) {
            throw SkillRejected("技能已写入 ${target.path}，但重新加载后没有生效；请重启 App 后再检查")
        }
        return Authored(name, target.path, flow != null)
    }

    private fun render(name: String, draft: Draft): String {
        val description = draft.description.trim()
        if (description.isEmpty() || '\n' in description) throw SkillRejected("简介必须是一行非空文字")
        val body = draft.instructions.trim()
        if (body.isEmpty()) throw SkillRejected("正文不能为空")
        val apps = draft.apps.map { it.trim() }.filter { it.isNotEmpty() }
        val front = buildString {
            appendLine("---")
            appendLine("name: $name")
            appendLine("description: '${description.replace("'", "''")}'")
            if (apps.isNotEmpty()) appendLine("apps: [${apps.joinToString(", ") { "'${it.replace("'", "''")}'" }}]")
            appendLine("channel: gui")
            appendLine("---")
        }
        return front + body + "\n"
    }

    /** `steps` arrive as one flow line each (`launch: com.x`, `type: { target: a, text: "{b}" }`). */
    private fun flowYaml(steps: List<String>): String? {
        if (steps.isEmpty()) return null
        val lines = steps.map { raw ->
            val line = raw.trim().removePrefix("- ").trim()
            if (line.isEmpty() || '\n' in line || '\r' in line) throw SkillRejected("每个步骤必须是单行文字")
            if (!line.contains(':')) throw SkillRejected("步骤 '$line' 必须是 '类型: 值' 的形式")
            line
        }
        return buildString {
            appendLine("steps:")
            lines.forEach { appendLine("  - $it") }
        }
    }

    private fun checkSize(markdown: String, flow: String?) {
        val md = markdown.toByteArray(Charsets.UTF_8).size
        val fl = flow?.toByteArray(Charsets.UTF_8)?.size ?: 0
        if (md > MAX_SKILL_MD) throw SkillRejected("正文太大：SKILL.md 上限 $MAX_SKILL_MD 字节")
        if (fl > MAX_FLOW) throw SkillRejected("步骤太大：flow.yaml 上限 $MAX_FLOW 字节")
        if (md + fl > MAX_TOTAL) throw SkillRejected("正文加步骤太大：合计上限 $MAX_TOTAL 字节")
    }

    private fun refuseBypass(instructions: String) {
        val flat = instructions.replace(WHITESPACE, "")
        val hit = BYPASS.firstOrNull { flat.contains(it) }
        if (hit != null) throw SkillRejected("正文包含绕过安全确认的话术「$hit」，技能未保存")
    }

    private fun targetDir(name: String): File {
        val target = File(userDir, name)
        val root = userDir.canonicalFile
        if (target.canonicalFile.parentFile != root) throw SkillRejected("技能名不能指向技能目录之外")
        return target
    }

    /** A user skill overrides a bundled one with the same name, so this must be refused. */
    private suspend fun refuseBuiltIn(name: String) {
        if (bundledDir != null && File(bundledDir, name).exists()) {
            throw SkillRejected("已存在同名内置技能 '$name'，请换一个名字")
        }
        val clash = repo.list().firstOrNull { it.name == name }
        if (clash != null && !inside(userDir, clash.source)) {
            throw SkillRejected("已存在同名内置技能 '$name'，请换一个名字")
        }
    }

    private suspend fun confirm(taskId: String, draft: Draft, target: File, hasFlow: Boolean, emit: (AgentEvent) -> Unit) {
        val preview = buildString {
            appendLine("要把下面这个流程保存成技能吗？")
            appendLine("名字：${draft.name.trim()}")
            appendLine("简介：${draft.description.trim()}")
            appendLine("路径：${target.path}")
            appendLine("文件：SKILL.md" + if (hasFlow) "、flow.yaml" else "")
            if (target.exists()) appendLine("注意：将覆盖已有的同名自建技能")
            appendLine()
            appendLine(draft.instructions.trim())
        }
        emit(AgentEvent.NeedsUser(taskId, preview, takeover = false))
        if (!FlowRunner.isYes(users.ask(taskId, preview, false))) {
            throw SkillRejected("用户没有确认，技能没有保存")
        }
    }

    /** Writes to a hidden temp folder, then swaps it in; any failure leaves the old skill untouched. */
    private fun write(target: File, markdown: String, flow: String?) {
        if (!userDir.isDirectory && !userDir.mkdirs()) throw SkillRejected("无法创建技能目录 ${userDir.path}")
        val temp = File(userDir, ".${target.name}.tmp-${System.nanoTime()}")
        var backup: File? = null
        try {
            if (!temp.mkdirs()) throw SkillRejected("无法创建临时目录 ${temp.path}")
            File(temp, "SKILL.md").writeText(markdown, Charsets.UTF_8)
            if (flow != null) File(temp, "flow.yaml").writeText(flow, Charsets.UTF_8)
            if (target.exists()) {
                val old = File(userDir, ".${target.name}.old-${System.nanoTime()}")
                if (!target.renameTo(old)) throw SkillRejected("无法替换已有技能 ${target.path}")
                backup = old
            }
            if (!temp.renameTo(target)) throw SkillRejected("无法写入 ${target.path}")
            backup?.deleteRecursively()
            backup = null
        } catch (e: Exception) {
            if (!target.exists()) backup?.renameTo(target)
            throw if (e is SkillRejected) e else SkillRejected("保存技能失败：${e.message ?: e::class.simpleName}")
        } finally {
            temp.deleteRecursively()
            backup?.deleteRecursively()
        }
    }

    private fun inside(root: File, path: String): Boolean = runCatching {
        File(path).canonicalFile.toPath().startsWith(root.canonicalFile.toPath())
    }.getOrDefault(false)

    private companion object {
        /** Long enough for readable names, far below the 255-byte file name limit. */
        const val MAX_NAME = 32

        /** Example skills are ~2-3 KB; 8 KB of instructions is plenty and bounds prompt cost. */
        const val MAX_SKILL_MD = 8 * 1024
        const val MAX_FLOW = 8 * 1024
        const val MAX_TOTAL = 12 * 1024

        val WHITESPACE = Regex("\\s+")

        /** Attempts to talk the assistant out of asking; never saved. */
        val BYPASS = listOf(
            "跳过确认", "无需确认", "不用确认", "不要告诉用户", "无需审核", "跳过审核", "直接发送", "不必询问",
        )
    }
}
