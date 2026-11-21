package io.github.trevarj.motd.ui.chat

import androidx.lifecycle.ViewModel
import androidx.room.withTransaction
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.trevarj.motd.R
import io.github.trevarj.motd.data.db.BufferEntity
import io.github.trevarj.motd.data.db.BufferType
import io.github.trevarj.motd.data.db.ComposerDraftEntity
import io.github.trevarj.motd.data.db.MotdDatabase
import io.github.trevarj.motd.data.db.ScheduledMessageEntity
import io.github.trevarj.motd.data.db.ScheduledMessageState
import io.github.trevarj.motd.data.db.ircTarget
import io.github.trevarj.motd.data.db.isSchedulableMessage
import kotlinx.coroutines.CancellationException
import javax.inject.Inject

internal fun BufferEntity.canScheduleMessage(): Boolean =
    id > 0 && !dismissed && pendingCloseAt == null && redirectToRoomId == null &&
        (type == BufferType.QUERY || (type == BufferType.CHANNEL && joined))

internal data class ScheduledDraft(
    val room: BufferEntity,
    val text: String,
    val revision: Long,
)

internal sealed interface ScheduledDraftCapture {
    data class Captured(
        val draft: ScheduledDraft,
    ) : ScheduledDraftCapture

    data class Refused(
        val reason: Int,
    ) : ScheduledDraftCapture
}

internal data class ScheduledTiming(
    val dueAt: Long? = null,
    val zoneId: String? = null,
)

internal data class ScheduledComposerActions(
    val capture: (String) -> ScheduledDraftCapture,
    val enqueue: suspend (ScheduledDraft, ScheduledTiming) -> Int?,
    val openList: (Long) -> Unit,
)

@HiltViewModel
internal class ScheduledMessagesViewModel
    @Inject
    constructor(
        private val db: MotdDatabase,
        private val draftStore: ComposerDraftStore,
    ) : ViewModel() {
        private val dao = db.scheduledMessageDao()
        val messages = dao.observeAll()

        suspend fun enqueue(
            capture: ScheduledDraft,
            persisted: ComposerDraftEntity,
            timing: ScheduledTiming,
        ): Int? =
            databaseOperation(R.string.scheduled_save_failed) {
                db.withTransaction {
                    val captured = capture.room
                    val room = db.bufferDao().rawById(captured.id)
                    if (room == null || !room.canScheduleMessage() || room.networkId != captured.networkId ||
                        room.type != captured.type || room.ircTarget != captured.ircTarget ||
                        room.membershipCycle != captured.membershipCycle || persisted.roomId != room.id
                    ) {
                        return@withTransaction R.string.scheduled_chat_unavailable
                    }
                    val network =
                        db.networkDao().byId(room.networkId)
                            ?: return@withTransaction R.string.scheduled_chat_unavailable
                    if (!isSchedulableMessage(capture.text) || persisted.replyToEventId != null || persisted.text != capture.text) {
                        return@withTransaction R.string.scheduled_text_unsupported
                    }
                    if (timing.dueAt != null && timing.dueAt <= System.currentTimeMillis()) return@withTransaction R.string.scheduled_time_past
                    dao.insert(
                        ScheduledMessageEntity(
                            bufferId = room.id,
                            networkId = network.id,
                            networkName = network.name,
                            target = room.ircTarget,
                            membershipCycle = room.membershipCycle.takeIf { room.type == BufferType.CHANNEL },
                            text = capture.text,
                            dueAt = timing.dueAt,
                            zoneId = timing.zoneId,
                        ),
                    )
                    draftStore.clearIfUnchanged(persisted)
                    null
                }
            }

        suspend fun revise(
            message: ScheduledMessageEntity,
            text: String,
            timing: ScheduledTiming,
        ): Int? =
            databaseOperation(R.string.scheduled_update_failed) {
                if (!isSchedulableMessage(text)) return@databaseOperation R.string.scheduled_text_unsupported
                if (timing.dueAt != null && timing.dueAt <= System.currentTimeMillis()) return@databaseOperation R.string.scheduled_time_past
                if (message.state == ScheduledMessageState.SENDING ||
                    dao.revise(message.id, message.revision, message.state, text, timing.dueAt, timing.zoneId) != 1
                ) {
                    R.string.scheduled_entry_changed
                } else {
                    null
                }
            }

        suspend fun remove(message: ScheduledMessageEntity): Int? =
            databaseOperation(R.string.scheduled_update_failed) {
                if (message.state == ScheduledMessageState.SENDING || dao.remove(message.id, message.revision, message.state) != 1) {
                    R.string.scheduled_entry_changed
                } else {
                    null
                }
            }

        private inline fun databaseOperation(
            failure: Int,
            block: () -> Int?,
        ): Int? =
            try {
                block()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                failure
            }
    }
