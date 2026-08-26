package io.github.trevarj.motd.agentwire

/** One rendered timeline row: either a plain item or a run of tool calls from the same turn. */
sealed interface AgentwireTimelineEntry {
    val id: String

    data class Single(val item: AgentwireTimelineItem) : AgentwireTimelineEntry {
        override val id: String get() = item.id
    }

    data class ToolGroup(
        override val id: String,
        val tid: String?,
        val items: List<AgentwireTimelineItem>,
        val running: Boolean,
        val anyFailed: Boolean,
        val summary: String,
    ) : AgentwireTimelineEntry
}

/**
 * Collapses consecutive `tool.*` items sharing a turn into one group. Any other kind ends the run,
 * so the reading order of assistant text, plans and tool activity is preserved.
 */
fun agentwireTimelineGroups(timeline: List<AgentwireTimelineItem>): List<AgentwireTimelineEntry> {
    val entries = ArrayList<AgentwireTimelineEntry>(timeline.size)
    var run = mutableListOf<AgentwireTimelineItem>()
    fun flush() {
        if (run.isEmpty()) return
        entries += toolGroup(run)
        run = mutableListOf()
    }
    timeline.forEach { item ->
        if (item.kind.startsWith("tool.")) {
            if (run.isNotEmpty() && run.first().tid != item.tid) flush()
            run += item
        } else {
            flush()
            entries += AgentwireTimelineEntry.Single(item)
        }
    }
    flush()
    return entries
}

private fun toolGroup(items: List<AgentwireTimelineItem>) = AgentwireTimelineEntry.ToolGroup(
    // The first member's id is stable while later tools join the same run, so LazyColumn keeps
    // the group's slot instead of recreating it on every tool event.
    id = "tools:${items.first().id}",
    tid = items.first().tid,
    items = items.toList(),
    running = items.any(AgentwireTimelineItem::running),
    anyFailed = items.any { it.success == false },
    summary = toolSummary(items),
)

private fun toolSummary(items: List<AgentwireTimelineItem>): String {
    val counts = LinkedHashMap<String, Int>()
    items.forEach { counts.merge(it.toolName(), 1, Int::plus) }
    val detail = counts.entries.joinToString(", ") { (name, count) ->
        if (count == 1) name else "$name ×$count"
    }
    return "${items.size} ${if (items.size == 1) "tool" else "tools"} · $detail"
}

/** Backends label a call freely ("$ git status"), so the machine-readable kind is preferred. */
private fun AgentwireTimelineItem.toolName(): String =
    data.string("kind")?.takeIf(String::isNotBlank)
        ?: title.trim().substringBefore(' ').takeIf(String::isNotBlank)
        ?: "tool"
