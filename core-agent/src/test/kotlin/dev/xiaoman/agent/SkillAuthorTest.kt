package dev.xiaoman.agent

import dev.xiaoman.api.AgentConfig
import dev.xiaoman.api.AgentEvent
import dev.xiaoman.api.ModelRole
import dev.xiaoman.api.Skill
import dev.xiaoman.api.SkillLoadResult
import dev.xiaoman.api.SkillRepository
import dev.xiaoman.api.ToolCall
import dev.xiaoman.skills.SkillParser
import kotlinx.coroutines.test.runTest
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** `author_skill`: validate first, ask the user, write atomically, reload. */
class SkillAuthorTest {
    private val userDir = createTempDirectory("skills-user").toFile()
    private val bundledDir = createTempDirectory("skills-bundled").toFile()

    @AfterTest
    fun cleanUp() {
        userDir.deleteRecursively()
        bundledDir.deleteRecursively()
    }

    @Test
    fun validDraftIsWrittenAndReloaded() = runTest {
        val skills = RecordingSkills(userDir)
        val users = FakeUsers("好")
        val author = SkillAuthor(userDir, bundledDir, skills, users)

        val result = author.author(
            "t1",
            draft(
                instructions = "第一步：打开微信\n第二步：找到联系人\n第三步：发送",
                steps = listOf("launch: com.tencent.mm", "tap: 发送"),
            ),
            {},
        )

        val dir = File(userDir, "wechat-test")
        assertTrue(dir.isDirectory)
        assertEquals(dir.path, result.path)
        assertTrue(result.hasFlow)
        val md = File(dir, "SKILL.md").readText()
        assertTrue("name: wechat-test" in md)
        assertTrue("com.tencent.mm" in md)
        assertTrue("第一步：打开微信" in md)
        assertTrue("launch: com.tencent.mm" in File(dir, "flow.yaml").readText())
        assertEquals(1, skills.reloads)
        assertEquals(1, users.asked.size)
        assertTrue("wechat-test" in users.asked.single().first)
        assertTrue("第三步：发送" in users.asked.single().first)
        assertFalse(userDir.listFiles()!!.any { it.name.startsWith(".") })
    }

    @Test
    fun illegalNamesAreRejectedAndNothingIsCreated() = runTest {
        val skills = RecordingSkills(userDir)
        val author = SkillAuthor(userDir, bundledDir, skills, FakeUsers())
        val bad = listOf("Bad-Name", "has space", "../escape", "a/b", "UPPER", "a".repeat(33))
        for (name in bad) {
            assertFailsWith<SkillRejected>("$name 应该被拒绝") {
                author.author("t1", draft(name = name), {})
            }
        }
        assertFalse(File(userDir, "../escape").exists())
        assertTrue(userDir.listFiles()!!.isEmpty())
        assertEquals(0, skills.reloads)
    }

    @Test
    fun builtInNamesAreRefused() = runTest {
        File(bundledDir, "taxi-home").mkdirs()
        val author = SkillAuthor(userDir, bundledDir, RecordingSkills(userDir), FakeUsers())

        val error = assertFailsWith<SkillRejected> {
            author.author("t1", draft(name = "taxi-home"), {})
        }

        assertTrue("内置" in (error.message ?: ""))
        assertFalse(File(userDir, "taxi-home").exists())
    }

    @Test
    fun bypassPhrasesAreRefusedWithTheHitWord() = runTest {
        val author = SkillAuthor(userDir, bundledDir, RecordingSkills(userDir), FakeUsers())

        val error = assertFailsWith<SkillRejected> {
            author.author("t1", draft(instructions = "点击发送，跳过确认即可"), {})
        }

        assertTrue("跳过确认" in (error.message ?: ""), error.message)
        assertTrue(userDir.listFiles()!!.isEmpty())
    }

    @Test
    fun declinedConfirmationWritesNothing() = runTest {
        val skills = RecordingSkills(userDir)
        val author = SkillAuthor(userDir, bundledDir, skills, FakeUsers("不"))

        assertFailsWith<SkillRejected> { author.author("t1", draft(), {}) }

        assertTrue(userDir.listFiles()!!.isEmpty())
        assertEquals(0, skills.reloads)
    }

    @Test
    fun unwritableFolderLeavesNoResidue() = runTest {
        val broken = File(createTempDirectory("skills-broken").toFile(), "not-a-dir")
        broken.writeText("x")
        val skills = RecordingSkills(broken)
        val author = SkillAuthor(broken, bundledDir, skills, FakeUsers("好"))

        assertFailsWith<SkillRejected> { author.author("t1", draft(), {}) }

        assertEquals(listOf("not-a-dir"), broken.parentFile!!.list()!!.toList())
        assertEquals(0, skills.reloads)
    }

    @Test
    fun chatToolWritesTheSkillThroughTheAgent() = runTest {
        val skills = RecordingSkills(userDir)
        val chat = ScriptedLlm(ModelRole.CHAT)
        val tool = """{"name":"wechat-test","description":"给联系人发消息","apps":["com.tencent.mm"],""" +
            """"instructions":"打开微信，找到联系人，确认后发送","steps":["launch: com.tencent.mm"]}"""
        chat.tools(ToolCall(id = "1", name = "author_skill", argumentsJson = tool)).reply("已经保存好了")
        val events = Events()
        val agent = XiaomanAgent(
            chat = chat,
            operator = ScriptedLlm(ModelRole.OPERATOR),
            device = FakeDevice(screen("com.android.launcher", "设置")),
            memory = InMemoryMemory(),
            skills = skills,
            sentinel = FakeSentinel(),
            users = FakeUsers("好"),
            reminders = FakeReminderScheduler(),
            config = AgentConfig(),
            skillsDir = userDir,
            bundledSkillsDir = bundledDir,
        )

        agent.handle("以后都按这个流程给张三发微信", events, Stop())

        assertTrue(File(userDir, "wechat-test/SKILL.md").isFile)
        assertEquals(1, skills.reloads)
        assertTrue(events.all.any { it is AgentEvent.ToolCalled && it.tool == "author_skill" && it.ok })
        assertTrue(events.all.any { it is AgentEvent.NeedsUser })
    }

    private fun draft(
        name: String = "wechat-test",
        instructions: String = "打开微信，找到联系人，确认后发送",
        steps: List<String> = emptyList(),
    ) = SkillAuthor.Draft(
        name = name,
        description = "给联系人发一条消息",
        apps = listOf("com.tencent.mm"),
        instructions = instructions,
        steps = steps,
    )
}

/** Minimal repository that re-reads [userDir] on reload, like [dev.xiaoman.skills.DirectorySkillRepository]. */
private class RecordingSkills(private val userDir: File) : SkillRepository {
    var reloads = 0
        private set

    override suspend fun reload(): SkillLoadResult {
        reloads++
        val found = userDir.listFiles { f -> f.isDirectory && !f.name.startsWith(".") }.orEmpty().mapNotNull { dir ->
            val md = File(dir, "SKILL.md").takeIf { it.isFile }?.readText() ?: return@mapNotNull null
            SkillParser.parseFolder(dir.name, md, File(dir, "flow.yaml").takeIf { it.isFile }?.readText(), dir.path).first
        }
        return SkillLoadResult(found, emptyList())
    }

    override suspend fun list(): List<Skill> = emptyList()
    override suspend fun get(name: String): Skill? = null
}
