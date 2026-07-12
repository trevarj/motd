package io.github.trevarj.motd.push

/** Flavor seam: the FOSS implementation is inert; the Google flavor owns Firebase classes. */
interface FcmPushApi {
    val available: Boolean
    fun start()
    suspend fun reconcile(connectable: Set<Long>)
    suspend fun onTokenChanged(token: String)
}
