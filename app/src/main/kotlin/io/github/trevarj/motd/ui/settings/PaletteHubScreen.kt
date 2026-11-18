package io.github.trevarj.motd.ui.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.core.graphics.toColorInt
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.trevarj.motd.R
import io.github.trevarj.motd.data.prefs.*
import io.github.trevarj.motd.data.repo.catalogPaletteLink
import io.github.trevarj.motd.ui.theme.MotdDarkRaw
import io.github.trevarj.motd.ui.theme.MotdLightRaw
import io.github.trevarj.motd.ui.theme.UiIcon
import io.github.trevarj.motd.ui.theme.rawFixedThemeScheme
import io.github.trevarj.motd.ui.theme.readableTextButtonColors
import io.github.trevarj.motd.ui.theme.themeIdentityPalette
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.util.Locale
import java.util.UUID
import io.github.trevarj.motd.ui.components.EmojiOutlinedTextField as OutlinedTextField
import io.github.trevarj.motd.ui.components.EmojiText as Text

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun PaletteHubScreen(
    onBack: () -> Unit,
    onEdit: (PaletteDocument, String?, PaletteMode) -> Unit,
    onPreview: (PaletteDocument, String?, String?, String?, PaletteMode) -> Unit,
    viewModel: PaletteHubViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    val scope = rememberCoroutineScope()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var query by rememberSaveable { mutableStateOf("") }
    var deleting by remember { mutableStateOf<InstalledPalette?>(null) }
    var exporting by rememberSaveable { mutableStateOf<String?>(null) }
    var contribution by remember { mutableStateOf(false) }
    var choosingTarget by remember { mutableStateOf<PaletteDocument?>(null) }
    val selection = state.appearance.paletteSelection
    var target by rememberSaveable { mutableStateOf(PaletteMode.SYSTEM) }
    val choice = selection.choiceFor(target == PaletteMode.DARK)
    val dynamic = state.settings.dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    val deviceLight = remember(context, dynamic) { if (dynamic) dynamicLightColorScheme(context) else MotdLightRaw }
    val deviceDark = remember(context, dynamic) { if (dynamic) dynamicDarkColorScheme(context) else MotdDarkRaw }

    fun isSelected(
        candidate: PaletteChoice,
        document: PaletteDocument,
    ): Boolean =
        if (target == PaletteMode.SYSTEM) {
            (document.light == null || selection.light == candidate) && (document.dark == null || selection.dark == candidate)
        } else {
            choice == candidate
        }

    fun launchAction(action: suspend () -> Unit) {
        scope.launch {
            try {
                action()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                viewModel.reportError(failure)
            }
        }
    }

    fun edit(
        installedId: String?,
        document: () -> PaletteDocument,
    ) {
        try {
            onEdit(decodePalette(encodePalette(document())), installedId, target)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            viewModel.reportError(failure)
        }
    }

    fun openCatalog(
        document: PaletteDocument,
        installedTarget: InstalledPalette? = null,
    ) {
        val reusable = installedTarget?.takeIf { it.catalogRevision == document.revision }
        onPreview(
            reusable?.document ?: document,
            catalogPaletteLink(document.id, document.revision),
            document.id,
            installedTarget?.document?.id?.takeIf { reusable == null },
            target,
        )
    }

    fun browseCatalog(document: PaletteDocument) {
        val tracked = state.appearance.customPalettes.filter { it.catalogId == document.id }
        if (tracked.size > 1) {
            choosingTarget = document
        } else {
            openCatalog(document, tracked.singleOrNull()?.takeIf { it.catalogRevision != document.revision })
        }
    }

    fun copyLink(link: String) {
        (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("Palette link", link))
    }
    val importer =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) launchAction { onPreview(readPaletteFile(context, uri), null, null, null, target) }
        }
    val exporter =
        rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
            val snapshot = exporting
            exporting = null
            if (uri != null && snapshot != null) launchAction { writePaletteFile(context, uri, decodePalette(snapshot.toByteArray(Charsets.UTF_8))) }
        }
    Scaffold(
        modifier = Modifier.testTag("palette_hub"),
        topBar = {
            TopAppBar(title = { Text(stringResource(R.string.palette_title)) }, navigationIcon = {
                IconButton(onClick = onBack, modifier = Modifier.testTag("palette_back")) {
                    UiIcon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.action_back))
                }
            })
        },
        bottomBar = {
            Surface {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .imePadding()
                        .padding(8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Button(modifier = Modifier.weight(1f).testTag("palette_create"), onClick = {
                        edit(null) {
                            val selected = state.appearance.customPalette(choice)
                            selected?.let { localPaletteFork(it.copy(name = "New palette")) }
                                ?: builtinPaletteDocument(choice.builtin, "New palette", deviceLight, deviceDark)
                        }
                    }) { Text(stringResource(R.string.palette_create)) }
                    OutlinedButton(onClick = { importer.launch(arrayOf("application/json", "text/*", "*/*")) }, modifier = Modifier.testTag("palette_import")) { Text(stringResource(R.string.palette_import)) }
                }
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(PaletteMode.SYSTEM, PaletteMode.LIGHT, PaletteMode.DARK).forEach { mode ->
                    FilterChip(
                        selected = target == mode,
                        onClick = { target = mode },
                        label = {
                            Text(
                                stringResource(
                                    when (mode) {
                                        PaletteMode.SYSTEM -> R.string.palette_both
                                        PaletteMode.LIGHT -> R.string.palette_mode_light
                                        PaletteMode.DARK -> R.string.palette_mode_dark
                                    },
                                ),
                            )
                        },
                        modifier = Modifier.testTag("palette_target_${mode.name.lowercase(Locale.ROOT)}"),
                    )
                }
            }
            PrimaryTabRow(selectedTabIndex = tab) {
                listOf(R.string.palette_builtin, R.string.palette_yours, R.string.palette_community).forEachIndexed { index, label ->
                    Tab(selected = tab == index, onClick = {
                        if (index == 2 && tab != 2) viewModel.refreshCatalog()
                        tab = index
                    }, text = { Text(stringResource(label)) })
                }
            }
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                label = { Text(stringResource(R.string.palette_search)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(12.dp).testTag("palette_search"),
            )
            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(12.dp)) }
            if (state.appearance.customPaletteReadError != null) Text(stringResource(R.string.palette_storage_error), color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(12.dp))
            LazyColumn(Modifier.weight(1f).testTag("palette_list"), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                when (tab) {
                    0 -> {
                        items(
                            ColorThemePreset.entries
                                .map { it.canonicalPalette }
                                .distinct()
                                .filter { paletteBuiltinNameText(it).contains(query, true) && (target == PaletteMode.SYSTEM || PaletteChoice(builtin = it).supportsMode(target == PaletteMode.DARK)) },
                            key = { it.name },
                        ) { preset ->
                            val document = remember(preset, deviceLight, deviceDark) { builtinPaletteDocument(preset, paletteBuiltinNameText(preset), deviceLight, deviceDark) }
                            PaletteHubRow(
                                paletteBuiltinName(preset),
                                document,
                                isSelected(PaletteChoice(builtin = preset), document),
                                Modifier.testTag("palette_builtin_${preset.name.lowercase(Locale.ROOT)}"),
                                onSelect = { viewModel.select(selection.withChoice(PaletteChoice(builtin = preset), target)) },
                            )
                            TextButton(colors = readableTextButtonColors(), onClick = { edit(null) { localPaletteFork(document) } }) { Text(stringResource(R.string.palette_edit)) }
                        }
                    }

                    1 -> {
                        val palettes = state.appearance.customPalettes.filter { it.document.name.contains(query, true) && (target == PaletteMode.SYSTEM || PaletteChoice(installedId = it.document.id).supportsMode(target == PaletteMode.DARK, state.appearance.customPalettes)) }
                        if (palettes.isEmpty()) item { Text(stringResource(R.string.palette_empty_yours)) }
                        items(palettes, key = { it.document.id }) { installed ->
                            val document = installed.document
                            Card {
                                PaletteHubRow(document.name, document, isSelected(PaletteChoice(installedId = document.id), document), onSelect = { viewModel.select(selection.withChoice(PaletteChoice(installedId = document.id), target, state.appearance.customPalettes)) })
                                FlowRow(Modifier.padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                    TextButton(colors = readableTextButtonColors(), onClick = { edit(if (installed.catalogId != null) null else document.id) { if (installed.catalogId != null) localPaletteFork(document) else document } }) { Text(stringResource(R.string.palette_edit)) }
                                    TextButton(colors = readableTextButtonColors(), onClick = { edit(null) { duplicatePalette(document) } }) { Text(stringResource(R.string.palette_duplicate)) }
                                    TextButton(colors = readableTextButtonColors(), onClick = { launchAction { sharePaletteFile(context, document) } }, modifier = Modifier.testTag("palette_share")) { Text(stringResource(R.string.palette_share)) }
                                    val hostedLink = installed.catalogId?.let { id -> installed.catalogRevision?.let { catalogPaletteLink(id, it) } } ?: installed.sourceLink
                                    if (hostedLink != null) TextButton(colors = readableTextButtonColors(), onClick = { copyLink(hostedLink) }) { Text("Copy link") }
                                    TextButton(colors = readableTextButtonColors(), onClick = {
                                        try {
                                            exporting = encodePalette(document).toString(Charsets.UTF_8)
                                            exporter.launch("${document.id}.motd-palette.json")
                                        } catch (cancelled: CancellationException) {
                                            throw cancelled
                                        } catch (failure: Exception) {
                                            exporting = null
                                            viewModel.reportError(failure)
                                        }
                                    }) { Text(stringResource(R.string.palette_export)) }
                                    TextButton(colors = readableTextButtonColors(), onClick = { if (selection.usesInstalled(document.id)) deleting = installed else viewModel.remove(document.id) }) { Text(stringResource(R.string.palette_delete)) }
                                }
                            }
                        }
                    }

                    else -> {
                        item {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("Community refresh connects directly from this device over HTTPS to GitHub, not through an IRC network proxy.")
                                TextButton(
                                    colors = readableTextButtonColors(),
                                    onClick = { viewModel.refreshCatalog() },
                                    enabled = !state.catalog.loading,
                                    modifier = Modifier.testTag("palette_catalog_refresh"),
                                ) { Text(if (state.catalog.error != null) "Retry" else "Refresh") }
                                if (state.catalog.loading) LinearProgressIndicator(Modifier.fillMaxWidth().testTag("palette_catalog_loading"))
                                if (state.catalog.cached) Text("Showing cached catalog", Modifier.testTag("palette_catalog_cached"))
                                state.catalog.error?.let { Text(it, Modifier.testTag("palette_catalog_error"), color = MaterialTheme.colorScheme.error) }
                                if (!state.catalog.loading && state.catalog.palettes.isEmpty()) Text(stringResource(R.string.palette_empty_community), Modifier.testTag("palette_catalog_empty"))
                            }
                        }
                        val matches = state.catalog.palettes.filter { it.name.contains(query, true) && (target == PaletteMode.SYSTEM || (if (target == PaletteMode.DARK) it.dark != null else it.light != null)) }
                        if (matches.isEmpty() && state.catalog.palettes.isNotEmpty()) item { Text("No matching palettes") }
                        items(matches, key = { it.id }) { document ->
                            val tracked = state.appearance.customPalettes.filter { it.catalogId == document.id }
                            val identical = state.appearance.customPalettes.any { sameCatalogDocument(it.document, document) }
                            Card {
                                PaletteHubRow(document.name, document, false, Modifier.testTag("palette_catalog_${document.id}"), onSelect = { browseCatalog(document) })
                                if (tracked.any { (it.catalogRevision ?: 0) < document.revision }) {
                                    Text("Update available", Modifier.padding(horizontal = 12.dp))
                                } else if (tracked.isNotEmpty() || identical) {
                                    Text("Installed", Modifier.padding(horizontal = 12.dp))
                                }
                                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
                                    TextButton(colors = readableTextButtonColors(), onClick = { browseCatalog(document) }, modifier = Modifier.weight(1f)) { Text("Preview palette") }
                                    IconButton(onClick = { copyLink(catalogPaletteLink(document.id, document.revision)) }, modifier = Modifier.size(48.dp)) {
                                        UiIcon(Icons.Filled.ContentCopy, stringResource(R.string.upload_copy_link))
                                    }
                                }
                            }
                        }
                        item {
                            Text(stringResource(R.string.palette_contribution))
                            Button(onClick = { contribution = true }) { Text(stringResource(R.string.palette_contribute)) }
                        }
                    }
                }
            }
        }
    }
    choosingTarget?.let { document ->
        AlertDialog(
            onDismissRequest = { choosingTarget = null },
            modifier = Modifier.testTag("palette_catalog_targets"),
            title = { Text("Choose installed palette") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    state.appearance.customPalettes.filter { it.catalogId == document.id }.forEach { installed ->
                        TextButton(colors = readableTextButtonColors(), onClick = {
                            choosingTarget = null
                            openCatalog(document, installed)
                        }, modifier = Modifier.testTag("palette_catalog_target_${installed.document.id}")) {
                            Text("${installed.document.name} · revision ${installed.catalogRevision} · ${installed.document.id}")
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(colors = readableTextButtonColors(), onClick = { choosingTarget = null }) { Text("Cancel") } },
        )
    }
    deleting?.let { installed ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text(stringResource(R.string.palette_delete_title)) },
            text = { Text(stringResource(R.string.palette_delete_active)) },
            confirmButton = {
                TextButton(colors = readableTextButtonColors(), onClick = {
                    viewModel.remove(installed.document.id)
                    deleting = null
                }) { Text(stringResource(R.string.palette_delete)) }
            },
            dismissButton = { TextButton(colors = readableTextButtonColors(), onClick = { deleting = null }) { Text(stringResource(R.string.palette_cancel)) } },
        )
    }
    if (contribution) {
        AlertDialog(
            onDismissRequest = { contribution = false },
            title = { Text(stringResource(R.string.palette_contribute)) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text(stringResource(R.string.palette_contribution))
                    state.appearance.customPalettes.filter { it.document.light != null && it.document.dark != null }.forEach { installed ->
                        TextButton(colors = readableTextButtonColors(), onClick = {
                            try {
                                validatePalette(installed.document, requireBothModes = true)
                                exporting = encodePalette(installed.document).toString(Charsets.UTF_8)
                                exporter.launch("${installed.document.id}.motd-palette.json")
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (failure: Exception) {
                                exporting = null
                                viewModel.reportError(failure)
                            }
                        }) { Text("Export ${installed.document.name}") }
                    }
                }
            },
            confirmButton = {
                TextButton(colors = readableTextButtonColors(), onClick = {
                    contribution = false
                    uriHandler.openUri("https://github.com/trevarj/motd/tree/main/palettes")
                }) { Text("Open repository") }
            },
            dismissButton = { TextButton(colors = readableTextButtonColors(), onClick = { contribution = false }) { Text(stringResource(R.string.palette_cancel)) } },
        )
    }
}

@Composable
private fun PaletteHubRow(
    name: String,
    document: PaletteDocument,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onSelect: () -> Unit,
) {
    Column(modifier.fillMaxWidth().selectable(selected = selected, role = Role.RadioButton, onClick = onSelect).padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(name, style = MaterialTheme.typography.titleMedium)
        Text(
            if (document.light != null && document.dark != null) {
                "Light and dark"
            } else if (document.light != null) {
                "Light only"
            } else {
                "Dark only"
            },
            style = MaterialTheme.typography.bodySmall,
        )
        val colors = document.dark ?: document.light ?: return
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(colors.primary, colors.secondary, colors.tertiary, colors.background, colors.onSurface).forEach { hex ->
                Surface(color = Color(hex.toColorInt()), shape = MaterialTheme.shapes.small, border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), modifier = Modifier.size(24.dp)) {}
            }
        }
    }
}

@Composable
internal fun paletteBuiltinName(preset: ColorThemePreset): String =
    when (preset.canonicalPalette) {
        ColorThemePreset.SYSTEM -> stringResource(R.string.palette_device_colors)
        ColorThemePreset.DARK -> stringResource(R.string.palette_brand)
        else -> paletteBuiltinNameText(preset)
    }

internal fun paletteBuiltinNameText(preset: ColorThemePreset): String =
    when (val family = preset.canonicalPalette) {
        ColorThemePreset.SYSTEM -> "Device colors"
        ColorThemePreset.DARK -> "MOTD"
        ColorThemePreset.AYU_DARK -> "Ayu"
        ColorThemePreset.CATPPUCCIN_MOCHA -> "Catppuccin"
        ColorThemePreset.EVERFOREST_DARK -> "Everforest"
        ColorThemePreset.GRUVBOX_DARK -> "Gruvbox"
        ColorThemePreset.KANAGAWA_WAVE -> "Kanagawa"
        ColorThemePreset.MODUS_VIVENDI -> "Modus"
        ColorThemePreset.MODUS_VIVENDI_TINTED -> "Modus Tinted"
        ColorThemePreset.MODUS_VIVENDI_DEUTERANOPIA -> "Modus Deuteranopia"
        ColorThemePreset.MODUS_VIVENDI_TRITANOPIA -> "Modus Tritanopia"
        ColorThemePreset.SOLARIZED_DARK -> "Solarized"
        else -> themePresetLabelText(family)
    }

internal fun builtinPaletteDocument(
    preset: ColorThemePreset,
    name: String,
    deviceLight: ColorScheme = MotdLightRaw,
    deviceDark: ColorScheme = MotdDarkRaw,
): PaletteDocument {
    val family = preset.canonicalPalette

    fun mode(dark: Boolean): PaletteColors? {
        val variant = resolvePalettePreset(family, if (dark) PaletteMode.DARK else PaletteMode.LIGHT, dark)
        if (family != ColorThemePreset.SYSTEM && variant.isDark != dark) return null
        val raw =
            if (family == ColorThemePreset.SYSTEM) {
                if (dark) deviceDark else deviceLight
            } else {
                rawFixedThemeScheme(variant) ?: if (dark) MotdDarkRaw else MotdLightRaw
            }

        fun hex(color: Color) = String.format(Locale.ROOT, "#%06X", color.toArgb() and 0xFFFFFF)
        return PaletteColors(primary = hex(raw.primary), secondary = hex(raw.secondary), tertiary = hex(raw.tertiary), background = hex(raw.background), surface = hex(raw.surface), surfaceContainer = hex(raw.surfaceContainer), onBackground = hex(raw.onBackground), onSurface = hex(raw.onSurface), onSurfaceVariant = hex(raw.onSurfaceVariant), outlineVariant = hex(raw.outlineVariant), nicknameColors = themeIdentityPalette(variant).map(::hex))
    }
    return decodePalette(encodePalette(PaletteDocument(id = UUID.randomUUID().toString(), name = name, revision = 1, light = mode(false), dark = mode(true))))
}
