package io.github.trevarj.motd.irc.ext

import io.github.trevarj.motd.irc.proto.IrcMessage

internal data class BatchTree(
    val ref: String,
    val type: String,
    val params: List<String>,
    val children: List<BatchChild>,
)

internal sealed interface BatchChild {
    data class Message(val message: IrcMessage) : BatchChild
    data class Nested(val batch: BatchTree) : BatchChild
}

/** Assembles an immutable, ordered IRCv3 batch tree without flattening nested semantics. */
internal class BatchAssembler {
    private sealed interface MutableChild {
        data class Message(val message: IrcMessage) : MutableChild
        data class Pending(val ref: String) : MutableChild
        data class Nested(val batch: BatchTree) : MutableChild
    }

    private class OpenBatch(
        val ref: String,
        val type: String,
        val params: List<String>,
        val parent: String?,
    ) {
        val children = mutableListOf<MutableChild>()
    }

    private val open = HashMap<String, OpenBatch>()

    sealed interface Outcome {
        data object Buffered : Outcome
        data class Closed(val tree: BatchTree) : Outcome
        data object PassThrough : Outcome
    }

    val hasOpenBatch: Boolean get() = open.isNotEmpty()

    fun reset() = open.clear()

    fun route(msg: IrcMessage): Outcome {
        if (msg.command == "BATCH" && msg.params.firstOrNull()?.startsWith("+") == true) {
            val ref = msg.params[0].substring(1)
            if (ref.isEmpty() || ref in open) return Outcome.PassThrough
            val parent = msg.tags["batch"]?.takeIf { it in open }
            open[ref] = OpenBatch(ref, msg.params.getOrNull(1).orEmpty(), msg.params.drop(2), parent)
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
            return Outcome.Closed(tree)
        }

        msg.tags["batch"]?.let { ref ->
            open[ref]?.let { batch ->
                batch.children += MutableChild.Message(msg)
                return Outcome.Buffered
            }
        }
        return Outcome.PassThrough
    }

    private fun OpenBatch.freeze(): BatchTree = BatchTree(
        ref = ref,
        type = type,
        params = params.toList(),
        children = children.mapNotNull { child ->
            when (child) {
                is MutableChild.Message -> BatchChild.Message(child.message)
                is MutableChild.Nested -> BatchChild.Nested(child.batch)
                is MutableChild.Pending -> null
            }
        },
    )

    private fun discardOpenDescendants(batch: OpenBatch) {
        batch.children.filterIsInstance<MutableChild.Pending>().forEach { pending ->
            open.remove(pending.ref)?.let(::discardOpenDescendants)
        }
    }
}
