package io.github.trevarj.motd.bouncer

import io.github.trevarj.motd.ui.onboarding.AuthForm
import io.github.trevarj.motd.ui.onboarding.AuthMode

/** User-facing bouncer implementations supported by the guided connection flows. */
enum class BouncerKind { SOJU, ZNC }

data class SojuLoginForm(
    val username: String = "",
    val password: String = "",
) {
    val isValid: Boolean get() = username.isNotBlank() && password.isNotBlank()

    fun toAuthForm() = AuthForm(
        mode = AuthMode.PLAIN,
        saslUser = username.trim(),
        saslPassword = password,
    )
}

data class ZncLoginForm(
    val username: String = "",
    val network: String = "",
    val password: String = "",
) {
    val isValid: Boolean
        get() = username.isNotBlank() && network.isNotBlank() && password.isNotBlank() &&
            '/' !in username && '/' !in network

    val authcid: String get() = "${username.trim()}/${network.trim()}"

    fun toAuthForm() = AuthForm(
        mode = AuthMode.PLAIN,
        saslUser = authcid,
        saslPassword = password,
    )
}

/** Decode persisted `username/network` credentials without throwing on malformed legacy data. */
fun parseZncLogin(authcid: String?, password: String?): ZncLoginForm {
    val value = authcid.orEmpty()
    val separator = value.indexOf('/')
    return if (separator >= 0) {
        ZncLoginForm(
            username = value.substring(0, separator),
            network = value.substring(separator + 1),
            password = password.orEmpty(),
        )
    } else {
        ZncLoginForm(username = value, password = password.orEmpty())
    }
}
