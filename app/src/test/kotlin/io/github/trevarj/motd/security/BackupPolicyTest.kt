package io.github.trevarj.motd.security

import android.content.Context
import android.content.pm.ApplicationInfo
import androidx.annotation.XmlRes
import androidx.test.core.app.ApplicationProvider
import io.github.trevarj.motd.R
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.xmlpull.v1.XmlPullParser

/** Guards the credential-backup policy across legacy backup and Android 12+ extraction paths. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class BackupPolicyTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun manifestDisablesBackup() {
        val info = context.applicationInfo

        assertFalse(info.flags and ApplicationInfo.FLAG_ALLOW_BACKUP != 0)
    }

    @Test
    fun legacyPolicyExcludesRoomAndDataStoreFromEveryStorageContext() {
        val exclusions = exclusionsIn(R.xml.backup_rules)

        assertSensitiveStoresExcluded(exclusions, "full-backup-content")
    }

    @Test
    fun android12PolicyExcludesRoomAndDataStoreFromCloudAndDeviceTransfer() {
        val exclusions = exclusionsIn(R.xml.data_extraction_rules)

        assertSensitiveStoresExcluded(exclusions, "cloud-backup")
        assertSensitiveStoresExcluded(exclusions, "device-transfer")
    }

    private fun assertSensitiveStoresExcluded(exclusions: Set<Exclusion>, section: String) {
        setOf(
            Exclusion(section, "database", "."),
            Exclusion(section, "file", "datastore/"),
            Exclusion(section, "device_database", "."),
            Exclusion(section, "device_file", "datastore/"),
        ).forEach { expected -> assertTrue("Missing backup exclusion: $expected", expected in exclusions) }
    }

    private fun exclusionsIn(@XmlRes resource: Int): Set<Exclusion> {
        val parser = context.resources.getXml(resource)
        val exclusions = mutableSetOf<Exclusion>()
        var section = ""
        try {
            while (parser.eventType != XmlPullParser.END_DOCUMENT) {
                if (parser.eventType == XmlPullParser.START_TAG) {
                    when (parser.name) {
                        "full-backup-content", "cloud-backup", "device-transfer" -> section = parser.name
                        "exclude" -> exclusions += Exclusion(
                            section = section,
                            domain = parser.getAttributeValue(null, "domain"),
                            path = parser.getAttributeValue(null, "path"),
                        )
                    }
                }
                parser.next()
            }
        } finally {
            parser.close()
        }
        return exclusions
    }

    private data class Exclusion(val section: String, val domain: String, val path: String)
}
