package io.github.trevarj.motd

import androidx.compose.ui.platform.AndroidUiDispatcher
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement
import kotlin.coroutines.ContinuationInterceptor

/**
 * Paging publishes rows via the JVM-wide [AndroidUiDispatcher.Main]; a Robolectric looper reset can
 * strand its scheduled flags so nothing posts again. Clear that state around each test.
 */
class UiDispatcherResetRule : TestRule {
    override fun apply(
        base: Statement,
        description: Description,
    ): Statement =
        object : Statement() {
            override fun evaluate() {
                reset()
                try {
                    base.evaluate()
                } finally {
                    // A test can wedge the dispatcher at disposal; leave it clean for the next class.
                    reset()
                }
            }
        }

    private fun reset() {
        val dispatcher = AndroidUiDispatcher.Main[ContinuationInterceptor] ?: return
        val type = dispatcher.javaClass

        fun field(name: String) = type.getDeclaredField(name).also { it.isAccessible = true }
        synchronized(field("lock").get(dispatcher)) {
            (field("toRunTrampolined").get(dispatcher) as ArrayDeque<*>).clear()
            field("scheduledTrampolineDispatch").setBoolean(dispatcher, false)
            field("scheduledFrameDispatch").setBoolean(dispatcher, false)
        }
    }
}
