package io.github.trevarj.motd.data.prefs

/** Durable one-shot state for opt-in behavior attached to a newly created network preset. */
interface PresetEnrollmentPrefs {
    /** Mark a newly inserted direct Libera preset as awaiting its first successful registration. */
    suspend fun markLiberaEligible(networkId: Long)

    /**
     * Atomically consume eligibility and record the attempt before any JOIN is written.
     * A true result can be returned at most once for a network id, including across process death.
     */
    suspend fun claimLiberaMotdJoin(networkId: Long): Boolean

    /** Remove unclaimed state when the provisional row is deleted or its endpoint changes. */
    suspend fun revokeLiberaEligibility(networkId: Long)
}
