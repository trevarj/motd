package io.github.trevarj.motd.ui.chat

import android.Manifest
import android.app.Application
import android.content.ComponentName
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import io.github.trevarj.motd.BuildConfig
import io.github.trevarj.motd.data.db.MessageEntity
import io.github.trevarj.motd.data.db.MessageKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class ExternalTranslationTest {
    private val application = ApplicationProvider.getApplicationContext<Application>()
    private val packageManager = application.packageManager

    @Test
    fun `compatible installed apps prefer process text and exclude unsupported apps`() {
        registerHandler("ru.yandex.translate", "Installed Yandex", listOf(Intent.ACTION_SEND))
        registerHandler("com.deepl.mobiletranslator", "Installed DeepL", listOf(Intent.ACTION_PROCESS_TEXT, Intent.ACTION_SEND))
        registerHandler("example.textprocessor", "Another processor", listOf(Intent.ACTION_PROCESS_TEXT))
        registerHandler("com.google.android.apps.translate", "Unsupported Google", listOf(Intent.ACTION_VIEW))

        val targets = externalTranslationTargets(packageManager)

        assertEquals(
            listOf(
                ExternalTranslationTarget("com.deepl.mobiletranslator", "Installed DeepL", Intent.ACTION_PROCESS_TEXT),
                ExternalTranslationTarget("ru.yandex.translate", "Installed Yandex", Intent.ACTION_SEND),
            ),
            targets,
        )
        val chooser = externalTranslationIntent("Bonjour", null, "Translate message")
        val process = chooser.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)!!
        assertNull(process.`package`)
        assertTrue(
            packageManager
                .queryIntentActivities(process, PackageManager.MATCH_DEFAULT_ONLY)
                .any { it.activityInfo.packageName == "example.textprocessor" },
        )
    }

    @Test
    fun `installed labels follow translator order rather than registration order`() {
        registerHandler("ru.yandex.translate", "Яндекс", listOf(Intent.ACTION_SEND))
        registerHandler("com.bnyro.translate", "Translate You", listOf(Intent.ACTION_PROCESS_TEXT))
        registerHandler("com.microsoft.translator", "Microsoft", listOf(Intent.ACTION_PROCESS_TEXT))
        registerHandler("com.deepl.mobiletranslator", "DeepL", listOf(Intent.ACTION_PROCESS_TEXT))
        registerHandler("com.google.android.apps.translate", "Google", listOf(Intent.ACTION_PROCESS_TEXT))

        assertEquals(
            listOf("Google", "DeepL", "Microsoft", "Translate You", "Яндекс"),
            externalTranslationTargets(packageManager).map { it.label },
        )
    }

    @Test
    fun `disabled private permission protected and nondefault handlers are omitted`() {
        registerHandler("com.google.android.apps.translate", "Disabled activity", listOf(Intent.ACTION_PROCESS_TEXT), enabled = false)
        registerHandler("com.deepl.mobiletranslator", "Private activity", listOf(Intent.ACTION_PROCESS_TEXT), exported = false)
        registerHandler("com.microsoft.translator", "Disabled app", listOf(Intent.ACTION_SEND), applicationEnabled = false)
        registerHandler("com.bnyro.translate", "Protected activity", listOf(Intent.ACTION_PROCESS_TEXT), permission = "example.permission.TRANSLATE")
        registerHandler("ru.yandex.translate", "Not a default handler", listOf(Intent.ACTION_SEND), defaultCategory = false)

        assertTrue(externalTranslationTargets(packageManager).isEmpty())
        assertFalse(externalTranslationAvailable(packageManager, null))
    }

    @Test
    fun `other requires a usable process handler rather than an installed share target`() {
        registerHandler("ru.yandex.translate", "Yandex", listOf(Intent.ACTION_SEND))
        registerHandler("example.private", "Private processor", listOf(Intent.ACTION_PROCESS_TEXT), exported = false)
        registerHandler("example.disabled", "Disabled processor", listOf(Intent.ACTION_PROCESS_TEXT), applicationEnabled = false)
        registerHandler("example.protected", "Protected processor", listOf(Intent.ACTION_PROCESS_TEXT), permission = "example.permission.TRANSLATE")
        registerHandler("example.nondefault", "Nondefault processor", listOf(Intent.ACTION_PROCESS_TEXT), defaultCategory = false)
        assertFalse(externalTranslationAvailable(packageManager, null))
        assertFalse(
            externalTranslationAvailable(
                packageManager,
                ExternalTranslationTarget("ru.yandex.translate", "Yandex", Intent.ACTION_PROCESS_TEXT),
            ),
        )
        assertTrue(
            externalTranslationAvailable(
                packageManager,
                ExternalTranslationTarget("ru.yandex.translate", "Yandex", Intent.ACTION_SEND),
            ),
        )
        registerHandler("example.processor", "Another processor", listOf(Intent.ACTION_PROCESS_TEXT))
        assertTrue(externalTranslationAvailable(packageManager, null))
    }

    @Test
    fun `usable send replaces unusable process but multiple process activities remain one package target`() {
        registerHandler("com.google.android.apps.translate", "Google", listOf(Intent.ACTION_PROCESS_TEXT), activityName = "PrivateProcess", exported = false)
        registerHandler("com.google.android.apps.translate", "Google", listOf(Intent.ACTION_SEND), activityName = "Share")
        registerHandler("com.deepl.mobiletranslator", "DeepL", listOf(Intent.ACTION_PROCESS_TEXT), activityName = "FirstProcess")
        registerHandler("com.deepl.mobiletranslator", "DeepL", listOf(Intent.ACTION_PROCESS_TEXT, Intent.ACTION_SEND), activityName = "SecondProcess")

        val targets = externalTranslationTargets(packageManager)
        assertEquals(
            listOf(
                ExternalTranslationTarget("com.google.android.apps.translate", "Google", Intent.ACTION_SEND),
                ExternalTranslationTarget("com.deepl.mobiletranslator", "DeepL", Intent.ACTION_PROCESS_TEXT),
            ),
            targets,
        )
        val intent = externalTranslationIntent("Bonjour", targets.last(), "Translate message")
        assertNull(intent.component)
        assertEquals("com.deepl.mobiletranslator", intent.`package`)
    }

    @Test
    fun `activity permission already held by motd is usable`() {
        shadowOf(application).grantPermissions(Manifest.permission.INTERNET)
        assertEquals(PackageManager.PERMISSION_GRANTED, packageManager.checkPermission(Manifest.permission.INTERNET, BuildConfig.APPLICATION_ID))
        registerHandler("com.microsoft.translator", "Microsoft", listOf(Intent.ACTION_PROCESS_TEXT), permission = Manifest.permission.INTERNET)

        assertEquals(
            listOf(ExternalTranslationTarget("com.microsoft.translator", "Microsoft", Intent.ACTION_PROCESS_TEXT)),
            externalTranslationTargets(packageManager),
        )
    }

    @Test
    fun `dedicated process sends only normalized message projection and requests a result`() {
        val message =
            message("\u0002Bonjour\u0002 👋\nSalut").copy(
                sender = "Private sender",
                replyToMsgid = "Private reply",
                channelContext = "#private",
                eventPayload = "Private event payload",
                ircFormattedText = "Do not send the protocol payload",
            )
        val target = ExternalTranslationTarget("com.deepl.mobiletranslator", "DeepL", Intent.ACTION_PROCESS_TEXT)
        val intent = externalTranslationIntent(externalTranslationText(message)!!, target, "Translate message")

        assertProcessIntent(intent, "Bonjour 👋\nSalut")
        assertEquals(target.packageName, intent.`package`)
    }

    @Test
    fun `other opens an untargeted text processing chooser with result semantics`() {
        val chooser = externalTranslationIntent(" Bonjour 👋\nSalut ", null, "Translate message")

        assertEquals(Intent.ACTION_CHOOSER, chooser.action)
        assertEquals("Translate message", chooser.getCharSequenceExtra(Intent.EXTRA_TITLE))
        assertNull(chooser.`package`)
        assertEquals(0, chooser.flags and Intent.FLAG_ACTIVITY_NEW_TASK)
        val process = chooser.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)!!
        assertProcessIntent(process, " Bonjour 👋\nSalut ")
        assertNull(process.`package`)
    }

    @Test
    fun `share only handoff is dedicated and makes no process return promise`() {
        val target = ExternalTranslationTarget("ru.yandex.translate", "Yandex", Intent.ACTION_SEND)
        val intent = externalTranslationIntent("Bonjour 👋\nSalut", target, "Translate message")

        assertEquals(Intent.ACTION_SEND, intent.action)
        assertEquals("text/plain", intent.type)
        assertEquals(target.packageName, intent.`package`)
        assertNull(intent.component)
        assertEquals("Bonjour 👋\nSalut", intent.getStringExtra(Intent.EXTRA_TEXT))
        assertEquals(setOf(Intent.EXTRA_TEXT), intent.extras!!.keySet())
        assertFalse(intent.hasExtra(Intent.EXTRA_PROCESS_TEXT))
        assertEquals(0, intent.flags and Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    @Test
    fun `blank redacted and canonical hidden voice fallback are ineligible`() {
        assertNull(externalTranslationText(message(" \n\t")))
        assertNull(externalTranslationText(message("\u0002 \u0002\n")))
        assertNull(externalTranslationText(message("Bonjour", MessageKind.REDACTED)))
        assertNull(externalTranslationText(message("[voice 0:12 audio/ogg] https://example.org/voice.ogg")))
        assertNull(externalTranslationText(message("[voice encrypted 0:12 audio/ogg expires=2026-12-01T00:00:00Z] https://example.org/encrypted")))
        assertEquals("A visible caption [voice 0:12 audio/ogg] https://example.org/voice.ogg", externalTranslationText(message("A visible caption [voice 0:12 audio/ogg] https://example.org/voice.ogg")))
    }

    @Test
    fun `normalization preserves unicode whitespace visible malformed colors and special kinds`() {
        assertEquals("Bonjour 👋\nSalut", externalTranslationText(message("\u0002Bonjour\u0002 👋\nSalut")))
        assertEquals(" \t日本語 👋\nالعربية ", externalTranslationText(message(" \t日本語 👋\nالعربية ")))
        assertEquals(",x", externalTranslationText(message("\u0003,x")))
        assertEquals("1", externalTranslationText(message("\u00041")))
        assertEquals(",12x", externalTranslationText(message("\u0004abcdef,12x")))
        for (kind in listOf(MessageKind.SERVER_INFO, MessageKind.INVITE, MessageKind.NETSPLIT, MessageKind.NETJOIN, MessageKind.DCC_TRANSFER)) {
            assertEquals("Bonjour", externalTranslationText(message("Bonjour", kind)))
        }
    }

    private fun assertProcessIntent(
        intent: Intent,
        text: String,
    ) {
        assertEquals(Intent.ACTION_PROCESS_TEXT, intent.action)
        assertEquals("text/plain", intent.type)
        assertEquals(text, intent.getStringExtra(Intent.EXTRA_PROCESS_TEXT))
        assertTrue(intent.hasExtra(Intent.EXTRA_PROCESS_TEXT_READONLY))
        assertFalse(intent.getBooleanExtra(Intent.EXTRA_PROCESS_TEXT_READONLY, true))
        assertEquals(setOf(Intent.EXTRA_PROCESS_TEXT, Intent.EXTRA_PROCESS_TEXT_READONLY), intent.extras!!.keySet())
        assertNull(intent.component)
        assertNull(intent.clipData)
        assertEquals(0, intent.flags and Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    private fun registerHandler(
        packageName: String,
        label: String,
        actions: List<String>,
        activityName: String = "TranslationActivity",
        enabled: Boolean = true,
        exported: Boolean = true,
        applicationEnabled: Boolean = true,
        permission: String? = null,
        defaultCategory: Boolean = true,
    ) {
        val shadow = shadowOf(packageManager)
        val component = ComponentName(packageName, "$packageName.$activityName")
        shadow.addActivityIfNotPresent(component)
        val appInfo =
            shadow.getInternalMutablePackageInfo(packageName).applicationInfo!!.apply {
                nonLocalizedLabel = label
                this.enabled = applicationEnabled
            }
        shadow.addOrUpdateActivity(
            ActivityInfo().apply {
                this.packageName = packageName
                name = component.className
                applicationInfo = appInfo
                this.enabled = enabled
                this.exported = exported
                this.permission = permission
            },
        )
        for (action in actions) {
            shadow.addIntentFilterForActivity(
                component,
                IntentFilter(action).apply {
                    if (defaultCategory) addCategory(Intent.CATEGORY_DEFAULT)
                    addDataType("text/plain")
                },
            )
        }
    }

    private fun message(
        text: String,
        kind: MessageKind = MessageKind.PRIVMSG,
    ) = MessageEntity(
        id = 88L,
        bufferId = 1L,
        serverTime = 123L,
        sender = "sender",
        kind = kind,
        text = text,
        dedupKey = "88",
    )
}
