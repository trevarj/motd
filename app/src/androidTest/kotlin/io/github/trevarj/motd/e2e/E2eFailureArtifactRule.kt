package io.github.trevarj.motd.e2e

import android.content.Context
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import io.github.trevarj.motd.MainActivity
import java.io.File
import org.junit.rules.TestWatcher
import org.junit.runner.Description

/** Writes allowlisted structural diagnostics before closing the activity scenario. */
class E2eFailureArtifactRule(
    private val holder: ScenarioHolder,
    private val milestones: E2eMilestoneRecorder,
) : TestWatcher() {
    private var failure: Throwable? = null
    private var description: Description? = null

    override fun starting(description: Description) {
        this.description = description
        val context = InstrumentationRegistry.getInstrumentation().context
        val root = artifactRoot(context)
        // This is the launcher-visible post-start boundary. It contains only the fixed test id;
        // fast-suite pulls it while adb is alive and never relies on logcat/instrumentation text.
        File(root, "started.jsonl").appendText("{\"test\":\"${safeName()}\"}\n")
    }

    override fun failed(e: Throwable, description: Description) {
        failure = e
        capture()
    }

    override fun finished(description: Description) {
        try { if (failure != null) capture() } finally { holder.close() }
    }

    private fun capture() {
        // Deliberately use the instrumentation APK's internal storage. It is always available,
        // survives clearing the target package, and remains readable to the launcher via run-as.
        val context = InstrumentationRegistry.getInstrumentation().context
        val output = File(artifactRoot(context), safeName()).apply {
            check(mkdirs() || isDirectory) { "Could not create structured E2E artifact directory" }
        }
        val error = failure ?: return
        File(output, "failure.json").writeText(
            "{\"test\":\"${safeName()}\",\"throwable\":\"${error::class.java.name}\",\"frames\":[" +
                error.stackTrace.take(20).joinToString(",") { "\"${it.className}.${it.methodName}:${it.lineNumber}\"" } + "]}",
        )
        File(output, "route.json").writeText("{\"screen\":\"unavailable\"}")
        File(output, "semantics.json").writeText("{\"tags\":[],\"bounds\":[]}")
        File(output, "lazy-state.json").writeText("{\"visible\":[]}")
        File(output, "connections.json").writeText("{\"states\":[]}")
        milestones.writeTo(File(output, "milestones.jsonl"))
    }

    private fun safeName(): String = (description?.className.orEmpty() + "_" + description?.methodName.orEmpty())
        .replace(Regex("[^A-Za-z0-9_.-]"), "_")

    private fun artifactRoot(context: Context): File {
        // ContextImpl creates filesDir lazily. Prime it through the framework API before making
        // the structured subdirectory; direct mkdirs() can fail under Test Orchestrator on a
        // freshly cleared instrumentation package.
        context.openFileOutput("required-e2e.init", Context.MODE_PRIVATE).close()
        return File(context.filesDir, "required-e2e").apply {
            check(mkdirs() || isDirectory) { "Could not create E2E artifact directory" }
        }
    }
}

class ScenarioHolder {
    var scenario: ActivityScenario<MainActivity>? = null
    fun launch() { scenario = ActivityScenario.launch(MainActivity::class.java) }
    fun close() { scenario?.close(); scenario = null }
}
