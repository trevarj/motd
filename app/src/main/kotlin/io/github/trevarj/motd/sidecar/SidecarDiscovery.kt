package io.github.trevarj.motd.sidecar

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.os.Build
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.trevarj.motd.sidecar.SidecarContract
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

data class SidecarProviderDescriptor(
    val component: ComponentName,
    val label: String,
    val apiVersion: Int,
    val currentSignerSha256: String,
    val signerHistorySha256: Set<String>,
)

@Singleton
class SidecarDiscovery
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) {
        @Suppress("DEPRECATION")
        fun providers(): List<SidecarProviderDescriptor> =
            context.packageManager
                .queryIntentServices(Intent(SidecarContract.PROVIDER_ACTION), PackageManager.GET_META_DATA)
                .mapNotNull { resolve ->
                    val service = resolve.serviceInfo ?: return@mapNotNull null
                    if (!service.exported) return@mapNotNull null
                    val signers = context.packageManager.signers(service.packageName) ?: return@mapNotNull null
                    val current = signers.first.firstOrNull() ?: return@mapNotNull null
                    SidecarProviderDescriptor(
                        component = ComponentName(service.packageName, service.name),
                        label = resolve.loadLabel(context.packageManager).toString(),
                        apiVersion = service.metaData?.getInt(SidecarContract.PROVIDER_API_METADATA, 0) ?: 0,
                        currentSignerSha256 = current,
                        signerHistorySha256 = signers.second,
                    )
                }.filter { it.apiVersion == SidecarContract.API_VERSION }
                .distinctBy { it.component }
                .sortedBy { it.label.lowercase() }

        fun descriptor(component: ComponentName): SidecarProviderDescriptor? = providers().firstOrNull { it.component == component }

        private fun PackageManager.signers(packageName: String): Pair<Set<String>, Set<String>>? =
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    val info = getPackageInfo(packageName, PackageManager.GET_SIGNING_CERTIFICATES).signingInfo ?: return null
                    info.apkContentsSigners.mapTo(linkedSetOf()) { it.digestSha256() } to
                        info.signingCertificateHistory.orEmpty().mapTo(linkedSetOf()) { it.digestSha256() }
                } else {
                    val digests = getPackageInfo(packageName, PackageManager.GET_SIGNATURES).signatures.orEmpty().mapTo(linkedSetOf()) { it.digestSha256() }
                    digests to digests
                }
            }.getOrNull()

        private fun Signature.digestSha256(): String =
            MessageDigest
                .getInstance("SHA-256")
                .digest(toByteArray())
                .joinToString("") { "%02x".format(it) }
    }
