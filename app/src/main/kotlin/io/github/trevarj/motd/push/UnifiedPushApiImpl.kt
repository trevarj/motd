package io.github.trevarj.motd.push

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import org.unifiedpush.android.connector.UnifiedPush

/**
 * Real [UnifiedPushApi] over the static `org.unifiedpush.android.connector.UnifiedPush` calls
 * (connector 2.5.0). Signatures verified against the artifact:
 *   getDistributors(context) -> List<String>
 *   getAckDistributor(context) -> String?
 *   saveDistributor(context, distributor)
 *   registerApp(context, instance, ...)   // extra args default
 *   unregisterApp(context, instance)
 *
 * All calls take the application context; the instance string is `networkId.toString()`.
 */
@Singleton
class UnifiedPushApiImpl @Inject constructor(
    @ApplicationContext private val context: Context,
) : UnifiedPushApi {
    override fun getDistributors(): List<String> = UnifiedPush.getDistributors(context)
    override fun getAckDistributor(): String? = UnifiedPush.getAckDistributor(context)
    override fun saveDistributor(distributor: String) = UnifiedPush.saveDistributor(context, distributor)
    override fun registerApp(instance: String) = UnifiedPush.registerApp(context, instance)
    override fun unregisterApp(instance: String) = UnifiedPush.unregisterApp(context, instance)
}
