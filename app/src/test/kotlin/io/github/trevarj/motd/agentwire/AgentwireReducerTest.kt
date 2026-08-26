package io.github.trevarj.motd.agentwire

import io.github.trevarj.motd.irc.agentwire.AgentwireEnvelope
import java.util.UUID
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentwireReducerTest {

    @Test
    fun `legacy added file payload is normalized as a unified diff`() {
        val diff = normalizeAgentwireDiff(
            "{'type': 'add'} /tmp/hello-world.sh\n#!/bin/sh\n\necho \"hello world\"",
        )

        assertEquals(
            "diff --git a/tmp/hello-world.sh b/tmp/hello-world.sh\n" +
                "--- /dev/null\n" +
                "+++ b/tmp/hello-world.sh\n" +
                "@@ -0,0 +1,3 @@\n" +
                "+#!/bin/sh\n" +
                "+\n" +
                "+echo \"hello world\"",
            diff,
        )
    }

    @Test
    fun `canonical unified diff remains unchanged`() {
        val diff = "diff --git a/file b/file\n--- a/file\n+++ b/file\n@@ -1 +1 @@\n-old\n+new"

        assertEquals(diff, normalizeAgentwireDiff(diff))
    }
    @Test
    fun `bootstrap snapshots establish binding settings queue and advertised actions`() {
        val reducer = AgentwireReducer()
        var state = AgentwireUiState(syncing = true)
        state = reducer.reduce(state, event("agent.hello", epoch = "epoch-1", data = buildJsonObject {
            put("epoch", "epoch-1")
            put("backend", "codex")
            put("actions", JsonArray(listOf(JsonPrimitive("turn.prompt"), JsonPrimitive("history.request"))))
            put("settings", JsonArray(listOf(JsonPrimitive("delivery"), JsonPrimitive("model"))))
            put("settingOptions", buildJsonObject {
                put("model", JsonArray(listOf(buildJsonObject {
                    put("value", "gpt-test")
                    put("label", "GPT Test")
                    put("efforts", JsonArray(listOf(JsonPrimitive("low"), JsonPrimitive("high"))))
                    put("defaultEffort", "high")
                    put("default", true)
                })))
            })
        }))
        state = reducer.reduce(state, event("channel.snapshot", sid = "s1", data = buildJsonObject {
            put("binding", buildJsonObject { put("sid", "s1"); put("cwd", "/work") })
            put("busy", true)
            put("tid", "t1")
            put("queue", JsonArray(listOf(buildJsonObject {
                put("iid", "q1"); put("sid", "s1"); put("position", 0); put("content", "later")
            })))
        }))

        assertEquals("epoch-1", state.epoch)
        assertEquals("s1", state.activeSid)
        assertEquals("/work", state.cwd)
        assertTrue(state.busy)
        assertEquals("later", state.queue.single().content)
        assertTrue("turn.prompt" in state.actions)
        assertEquals(setOf("delivery", "model"), state.supportedSettings)
        assertEquals("gpt-test", state.modelOptions.single().value)
        assertEquals(listOf("low", "high"), state.modelOptions.single().efforts)
        assertTrue(state.modelOptions.single().default)
        assertFalse(state.syncing)
    }

    @Test
    fun `session pages merge for a workspace hierarchy`() {
        val reducer = AgentwireReducer()
        var state = reducer.reduce(AgentwireUiState(), event("session.page", data = buildJsonObject {
            put("cwd", "/work/one")
            put("items", JsonArray(listOf(buildJsonObject {
                put("sid", "s1"); put("cwd", "/work/one"); put("title", "One")
            })))
        }))
        state = reducer.reduce(state, event("session.page", data = buildJsonObject {
            put("cwd", "/work/two")
            put("items", JsonArray(listOf(buildJsonObject {
                put("sid", "s2"); put("cwd", "/work/two"); put("title", "Two")
            })))
        }))

        assertEquals(listOf("s1"), state.workspaceSessions.getValue("/work/one").map(AgentwireListItem::id))
        assertEquals(listOf("s2"), state.workspaceSessions.getValue("/work/two").map(AgentwireListItem::id))
        assertEquals(setOf("/work/one", "/work/two"), state.loadedSessionDirectories)
    }

    @Test
    fun `session runtime status is distinct from the attached binding`() {
        val running = AgentwireListItem(
            id = "running",
            title = "Running",
            raw = buildJsonObject { put("busy", true) },
        )
        val waiting = AgentwireListItem(
            id = "waiting",
            title = "Waiting",
            raw = buildJsonObject {
                put("busy", true)
                put("flags", JsonArray(listOf(JsonPrimitive("waitingOnApproval"))))
            },
        )
        val idle = AgentwireListItem(
            id = "idle",
            title = "Idle",
            raw = buildJsonObject { put("busy", false) },
        )

        assertEquals("Running", agentwireSessionRuntimeStatus(running))
        assertEquals("Waiting", agentwireSessionRuntimeStatus(waiting))
        assertEquals(null, agentwireSessionRuntimeStatus(idle))
    }

    @Test
    fun `continued session pages append within the same directory`() {
        val reducer = AgentwireReducer()
        var state = reducer.reduce(AgentwireUiState(), event("session.page", data = buildJsonObject {
            put("cwd", "/work")
            put("items", JsonArray(listOf(buildJsonObject {
                put("sid", "s1"); put("cwd", "/work"); put("title", "One")
            })))
        }))
        state = reducer.reduce(state, event("session.page", data = buildJsonObject {
            put("cwd", "/work")
            put("cursor", "100")
            put("items", JsonArray(listOf(buildJsonObject {
                put("sid", "s2"); put("cwd", "/work"); put("title", "Two")
            })))
        }))

        assertEquals(
            listOf("s1", "s2"),
            state.workspaceSessions.getValue("/work").map(AgentwireListItem::id),
        )
    }

    @Test
    fun `live session pages stay separate from workspace pages`() {
        val reducer = AgentwireReducer()
        var state = reducer.reduce(
            AgentwireUiState(),
            event("session.page", data = buildJsonObject {
                put("scope", "live")
                put("items", JsonArray(listOf(buildJsonObject {
                    put("sid", "live"); put("cwd", "/work"); put("title", "Desktop TUI")
                })))
            }),
        )
        state = reducer.reduce(
            state,
            event("session.page", data = buildJsonObject {
                put("scope", "workspace")
                put("cwd", "/work")
                put("items", JsonArray(listOf(buildJsonObject {
                    put("sid", "stored"); put("cwd", "/work"); put("title", "Stored")
                })))
            }),
        )

        assertEquals(listOf("live"), state.liveSessions.map(AgentwireListItem::id))
        assertEquals(
            listOf("stored"),
            state.workspaceSessions.getValue("/work").map(AgentwireListItem::id),
        )
    }

    @Test
    fun `workspace pages retain lazy directory hierarchy`() {
        val reducer = AgentwireReducer()
        var state = reducer.reduce(AgentwireUiState(), event("workspace.page", data = buildJsonObject {
            put("items", JsonArray(listOf(buildJsonObject {
                put("path", "/work"); put("name", "work"); put("hasChildren", true)
            })))
        }))
        state = reducer.reduce(state, event("workspace.page", data = buildJsonObject {
            put("parent", "/work")
            put("items", JsonArray(listOf(buildJsonObject {
                put("path", "/work/project"); put("name", "project"); put("hasChildren", false)
            })))
        }))

        assertEquals("/work", state.workspaceChildren.getValue("").single().id)
        assertEquals("/work/project", state.workspaceChildren.getValue("/work").single().id)
    }

    @Test
    fun `revision ordering deduplicates stale queue updates and snapshots reconcile`() {
        val reducer = AgentwireReducer()
        var state = AgentwireUiState()
        state = reducer.reduce(state, event("queue.item.added", iid = "q1", rev = 2, data = queue("q1", "new", 0)))
        state = reducer.reduce(state, event("queue.item.updated", iid = "q1", rev = 1, data = queue("q1", "old", 0)))
        assertEquals("new", state.queue.single().content)

        state = reducer.reduce(state, event("queue.snapshot", data = buildJsonObject {
            put("items", JsonArray(listOf(queue("q2", "snapshot", 0))))
        }))
        assertEquals(listOf("q2"), state.queue.map(AgentwireQueueItem::iid))
    }

    @Test
    fun `binding detach clears the active session`() {
        val reducer = AgentwireReducer()
        val state = reducer.reduce(
            AgentwireUiState(activeSid = "s1", cwd = "/work", busy = true, currentTid = "t1"),
            event("binding.changed", data = buildJsonObject { put("sid", JsonNull) }),
        )

        assertEquals(null, state.activeSid)
        assertEquals(null, state.cwd)
        assertFalse(state.busy)
        assertEquals(null, state.currentTid)
    }

    @Test
    fun `binding switch clears old activity and restores recent session outputs`() {
        val reducer = AgentwireReducer()
        var state = AgentwireUiState(
            activeSid = "old",
            cwd = "/work/old",
            busy = true,
            currentTid = "old-turn",
            timeline = listOf(
                AgentwireTimelineItem(
                    "old-output", "assistant.completed", 1, "old", "old-turn",
                    "Assistant", "Old session output",
                ),
            ),
            actionStatus = mapOf("old-output" to "succeeded"),
            historyLoading = true,
            historyPage = "old-page",
            historyBeforeAt = 1,
            olderHistoryAvailable = true,
        )

        state = reducer.reduce(
            state,
            event(
                "binding.changed",
                sid = "new",
                data = buildJsonObject {
                    put("previousSid", "old")
                    put("session", buildJsonObject {
                        put("sid", "new")
                        put("cwd", "/work/new")
                    })
                },
            ),
        )

        assertEquals("new", state.activeSid)
        assertEquals("/work/new", state.cwd)
        assertTrue(state.timeline.isEmpty())
        assertTrue(state.actionStatus.isEmpty())
        assertFalse(state.historyLoading)
        assertFalse(state.olderHistoryAvailable)
        assertEquals(null, state.historyBeforeAt)

        state = reducer.reduce(
            state,
            event(
                "session.snapshot",
                sid = "new",
                tid = "new-turn",
                data = buildJsonObject {
                    put("busy", true)
                    put("status", "running")
                    put("recentOutputs", JsonArray(listOf(
                        buildJsonObject {
                            put("iid", "recent-1")
                            put("tid", "prior-turn")
                            put("phase", "final")
                            put("content", "First recovered output")
                        },
                        buildJsonObject {
                            put("iid", "recent-2")
                            put("tid", "new-turn")
                            put("phase", "commentary")
                            put("content", "Current recovered update")
                        },
                    )))
                    put("recentActivity", JsonArray(listOf(
                        buildJsonObject {
                            put("kind", "tool.started")
                            put("iid", "tool-1")
                            put("tid", "new-turn")
                            put("data", buildJsonObject {
                                put("id", "tool-1")
                                put("kind", "shell")
                                put("label", "\$ git status --short")
                                put("input", "git status --short")
                            })
                        },
                    )))
                },
            ),
        )

        assertTrue(state.busy)
        assertEquals("new-turn", state.currentTid)
        assertEquals(
            listOf("First recovered output", "Current recovered update", "Command\ngit status --short"),
            state.timeline.map { it.body },
        )
        assertEquals(listOf("assistant.completed", "assistant.completed", "tool.started"), state.timeline.map { it.kind })
        assertTrue(state.timeline.last().running)
        assertTrue(state.timeline.all(AgentwireTimelineItem::historical))
    }

    @Test
    fun `empty restored session snapshot shows current status`() {
        val reducer = AgentwireReducer()
        val state = reducer.reduce(
            AgentwireUiState(activeSid = "s1"),
            event(
                "session.snapshot",
                sid = "s1",
                data = buildJsonObject {
                    put("busy", false)
                    put("status", "ready")
                    put("recentOutputs", JsonArray(emptyList()))
                },
            ),
        )

        assertEquals("Session", state.timeline.single().title)
        assertEquals("Session is ready for a prompt.", state.timeline.single().body)
        assertFalse(state.timeline.single().historical)
    }

    @Test
    fun `history for another session is rejected without poisoning later replay`() {
        val reducer = AgentwireReducer()
        val requestId = UUID.randomUUID().toString()
        var state = AgentwireUiState(
            activeSid = "live",
            cwd = "/work/live",
            busy = false,
            settings = mapOf("model" to "current"),
            historyLoading = true,
            historyRequestId = requestId,
            historySid = "live",
        )
        val replayId = UUID.randomUUID().toString()
        val old = event(
            "assistant.completed",
            sid = "old",
            tid = "t1",
            iid = "answer",
            reply = requestId,
            history = true,
            data = buildJsonObject { put("content", "Wrong session") },
        ).copy(id = replayId)
        state = reducer.reduce(state, old)

        assertTrue(state.timeline.isEmpty())
        assertTrue(state.historyStaged.isEmpty())

        val current = old.copy(
            sid = "live",
            data = buildJsonObject { put("content", "Correct session") },
        )
        state = reducer.reduce(state, current)
        state = reducer.reduce(
            state,
            event(
                "history.end",
                sid = "live",
                reply = requestId,
                data = buildJsonObject { put("count", 1) },
            ),
        )

        assertEquals("live", state.activeSid)
        assertEquals("/work/live", state.cwd)
        assertFalse(state.busy)
        assertEquals(mapOf("model" to "current"), state.settings)
        assertEquals(listOf("Correct session"), state.timeline.map { it.body })
        assertEquals(1L, state.historyBeforeAt)
    }

    @Test
    fun `a short nonempty history page remains pageable until an empty page`() {
        val reducer = AgentwireReducer()
        val firstRequest = UUID.randomUUID().toString()
        var state = reducer.reduce(
            AgentwireUiState(
                activeSid = "s1",
                historyLoading = true,
                historyRequestId = firstRequest,
                historySid = "s1",
            ),
            event(
                "history.end",
                sid = "s1",
                reply = firstRequest,
                data = buildJsonObject { put("count", 1) },
            ),
        )

        assertFalse(state.historyLoading)
        assertTrue(state.olderHistoryAvailable)

        val secondRequest = UUID.randomUUID().toString()
        state = reducer.reduce(
            state.copy(historyLoading = true, historyRequestId = secondRequest),
            event(
                "history.end",
                sid = "s1",
                reply = secondRequest,
                data = buildJsonObject { put("count", 0) },
            ),
        )
        assertFalse(state.historyLoading)
        assertFalse(state.olderHistoryAvailable)
    }

    @Test
    fun `late history reply after a binding switch is ignored`() {
        val reducer = AgentwireReducer()
        val requestId = UUID.randomUUID().toString()
        var state = AgentwireUiState(
            activeSid = "old",
            historyRequestId = requestId,
            historySid = "old",
            historyLoading = true,
        )
        state = reducer.reduce(state, event("binding.changed", sid = "new"))
        state = reducer.reduce(
            state,
            event(
                "assistant.completed",
                sid = "old",
                tid = "t1",
                reply = requestId,
                history = true,
                data = buildJsonObject { put("content", "Late") },
            ),
        )

        assertEquals("new", state.activeSid)
        assertTrue(state.timeline.isEmpty())
        assertTrue(state.historyStaged.isEmpty())
    }

    @Test
    fun `history preserves multiple assistant items in one turn and deduplicates snapshot`() {
        val reducer = AgentwireReducer()
        val requestId = UUID.randomUUID().toString()
        var state = AgentwireUiState(
            activeSid = "s1",
            timeline = listOf(
                AgentwireTimelineItem(
                    "restored", "assistant.completed", 10, "s1", "t1", "Assistant", "Second",
                    historical = true, backendItemId = "a2",
                ),
            ),
            historyLoading = true,
            historyRequestId = requestId,
            historySid = "s1",
        )
        listOf("a1" to "First", "a2" to "Second").forEach { (iid, content) ->
            state = reducer.reduce(
                state,
                event(
                    "assistant.completed",
                    sid = "s1",
                    tid = "t1",
                    iid = iid,
                    reply = requestId,
                    history = true,
                    data = buildJsonObject { put("content", content) },
                ),
            )
        }
        state = reducer.reduce(
            state,
            event(
                "history.end",
                sid = "s1",
                reply = requestId,
                data = buildJsonObject { put("count", 2); put("next", "older") },
            ),
        )

        assertEquals(listOf("First", "Second"), state.timeline.map { it.body })
        assertEquals("older", state.historyCursor)
        assertTrue(state.olderHistoryAvailable)
    }

    @Test
    fun `echoed user prompt replaces its optimistic local row`() {
        val reducer = AgentwireReducer()
        val actionId = UUID.randomUUID().toString()
        val local = AgentwireTimelineItem(
            actionId,
            "user.prompt",
            1,
            "s1",
            null,
            "You",
            "hello",
            backendItemId = actionId,
        )

        val state = reducer.reduce(
            AgentwireUiState(activeSid = "s1", timeline = listOf(local)),
            event(
                "user.prompt",
                sid = "s1",
                tid = "t1",
                iid = actionId,
                data = buildJsonObject { put("content", "hello") },
            ),
        )

        assertEquals(1, state.timeline.size)
        assertEquals("t1", state.timeline.single().tid)
    }

    @Test
    fun `historical envelopes may retain an old epoch but stale live events may not`() {
        val oldHistory = event("assistant.completed", epoch = "old", history = true)
        val staleLive = event("assistant.completed", epoch = "old")

        assertTrue(acceptsAgentwireEpoch(oldHistory, "current"))
        assertFalse(acceptsAgentwireEpoch(staleLive, "current"))
        assertTrue(acceptsAgentwireEpoch(staleLive, null))
    }

    @Test
    fun `sync retries with fresh ids and a bounded backoff until correlated state is ready`() = runTest {
        var ready = false
        val sent = mutableListOf<String>()
        val waits = mutableListOf<Long>()

        retryAgentwireSync(
            isReady = { ready },
            issue = { sent += it },
            nextId = { "sync-${sent.size + 1}" },
            pause = { duration ->
                waits += duration
                if (sent.size == 6) ready = true
            },
        )

        assertEquals((1..6).map { "sync-$it" }, sent)
        assertEquals(listOf(1_000L, 2_000L, 4_000L, 8_000L, 10_000L, 10_000L), waits)
    }

    @Test
    fun `turn assistant plan tool usage and request families reduce into harness state`() {
        val reducer = AgentwireReducer()
        var state = AgentwireUiState(activeSid = "s1")
        val kinds = listOf(
            "turn.started", "assistant.delta", "assistant.completed", "plan.updated", "tool.started",
            "tool.updated", "tool.completed", "usage.updated", "approval.review.started",
            "approval.review.completed", "turn.completed",
        )
        kinds.forEach { kind ->
            state = reducer.reduce(state, event(kind, sid = "s1", tid = "t1", iid = "i1", data = buildJsonObject {
                put("content", if (kind == "assistant.delta") "part" else "final")
                put("summary", "summary")
                put("kind", "shell")
                put("success", true)
            }))
        }
        state = reducer.reduce(state, event("request.opened", sid = "s1", rid = "r1", data = buildJsonObject {
            put("type", "approval"); put("summary", "Run command"); put("redacted", false); put("inactive", false)
        }))

        assertFalse(state.busy)
        assertTrue(state.timeline.any { it.kind == "assistant.completed" && it.body == "final" })
        assertTrue(state.timeline.any { it.kind == "tool.completed" && it.success == true })
        assertEquals("r1", state.requests.single().rid)

        state = reducer.reduce(state, event("request.resolved", rid = "r1"))
        assertTrue(state.requests.isEmpty())
    }

    @Test
    fun `tool lifecycle is one labeled card whose payload is left to the session log`() {
        val reducer = AgentwireReducer()
        var state = reducer.reduce(
            AgentwireUiState(),
            event(
                "tool.started",
                tid = "t1",
                iid = "i1",
                data = buildJsonObject {
                    put("id", "i1")
                    put("kind", "shell")
                    put("label", "$ git status --short")
                    put("input", "git status --short")
                },
            ),
        )
        state = reducer.reduce(
            state,
            event(
                "tool.completed",
                tid = "t1",
                iid = "i1",
                data = buildJsonObject {
                    put("id", "i1")
                    put("kind", "shell")
                    put("label", "$ git status --short")
                    put("input", "git status --short")
                    put("output", "M src/agentwire/bridge.py")
                    put("status", "completed")
                    put("exitCode", 0)
                    put("success", true)
                },
            ),
        )

        val tool = state.timeline.single()
        assertEquals("tool.completed", tool.kind)
        assertEquals("$ git status --short", tool.title)
        // The grouped card needs the label and outcome only; payloads live in AgentwireLogStore.
        assertEquals(null, tool.body)
        assertEquals(null, tool.data.string("input"))
        assertEquals(null, tool.data.string("output"))
        assertEquals(null, tool.data.string("diff"))
        assertEquals("completed", tool.data.string("status"))
        assertEquals(0, tool.data.int("exitCode"))
        assertEquals("shell", tool.data.string("kind"))
        assertEquals(true, tool.success)
    }

    @Test
    fun `a diff is stripped from the retained tool state as well`() {
        val reducer = AgentwireReducer()
        val state = reducer.reduce(
            AgentwireUiState(),
            event(
                "tool.completed",
                tid = "t1",
                iid = "i1",
                data = buildJsonObject {
                    put("id", "i1")
                    put("kind", "apply_patch")
                    put("label", "apply_patch")
                    put("diff", "diff --git a/f b/f\n--- a/f\n+++ b/f\n@@ -1 +1 @@\n-a\n+b")
                    put("durationMs", 42)
                    put("success", true)
                },
            ),
        )

        val tool = state.timeline.single()
        assertEquals(null, tool.data.string("diff"))
        assertEquals(42, tool.data.int("durationMs"))
    }

    @Test
    fun `ordered insertion sorts events that arrive out of order without a full re-sort`() {
        val reducer = AgentwireReducer()
        var state = AgentwireUiState()
        listOf(30L, 10L, 20L, 10L, 40L).forEach { at ->
            state = reducer.reduce(
                state,
                event("assistant.completed", tid = "t1", at = at, data = buildJsonObject { put("content", "at-$at") }),
            )
        }

        assertEquals(listOf(10L, 10L, 20L, 30L, 40L), state.timeline.map(AgentwireTimelineItem::at))
        // Items sharing a timestamp keep arrival order, as the previous stable sort did.
        assertEquals(
            listOf("at-10", "at-10", "at-20", "at-30", "at-40"),
            state.timeline.map { it.body },
        )
    }

    @Test
    fun `a completing tool holds the position its first event claimed`() {
        val reducer = AgentwireReducer()
        var state = reducer.reduce(
            AgentwireUiState(),
            event("tool.started", tid = "t1", iid = "i1", at = 10, data = buildJsonObject { put("id", "i1") }),
        )
        state = reducer.reduce(
            state,
            event("assistant.completed", tid = "t1", iid = "a1", at = 20, data = buildJsonObject { put("content", "after") }),
        )
        state = reducer.reduce(
            state,
            event("tool.completed", tid = "t1", iid = "i1", at = 30, data = buildJsonObject { put("id", "i1"); put("success", true) }),
        )

        assertEquals(listOf("tool.completed", "assistant.completed"), state.timeline.map(AgentwireTimelineItem::kind))
        assertEquals(listOf(10L, 20L), state.timeline.map(AgentwireTimelineItem::at))
    }

    @Test
    fun `the live timeline is capped and keeps the newest items`() {
        val reducer = AgentwireReducer()
        var state = AgentwireUiState()
        val total = AGENTWIRE_TIMELINE_CAP + 50
        (1..total).forEach { index ->
            state = reducer.reduce(
                state,
                event(
                    "assistant.completed",
                    tid = "t1",
                    at = index.toLong(),
                    data = buildJsonObject { put("content", "line-$index") },
                ),
            )
        }

        assertEquals(AGENTWIRE_TIMELINE_CAP, state.timeline.size)
        assertEquals(51L, state.timeline.first().at)
        assertEquals(total.toLong(), state.timeline.last().at)
    }

    @Test
    fun `plan updates replace the card and stop animating when complete`() {
        val reducer = AgentwireReducer()
        var state = reducer.reduce(
            AgentwireUiState(),
            event(
                "plan.updated",
                tid = "t1",
                data = buildJsonObject {
                    put("summary", "Implement the reducer")
                    put("running", true)
                    put("status", "inProgress")
                    put("completedSteps", 1)
                    put("totalSteps", 2)
                },
            ),
        )

        assertTrue(state.timeline.single().running)
        assertEquals("Implement the reducer\n\n1 of 2 steps complete", state.timeline.single().body)

        state = reducer.reduce(
            state,
            event(
                "plan.updated",
                tid = "t1",
                data = buildJsonObject {
                    put("summary", "Plan completed")
                    put("running", false)
                    put("status", "completed")
                    put("completedSteps", 2)
                    put("totalSteps", 2)
                },
            ),
        )

        assertEquals(1, state.timeline.size)
        assertFalse(state.timeline.single().running)
        assertEquals("Plan completed\n\n2 of 2 steps complete", state.timeline.single().body)
    }

    @Test
    fun `turn completion stops a plan when the backend omits its final update`() {
        val reducer = AgentwireReducer()
        var state = reducer.reduce(
            AgentwireUiState(),
            event(
                "plan.updated",
                tid = "t1",
                data = buildJsonObject {
                    put("summary", "Run validation")
                    put("running", true)
                },
            ),
        )

        state = reducer.reduce(state, event("turn.completed", tid = "t1"))

        assertFalse(state.timeline.single { it.kind == "plan.updated" }.running)
    }

    @Test
    fun `replayed UUID is idempotent and action outcomes are never retried by reducer`() {
        val reducer = AgentwireReducer()
        val completed = event("assistant.completed", tid = "t1", data = buildJsonObject { put("content", "once") })
        var state = reducer.reduce(AgentwireUiState(), completed)
        state = reducer.reduce(state, completed.copy(history = true))
        assertEquals(1, state.timeline.size)

        state = reducer.reduce(state, event("action.accepted", reply = "a1"))
        state = reducer.reduce(state, event("action.uncertain", reply = "a1"))
        assertEquals("uncertain", state.actionStatus["a1"])
    }

    @Test
    fun `status of another session fills the registry without touching the bound session`() {
        val reducer = AgentwireReducer()
        val bound = AgentwireUiState(activeSid = "s1", cwd = "/work", busy = true, currentTid = "t1")

        val state = reducer.reduce(bound, event("session.status", sid = "s9", data = buildJsonObject {
            put("busy", false)
            put("flags", JsonArray(listOf(JsonPrimitive("waiting"))))
            put("cwd", "/other")
            put("tuiAttached", true)
        }))

        assertEquals(
            AgentwireSessionStatus("s9", busy = false, flags = listOf("waiting"), cwd = "/other", tuiAttached = true, at = 1),
            state.sessionStatuses["s9"],
        )
        // The drawer learned about s9; the bound session's own state is unchanged.
        assertEquals("s1", state.activeSid)
        assertEquals("/work", state.cwd)
        assertTrue(state.busy)
        assertEquals("t1", state.currentTid)
        assertTrue(state.timeline.isEmpty())
    }

    @Test
    fun `status of the bound session updates both the session and its registry entry`() {
        val reducer = AgentwireReducer()
        val bound = AgentwireUiState(activeSid = "s1", cwd = "/work", busy = false)

        val state = reducer.reduce(bound, event("session.status", sid = "s1", tid = "t2", data = buildJsonObject {
            put("busy", true)
            put("flags", JsonArray(listOf(JsonPrimitive("waiting"))))
            put("cwd", "/moved")
        }))

        assertTrue(state.busy)
        assertEquals("/moved", state.cwd)
        assertEquals("t2", state.currentTid)
        assertEquals(
            AgentwireSessionStatus("s1", busy = true, flags = listOf("waiting"), cwd = "/moved", tuiAttached = null, at = 1),
            state.sessionStatuses["s1"],
        )
    }

    @Test
    fun `other session-owned kinds with a foreign sid are still dropped`() {
        val reducer = AgentwireReducer()
        val bound = AgentwireUiState(activeSid = "s1", busy = false)

        var state = reducer.reduce(bound, event("assistant.completed", sid = "s9", data = buildJsonObject {
            put("content", "not ours")
        }))
        state = reducer.reduce(state, event("turn.started", sid = "s9", tid = "t9"))
        state = reducer.reduce(state, event("session.snapshot", sid = "s9", data = buildJsonObject {
            put("cwd", "/other")
        }))

        assertEquals(bound, state)
        assertTrue(state.sessionStatuses.isEmpty())
    }

    @Test
    fun `resync clears the session status registry`() {
        val reducer = AgentwireReducer()
        val state = reducer.reduce(
            AgentwireUiState(activeSid = "s1"),
            event("session.status", sid = "s9", data = buildJsonObject { put("busy", true) }),
        )
        assertTrue(state.sessionStatuses.isNotEmpty())

        assertTrue(state.awaitingAgentwireSync().sessionStatuses.isEmpty())
    }

    private fun queue(id: String, content: String, position: Int) = buildJsonObject {
        put("iid", id); put("content", content); put("position", position); put("sid", "s1")
    }

    @Test
    fun `subagent updates replace the list and clear with the binding`() {
        val reducer = AgentwireReducer()
        var state = AgentwireUiState(activeSid = "s1")

        state = reducer.reduce(state, event("subagent.updated", sid = "s1", data = subagents(
            buildJsonObject {
                put("id", "a1")
                put("type", "Explore")
                put("description", "map the repository")
                put("status", "running")
                put("isBackground", true)
            },
            buildJsonObject {
                put("id", "a2")
                put("type", "Terra")
                put("description", "add the reducer test")
                put("status", "completed")
                put("isBackground", false)
                put("toolUses", 7)
                put("durationMs", 4200)
                put("tokens", 1234)
            },
            // Dropped: an agent without an id or a status is not renderable.
            buildJsonObject { put("type", "Terra") },
        )))

        assertEquals(
            listOf(
                AgentwireSubagent("a1", "Explore", "map the repository", "running", true),
                AgentwireSubagent("a2", "Terra", "add the reducer test", "completed", false, 7, 4200, 1234),
            ),
            state.subagents,
        )
        assertFalse(state.subagents[0].terminal)
        assertTrue(state.subagents[1].terminal)

        // Replace, never merge: the newest list is the whole truth.
        state = reducer.reduce(state, event("subagent.updated", sid = "s1", data = subagents(
            buildJsonObject {
                put("id", "a3")
                put("type", "Terra")
                put("description", "ship it")
                put("status", "queued")
            },
        )))
        assertEquals(listOf("a3"), state.subagents.map(AgentwireSubagent::id))

        // Bound-session state: another session's list never lands here.
        state = reducer.reduce(state, event("subagent.updated", sid = "other", data = subagents(
            buildJsonObject {
                put("id", "elsewhere")
                put("type", "Terra")
                put("description", "x")
                put("status", "running")
            },
        )))
        assertEquals(listOf("a3"), state.subagents.map(AgentwireSubagent::id))

        state = reducer.reduce(
            state,
            event("binding.changed", sid = "s2", data = buildJsonObject { put("previousSid", "s1") }),
        )
        assertTrue(state.subagents.isEmpty())
    }

    @Test
    fun `awaiting sync drops the subagent registry`() {
        val state = AgentwireUiState(
            activeSid = "s1",
            subagents = listOf(AgentwireSubagent("a1", "Terra", "x", "running", false)),
        ).awaitingAgentwireSync()

        assertTrue(state.subagents.isEmpty())
    }

    @Test
    fun `subagent detail line is built only from reported numbers`() {
        val agent = AgentwireSubagent("a1", "Terra", "x", "completed", false)

        assertEquals(null, agentwireSubagentDetail(agent))
        assertEquals("1 tool use", agentwireSubagentDetail(agent.copy(toolUses = 1)))
        assertEquals(
            "7 tool uses · 1.2k tokens · 4s",
            agentwireSubagentDetail(agent.copy(toolUses = 7, tokens = 1234, durationMs = 4200)),
        )
        assertEquals("999 tokens · 0s", agentwireSubagentDetail(agent.copy(tokens = 999, durationMs = 400)))
    }

    private fun subagents(vararg agents: kotlinx.serialization.json.JsonObject) = buildJsonObject {
        put("agents", JsonArray(agents.toList()))
    }

    private fun event(
        kind: String,
        sid: String? = null,
        tid: String? = null,
        iid: String? = null,
        rid: String? = null,
        rev: Long? = null,
        reply: String? = null,
        history: Boolean? = null,
        epoch: String = "epoch",
        at: Long = 1,
        data: kotlinx.serialization.json.JsonObject? = null,
    ) = AgentwireEnvelope(
        kind, "event", UUID.randomUUID().toString(), at, "bridge", epoch, sid = sid,
        tid = tid, iid = iid, rid = rid, rev = rev, reply = reply, history = history, data = data,
    )
}
