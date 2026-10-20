package io.github.trevarj.motd.data.repo

import androidx.paging.CombinedLoadStates
import androidx.paging.ExperimentalPagingApi
import androidx.paging.ItemSnapshotList
import androidx.paging.LoadState
import androidx.paging.LoadType
import androidx.paging.PagingData
import androidx.paging.PagingSource
import androidx.paging.PagingState
import androidx.paging.RemoteMediator
import androidx.paging.cachedIn
import androidx.room.withTransaction
import io.github.trevarj.motd.data.db.MessageEntity
import io.github.trevarj.motd.data.db.MotdDatabase
import io.github.trevarj.motd.data.prefs.Settings
import io.github.trevarj.motd.data.visibility.MessageVisibilitySpec
import io.github.trevarj.motd.di.IoDispatcher
import io.github.trevarj.motd.diagnostics.AutoFollowTrace
import io.github.trevarj.motd.service.AppVisibility
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.IdentityHashMap
import java.util.UUID
import javax.inject.Inject

/** Data ownership for the root UI host's two retained native presentations. Never owns UI objects. */
@OptIn(ExperimentalPagingApi::class)
class ChatPageCache
    @Inject
    constructor(
        private val repository: MessageRepositoryImpl,
        private val db: MotdDatabase,
        @IoDispatcher private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    ) {
        private val lock = Any()
        private val entries = LinkedHashMap<Key, Entry>()
        private val attached = LinkedHashSet<Entry>()
        private val ownerId = UUID.randomUUID().toString()
        private var nextSlot = 0L
        private lateinit var owner: CoroutineScope
        private lateinit var onScreen: StateFlow<Boolean>
        private val _slots = MutableStateFlow<List<Entry>>(emptyList())
        internal val slots: StateFlow<List<Entry>> = _slots.asStateFlow()

        fun start(
            scope: CoroutineScope,
            visibility: AppVisibility,
            settings: Flow<Settings>,
        ) {
            check(!::owner.isInitialized)
            owner = scope
            onScreen = visibility.onScreen
            scope.launch {
                visibility.onScreen.collect { visible ->
                    if (!visible) evictDetached("background")
                }
            }
            scope.launch {
                settings.map { MessageVisibilitySpec.from(it) }.distinctUntilChanged().collect {
                    evictDetached("visibility_changed")
                }
            }
            scope.coroutineContext[Job]?.invokeOnCompletion {
                val retired =
                    synchronized(lock) {
                        (entries.values + attached).distinct().onEach { retireLocked(it, "owner_destroyed") }
                    }
                publishSlots()
                retired.forEach { it.scope.cancel() }
            }
        }

        /** Reserve a slot, not permission to paint it. [Lease.admit] certifies actual native pixels. */
        internal suspend fun acquire(
            bufferId: Long,
            spec: MessageVisibilitySpec,
            initialKey: Int?,
            targetIndex: Int,
            expectedEventId: Long?,
            viewport: ViewportRefreshAnchor?,
            allowRetention: Boolean = true,
            continueLocalRefresh: Boolean = false,
            continuePresentationFrom: Lease? = null,
        ): Lease {
            val context = withContext(ioDispatcher) { db.withTransaction { repository.resolvePagingContext(bufferId) } }
            val key = Key(context, spec)
            // A canceled reveal/reversal must leave the paused native presenter untouched.
            val stagedIdentity = continuePresentationFrom?.let { synchronized(lock) { "$ownerId:${++nextSlot}" } }
            var lease: Lease? = null
            try {
                val stagedVersion =
                    stagedIdentity?.let { identity ->
                        withContext(ioDispatcher) {
                            db.withTransaction { readChatPageVersion(db.openHelper.writableDatabase, identity, context.roomId) }
                        }
                    }
                val retired = ArrayList<Entry>(MAX_ROOMS)
                var reused = false
                val acquired =
                    synchronized(lock) {
                        if (owner.coroutineContext[Job]?.isActive == false) throw CancellationException("Chat presentation owner was destroyed")

                        fun remove(
                            entry: Entry,
                            reason: String,
                        ) {
                            if (retireLocked(entry, reason)) retired += entry
                        }
                        val predecessor =
                            continuePresentationFrom?.takeIf {
                                it.ownsSlot() && it.entry.handoffPending && onScreen.value && it.entry.eligible &&
                                    it.entry.key.context == context &&
                                    it.entry.key.spec
                                        .samePolicyExceptHiddenReveal(spec) &&
                                    it.entry.nativePresentationWindow != null
                            }
                        val inheritedWindow = predecessor?.entry?.nativePresentationWindow
                        val presentationIdentity = predecessor?.entry?.presentationIdentity
                        val visitIdentity = predecessor?.presentationVisitIdentity
                        continuePresentationFrom?.takeIf { it.ownsSlot() && it.entry.handoffPending }?.let {
                            remove(it.entry, if (predecessor != null) "temporary_fool_visibility" else "continuity_rejected")
                        }
                        val existing = entries[key]
                        val independent = !allowRetention || !onScreen.value || existing?.active == true
                        if (continuePresentationFrom == null && !independent && existing != null && !existing.retired) {
                            entries.remove(key)
                            entries[key] = existing
                            existing.leaseId++
                            existing.active = true
                            existing.remoteAllowed = true
                            existing.viewport.value = viewport
                            attached += existing
                            reused = true
                            Lease(existing, existing.leaseId, bufferId, retained = true)
                        } else {
                            entries.values.filter { it.key.context.roomId == context.roomId && !it.active }.toList().forEach {
                                remove(it, "key_changed")
                            }
                            var retainable = !independent
                            if (retainable && entries.size == MAX_ROOMS) {
                                val oldest = entries.values.firstOrNull { !it.active }
                                if (oldest == null) retainable = false else remove(oldest, "lru")
                            }
                            val entry =
                                Entry(
                                    stagedIdentity ?: "$ownerId:${++nextSlot}",
                                    key,
                                    initialKey,
                                    viewport,
                                    retainable,
                                    continueLocalRefresh,
                                    presentationIdentity,
                                    inheritedWindow,
                                    stagedVersion,
                                )
                            if (retainable) entries[key] = entry
                            attached += entry
                            Lease(entry, entry.leaseId, bufferId, retained = false, presentationVisitIdentity = visitIdentity ?: "${entry.identity}:${entry.leaseId}")
                        }
                    }
                lease = acquired
                retired.forEach { it.scope.cancel() }
                publishSlots()
                // Handoffs have no suspension between publication and the caller owning this lease.
                val version = stagedVersion ?: acquired.entry.readVersion()
                val reusable =
                    synchronized(lock) {
                        val snapshot = acquired.entry.snapshot
                        val row = snapshot?.takeIf { targetIndex in it.indices }?.get(targetIndex)
                        reused && acquired.entry.presentedVersion == version &&
                            (
                                (row != null && (expectedEventId == null || row.id == expectedEventId)) ||
                                    (snapshot?.size == 0 && targetIndex == 0 && expectedEventId == null)
                            )
                    }
                trace(if (reusable) "hit" else "miss", context.roomId, if (reusable) "presentation" else "refresh_required")
                if (reused && !reusable) acquired.entry.requestRefresh(if (expectedEventId == null) initialKey ?: 0 else null)
                acquired.entry.changed()
                acquired.entry.wakeLoads()
                return acquired
            } catch (failure: Throwable) {
                lease?.release()
                if (lease == null && stagedIdentity != null) {
                    withContext(ioDispatcher + NonCancellable) {
                        db.withTransaction { releaseChatPageVersion(db.openHelper.writableDatabase, stagedIdentity) }
                    }
                }
                throw failure
            }
        }

        /** Reserve an existing payload without waiting for this visit's target/unread preparation. */
        internal suspend fun acquireRetained(
            bufferId: Long,
            spec: MessageVisibilitySpec,
        ): Lease? {
            if (synchronized(lock) { entries.isEmpty() || !onScreen.value }) return null
            val context = withContext(ioDispatcher) { db.withTransaction { repository.resolvePagingContext(bufferId) } }
            val key = Key(context, spec)
            val lease =
                synchronized(lock) {
                    val existing = entries[key] ?: return null
                    if (existing.active || existing.retired || !existing.eligible ||
                        existing.snapshot?.items.isNullOrEmpty() || existing.presentedVersion == null || !onScreen.value
                    ) {
                        return null
                    }
                    entries.remove(key)
                    entries[key] = existing
                    existing.active = true
                    existing.remoteAllowed = false
                    existing.leaseId++
                    attached += existing
                    Lease(existing, existing.leaseId, bufferId, retained = true)
                }
            try {
                publishSlots()
                val version = lease.entry.readVersion()
                val current = synchronized(lock) { lease.entry.presentedVersion == version }
                if (!current) lease.entry.requestRefresh()
                trace(if (current) "hit" else "miss", context.roomId, if (current) "presentation_candidate" else "refresh_required")
                lease.entry.changed()
                lease.entry.wakeLoads()
                return lease
            } catch (failure: Throwable) {
                lease.release()
                throw failure
            }
        }

        private fun publishSlots() {
            _slots.value = synchronized(lock) { (entries.values + attached).distinct().filterNot { it.retired } }
        }

        private fun evictDetached(reason: String) {
            val retired =
                synchronized(lock) {
                    entries.values
                        .filter { !it.active }
                        .toList()
                        .onEach { retireLocked(it, reason) }
                }
            publishSlots()
            retired.forEach { it.scope.cancel() }
        }

        private fun retireLocked(
            entry: Entry,
            reason: String,
        ): Boolean {
            if (entry.retired) return false
            entry.retired = true
            if (entries[entry.key] === entry) entries.remove(entry.key)
            attached.remove(entry)
            entry.clearWindows()
            entry.changed()
            trace("retire", entry.key.context.roomId, reason)
            return true
        }

        inner class Lease internal constructor(
            internal val entry: Entry,
            private val token: Long,
            private val routeBufferId: Long,
            val retained: Boolean,
            internal val presentationVisitIdentity: String = "${entry.identity}:$token",
        ) {
            val pages: Flow<PagingData<MessageEntity>> get() = entry.pages
            val identity: String get() = entry.identity
            internal val presentationIdentity: String get() = entry.presentationIdentity
            internal val continuesPresentation: Boolean get() = entry.presentationIdentity != entry.identity
            val adoptionId: Long get() = token
            val changes: StateFlow<Long> get() = entry.changes
            val isActive: Boolean get() = synchronized(lock) { ownsSlot() && entry.active }
            internal val canContinueLocalRefresh: Boolean
                get() = synchronized(lock) { entry.leaseId == token && entry.canContinueLocalRefresh }

            /** Disposal may save its final anchor until, but never after, a successor adopts the slot. */
            fun ownsSlot(): Boolean = synchronized(lock) { entry.leaseId == token && !entry.retired }

            fun updateViewport(anchor: ViewportRefreshAnchor?) {
                synchronized(lock) {
                    if (ownsSlot() && entry.active) entry.viewport.value = anchor
                }
            }

            fun prepareTarget(
                index: Int,
                expectedEventId: Long?,
                initialKey: Int?,
                anchor: ViewportRefreshAnchor?,
            ) {
                entry.prepareTarget(token, index, expectedEventId, initialKey, anchor)
            }

            fun allowRemoteLoads(allowed: Boolean) {
                val changed =
                    synchronized(lock) {
                        if (!isActive || entry.remoteAllowed == allowed) return
                        entry.remoteAllowed = allowed
                        true
                    }
                if (changed) entry.wakeLoads()
            }

            /** A materialized native window may outlive a pending refresh; this is not admission. */
            fun hasPublishedWindow(items: ItemSnapshotList<MessageEntity>): Boolean = synchronized(lock) { isActive && entry.snapshot === items && entry.presentedVersion != null }

            /** Called on Main with the very snapshot the destination is about to expose. */
            suspend fun admit(items: ItemSnapshotList<MessageEntity>): Boolean {
                val certified =
                    synchronized(lock) {
                        if (!isActive || entry.snapshot !== items) return false
                        entry.presentedVersion ?: return false
                    }
                val (context, current) =
                    withContext(ioDispatcher) {
                        db.withTransaction {
                            repository.resolvePagingContext(routeBufferId) to
                                readChatPageVersion(db.openHelper.writableDatabase, entry.identity, entry.key.context.roomId)
                        }
                    }
                if (context != entry.key.context) {
                    entry.contextChanged(context)
                    return false
                }
                val admitted =
                    synchronized(lock) {
                        isActive && entry.snapshot === items && entry.presentedVersion == certified && certified == current
                    }
                if (!admitted && current != certified) entry.ensureVersion(current)
                return admitted
            }

            suspend fun awaitRetirement() {
                changes.first { !ownsSlot() }
            }

            /** Revoke all effects and SQL while the same visit acquires an exact-spec successor. */
            internal fun preparePresentationHandoff() = entry.preparePresentationHandoff(token)

            fun release() = entry.release(token)
        }

        internal inner class Entry internal constructor(
            val identity: String,
            internal val key: Key,
            initialKey: Int?,
            initialViewport: ViewportRefreshAnchor?,
            internal val retainable: Boolean,
            private val inheritedLocalRefresh: Boolean,
            inheritedPresentationIdentity: String? = null,
            private var inheritedNativeWindow: ItemSnapshotList<MessageEntity>? = null,
            initialVersion: ChatPageVersion? = null,
        ) {
            internal val scope = CoroutineScope(owner.coroutineContext + SupervisorJob(owner.coroutineContext[Job]))
            internal val presentationIdentity = inheritedPresentationIdentity ?: identity
            internal val nativePresentationWindow get() = snapshot ?: inheritedNativeWindow
            internal var handoffPending = false
            internal var active = true
            internal var leaseId = 0L
            internal var retired = false
            internal var source: PagingSource<Int, MessageEntity>? = null
            private var querySource: PagingSource<Int, MessageEntity>? = null
            internal var snapshot: ItemSnapshotList<MessageEntity>? = null
            internal var presentedVersion: ChatPageVersion? = null
            private var lastVersion: ChatPageVersion? = initialVersion
            internal val version: ChatPageVersion get() = checkNotNull(lastVersion)
            internal val viewport = MutableStateFlow(initialViewport)
            private val loadWake = MutableStateFlow(0L)
            private val _changes = MutableStateFlow(0L)
            val changes: StateFlow<Long> = _changes.asStateFlow()
            private var loading = 0
            private var remoteLoading = 0
            internal var remoteAllowed = true
            private var presentedIdle = false
            private var refreshFailed = false
            private var hasPresented = false
            internal val canContinueLocalRefresh: Boolean get() = active && (hasPresented || inheritedLocalRefresh)
            private var requestedRefreshKey: Int? = null
            private var cohort: ChatPageVersion? = null
            private var presenterId = 0L
            private var presenterNeedsBootstrap = false
            internal var refreshProof: PageProof? = null
            private var nativeWindow: PageProof? = null
            internal val pendingPages = ArrayList<PageProof>()
            private val resident = IdentityHashMap<MessageEntity, Boolean>()
            private var retainedRows = 0
            private var retainedCharacters = 0L
            internal val eligible: Boolean
                get() = !retired && retainedRows <= MAX_ROWS && retainedCharacters <= MAX_CHARACTERS

            // This object remains identical through native invalidation/refresh generations.
            val pages: Flow<PagingData<MessageEntity>> =
                repository
                    .retainedMessages(key.context.roomId, key.spec, initialKey, this)
                    .flowOn(ioDispatcher)
                    .cachedIn(scope)

            init {
                accountWindows()
                scope.launch(start = CoroutineStart.UNDISPATCHED) {
                    try {
                        awaitCancellation()
                    } finally {
                        withContext(ioDispatcher + NonCancellable) {
                            synchronized(lock) { source }?.invalidate()
                            db.withTransaction { releaseChatPageVersion(db.openHelper.writableDatabase, identity) }
                        }
                    }
                }
            }

            internal fun changed() {
                _changes.update { it + 1 }
            }

            internal suspend fun readVersion(loadedSource: PagingSource<Int, MessageEntity>? = null): ChatPageVersion =
                withContext(ioDispatcher) {
                    db
                        .withTransaction {
                            if (loadedSource != null) requireQueryPermission(loadedSource)
                            readChatPageVersion(db.openHelper.writableDatabase, identity, key.context.roomId).also {
                                // Retirement may have completed its cleanup before this admission read.
                                if (synchronized(lock) { retired }) releaseChatPageVersion(db.openHelper.writableDatabase, identity)
                            }
                        }.also { synchronized(lock) { lastVersion = it } }
                }

            internal fun contextChanged(context: MessageRepositoryImpl.PagingContext) {
                if (context == key.context) return
                val close = synchronized(lock) { retireLocked(this, "context_changed") }
                publishSlots()
                if (close) scope.cancel()
            }

            internal fun sourceCreated(next: PagingSource<Int, MessageEntity>) {
                val rejected =
                    synchronized(lock) {
                        if (retired) {
                            true
                        } else {
                            source = next
                            querySource = next
                            cohort = null
                            // The canceled generation's published window is still counted separately.
                            pendingPages.clear()
                            refreshProof = null
                            accountWindows()
                            false
                        }
                    }
                if (rejected) {
                    next.invalidate()
                    throw CancellationException("Chat presentation was retired")
                }
            }

            internal fun prepareTarget(
                token: Long,
                index: Int,
                expectedEventId: Long?,
                initialKey: Int?,
                anchor: ViewportRefreshAnchor?,
            ) {
                val refresh =
                    synchronized(lock) {
                        if (leaseId != token || !active || retired) return@synchronized null
                        val current = snapshot
                        val covered =
                            if (expectedEventId != null) {
                                current?.items?.any { it.id == expectedEventId } == true
                            } else {
                                current?.getOrNull(index) != null || (index == 0 && current?.size == 0)
                            }
                        val moved = viewport.value != anchor
                        viewport.value = anchor
                        if (!covered || (moved && (loading != 0 || cohort == null || querySource !== source || source?.invalid != false))) {
                            requestedRefreshKey = if (expectedEventId == null) initialKey ?: 0 else null
                            querySource = null
                            source
                        } else {
                            null
                        }
                    }
                refresh?.let { scope.launch(ioDispatcher) { it.invalidate() } }
            }

            internal fun requestRefresh(key: Int? = null) {
                val current =
                    synchronized(lock) {
                        if (key != null) requestedRefreshKey = key
                        querySource = null
                        source
                    }
                if (current != null) scope.launch(ioDispatcher) { current.invalidate() }
            }

            internal fun requireQueryPermission(loadedSource: PagingSource<Int, MessageEntity>) {
                val allowed =
                    synchronized(lock) {
                        active && !retired && source === loadedSource && querySource === loadedSource && !loadedSource.invalid &&
                            (onScreen.value || canContinueLocalRefresh)
                    }
                if (!allowed) throw RevokedChatPageQuery()
            }

            internal fun acceptsVersion(
                loadedSource: PagingSource<Int, MessageEntity>,
                version: ChatPageVersion,
            ): Boolean = synchronized(lock) { source === loadedSource && querySource === loadedSource && (cohort == null || cohort == version) }

            internal fun ensureVersion(version: ChatPageVersion) {
                // A fresh REFRESH may already have returned while the native UI still shows its
                // previous PageStore. Wait for publication instead of invalidating that good work.
                val stale = synchronized(lock) { cohort != version && loading == 0 && !refreshFailed }
                if (stale) requestRefresh()
            }

            internal fun takeRefreshKey(): Int? = synchronized(lock) { requestedRefreshKey.also { requestedRefreshKey = null } }

            internal fun clearWindows() {
                snapshot = null
                inheritedNativeWindow = null
                pendingPages.clear()
                refreshProof = null
                nativeWindow = null
                resident.clear()
                retainedRows = 0
                retainedCharacters = 0L
            }

            internal fun wakeLoads() {
                loadWake.update { it + 1 }
            }

            internal suspend fun beginLoad(foregroundRequired: Boolean = false) {
                fun allowed() =
                    active && !retired && (!foregroundRequired || remoteAllowed) &&
                        (onScreen.value || (!foregroundRequired && canContinueLocalRefresh))
                while (true) {
                    synchronized(lock) {
                        if (allowed()) {
                            loading++
                            if (foregroundRequired) remoteLoading++
                            return
                        }
                    }
                    combine(loadWake, onScreen) { _, _ -> synchronized(lock) { allowed() } }.first { it }
                }
            }

            internal fun endLoad(foregroundRequired: Boolean = false) {
                synchronized(lock) {
                    loading--
                    if (foregroundRequired) remoteLoading--
                }
            }

            /** A source cohort never mixes pages from distinct committed room revisions. */
            internal fun loaded(
                loadedSource: PagingSource<Int, MessageEntity>,
                refresh: Boolean,
                page: PagingSource.LoadResult.Page<Int, MessageEntity>,
                before: ChatPageVersion,
                after: ChatPageVersion,
            ): Boolean {
                var close = false
                val accepted =
                    synchronized(lock) {
                        if (retired || source !== loadedSource || querySource !== loadedSource || loadedSource.invalid || before != after ||
                            (cohort != null && cohort != before)
                        ) {
                            return@synchronized false
                        }
                        cohort = before
                        val proof = PageProof(loadedSource, before, page.itemsBefore, page.itemsAfter, page.data)
                        pendingPages += proof
                        if (refresh) refreshProof = proof
                        if (refresh) refreshFailed = false
                        accountWindows()
                        if (!active && !eligible) close = retireLocked(this, "memory_bound")
                        if (refresh && !close) changed()
                        !close
                    }
                if (close) {
                    publishSlots()
                    scope.cancel()
                }
                return accepted
            }

            internal fun failed(loadedSource: PagingSource<Int, MessageEntity>) {
                synchronized(lock) {
                    if (source === loadedSource) refreshFailed = true
                }
            }

            internal fun attachPresenter(): Long =
                synchronized(lock) {
                    presenterNeedsBootstrap = true
                    ++presenterId
                }

            internal fun detachPresenter(token: Long) {
                synchronized(lock) {
                    if (presenterId == token) presenterId++
                }
            }

            /** Root-host observation, not an assertion that an offscreen room was read. */
            internal fun presented(
                token: Long,
                items: ItemSnapshotList<MessageEntity>,
                states: CombinedLoadStates,
            ) {
                var close = false
                var updated = false
                var refreshNeeded = false
                synchronized(lock) {
                    if (retired || presenterId != token) return
                    val previousVersion = presentedVersion
                    if (presenterNeedsBootstrap) {
                        val previous = snapshot
                        if (previousVersion != null && previous != null &&
                            (
                                previous.placeholdersBefore != items.placeholdersBefore ||
                                    previous.placeholdersAfter != items.placeholdersAfter || previous.items != items.items
                            )
                        ) {
                            presentedVersion = null
                            nativeWindow = null
                            refreshNeeded = refreshProof == null
                        }
                        presenterNeedsBootstrap = false
                    }
                    val proof = refreshProof
                    if (inheritedNativeWindow != null && presentedVersion == null && proof?.matches(items, states) != true) return
                    if (proof != null && proof.matches(items, states)) {
                        presentedVersion = proof.version
                        refreshProof = null
                        nativeWindow = proof
                        hasPresented = true
                        inheritedNativeWindow = null
                    }
                    updated = snapshot !== items || presentedVersion != previousVersion
                    snapshot = items
                    presentedIdle = states.source.refresh is LoadState.NotLoading &&
                        states.source.prepend is LoadState.NotLoading && states.source.append is LoadState.NotLoading
                    pendingPages.removeAll { pending ->
                        pending.source !== source || pending.matches(items, states) ||
                            (
                                presentedIdle && loading == 0 && presentedVersion == pending.version &&
                                    pending.before >= 0 &&
                                    (
                                        pending.before + pending.rows.size <= items.placeholdersBefore ||
                                            pending.before >= items.placeholdersBefore + items.items.size
                                    )
                            )
                    }
                    nativeWindow?.let { native ->
                        if (native.rows.isEmpty() || native.matchesReferences(items) ||
                            (
                                presentedVersion == native.version &&
                                    (
                                        native.before + native.rows.size <= items.placeholdersBefore ||
                                            native.before >= items.placeholdersBefore + items.items.size
                                    )
                            )
                        ) {
                            nativeWindow = null
                        }
                    }
                    accountWindows()
                    if (!active && !handoffPending && (!eligible || !retainable)) close = retireLocked(this, "memory_bound")
                }
                if (updated) changed()
                if (close) {
                    publishSlots()
                    scope.cancel()
                } else if (refreshNeeded) {
                    requestRefresh()
                }
            }

            /** Identity-union accounting counts current and pending windows, never refresh traffic. */
            private fun accountWindows() {
                resident.clear()
                var characters = 0L

                fun add(row: MessageEntity) {
                    if (resident.put(row, true) == null) characters += row.retainedCharacters()
                }
                snapshot?.items?.forEach(::add)
                inheritedNativeWindow?.items?.forEach(::add)
                nativeWindow?.rows?.forEach(::add)
                pendingPages.forEach { it.rows.forEach(::add) }
                retainedRows = resident.size
                retainedCharacters = characters
            }

            internal fun preparePresentationHandoff(token: Long) {
                val previous =
                    synchronized(lock) {
                        if (leaseId != token || !active || retired) return
                        active = false
                        handoffPending = true
                        querySource = null
                        source
                    }
                changed()
                previous?.let { pending -> scope.launch(ioDispatcher) { pending.invalidate() } }
            }

            internal fun release(token: Long) {
                var pendingSource: PagingSource<Int, MessageEntity>? = null
                val close =
                    synchronized(lock) {
                        if (leaseId != token || (!active && !handoffPending) || retired) return
                        active = false
                        handoffPending = false
                        attached.remove(this)
                        val reason =
                            when {
                                !retainable -> "visit_not_retained"
                                !onScreen.value -> "background"
                                !eligible -> "memory_bound"
                                remoteLoading != 0 -> "remote_load_in_flight"
                                snapshot == null || presentedVersion == null -> "not_materialized"
                                else -> null
                            }
                        if (reason != null) {
                            retireLocked(this, reason)
                        } else {
                            if (loading != 0) {
                                pendingSource = source
                                querySource = null
                            }
                            trace("admit", key.context.roomId, "presentation", checkNotNull(snapshot).items.size)
                            false
                        }
                    }
                changed()
                publishSlots()
                if (close) {
                    scope.cancel()
                } else {
                    // Native invalidation cancels this generation's queued/in-flight local load.
                    // Its successor waits at beginLoad; the acknowledged UI PageStore stays intact.
                    pendingSource?.let { pending -> scope.launch(ioDispatcher) { pending.invalidate() } }
                }
            }

            internal fun mediator(delegate: RemoteMediator<Int, MessageEntity>): RemoteMediator<Int, MessageEntity> =
                object : RemoteMediator<Int, MessageEntity>() {
                    override suspend fun initialize(): InitializeAction {
                        beginLoad()
                        try {
                            return delegate.initialize()
                        } finally {
                            endLoad()
                        }
                    }

                    override suspend fun load(
                        loadType: LoadType,
                        state: PagingState<Int, MessageEntity>,
                    ): MediatorResult {
                        beginLoad(foregroundRequired = true)
                        try {
                            return delegate.load(loadType, state)
                        } finally {
                            endLoad(foregroundRequired = true)
                        }
                    }
                }
        }

        internal data class Key(
            val context: MessageRepositoryImpl.PagingContext,
            val spec: MessageVisibilitySpec,
        )

        internal class PageProof(
            val source: PagingSource<Int, MessageEntity>,
            val version: ChatPageVersion,
            val before: Int,
            val after: Int,
            val rows: List<MessageEntity>,
        ) {
            fun matches(
                items: ItemSnapshotList<MessageEntity>,
                states: CombinedLoadStates,
            ): Boolean {
                if (rows.isEmpty()) {
                    return before == 0 && after == 0 && items.size == 0 && states.source.refresh is LoadState.NotLoading
                }
                if (before < 0 || after < 0 || items.size != before + rows.size + after || before !in items.indices) return false
                if (matchesReferences(items)) return true
                // Compose may retain old references when ItemSnapshotList compares structurally
                // equal. Certify the ENTIRE native window, never just an event id or a text field.
                return items.placeholdersBefore == before && items.placeholdersAfter == after && items.items == rows
            }

            fun matchesReferences(items: ItemSnapshotList<MessageEntity>): Boolean = rows.isNotEmpty() && before in items.indices && items[before] === rows.first()
        }

        private companion object {
            const val MAX_ROOMS = 2
            const val MAX_ROWS = 1_500
            const val MAX_CHARACTERS = 1_048_576L

            fun trace(
                event: String,
                room: Long,
                reason: String,
                rows: Int = 0,
            ) {
                AutoFollowTrace.record("page_cache_$event", room) { "reason=$reason loaded=$rows" }
            }

            fun MessageEntity.retainedCharacters(): Long =
                sender.length.toLong() + normalizedActor.length + text.length + dedupKey.length +
                    (msgid?.length ?: 0) + (senderAccount?.length ?: 0) + (ircFormattedText?.length ?: 0) +
                    (replyToMsgid?.length ?: 0) + (channelContext?.length ?: 0) + (pendingLabel?.length ?: 0) +
                    (eventKey?.length ?: 0) + (eventPayload?.length ?: 0) + (notificationClaimOwner?.length ?: 0)
        }
    }

/** Only revoked ownership is translated to native Invalid; real SQLite/conversion errors survive. */
internal class RevokedChatPageQuery : CancellationException("Chat page query ownership was revoked")
