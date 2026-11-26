package io.github.trevarj.motd.irc.ext

import io.github.trevarj.motd.irc.proto.IrcMessage

internal data class BatchTree(
    val ref: String,
    val type: String,
    val params: List<String>,
    val opening: IrcMessage,
    val children: List<BatchChild>,
    val openedAtNanos: Long = 0L,
)

internal sealed interface BatchChild {
    data class Message(
        val message: IrcMessage,
    ) : BatchChild

    data class Nested(
        val batch: BatchTree,
    ) : BatchChild
}

/** Assembles an immutable, ordered IRCv3 batch tree without flattening nested semantics. */
internal class BatchAssembler(
    private val maxOpenBatches: Int = MAX_OPEN_BATCHES,
    private val maxBufferedMessages: Int = MAX_BUFFERED_MESSAGES,
) {
    init {
        require(maxOpenBatches > 0)
        require(maxBufferedMessages > 0)
    }

    private sealed interface MutableChild {
        data class Message(
            val message: IrcMessage,
        ) : MutableChild

        data class Pending(
            val ref: String,
        ) : MutableChild

        data class Nested(
            val batch: BatchTree,
        ) : MutableChild
    }

    private class OpenBatch(
        val ref: String,
        val type: String,
        val params: List<String>,
        val opening: IrcMessage,
        val parent: String?,
        val openedAtNanos: Long,
    ) {
        val children = mutableListOf<MutableChild>()
    }

    private val open = HashMap<String, OpenBatch>()
    private var bufferedMessages = 0

    sealed interface Outcome {
        data object Buffered : Outcome

        data class Closed(
            val tree: BatchTree,
        ) : Outcome

        data object PassThrough : Outcome

        data class Overflow(
            val detail: String,
        ) : Outcome
    }

    val hasOpenBatch: Boolean get() = open.isNotEmpty()

    fun reset() {
        open.clear()
        bufferedMessages = 0
    }

    fun route(msg: IrcMessage): Outcome {
        if (msg.command == "BATCH" && msg.params.firstOrNull()?.startsWith("+") == true) {
            val ref = msg.params[0].substring(1)
            if (ref.isEmpty() || ref in open) return Outcome.PassThrough
            if (open.size >= maxOpenBatches) return overflow("more than $maxOpenBatches batches are open")
            val parent = msg.tags["batch"]?.takeIf { it in open }
            val type = msg.params.getOrNull(1).orEmpty()
            val openedAtNanos = if (type == "metadata") System.nanoTime() else 0L
            open[ref] = OpenBatch(ref, type, msg.params.drop(2), msg, parent, openedAtNanos)
            parent?.let { open[it]?.children?.add(MutableChild.Pending(ref)) }
            return Outcome.Buffered
        }

        if (msg.command == "BATCH" && msg.params.firstOrNull()?.startsWith("-") == true) {
            val ref = msg.params[0].substring(1)
            val closed = open.remove(ref) ?: return Outcome.PassThrough
            discardOpenDescendants(closed)
            val tree = closed.freeze()
            val parent = closed.parent?.let(open::get)
            if (parent != null) {
                val index = parent.children.indexOfFirst { it is MutableChild.Pending && it.ref == ref }
                if (index >= 0) parent.children[index] = MutableChild.Nested(tree)
                return Outcome.Buffered
            }
            bufferedMessages -= tree.messageCount()
            return Outcome.Closed(tree)
        }

        msg.tags["batch"]?.let { ref ->
            open[ref]?.let { batch ->
                if (bufferedMessages >= maxBufferedMessages) {
                    return overflow("more than $maxBufferedMessages messages are buffered")
                }
                batch.children += MutableChild.Message(msg)
                bufferedMessages++
                return Outcome.Buffered
            }
        }
        return Outcome.PassThrough
    }

    private fun OpenBatch.freeze(): BatchTree =
        BatchTree(
            ref = ref,
            type = type,
            params = params.toList(),
            opening = opening,
            openedAtNanos = openedAtNanos,
            children =
                children.mapNotNull { child ->
                    when (child) {
                        is MutableChild.Message -> BatchChild.Message(child.message)
                        is MutableChild.Nested -> BatchChild.Nested(child.batch)
                        is MutableChild.Pending -> null
                    }
                },
        )

    private fun discardOpenDescendants(batch: OpenBatch) {
        batch.children.filterIsInstance<MutableChild.Pending>().forEach { pending ->
            open.remove(pending.ref)?.let { descendant ->
                bufferedMessages -= descendant.retainedMessageCount()
                discardOpenDescendants(descendant)
            }
        }
    }

    private fun OpenBatch.retainedMessageCount(): Int =
        children.sumOf { child ->
            when (child) {
                is MutableChild.Message -> 1
                is MutableChild.Nested -> child.batch.messageCount()
                is MutableChild.Pending -> 0
            }
        }

    private fun BatchTree.messageCount(): Int =
        children.sumOf { child ->
            when (child) {
                is BatchChild.Message -> 1
                is BatchChild.Nested -> child.batch.messageCount()
            }
        }

    private fun overflow(detail: String): Outcome.Overflow {
        reset()
        return Outcome.Overflow(detail)
    }

    private companion object {
        const val MAX_OPEN_BATCHES = 64
        const val MAX_BUFFERED_MESSAGES = 4_096
    }
}
