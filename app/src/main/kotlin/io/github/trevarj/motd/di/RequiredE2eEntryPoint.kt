package io.github.trevarj.motd.di

import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import io.github.trevarj.motd.data.prefs.CertTrustStore
import io.github.trevarj.motd.data.repo.BufferRepository
import io.github.trevarj.motd.data.repo.NetworkRepository
import io.github.trevarj.motd.data.repo.SearchRepository
import io.github.trevarj.motd.service.ConnectionManager

/** Access to existing production seams for out-of-process instrumentation. */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface RequiredE2eEntryPoint {
    fun networks(): NetworkRepository
    fun buffers(): BufferRepository
    fun search(): SearchRepository
    fun certTrust(): CertTrustStore
    fun connections(): ConnectionManager
}
