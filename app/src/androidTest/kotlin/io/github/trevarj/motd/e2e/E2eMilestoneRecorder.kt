package io.github.trevarj.motd.e2e

import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

/** Privacy-safe event names and numeric identifiers only; never fixture values or UI text. */
class E2eMilestoneRecorder {
    private val entries = CopyOnWriteArrayList<String>()

    fun record(event: String, detail: String = "") {
        require(event.matches(Regex("[a-z0-9_]+")))
        entries += "{\"event\":\"$event\",\"detail\":\"${detail.replace(Regex("[^a-zA-Z0-9_=,.-]"), "_")}\"}"
    }

    fun writeTo(file: File) {
        file.writeText(entries.joinToString(separator = "\n", postfix = if (entries.isEmpty()) "" else "\n"))
    }
}
