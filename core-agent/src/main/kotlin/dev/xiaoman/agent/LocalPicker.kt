package dev.xiaoman.agent

import dev.xiaoman.api.ScreenState
import dev.xiaoman.api.UiNode

/**
 * Local element picker for flow steps whose target is already written down (`tap` / `type`).
 *
 * It only *chooses a node*: it never invents coordinates, never decides whether an action is safe
 * and never bypasses Sentinel. Anything short of exactly one match returns null, and the caller
 * then uses the model channel unchanged. See docs/FAST_PATH.md.
 */
internal object LocalPicker {
    /** [node] is the unique match; [reason] is `exact` or `substring` and goes into the timing log. */
    data class Pick(val node: UiNode, val reason: String)

    fun pick(kind: String, target: String, screen: ScreenState): Pick? {
        if (kind != "tap" && kind != "type") return null
        // A secure screen never reaches the picker in the loop; keep the guard local anyway.
        if (screen.secure) return null
        val wanted = normalize(target)
        if (wanted.isEmpty()) return null

        val candidates = screen.nodes.filter { usable(it, kind) }

        val exact = candidates.filter { node -> values(node).any { it == wanted } }
        if (exact.size > 1) return null
        if (exact.size == 1) return Pick(exact.single(), "exact")

        val partial = candidates.filter { node -> values(node).any { matches(it, wanted) } }
        return if (partial.size == 1) Pick(partial.single(), "substring") else null
    }

    /** Password fields, zero-area bounds and (for `type`) non-editable nodes can never be picked. */
    private fun usable(node: UiNode, kind: String): Boolean =
        !node.password &&
            node.bounds.right > node.bounds.left && node.bounds.bottom > node.bounds.top &&
            (kind != "type" || node.editable)

    private fun values(node: UiNode): List<String> =
        listOfNotNull(node.text, node.description).map(::normalize).filter { it.isNotEmpty() }

    /** `确认呼叫 87元` contains `确认呼叫`; a 2+ character node text inside the target also counts. */
    private fun matches(value: String, wanted: String): Boolean =
        value.contains(wanted) || (value.length >= 2 && wanted.contains(value))

    private fun normalize(s: String): String = s.trim().lowercase().replace(WHITESPACE, "")

    private val WHITESPACE = Regex("\\s+")
}
