package io.github.trevarj.motd

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.flowWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class LifecycleAwareCollectionTest {
    @Test
    fun `screen collection stops and resumes with lifecycle`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val owner = TestOwner()
            val source = MutableStateFlow(0)
            val values = mutableListOf<Int>()
            val collection = launch {
                source.flowWithLifecycle(owner.lifecycle, Lifecycle.State.STARTED)
                    .collect(values::add)
            }

            owner.registry.currentState = Lifecycle.State.STARTED
            runCurrent()
            source.emit(1)
            runCurrent()

            owner.registry.currentState = Lifecycle.State.CREATED
            runCurrent()
            source.emit(2)
            runCurrent()

            owner.registry.currentState = Lifecycle.State.STARTED
            runCurrent()
            assertEquals(listOf(0, 1, 2), values)
            collection.cancel()
        } finally {
            Dispatchers.resetMain()
        }
    }

    private class TestOwner : LifecycleOwner {
        val registry = LifecycleRegistry(this)
        override val lifecycle: Lifecycle = registry
    }
}
