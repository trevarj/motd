package io.github.trevarj.motd.push

/**
 * Testable seam over the static UnifiedPush connector API (plans/10 §"New round-2 types").
 *
 * Interface only for WP-R0; WP-R2 adds the impl over the static connector and its Hilt binding.
 */
interface UnifiedPushApi {
    fun getDistributors(): List<String>
    fun getAckDistributor(): String?
    fun saveDistributor(distributor: String)
    fun registerApp(instance: String)
    fun unregisterApp(instance: String)
}
