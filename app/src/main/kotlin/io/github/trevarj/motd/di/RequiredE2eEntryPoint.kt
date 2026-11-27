package io.github.trevarj.motd.di

import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import io.github.trevarj.motd.audio.VoiceMessageSender
import io.github.trevarj.motd.data.prefs.CertTrustStore
import io.github.trevarj.motd.data.repo.BufferRepository
import io.github.trevarj.motd.data.repo.NetworkRepository
import io.github.trevarj.motd.data.repo.SearchRepository
import io.github.trevarj.motd.diagnostics.DiagnosticLogger
import io.github.trevarj.motd.service.ConnectionManager
import io.github.trevarj.motd.service.HistoryResyncController
import io.github.trevarj.motd.ui.chat.ComposerDraftStore
import io.github.trevarj.motd.ui.share.PendingShareStore

/** Access to existing production seams for out-of-process instrumentation. */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface RequiredE2eEntryPoint {
    fun networks(): NetworkRepository

    fun buffers(): BufferRepository

    fun search(): SearchRepository

    fun certTrust(): CertTrustStore

    fun connections(): ConnectionManager

    fun history(): HistoryResyncController

    fun voiceMessages(): VoiceMessageSender

    fun pendingShares(): PendingShareStore

    fun composerDrafts(): ComposerDraftStore

    /**
     * The app's own decision journal, so a required-gate failure can carry WHY.
     *
     * Deliberate, not incidental. History paging is a control flow whose outcome is observable only
     * as a row count: which boundary each demand source requested, which gap a fill was pinned to,
     * and which fixed classification ended it are all invisible from the timeline, so a red run
     * reports a number and nothing that explains it. That gap cost eleven CI cycles once already.
     * The logger is off by default and its recorded fields are classification, ids, counts and
     * timestamps only — never message content — which is what makes exporting it into the uploaded
     * artifact tree admissible at all; `fast-suite-privacy.sh` keeps that honest.
     */
    fun diagnostics(): DiagnosticLogger
}
