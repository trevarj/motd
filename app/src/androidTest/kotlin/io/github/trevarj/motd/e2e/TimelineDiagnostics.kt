package io.github.trevarj.motd.e2e

import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.sqlite.db.SupportSQLiteProgram
import androidx.sqlite.db.SupportSQLiteQuery
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.platform.io.PlatformTestStorageRegistry
import io.github.trevarj.motd.data.db.HistoryGapEntity
import io.github.trevarj.motd.data.db.TimelineAnchor
import io.github.trevarj.motd.data.history.GapEdgeAnchor
import io.github.trevarj.motd.data.history.ResolvedGap
import io.github.trevarj.motd.data.visibility.MessageVisibilitySpec
import io.github.trevarj.motd.data.visibility.messagePagingQuery
import io.github.trevarj.motd.diagnostics.DiagnosticLogger
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Newest-first rows read straight from `messages`, enough to see who owns the newest positions. */
private const val RAW_ROW_LIMIT = 24

/** Source-query rows walked to locate the requested row's newest-first rank. */
private const val SOURCE_SCAN_LIMIT = 512

/** Composed timeline rows recorded; the viewport holds well under this many. */
private const val COMPOSED_ROW_LIMIT = 60

/**
 * Failure-time snapshot explaining why one timeline row is not presented.
 *
 * A required timeline wait can only report "the row never appeared", which is true of
 * every competing explanation at once. This captures the four independent states that disagree in
 * different ways per hypothesis — what Paging presents, what the Paging key map resolves, what Room
 * holds, and where the room's history gaps resolve to — so the next red run names the cause instead
 * of restating the symptom.
 *
 * Two invariants make this safe to run inside a required journey:
 *
 * 1. **It cannot change the verdict.** Every capture runs strictly after the wait it documents has
 *    already returned or thrown, and every part of it is individually guarded. A diagnostic that
 *    throws is swallowed and recorded as a milestone, so the original failure is what propagates.
 * 2. **It cannot perturb Paging.** No scroll, no sweep, no `get()`. The semantics reads are
 *    property/collection lookups plus `IndexForKey`, which walks the presented list with `peek`
 *    and never triggers a load. Room is read through a second, `OPEN_READONLY` SQLite connection
 *    to the same file, so no write, no invalidation, and no `PagingSource` regeneration is
 *    possible — the observer-perturbs-observed failure this journey already suffered once.
 *
 * Privacy: ids, hashed msgids/tags, counts, indices, timestamps, and boolean flags only. Message
 * bodies, senders, room names, and credentials are never read out of the row, and the SQL below
 * selects those columns by name so a schema change cannot silently widen the dump.
 */
internal class TimelineDiagnostics(
    private val compose: ComposeTestRule,
    private val targetContext: Context,
    private val artifactPrefix: String,
    private val milestones: E2eMilestoneRecorder,
    private val bufferId: Long,
    private val probedEventId: Long,
    private val probedMsgid: String?,
) {
    /** Newest-first `(eventId, msgid)` pairs, resolved by the Room pass and reused by the probes. */
    private var newestRowKeys: List<Pair<Long, String?>> = emptyList()

    /**
     * Writes one snapshot to `<artifactPrefix>/<label>/<outcome>/timeline.json`.
     *
     * [outcome] is part of the path rather than the file name because the uploaded artifact's
     * privacy audit allowlists file names, not paths.
     */
    fun capture(
        label: String,
        outcome: String,
        containerTag: String,
        targetTag: String,
        targetKey: Long,
        budgetMs: Long,
    ) {
        val root = JSONObject()
        runCatching {
            root.put("schema", "timeline-row/2")
            root.put("label", label)
            root.put("outcome", outcome)
            root.put("budgetMs", budgetMs)
            root.put("capturedAtMs", System.currentTimeMillis())
            root.put(
                "target",
                JSONObject().apply {
                    putNullable("tagHash", DiagnosticLogger.Noop.fingerprint(targetTag))
                    put("pagingKey", targetKey)
                    put("probedEventId", probedEventId)
                    putNullable("probedMsgidHash", DiagnosticLogger.Noop.fingerprint(probedMsgid))
                    put("bufferId", bufferId)
                },
            )
            root.put("divider", guarded { dividerSnapshot() })
            // Room first: it resolves the newest event ids that the Paging key probes ask for.
            root.put("room", guarded { roomSnapshot(targetKey) })
            root.put("paging", guarded { pagingSnapshot(containerTag, targetTag, targetKey) })
        }.onFailure { root.put("captureError", it::class.java.name) }
        val delivered = E2eArtifactSink.write("$artifactPrefix/$label/$outcome/timeline.json", root.toString())
        milestones.record(
            "timeline_diagnostics",
            "label=$label outcome=$outcome sinks=$delivered",
        )
    }

    // ---------------------------------------------------------------- Compose / Paging surface

    /**
     * What the timeline actually presents right now.
     *
     * `presentedCount` is `LazyPagingItems.itemCount` (placeholders included) as published through
     * the list's `CollectionInfo`. `targetIndex` is the same `IndexForKey` lookup
     * `performScrollToKey` uses: `>= 0` means the row is a loaded, non-placeholder item at that
     * index; `-1` means the whole presented list holds no item with that key, which is exactly the
     * "loaded but unreachable by key" versus "not in the list at all" distinction the failure needs.
     */
    private fun pagingSnapshot(
        containerTag: String,
        targetTag: String,
        targetKey: Long,
    ): JSONObject {
        val out = JSONObject()
        val containers = compose.onAllNodesWithTag(containerTag, useUnmergedTree = true).fetchSemanticsNodes()
        out.put("containerTag", containerTag)
        out.put("containerCount", containers.size)
        val container = containers.firstOrNull() ?: return out
        out.put("containerTop", container.boundsInRoot.top.toInt())
        out.put("containerBottom", container.boundsInRoot.bottom.toInt())
        out.put("presentedCount", container.config.getOrNull(SemanticsProperties.CollectionInfo)?.rowCount ?: -1)
        container.config.getOrNull(SemanticsProperties.VerticalScrollAxisRange)?.let { range ->
            // Under reverseLayout the value is measured from the newest end, so a value near 0
            // means the viewport is parked on the newest rows and a large value means it is deep
            // in older history.
            out.put("scrollValue", range.value().toDouble())
            out.put("scrollMaxValue", range.maxValue().toDouble())
            out.put("scrollReversed", range.reverseScrolling)
        }
        val indexForKey = container.config.getOrNull(SemanticsProperties.IndexForKey)
        out.put("indexForKeySupported", indexForKey != null)
        out.put("targetIndex", indexForKey?.invoke(targetKey) ?: -2)
        out.put("targetComposed", compose.onAllNodesWithTag(targetTag, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty())

        // The newest rows Room holds, asked for by their Paging key one at a time. A contiguous
        // block of resolved indices starting at 0 means the presented list agrees with Room about
        // the newest end; a leading run of -1 means those rows are absent or still placeholders,
        // and a shifted block means something the DB scan does not know about occupies index 0.
        val probes = JSONArray()
        var newestResolvedIndex = -1
        newestRowKeys.forEachIndexed { rank, row ->
            val index = indexForKey?.invoke(row.first) ?: -2
            if (index >= 0 && (newestResolvedIndex < 0 || index < newestResolvedIndex)) newestResolvedIndex = index
            probes.put(
                JSONObject().apply {
                    put("rank", rank)
                    put("eventId", row.first)
                    putNullable("msgidHash", DiagnosticLogger.Noop.fingerprint(row.second))
                    put("index", index)
                },
            )
        }
        out.put("keyProbes", probes)
        // Index of the newest Room row the presented list actually claims. Paging substitutes a
        // private position key for every placeholder slot and the placeholder row clears its
        // semantics, so placeholders are invisible both to the key map and to the semantics tree;
        // a positive value here is the observable consequence — that many newest-end slots are
        // occupied by something no retained row claims, which is what a leading placeholder run
        // looks like from outside.
        out.put("newestResolvedIndex", newestResolvedIndex)

        val rowMatcher =
            SemanticsMatcher("timeline row tag") { node ->
                node.config.getOrElse(SemanticsProperties.TestTag) { "" }.startsWith(TIMELINE_ROW_TAG_PREFIX)
            }
        val composed = JSONArray()
        compose
            .onAllNodes(rowMatcher and hasAnyAncestor(hasTestTag(containerTag)), useUnmergedTree = true)
            .fetchSemanticsNodes()
            .sortedBy { it.boundsInRoot.top }
            .take(COMPOSED_ROW_LIMIT)
            .forEach { node ->
                composed.put(
                    JSONObject().apply {
                        putNullable("tagHash", DiagnosticLogger.Noop.fingerprint(node.config.getOrElse(SemanticsProperties.TestTag) { "" }))
                        put("top", node.boundsInRoot.top.toInt())
                        put("bottom", node.boundsInRoot.bottom.toInt())
                    },
                )
            }
        out.put("composedRows", composed)
        out.put("composedRowCount", composed.length())
        val footerTagged =
            listOf(HISTORY_LOADING_TAG, HISTORY_MORE_TAG).any { tag ->
                compose.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
            }
        out.put("historyFooterComposed", footerTagged)
        out.put("unreadDividerComposed", compose.onAllNodesWithTag(UNREAD_DIVIDER_TAG, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty())
        return out
    }

    private fun dividerSnapshot(): JSONObject {
        val nodes = compose.onAllNodesWithTag(UNREAD_DIVIDER_TAG, useUnmergedTree = true).fetchSemanticsNodes()
        return JSONObject().apply {
            put("composed", nodes.isNotEmpty())
            put("count", nodes.size)
            put(
                "bounds",
                JSONArray().apply {
                    nodes.forEach { node ->
                        put(
                            JSONObject().apply {
                                put("top", node.boundsInRoot.top.toInt())
                                put("bottom", node.boundsInRoot.bottom.toInt())
                            },
                        )
                    }
                },
            )
        }
    }

    // ------------------------------------------------------------------------- Room / DB truth

    /**
     * Room's own answer, re-read fresh: the target row, the newest rows around it, the room's
     * history gaps with both role projections of each newer edge, and the exact row set the
     * production paging query would present.
     */
    private fun roomSnapshot(targetKey: Long): JSONObject {
        val out = JSONObject()
        val file = targetContext.getDatabasePath(DATABASE_NAME)
        out.put("databasePresent", file.exists())
        openReadOnly(file).use { db ->
            if (db == null) {
                out.put("databaseOpen", false)
                return out
            }
            out.put("databaseOpen", true)
            val roomId = db.longOrNull("SELECT COALESCE(redirectToRoomId, id) FROM buffers WHERE id = ?", bufferId) ?: bufferId
            out.put("requestedBufferId", bufferId)
            out.put("canonicalRoomId", roomId)
            out.put("buffer", guarded { bufferRow(db) })
            out.put("rowCount", db.longOrNull("SELECT COUNT(*) FROM messages WHERE bufferId = ?", roomId) ?: -1)
            out.put("target", guarded { targetRow(db, roomId, targetKey) })
            val newest = guarded { newestRows(db, roomId) }
            out.put("newestRows", newest)
            val gaps = readGaps(db, roomId)
            out.put("gaps", guarded { gapRows(db, roomId, gaps) })
            out.put("pagingQuery", guarded { pagingQuerySnapshot(db, roomId, targetKey) })
        }
        return out
    }

    private fun bufferRow(db: SQLiteDatabase): JSONObject =
        JSONObject().apply {
            db
                .rawQuery(
                    "SELECT id, networkId, type, joined, redirectToRoomId, readMarkerTime, localReadAnchorTime, " +
                        "localReadAnchorEventId, oldestFetchedTime, historyComplete FROM buffers WHERE id = ?",
                    arrayOf(bufferId.toString()),
                ).use { cursor ->
                    val found = cursor.moveToFirst()
                    put("found", found)
                    if (!found) return@use
                    put("id", cursor.long("id"))
                    put("networkId", cursor.long("networkId"))
                    put("type", cursor.string("type"))
                    put("joined", cursor.long("joined") != 0L)
                    putNullable("redirectToRoomId", cursor.longOrNull("redirectToRoomId"))
                    putNullable("readMarkerTime", cursor.longOrNull("readMarkerTime"))
                    putNullable("localReadAnchorTime", cursor.longOrNull("localReadAnchorTime"))
                    putNullable("localReadAnchorEventId", cursor.longOrNull("localReadAnchorEventId"))
                    putNullable("oldestFetchedTime", cursor.longOrNull("oldestFetchedTime"))
                    put("historyComplete", cursor.long("historyComplete") != 0L)
                }
        }

    /**
     * The target row as Room holds it now, plus the two ways its identity could have moved since
     * the probe captured it: a different row now owning the probed msgid, and an event redirect
     * pointing the probed id at a winner. Either one explains a key the timeline can never resolve.
     */
    private fun targetRow(
        db: SQLiteDatabase,
        roomId: Long,
        targetKey: Long,
    ): JSONObject =
        JSONObject().apply {
            put("byEventId", messageRow(db, "SELECT $MESSAGE_COLUMNS FROM messages WHERE id = ?", arrayOf(targetKey.toString())))
            put(
                "byMsgid",
                probedMsgid?.let {
                    messageRow(
                        db,
                        "SELECT $MESSAGE_COLUMNS FROM messages WHERE bufferId = ? AND msgid = ? LIMIT 1",
                        arrayOf(roomId.toString(), it),
                    )
                } ?: JSONObject().put("found", false),
            )
            putNullable(
                "redirectedTo",
                db.longOrNull("SELECT canonicalEventId FROM event_redirects WHERE losingEventId = ?", targetKey),
            )
            put(
                "redirectedFrom",
                db.longOrNull("SELECT COUNT(*) FROM event_redirects WHERE canonicalEventId = ?", targetKey) ?: -1,
            )
        }

    /**
     * The newest rows in the room, ordered exactly as the paging query orders them and with no
     * window bound applied, so a row Room retains but the window excludes still shows up here.
     * Doubles as the key-probe source for [pagingSnapshot].
     */
    private fun newestRows(
        db: SQLiteDatabase,
        roomId: Long,
    ): JSONArray {
        val rows = JSONArray()
        val keys = mutableListOf<Pair<Long, String?>>()
        db
            .rawQuery(
                "SELECT $MESSAGE_COLUMNS FROM messages WHERE bufferId = ? " +
                    "ORDER BY serverTime DESC, timelineOrder DESC, id DESC LIMIT $RAW_ROW_LIMIT",
                arrayOf(roomId.toString()),
            ).use { cursor ->
                var rank = 0
                while (cursor.moveToNext()) {
                    rows.put(cursor.messageJson().put("rank", rank))
                    keys += cursor.long("id") to cursor.stringOrNull("msgid")
                    rank++
                }
            }
        newestRowKeys = keys
        return rows
    }

    private fun readGaps(
        db: SQLiteDatabase,
        roomId: Long,
    ): List<HistoryGapEntity> =
        buildList {
            runCatching {
                db
                    .rawQuery(
                        "SELECT id, roomId, olderMsgid, olderServerTime, newerMsgid, newerServerTime, recoverable, " +
                            "olderEventId, olderTimelineOrder, newerEventId, newerTimelineOrder FROM history_gaps WHERE roomId = ?",
                        arrayOf(roomId.toString()),
                    ).use { cursor ->
                        while (cursor.moveToNext()) {
                            add(
                                HistoryGapEntity(
                                    id = cursor.long("id"),
                                    roomId = cursor.long("roomId"),
                                    olderMsgid = cursor.stringOrNull("olderMsgid"),
                                    olderServerTime = cursor.long("olderServerTime"),
                                    newerMsgid = cursor.stringOrNull("newerMsgid"),
                                    newerServerTime = cursor.long("newerServerTime"),
                                    recoverable = cursor.long("recoverable") != 0L,
                                    olderEventId = cursor.longOrNull("olderEventId"),
                                    olderTimelineOrder = cursor.longOrNull("olderTimelineOrder"),
                                    newerEventId = cursor.longOrNull("newerEventId"),
                                    newerTimelineOrder = cursor.longOrNull("newerTimelineOrder"),
                                ),
                            )
                        }
                    }
            }
        }

    private fun gapRows(
        db: SQLiteDatabase,
        roomId: Long,
        gaps: List<HistoryGapEntity>,
    ): JSONArray {
        val out = JSONArray()
        resolve(db, roomId, gaps).forEach { resolved ->
            out.put(
                JSONObject().apply {
                    put("id", resolved.gap.id)
                    put("roomId", resolved.gap.roomId)
                    put("recoverable", resolved.gap.recoverable)
                    putNullable("olderMsgidHash", DiagnosticLogger.Noop.fingerprint(resolved.gap.olderMsgid))
                    put("olderServerTime", resolved.gap.olderServerTime)
                    putNullable("olderEventId", resolved.gap.olderEventId)
                    putNullable("olderTimelineOrder", resolved.gap.olderTimelineOrder)
                    putNullable("newerMsgidHash", DiagnosticLogger.Noop.fingerprint(resolved.gap.newerMsgid))
                    put("newerServerTime", resolved.gap.newerServerTime)
                    putNullable("newerEventId", resolved.gap.newerEventId)
                    putNullable("newerTimelineOrder", resolved.gap.newerTimelineOrder)
                    // The newer edge is where the seam is CUT, projected through the same role the
                    // timeline places it with: a synthetic MIN eventId here is the signature of an
                    // unidentifiable boundary, and it is why a seam can sit under a whole
                    // equal-timestamp cohort.
                    put("resolvedNewer", resolved.newer.asInclusiveLowerBound().json())
                    // How the same edge ranks for a hands-free fill — deliberately the opposite
                    // sentinel, so a report where the two disagree is reporting a TimeOnly edge.
                    put("selectionNewer", resolved.newer.asFocusNewerPosition().json())
                },
            )
        }
        return out
    }

    /**
     * The production paging query, executed verbatim against the read-only connection.
     *
     * This is the row set the `PagingSource` would return, so comparing it with the presented list
     * separates "the source never offered the row" (visibility excluded it) from "the source offered
     * it and Paging did not present it". There is exactly one such query: the timeline is unbounded
     * and no window is derived from the room's gaps, so a row missing here is missing from the store
     * or filtered by the spec, never clamped away.
     */
    private fun pagingQuerySnapshot(
        db: SQLiteDatabase,
        roomId: Long,
        targetKey: Long,
    ): JSONObject {
        // Default spec and identity rules: the required journeys never change the join/part/quit
        // or fools settings, and an empty fools set makes identity normalization inert.
        val query = messagePagingQuery(bufferId = roomId, spec = MessageVisibilitySpec())
        val args = query.stringArgs()
        val out = JSONObject()
        out.put("argCount", query.argCount)
        out.put(
            "sourceCount",
            db.longOrNull("SELECT COUNT(*) FROM (${query.sql})", *args) ?: -1,
        )
        var targetIndex = -1
        var scanned = 0
        val head = JSONArray()
        db.rawQuery(query.sql, args).use { cursor ->
            while (scanned < SOURCE_SCAN_LIMIT && cursor.moveToNext()) {
                val id = cursor.long("id")
                if (scanned < RAW_ROW_LIMIT) head.put(cursor.messageJson().put("rank", scanned))
                if (id == targetKey && targetIndex < 0) targetIndex = scanned
                scanned++
            }
        }
        out.put("scanned", scanned)
        out.put("scanTruncated", scanned >= SOURCE_SCAN_LIMIT)
        out.put("targetSourceIndex", targetIndex)
        out.put("headRows", head)
        return out
    }

    // ------------------------------------------------------------------------------- plumbing

    private fun resolve(
        db: SQLiteDatabase,
        roomId: Long,
        gaps: List<HistoryGapEntity>,
    ): List<ResolvedGap> =
        gaps.map { gap ->
            ResolvedGap(
                gap = gap,
                older = resolveBoundary(db, roomId, gap.olderMsgid, gap.olderServerTime, gap.olderEventId, gap.olderTimelineOrder),
                newer = resolveBoundary(db, roomId, gap.newerMsgid, gap.newerServerTime, gap.newerEventId, gap.newerTimelineOrder),
            )
        }

    /** Mirrors `GapAnchorResolver.resolve`, including its redirect-following lookup. */
    private fun resolveBoundary(
        db: SQLiteDatabase,
        roomId: Long,
        msgid: String?,
        serverTime: Long,
        eventId: Long?,
        timelineOrder: Long?,
    ): GapEdgeAnchor {
        msgid?.let { anchorByMsgid(db, roomId, it) }?.let { return GapEdgeAnchor.Exact(it) }
        eventId?.let { anchorByCanonicalId(db, it, roomId) }?.let { return GapEdgeAnchor.Exact(it) }
        eventId?.let { return GapEdgeAnchor.Exact(TimelineAnchor(serverTime, it, timelineOrder ?: it)) }
        return GapEdgeAnchor.TimeOnly(serverTime)
    }

    private fun anchorByMsgid(
        db: SQLiteDatabase,
        roomId: Long,
        msgid: String,
    ): TimelineAnchor? =
        db
            .rawQuery(
                "SELECT serverTime, id, timelineOrder FROM messages WHERE bufferId = ? AND msgid = ? LIMIT 1",
                arrayOf(roomId.toString(), msgid),
            ).use { it.anchorOrNull() }

    private fun anchorByCanonicalId(
        db: SQLiteDatabase,
        eventId: Long,
        roomId: Long,
    ): TimelineAnchor? =
        db
            .rawQuery(
                "SELECT serverTime, id, timelineOrder FROM messages WHERE bufferId = ? AND id = COALESCE(" +
                    "(SELECT canonicalEventId FROM event_redirects WHERE losingEventId = ?), ?) LIMIT 1",
                arrayOf(roomId.toString(), eventId.toString(), eventId.toString()),
            ).use { it.anchorOrNull() }

    private fun Cursor.anchorOrNull(): TimelineAnchor? = if (moveToFirst()) TimelineAnchor(long("serverTime"), long("id"), long("timelineOrder")) else null

    private fun messageRow(
        db: SQLiteDatabase,
        sql: String,
        args: Array<String>,
    ): JSONObject =
        db.rawQuery(sql, args).use { cursor ->
            if (cursor.moveToFirst()) cursor.messageJson().put("found", true) else JSONObject().put("found", false)
        }

    /** Structural columns only: no body, no sender, no room name. */
    private fun Cursor.messageJson(): JSONObject =
        JSONObject().apply {
            put("eventId", long("id"))
            put("bufferId", long("bufferId"))
            val msgid = stringOrNull("msgid")
            putNullable("msgidHash", DiagnosticLogger.Noop.fingerprint(msgid))
            putNullable("tagHash", DiagnosticLogger.Noop.fingerprint("$TIMELINE_ROW_TAG_PREFIX${msgid ?: long("id")}"))
            put("serverTime", long("serverTime"))
            put("timelineOrder", long("timelineOrder"))
            put("kind", string("kind"))
            put("isSelf", long("isSelf") != 0L)
            put("pending", stringOrNull("pendingLabel") != null)
            put("failed", long("failed") != 0L)
            put("serverTimeAuthoritative", long("serverTimeAuthoritative") != 0L)
            put("timelineOrderConfirmed", long("timelineOrderConfirmed") != 0L)
        }

    private fun TimelineAnchor.json(): JSONObject =
        JSONObject().apply {
            put("serverTime", serverTime)
            put("eventId", eventId)
            put("timelineOrder", timelineOrder)
        }

    /** A failing section becomes an error object; the rest of the snapshot still gets written. */
    private fun guarded(block: () -> Any): Any = runCatching(block).getOrElse { JSONObject().put("error", it::class.java.name) }

    private fun openReadOnly(file: File): SQLiteDatabase? {
        if (!file.exists()) return null
        // OPEN_READONLY is what makes this incapable of invalidating the live Pager: the connection
        // cannot write, so Room's invalidation tracker never fires and no PagingSource regenerates.
        // NO_LOCALIZED_COLLATORS keeps a read-only open from touching android_metadata; the
        // fallback covers a schema that turns out to need the collators after all.
        return runCatching {
            SQLiteDatabase.openDatabase(
                file.path,
                null,
                SQLiteDatabase.OPEN_READONLY or SQLiteDatabase.NO_LOCALIZED_COLLATORS,
            )
        }.recoverCatching {
            SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY)
        }.getOrNull()
    }

    private inline fun <T> SQLiteDatabase?.use(block: (SQLiteDatabase?) -> T): T =
        try {
            block(this)
        } finally {
            runCatching { this?.close() }
        }

    private fun SQLiteDatabase.longOrNull(
        sql: String,
        vararg args: Any,
    ): Long? =
        runCatching {
            rawQuery(sql, args.map { it.toString() }.toTypedArray()).use { cursor ->
                if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getLong(0) else null
            }
        }.getOrNull()

    private fun Cursor.long(column: String): Long = getLong(getColumnIndexOrThrow(column))

    private fun Cursor.longOrNull(column: String): Long? =
        getColumnIndexOrThrow(column)
            .let { if (isNull(it)) null else getLong(it) }

    private fun Cursor.string(column: String): String = getString(getColumnIndexOrThrow(column)).orEmpty()

    private fun Cursor.stringOrNull(column: String): String? =
        getColumnIndexOrThrow(column)
            .let { if (isNull(it)) null else getString(it) }

    private fun JSONObject.putNullable(
        key: String,
        value: Any?,
    ): JSONObject = put(key, value ?: JSONObject.NULL)

    /**
     * Re-reads the query's own bind arguments instead of rebuilding them, so the executed statement
     * stays the production one. SQLite applies column affinity to string arguments, so the integer
     * comparisons still bind as integers.
     */
    private fun SupportSQLiteQuery.stringArgs(): Array<String> {
        val captured = arrayOfNulls<String>(argCount)
        bindTo(
            object : SupportSQLiteProgram {
                override fun bindNull(index: Int) {
                    captured[index - 1] = null
                }

                override fun bindLong(
                    index: Int,
                    value: Long,
                ) {
                    captured[index - 1] = value.toString()
                }

                override fun bindDouble(
                    index: Int,
                    value: Double,
                ) {
                    captured[index - 1] = value.toString()
                }

                override fun bindString(
                    index: Int,
                    value: String,
                ) {
                    captured[index - 1] = value
                }

                override fun bindBlob(
                    index: Int,
                    value: ByteArray,
                ) {
                    captured[index - 1] = ""
                }

                override fun clearBindings() = Unit

                override fun close() = Unit
            },
        )
        return Array(captured.size) { captured[it].orEmpty() }
    }

    private companion object {
        const val DATABASE_NAME = "motd.db"
        const val TIMELINE_ROW_TAG_PREFIX = "chat_message_"
        const val HISTORY_LOADING_TAG = "chat_history_loading"

        /** An armed ladder renders this instead of the shimmer, and still means "footer composed". */
        const val HISTORY_MORE_TAG = "chat_history_more"
        const val UNREAD_DIVIDER_TAG = "chat_read_marker_divider"

        /** Structural columns of `messages`; deliberately excludes every free-text column. */
        const val MESSAGE_COLUMNS =
            "id, bufferId, msgid, serverTime, timelineOrder, kind, isSelf, pendingLabel, failed, " +
                "serverTimeAuthoritative, timelineOrderConfirmed"
    }
}

/**
 * Writes one artifact through every sink the launcher collects from, because a diagnostic that
 * stays on the emulator is worthless.
 *
 * `fast-suite.sh` gathers required-gate artifacts three ways: AGP's additional-test-output
 * directory (`e2e_collect_gradle_required_e2e_artifacts`), a `run-as` tar of the instrumentation
 * package's `files/required-e2e` and a tar of the target package's
 * `additionalTestOutputDir/required-e2e` under its external media directory
 * (`e2e_pull_required_e2e_artifacts`). All three extract to the same relative path, so writing to
 * all of them costs three small files and survives any one path being unavailable on the runner.
 */
internal object E2eArtifactSink {
    fun write(
        path: String,
        content: String,
    ): Int {
        var delivered = 0
        runCatching {
            PlatformTestStorageRegistry
                .getInstance()
                .openOutputFile(path, false)
                .bufferedWriter()
                .use { it.write(content) }
            delivered++
        }
        runCatching {
            writeFile(File(InstrumentationRegistry.getInstrumentation().context.filesDir, path), content)
            delivered++
        }
        runCatching {
            @Suppress("DEPRECATION")
            val media =
                InstrumentationRegistry
                    .getInstrumentation()
                    .targetContext.externalMediaDirs
                    .firstOrNull()
            if (media != null) {
                writeFile(File(File(media, "additionalTestOutputDir"), path), content)
                delivered++
            }
        }
        return delivered
    }

    private fun writeFile(
        file: File,
        content: String,
    ) {
        file.parentFile?.mkdirs()
        file.writeText(content)
    }
}
