package io.github.trevarj.motd.agentwire

import io.github.trevarj.motd.irc.agentwire.AgentwireEnvelope
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

enum class AgentwireGate { LOADING, ORDINARY, BLOCKED, ACTIVE }

private val SESSION_OWNED_KINDS = setOf(
    "session.snapshot", "session.status", "user.prompt", "turn.started", "turn.completed",
    "turn.failed", "assistant.delta", "assistant.completed", "plan.updated", "tool.started",
    "tool.updated", "tool.completed", "usage.updated", "request.opened", "request.resolved",
    "approval.review.started", "approval.review.completed",
)

data class AgentwireListItem(
    val id: String,
    val title: String,
    val subtitle: String? = null,
    val raw: JsonObject = JsonObject(emptyMap()),
)

data class AgentwireModelOption(
    val value: String,
    val label: String,
    val efforts: List<String>,
    val defaultEffort: String? = null,
    val default: Boolean = false,
)

data class AgentwireQueueItem(
    val iid: String,
    val content: String,
    val position: Int,
    val sid: String? = null,
    val rev: Long? = null,
)

data class AgentwireRequest(
    val rid: String,
    val type: String,
    val summary: String?,
    val redacted: Boolean,
    val inactive: Boolean,
    val canSkip: Boolean,
    val questions: List<AgentwireQuestion> = emptyList(),
    val sid: String? = null,
)

data class AgentwireQuestion(
    val id: String,
    val header: String?,
    val prompt: String,
    val options: List<String>,
    val multiple: Boolean,
    val custom: Boolean,
)

/** Liveness of one session, bound or not, as last reported by `session.status`. */
data class AgentwireSessionStatus(
    val sid: String,
    val busy: Boolean,
    val flags: List<String>,
    val cwd: String?,
    val tuiAttached: Boolean?,
    val at: Long,
)

data class AgentwireTimelineItem(
    val id: String,
    val kind: String,
    val at: Long,
    val sid: String?,
    val tid: String?,
    val title: String,
    val body: String?,
    val running: Boolean = false,
    val success: Boolean? = null,
    val historical: Boolean = false,
    val data: JsonObject = JsonObject(emptyMap()),
    val backendItemId: String? = null,
)

data class AgentwireUiState(
    val gate: AgentwireGate = AgentwireGate.LOADING,
    val channel: String = "",
    val title: String = "Agentwire",
    val controllerAccount: String? = null,
    val backendAccount: String? = null,
    val backend: String? = null,
    val missingCaps: Set<String> = emptySet(),
    val connected: Boolean = false,
    val syncing: Boolean = false,
    val epoch: String? = null,
    val botAccount: String? = null,
    val activeSid: String? = null,
    val cwd: String? = null,
    val busy: Boolean = false,
    val currentTid: String? = null,
    val actions: Set<String> = emptySet(),
    val supportedSettings: Set<String> = emptySet(),
    val settings: Map<String, String> = emptyMap(),
    val modelOptions: List<AgentwireModelOption> = emptyList(),
    val workspaceChildren: Map<String, List<AgentwireListItem>> = emptyMap(),
    val liveSessions: List<AgentwireListItem> = emptyList(),
    val workspaceSessions: Map<String, List<AgentwireListItem>> = emptyMap(),
    val loadedSessionDirectories: Set<String> = emptySet(),
    val queue: List<AgentwireQueueItem> = emptyList(),
    val requests: List<AgentwireRequest> = emptyList(),
    val timeline: List<AgentwireTimelineItem> = emptyList(),
    val sessionStatuses: Map<String, AgentwireSessionStatus> = emptyMap(),
    val recentSessions: List<AgentwireRecentSession> = emptyList(),
    val actionStatus: Map<String, String> = emptyMap(),
    val historyLoading: Boolean = false,
    val historyPage: String? = null,
    val historyRequestId: String? = null,
    val historySid: String? = null,
    val historyCursor: String? = null,
    val historyStaged: List<AgentwireTimelineItem> = emptyList(),
    val historyBeforeAt: Long? = null,
    val olderHistoryAvailable: Boolean = false,
    val error: String? = null,
    val transcriptOverride: Boolean = false,
    val autoReviewConfirmed: Boolean = false,
)

class AgentwireReducer {
    private val seen = LinkedHashSet<String>()
    private val revisions = HashMap<String, Long>()

    fun reset() {
        seen.clear()
        revisions.clear()
    }

    fun reduce(state: AgentwireUiState, envelope: AgentwireEnvelope): AgentwireUiState {
        if (envelope.history == true) {
            if (
                envelope.sid == null ||
                envelope.sid != state.activeSid ||
                envelope.sid != state.historySid ||
                envelope.reply != state.historyRequestId
            ) {
                return state
            }
            if (!seen.add("${envelope.sid}:${envelope.id}")) return state
            val anchored = state.copy(
                historyBeforeAt = state.historyBeforeAt?.let { minOf(it, envelope.at) } ?: envelope.at,
            )
            return reduceHistorical(anchored, envelope)
        }
        // `session.status` is the one session-owned kind that may describe a session this channel
        // is not bound to; it feeds the status registry instead of the bound timeline.
        if (
            envelope.kind in SESSION_OWNED_KINDS &&
            envelope.kind != "session.status" &&
            envelope.sid != null &&
            envelope.sid != state.activeSid
        ) {
            return state
        }
        if (!seen.add(envelope.id)) return state
        val entityKey = listOfNotNull(envelope.kind.substringBeforeLast('.'), envelope.sid, envelope.iid, envelope.rid)
            .joinToString(":")
        envelope.rev?.let { rev ->
            val prior = revisions[entityKey]
            if (prior != null && rev <= prior) return state
            revisions[entityKey] = rev
        }
        val data = envelope.data ?: JsonObject(emptyMap())
        return when (envelope.kind) {
            "agent.hello" -> state.copy(
                syncing = true,
                epoch = envelope.epoch ?: data.string("epoch"),
                backend = data.string("backend") ?: state.backend,
                actions = data.stringList("actions").toSet(),
                supportedSettings = data.stringList("settings").toSet(),
                modelOptions = modelOptions(data),
                error = null,
            )
            "channel.snapshot" -> {
                val nextSid = data.obj("binding")?.string("sid")
                val changed = nextSid != state.activeSid
                if (changed) resetSessionTracking(envelope.id)
                state.copy(
                    syncing = false,
                    activeSid = nextSid,
                    cwd = data.obj("binding")?.string("cwd"),
                    busy = data.bool("busy") ?: false,
                    currentTid = data.string("tid"),
                    settings = data.objectStrings("settings").ifEmpty { state.settings },
                    queue = data.array("queue")?.mapNotNull(::queueItem).orEmpty(),
                    timeline = if (changed) emptyList() else state.timeline,
                    historyLoading = false,
                    historyPage = null,
                    historyRequestId = null,
                    historySid = null,
                    historyCursor = null,
                    historyStaged = emptyList(),
                    historyBeforeAt = null,
                    olderHistoryAvailable = false,
                )
            }
            "binding.changed" -> {
                val detached = data.containsKey("sid") && data["sid"] is JsonNull
                resetSessionTracking(envelope.id)
                state.copy(
                    activeSid = if (detached) null else envelope.sid ?: data.string("sid")
                        ?: data.obj("session")?.string("sid"),
                    cwd = if (detached) null else data.string("cwd") ?: data.obj("session")?.string("cwd"),
                    busy = false,
                    currentTid = null,
                    timeline = emptyList(),
                    actionStatus = emptyMap(),
                    historyLoading = false,
                    historyPage = null,
                    historyRequestId = null,
                    historySid = null,
                    historyCursor = null,
                    historyStaged = emptyList(),
                    historyBeforeAt = null,
                    olderHistoryAvailable = false,
                )
            }
            "session.snapshot" -> state.copy(
                cwd = data.string("cwd") ?: state.cwd,
                busy = data.bool("busy") ?: state.busy,
                currentTid = envelope.tid ?: data.string("tid") ?: state.currentTid,
                settings = data.objectStrings("settings").ifEmpty { state.settings },
                timeline = if (data.containsKey("recentOutputs")) {
                    restoredSessionTimeline(envelope, data)
                } else {
                    state.timeline
                },
            )
            "session.status" -> {
                val sid = envelope.sid
                val statuses = if (sid == null) {
                    state.sessionStatuses
                } else {
                    state.sessionStatuses + (sid to AgentwireSessionStatus(
                        sid = sid,
                        busy = data.bool("busy") ?: false,
                        flags = data.stringList("flags"),
                        cwd = data.string("cwd"),
                        tuiAttached = data.bool("tuiAttached"),
                        at = envelope.at,
                    ))
                }
                if (sid != null && sid != state.activeSid) {
                    state.copy(sessionStatuses = statuses)
                } else {
                    state.copy(
                        cwd = data.string("cwd") ?: state.cwd,
                        busy = data.bool("busy") ?: state.busy,
                        currentTid = envelope.tid ?: data.string("tid") ?: state.currentTid,
                        settings = data.objectStrings("settings").ifEmpty { state.settings },
                        sessionStatuses = statuses,
                    )
                }
            }
            "workspace.page" -> {
                val parent = data.string("parent").orEmpty()
                val page = workspaceItems(data)
                state.copy(
                    workspaceChildren = state.workspaceChildren + (parent to page),
                )
            }
            "session.page" -> {
                val page = pageItems(data, "sid")
                val cwd = data.string("cwd")
                val live = data.string("scope") == "live" || cwd == null
                val continuing = data.string("cursor") != null
                state.copy(
                    liveSessions = if (live) {
                        mergePage(state.liveSessions, page, continuing)
                    } else {
                        state.liveSessions
                    },
                    workspaceSessions = if (!live) {
                        val directory = requireNotNull(cwd)
                        state.workspaceSessions + (directory to mergePage(
                            state.workspaceSessions[directory].orEmpty(),
                            page,
                            continuing,
                        ))
                    } else {
                        state.workspaceSessions
                    },
                    loadedSessionDirectories = cwd?.let { state.loadedSessionDirectories + it }
                        ?: state.loadedSessionDirectories,
                )
            }
            "history.begin" -> if (matchesHistoryRequest(state, envelope)) {
                state.copy(
                    historyLoading = true,
                    historyPage = data.string("page"),
                    historyStaged = emptyList(),
                )
            } else {
                state
            }
            "history.end" -> if (matchesHistoryRequest(state, envelope)) {
                val count = data.int("count") ?: 0
                state.copy(
                    timeline = mergeHistoryPage(state.historyStaged, state.timeline),
                    historyLoading = false,
                    historyPage = data.string("page") ?: state.historyPage,
                    historyRequestId = null,
                    historyCursor = data.string("next"),
                    historyStaged = emptyList(),
                    olderHistoryAvailable = if (data.containsKey("next")) {
                        data.string("next") != null
                    } else {
                        count > 0
                    },
                )
            } else {
                state
            }
            "action.accepted", "action.succeeded", "action.failed", "action.uncertain" -> {
                val status = envelope.kind.substringAfter("action.")
                state.copy(
                    actionStatus = envelope.reply?.let { state.actionStatus + (it to status) } ?: state.actionStatus,
                    error = when (envelope.kind) {
                        "action.failed" -> data.string("message") ?: "Action failed"
                        "action.uncertain" -> data.string("message") ?: "Action outcome is unknown"
                        else -> state.error
                    },
                )
            }
            "queue.snapshot" -> state.copy(queue = data.array("items")?.mapNotNull(::queueItem).orEmpty())
            "queue.item.added", "queue.item.updated", "queue.item.moved" -> {
                val item = queueItem(data) ?: return state
                state.copy(queue = (state.queue.filterNot { it.iid == item.iid } + item).sortedBy { it.position })
            }
            "queue.item.removed" -> state.copy(queue = state.queue.filterNot { it.iid == envelope.iid || it.iid == data.string("iid") })
            "request.opened" -> {
                val request = request(envelope, data) ?: return state
                state.copy(
                    requests = state.requests.filterNot { it.rid == request.rid } + request,
                    timeline = state.timeline.upsert(envelope.timelineItem()),
                )
            }
            "request.resolved" -> state.copy(
                requests = state.requests.filterNot { it.rid == envelope.rid || it.rid == data.string("rid") },
                timeline = state.timeline.upsert(envelope.timelineItem()),
            )
            "turn.started" -> state.copy(
                busy = true, currentTid = envelope.tid,
                timeline = state.timeline.upsert(envelope.timelineItem(running = true)),
            )
            "turn.completed", "turn.failed" -> state.copy(
                busy = false, currentTid = null,
                timeline = state.timeline
                    .stopPlan(envelope.tid)
                    .upsert(envelope.timelineItem(success = envelope.kind == "turn.completed")),
            )
            "assistant.delta" -> state.copy(timeline = state.timeline.appendDelta(envelope))
            "user.prompt", "assistant.completed", "plan.updated", "tool.started", "tool.updated", "tool.completed",
            "usage.updated", "approval.review.started", "approval.review.completed" -> state.copy(
                timeline = state.timeline.upsert(
                    envelope.timelineItem(
                        running = envelope.timelineRunning(),
                        success = if (envelope.kind == "tool.completed") data.bool("success") else null,
                    ),
                ),
            )
            else -> state
        }
    }

    /** History rebuilds the transcript but must never replace the current channel binding or state. */
    private fun reduceHistorical(
        state: AgentwireUiState,
        envelope: AgentwireEnvelope,
    ): AgentwireUiState = when (envelope.kind) {
        "request.opened", "request.resolved" -> state.copy(
            historyStaged = state.historyStaged.upsert(envelope.timelineItem()),
        )
        "turn.started" -> state.copy(
            historyStaged = state.historyStaged.upsert(envelope.timelineItem(running = true)),
        )
        "turn.completed", "turn.failed" -> state.copy(
            historyStaged = state.historyStaged
                .stopPlan(envelope.tid)
                .upsert(envelope.timelineItem(success = envelope.kind == "turn.completed")),
        )
        "assistant.delta" -> state.copy(historyStaged = state.historyStaged.appendDelta(envelope))
        "user.prompt", "assistant.completed", "plan.updated", "tool.started", "tool.updated", "tool.completed",
        "usage.updated", "approval.review.started", "approval.review.completed" -> state.copy(
            historyStaged = state.historyStaged.upsert(
                envelope.timelineItem(
                    running = envelope.timelineRunning(),
                    success = if (envelope.kind == "tool.completed") {
                        envelope.data?.bool("success")
                    } else {
                        null
                    },
                ),
            ),
        )
        else -> state
    }

    private fun resetSessionTracking(envelopeId: String) {
        seen.clear()
        seen.add(envelopeId)
        revisions.clear()
    }

    private fun pageItems(data: JsonObject, identity: String): List<AgentwireListItem> {
        val values = data.array("items") ?: data.array(if (identity == "sid") "sessions" else "workspaces") ?: return emptyList()
        return values.mapNotNull { element ->
            val item = element as? JsonObject ?: return@mapNotNull null
            val id = item.string(identity) ?: return@mapNotNull null
            AgentwireListItem(id, item.string("title") ?: id, item.string("cwd"), item)
        }
    }

    private fun workspaceItems(data: JsonObject): List<AgentwireListItem> = data.array("items").orEmpty().mapNotNull { element ->
        val item = element as? JsonObject ?: return@mapNotNull null
        val path = item.string("path") ?: return@mapNotNull null
        AgentwireListItem(path, item.string("name") ?: path, path, item)
    }

    private fun modelOptions(data: JsonObject): List<AgentwireModelOption> =
        data.obj("settingOptions")?.array("model").orEmpty().mapNotNull { element ->
            val option = element as? JsonObject ?: return@mapNotNull null
            AgentwireModelOption(
                value = option.string("value") ?: return@mapNotNull null,
                label = option.string("label") ?: option.string("value").orEmpty(),
                efforts = option.stringList("efforts"),
                defaultEffort = option.string("defaultEffort"),
                default = option.bool("default") ?: false,
            )
        }
}

private fun matchesHistoryRequest(
    state: AgentwireUiState,
    envelope: AgentwireEnvelope,
): Boolean = envelope.sid != null &&
    envelope.sid == state.activeSid &&
    envelope.sid == state.historySid &&
    envelope.reply == state.historyRequestId

private fun mergePage(
    current: List<AgentwireListItem>,
    page: List<AgentwireListItem>,
    continuing: Boolean,
): List<AgentwireListItem> = if (continuing) {
    current.filterNot { old -> page.any { it.id == old.id } } + page
} else {
    page
}

private fun queueItem(element: JsonElement): AgentwireQueueItem? {
    val data = element as? JsonObject ?: return null
    return AgentwireQueueItem(
        iid = data.string("iid") ?: return null,
        content = data.string("content").orEmpty(),
        position = data.int("position") ?: 0,
        sid = data.string("sid"),
        rev = data["rev"]?.let { (it as? JsonPrimitive)?.contentOrNull?.toLongOrNull() },
    )
}

private fun request(envelope: AgentwireEnvelope, data: JsonObject): AgentwireRequest? {
    val rid = envelope.rid ?: data.string("rid") ?: return null
    val questions = data.array("questions").orEmpty().mapNotNull { element ->
        val question = element as? JsonObject ?: return@mapNotNull null
        AgentwireQuestion(
            id = question.string("id") ?: return@mapNotNull null,
            header = question.string("header"),
            prompt = question.string("prompt").orEmpty(),
            options = question.stringList("options"),
            multiple = question.bool("multiple") ?: false,
            custom = question.bool("custom") ?: false,
        )
    }
    return AgentwireRequest(
        rid, data.string("type") ?: "approval", data.string("summary"),
        data.bool("redacted") ?: false, data.bool("inactive") ?: false,
        data.bool("canSkip") ?: false, questions, envelope.sid ?: data.string("sid"),
    )
}

private fun AgentwireEnvelope.timelineItem(running: Boolean = false, success: Boolean? = null): AgentwireTimelineItem {
    val payload = data ?: JsonObject(emptyMap())
    val title = when {
        kind == "user.prompt" -> "You"
        kind.startsWith("assistant.") -> "Assistant"
        kind.startsWith("turn.") -> kind.substringAfter('.').replaceFirstChar(Char::uppercase)
        kind.startsWith("tool.") -> payload.string("label") ?: payload.string("kind") ?: "Tool"
        kind == "plan.updated" -> "Plan"
        kind == "usage.updated" -> "Usage"
        kind.startsWith("request.") -> payload.string("type")?.replaceFirstChar(Char::uppercase) ?: "Request"
        else -> kind
    }
    val body = when {
        kind.startsWith("tool.") -> toolPreview(payload)
        kind == "plan.updated" -> planPreview(payload)
        payload.bool("omitted") == true -> "Content omitted because it may contain a secret"
        else -> payload.string("content") ?: payload.string("summary") ?: payload.string("message")
    }
    return AgentwireTimelineItem(
        id, kind, at, sid, tid, title, body, running, success, history == true, payload, iid,
    )
}

private fun AgentwireEnvelope.timelineRunning(): Boolean = when (kind) {
    "plan.updated" -> data?.bool("running") ?: false
    else -> kind.endsWith("started") || kind.endsWith("updated")
}

private fun planPreview(data: JsonObject): String? {
    val summary = data.string("summary")
    val completed = data.int("completedSteps")
    val total = data.int("totalSteps")
    val progress = if (completed != null && total != null && total > 0) {
        "$completed of $total steps complete"
    } else {
        null
    }
    return listOfNotNull(summary, progress).takeIf { it.isNotEmpty() }?.joinToString("\n\n")
}

private fun toolPreview(data: JsonObject): String? {
    val sections = buildList {
        data.string("input")?.let { add("Command\n$it") }
        data.string("output")?.let { add("Output\n$it") }
        data.string("diff")?.let { add("Diff\n$it") }
        val status = data.string("status")
        val exitCode = data.int("exitCode")
        if (status != null || exitCode != null) {
            add(listOfNotNull(status?.let { "Status: $it" }, exitCode?.let { "exit $it" }).joinToString(" · "))
        }
    }
    return sections.takeIf { it.isNotEmpty() }?.joinToString("\n\n")
}

private fun restoredSessionTimeline(
    envelope: AgentwireEnvelope,
    data: JsonObject,
): List<AgentwireTimelineItem> {
    val outputs = data.array("recentOutputs").orEmpty().mapNotNull { it as? JsonObject }
    val activity = data.array("recentActivity").orEmpty().mapNotNull { it as? JsonObject }
    if (outputs.isEmpty() && activity.isEmpty()) {
        val status = when (data.string("status")) {
            "waiting" -> "Waiting for your input."
            "running" -> "Codex is currently working."
            "ready" -> "Session is ready for a prompt."
            else -> "Session attached."
        }
        return listOf(
            AgentwireTimelineItem(
                id = "restored:${envelope.sid}:${envelope.id}:status",
                kind = "session.status",
                at = envelope.at,
                sid = envelope.sid,
                tid = envelope.tid,
                title = "Session",
                body = status,
                running = data.bool("busy") ?: false,
                data = data,
            ),
        )
    }
    val restoredOutputs = outputs.mapIndexed { index, output ->
        AgentwireTimelineItem(
            id = "restored:${envelope.sid}:${output.string("iid") ?: index}",
            kind = "assistant.completed",
            at = envelope.at + index,
            sid = envelope.sid,
            tid = output.string("tid"),
            title = "Assistant",
            body = output.string("content"),
            historical = true,
            data = output,
            backendItemId = output.string("iid"),
        )
    }
    val restoredActivity = activity.mapIndexed { index, item ->
        val kind = item.string("kind") ?: "tool.started"
        val itemData = item.obj("data") ?: JsonObject(emptyMap())
        AgentwireTimelineItem(
            id = "restored:${envelope.sid}:${item.string("iid") ?: index}",
            kind = kind,
            at = envelope.at + outputs.size + index,
            sid = envelope.sid,
            tid = item.string("tid"),
            title = itemData.string("label") ?: itemData.string("kind") ?: "Tool",
            body = toolPreview(itemData),
            running = kind != "tool.completed",
            success = itemData.bool("success"),
            historical = true,
            data = itemData,
            backendItemId = item.string("iid"),
        )
    }
    return restoredOutputs + restoredActivity
}

private fun List<AgentwireTimelineItem>.upsert(item: AgentwireTimelineItem): List<AgentwireTimelineItem> {
    val stableId = item.stableTimelineId()
    if (stableId == null) return (this + item).sortedBy(AgentwireTimelineItem::at)
    val existing = indexOfFirst { old ->
        old.stableTimelineId() == stableId
    }
    return if (existing < 0) (this + item).sortedBy(AgentwireTimelineItem::at)
    else toMutableList().also { it[existing] = item }
}

private fun AgentwireTimelineItem.stableTimelineId(): String? {
    if (kind == "user.prompt") return "prompt:$sid:${backendItemId ?: id}"
    return tid?.let { turnId ->
        when {
            kind.startsWith("assistant.") -> backendItemId?.let { "assistant:$sid:$turnId:$it" }
            kind.startsWith("tool.") -> (backendItemId ?: data.string("id"))?.let {
                "tool:$sid:$turnId:$it"
            }
            kind == "plan.updated" -> backendItemId?.let { "plan:$sid:$turnId:$it" }
                ?: "plan:$sid:$turnId"
            kind.startsWith("turn.") -> "turn:$sid:$turnId"
            else -> null
        }
    }
}

private fun mergeHistoryPage(
    history: List<AgentwireTimelineItem>,
    current: List<AgentwireTimelineItem>,
): List<AgentwireTimelineItem> = (history + current).fold(emptyList()) { result, item ->
    result.upsert(item)
}

private fun List<AgentwireTimelineItem>.stopPlan(turnId: String?): List<AgentwireTimelineItem> =
    map { item ->
        if (turnId != null && item.tid == turnId && item.kind == "plan.updated") {
            item.copy(running = false)
        } else {
            item
        }
    }

private fun List<AgentwireTimelineItem>.appendDelta(envelope: AgentwireEnvelope): List<AgentwireTimelineItem> {
    val delta = envelope.data?.string("content").orEmpty()
    val existing = indexOfLast { it.tid == envelope.tid && it.kind.startsWith("assistant.") }
    if (existing < 0) return upsert(envelope.timelineItem(running = true))
    return toMutableList().also { items ->
        val prior = items[existing]
        items[existing] = prior.copy(body = prior.body.orEmpty() + delta, running = true)
    }
}

internal fun JsonObject.string(key: String): String? = (get(key) as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
internal fun JsonObject.bool(key: String): Boolean? = (get(key) as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull
internal fun JsonObject.int(key: String): Int? = (get(key) as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull
internal fun JsonObject.obj(key: String): JsonObject? = get(key) as? JsonObject
internal fun JsonObject.array(key: String): JsonArray? = get(key) as? JsonArray
internal fun JsonObject.stringList(key: String): List<String> = array(key).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
internal fun JsonObject.objectStrings(key: String): Map<String, String> = obj(key).orEmpty().mapNotNull { (name, value) ->
    (value as? JsonPrimitive)?.contentOrNull?.let { name to it }
}.toMap()
