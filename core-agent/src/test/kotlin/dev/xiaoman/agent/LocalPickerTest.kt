package dev.xiaoman.agent

import dev.xiaoman.api.Bounds
import dev.xiaoman.api.ScreenState
import dev.xiaoman.api.UiNode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class LocalPickerTest {
    @Test fun exactMatchWinsOverSubstring() {
        val pick = LocalPicker.pick(
            "tap", "确认",
            screenOf(node(0, text = "确认呼叫"), node(1, text = "确认")),
        )
        assertEquals(1, pick?.node?.id)
        assertEquals("exact", pick?.reason)
    }

    @Test fun twoExactMatchesReturnNull() {
        assertNull(LocalPicker.pick("tap", "确认", screenOf(node(0, text = "确认"), node(1, text = "确认"))))
    }

    @Test fun twoSubstringMatchesReturnNull() {
        assertNull(
            LocalPicker.pick("tap", "确认", screenOf(node(0, text = "确认叫车"), node(1, text = "确认支付"))),
        )
    }

    @Test fun normalizationIgnoresCaseAndWhitespace() {
        val pick = LocalPicker.pick("tap", "abc", screenOf(node(0, text = "  A B c ")))
        assertEquals(0, pick?.node?.id)
    }

    @Test fun substringInsideLongerNodeTextMatches() {
        val pick = LocalPicker.pick("tap", "确认呼叫", screenOf(node(0, text = "确认呼叫 87元")))
        assertEquals(0, pick?.node?.id)
        assertEquals("substring", pick?.reason)
    }

    @Test fun descriptionIsUsedWhenTextIsMissing() {
        val pick = LocalPicker.pick("tap", "返回", screenOf(node(0, desc = "返回")))
        assertEquals(0, pick?.node?.id)
    }

    @Test fun emptyBoundsAreExcluded() {
        assertNull(
            LocalPicker.pick("tap", "登录", screenOf(node(0, text = "登录", bounds = Bounds(10, 10, 10, 10)))),
        )
    }

    @Test fun passwordNodesAreNeverPicked() {
        assertNull(
            LocalPicker.pick("tap", "密码", screenOf(node(0, desc = "密码", editable = true, password = true))),
        )
    }

    @Test fun typeOnlyMatchesEditableNodes() {
        assertNull(LocalPicker.pick("type", "目的地", screenOf(node(0, text = "目的地"))))
        assertEquals(0, LocalPicker.pick("type", "目的地", screenOf(node(0, text = "目的地", editable = true)))?.node?.id)
    }

    @Test fun otherStepKindsAreLeftToTheModel() {
        assertNull(LocalPicker.pick("assert", "确认", screenOf(node(0, text = "确认"))))
    }

    @Test fun secureScreensAreNeverUsed() {
        assertNull(LocalPicker.pick("tap", "确认", screenOf(node(0, text = "确认"), secure = true)))
    }

    private fun node(
        id: Int,
        text: String? = null,
        desc: String? = null,
        editable: Boolean = false,
        password: Boolean = false,
        bounds: Bounds = Bounds(0, 100, 1000, 200),
    ) = UiNode(
        id = id,
        text = text,
        description = desc,
        bounds = bounds,
        clickable = true,
        editable = editable,
        password = password,
    )

    private fun screenOf(vararg nodes: UiNode, secure: Boolean = false) = ScreenState(
        screenshotPng = byteArrayOf(1),
        widthPx = 1080,
        heightPx = 2400,
        packageName = "com.example.app",
        nodes = nodes.toList(),
        secure = secure,
    )
}
