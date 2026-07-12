package io.github.trevarj.motd.push

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class FossFcmPushApi @Inject constructor() : FcmPushApi {
    override val available = false
    override fun start() = Unit
    override suspend fun reconcile(connectable: Set<Long>) = Unit
    override suspend fun onTokenChanged(token: String) = Unit
}

@Module
@InstallIn(SingletonComponent::class)
abstract class FossFcmModule {
    @Binds @Singleton
    abstract fun bindFcmPushApi(impl: FossFcmPushApi): FcmPushApi
}
