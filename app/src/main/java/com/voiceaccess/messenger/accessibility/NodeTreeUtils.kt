package com.voiceaccess.messenger.accessibility

import android.os.Build
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Generic helpers for walking the on-screen accessibility node tree. None of
 * this is Outlook/WhatsApp-specific — the per-app knowledge lives entirely
 * in [AppUiConfig] as lists of candidate resource IDs, and everything here
 * just tries those candidates and falls back to text/content-description
 * matching when IDs don't hit (they will drift across app updates).
 */
object NodeTreeUtils {

    /** Breadth-first walk collecting every node currently in the subtree. */
    private fun allNodes(root: AccessibilityNodeInfo): List<AccessibilityNodeInfo> {
        val result = mutableListOf<AccessibilityNodeInfo>()
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            result.add(node)
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { queue.add(it) }
            }
        }
        return result
    }

    /** Tries each fully-qualified resource id in order, returns the first match found. */
    fun findFirstByViewIdAny(root: AccessibilityNodeInfo, viewIds: List<String>): AccessibilityNodeInfo? {
        for (id in viewIds) {
            val matches = root.findAccessibilityNodeInfosByViewId(id)
            if (matches.isNotEmpty()) return matches[0]
        }
        return null
    }

    fun findAllByViewIdAny(root: AccessibilityNodeInfo, viewIds: List<String>): List<AccessibilityNodeInfo> {
        for (id in viewIds) {
            val matches = root.findAccessibilityNodeInfosByViewId(id)
            if (matches.isNotEmpty()) return matches
        }
        return emptyList()
    }

    /** Fallback for when resource IDs have drifted: match visible text. */
    fun findFirstByTextContains(root: AccessibilityNodeInfo, needle: String): AccessibilityNodeInfo? =
        allNodes(root).firstOrNull { node ->
            node.text?.toString()?.contains(needle, ignoreCase = true) == true
        }

    /** Fallback for icon-only controls (e.g. a search magnifying glass with no label). */
    fun findFirstByContentDescriptionAny(root: AccessibilityNodeInfo, candidates: List<String>): AccessibilityNodeInfo? =
        allNodes(root).firstOrNull { node ->
            val description = node.contentDescription?.toString() ?: return@firstOrNull false
            candidates.any { description.contains(it, ignoreCase = true) }
        }

    /**
     * Scrapes every piece of visible text under [root] into a single string,
     * used to read a full email/chat body that the notification preview
     * truncated. De-duplicates adjacent repeats, since many layouts mirror
     * text into both a text node and a content-description on a wrapper.
     */
    fun collectVisibleText(root: AccessibilityNodeInfo): String {
        val lines = LinkedHashSet<String>()
        for (node in allNodes(root)) {
            if (!node.isVisibleToUser) continue
            node.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let { lines.add(it) }
        }
        return lines.joinToString("\n")
    }

    /** Walks up the parent chain to find a clickable ancestor (list rows are rarely the leaf node). */
    fun findClickableAncestorOrSelf(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var current: AccessibilityNodeInfo? = node
        var depth = 0
        while (current != null && depth < 10) {
            if (current.isClickable) return current
            current = current.parent
            depth++
        }
        return null
    }

    fun click(node: AccessibilityNodeInfo): Boolean {
        val target = if (node.isClickable) node else findClickableAncestorOrSelf(node)
        return target?.performAction(AccessibilityNodeInfo.ACTION_CLICK) ?: false
    }

    /** Programmatically types [text] into an editable node (search box, reply box, etc). */
    fun setText(node: AccessibilityNodeInfo, text: String): Boolean {
        val arguments = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        return node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
    }

    /**
     * Presses the IME "search"/"send" action on the on-screen keyboard, where supported
     * (API 30+ only — the field doesn't exist on older framework versions, so it must be
     * guarded rather than just referenced, unlike the older actions such as ACTION_CLICK).
     */
    fun submitImeAction(node: AccessibilityNodeInfo): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return false
        return node.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
    }

    /** Some EditTexts only accept ACTION_SET_TEXT once focused. Best-effort, ignore the result. */
    fun focus(node: AccessibilityNodeInfo) {
        node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
    }
}
