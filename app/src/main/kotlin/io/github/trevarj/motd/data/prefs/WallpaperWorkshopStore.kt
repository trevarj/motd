package io.github.trevarj.motd.data.prefs

import android.content.Context
import android.net.Uri
import androidx.datastore.preferences.core.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

internal class WallpaperConflictException : IllegalStateException("Wallpaper changed since it was opened")

internal class WallpaperTargetUnavailableException : IllegalStateException("Conversation is no longer available")

internal data class WallpaperSaveResult(
    val design: SavedWallpaperDesign,
    /** Null for Save/global Apply; false means the design was saved but the chat write failed. */
    val chatApplied: Boolean?,
)

/** Appearance owns the global selection and library. Room mutations enter through callbacks only. */
@Singleton
class WallpaperWorkshopStore
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val appearancePrefs: AppearancePrefs,
        private val customWallpaperStore: CustomWallpaperStore,
    ) {
        private val store = context.appearanceDataStore
        private val mutation = Mutex()
        private val validatedBuiltins = HashSet<String>()

        internal suspend fun librarySnapshot(): WallpaperLibrarySnapshot = store.data.first().wallpaperLibrarySnapshot()

        suspend fun setWallpaper(selection: WallpaperSelection) {
            mutation.withLock {
                val previous = appearancePrefs.config.first().wallpaper
                // A queued slider callback must not resurrect a photo that has just been replaced.
                if (selection.localImageName != null && selection.localImageName != previous.localImageName) return
                withContext(NonCancellable) {
                    appearancePrefs.setWallpaper(selection)
                    previous.localImageName?.takeIf { it != selection.localImageName }?.let { customWallpaperStore.delete(it) }
                }
            }
        }

        suspend fun importWallpaper(uri: Uri): WallpaperSelection =
            mutation.withLock {
                val previous = appearancePrefs.config.first().wallpaper
                val name = customWallpaperStore.import(uri).getOrThrow()
                // Once copied, commit or reclaim the file even if the picker closes.
                withContext(NonCancellable) {
                    val selection = previous.copy(localImageName = name)
                    var committed = false
                    try {
                        appearancePrefs.setWallpaper(selection)
                        committed = true
                        previous.localImageName?.let { customWallpaperStore.delete(it) }
                        selection
                    } finally {
                        if (!committed) customWallpaperStore.delete(name)
                    }
                }
            }

        /** Callbacks may write Room, but must never call this store from a Room transaction. */
        internal suspend fun saveDesign(
            design: SavedWallpaperDesign,
            expected: SavedWallpaperDesign?,
            imports: List<ImportedWallpaperIcon>,
            applyGlobally: Boolean = false,
            checkChatTarget: (suspend () -> Boolean)? = null,
            applyToChat: (suspend (WallpaperChatChoice) -> Boolean)? = null,
        ): WallpaperSaveResult {
            require(!applyGlobally || applyToChat == null)
            val saved = design.copy(name = design.name.trim())
            val ref = WallpaperPatternRef(WallpaperPatternRefKind.SAVED, saved.id)
            return mutation.withLock {
                if (checkChatTarget?.invoke() == false) throw WallpaperTargetUnavailableException()
                withContext(NonCancellable) {
                    var previousImage: String? = null
                    store.edit { prefs ->
                        val library = prefs.wallpaperLibrarySnapshot().requireLibrary()
                        library.checkDesign(saved.id, expected)
                        val referenced = saved.pattern.icons.mapTo(HashSet(), WallpaperPatternIcon::assetId)
                        val byId = library.imports.associateByTo(LinkedHashMap(), ImportedWallpaperIcon::id)
                        for (icon in imports) {
                            if (icon.id !in referenced) continue
                            val existing = byId.putIfAbsent(icon.id, icon)
                            if (existing != null && existing.svg != icon.svg) throw WallpaperLibraryException(WallpaperLibraryReadError.INVALID)
                        }
                        val target =
                            library
                                .copy(
                                    designs = listOf(saved) + library.designs.filterNot { it.id == saved.id },
                                    imports = byId.values.toList(),
                                ).withReferencedImports()
                        prefs[WALLPAPER_LIBRARY] = encodeForWrite(target)
                        validateBuiltins(saved.pattern)
                        if (applyGlobally) {
                            val previous = prefs.storedWallpaper()
                            previousImage = previous.localImageName
                            prefs.writeWallpaper(previous.forGlobalApply(ref))
                        }
                    }
                    previousImage?.let { customWallpaperStore.delete(it) }
                    val applied =
                        applyToChat?.let { write ->
                            try {
                                write(WallpaperChatChoice(WallpaperChatChoiceKind.PATTERN, ref))
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (_: Exception) {
                                false
                            }
                        }
                    WallpaperSaveResult(saved, applied)
                }
            }
        }

        internal suspend fun applyGlobal(
            ref: WallpaperPatternRef,
            expected: SavedWallpaperDesign? = null,
        ) {
            mutation.withLock {
                withContext(NonCancellable) {
                    var previousImage: String? = null
                    store.edit { prefs ->
                        val library = prefs.wallpaperLibrarySnapshot().requireLibrary()
                        validateReference(ref, library, expected)
                        val previous = prefs.storedWallpaper()
                        previousImage = previous.localImageName
                        prefs.writeWallpaper(previous.forGlobalApply(ref))
                    }
                    previousImage?.let { customWallpaperStore.delete(it) }
                }
            }
        }

        internal suspend fun applyToChat(
            choice: WallpaperChatChoice?,
            expected: SavedWallpaperDesign? = null,
            write: suspend (WallpaperChatChoice?) -> Boolean,
        ): Boolean =
            mutation.withLock {
                val library = librarySnapshot().requireLibrary()
                choice?.pattern?.let { validateReference(it, library, expected) }
                write(choice)
            }

        internal suspend fun deleteDesign(
            expected: SavedWallpaperDesign,
            clearChatReferences: suspend (WallpaperPatternRef) -> Unit,
        ) {
            val ref = WallpaperPatternRef(WallpaperPatternRefKind.SAVED, expected.id)
            mutation.withLock {
                withContext(NonCancellable) {
                    store.edit { prefs ->
                        val library = prefs.wallpaperLibrarySnapshot().requireLibrary()
                        library.checkDesign(expected.id, expected)
                        val target = library.copy(designs = library.designs.filterNot { it.id == expected.id }).withReferencedImports()
                        prefs[WALLPAPER_LIBRARY] = encodeForWrite(target)
                        val previous = prefs.storedWallpaper()
                        if (previous.patternRef == ref) {
                            prefs.writeWallpaper(
                                previous.copy(
                                    // An explicit default keeps the photo's independent opacity unchanged.
                                    patternRef = previous.localImageName?.let { WallpaperPatternRef(WallpaperPatternRefKind.STARTER, "motd") },
                                    outlineColor = null,
                                    intensity = if (previous.localImageName == null) DEFAULT_WALLPAPER_INTENSITY else previous.intensity,
                                ),
                            )
                        }
                    }
                    clearChatReferences(ref)
                }
            }
        }

        /**
         * Compare the preflight library before any writes. The two callbacks run under the same
         * mutation lock, but the overall import is not atomic across DataStore and Room.
         */
        internal suspend fun restore(
            expected: WallpaperLibrarySnapshot,
            library: WallpaperLibrary?,
            selection: WallpaperSelection?,
            replace: Boolean,
            beforeCommit: suspend () -> Unit = {},
            afterCommit: suspend () -> Unit = {},
        ) {
            mutation.withLock {
                val base = librarySnapshot()
                if (base.raw != expected.raw) throw WallpaperConflictException()
                if (!replace || library == null) base.requireLibrary()
                val encoded = library?.let { encodeForWrite(it) }
                if (library != null) {
                    selection?.patternRef?.let { ref ->
                        require(selection.preset == ChatWallpaperPreset.PATTERN)
                        validateReference(ref, library, expected = null)
                    }
                    for (design in library.designs) validateBuiltins(design.pattern)
                }
                beforeCommit()
                withContext(NonCancellable) {
                    var removedImage: String? = null
                    if (encoded != null || selection != null) {
                        store.edit { prefs ->
                            if (prefs[WALLPAPER_LIBRARY] != base.raw) throw WallpaperConflictException()
                            if (encoded != null) prefs[WALLPAPER_LIBRARY] = encoded
                            if (selection != null) {
                                val currentPhoto = prefs.storedWallpaper().localImageName
                                val retainedPhoto = currentPhoto?.takeIf { library == null && CustomWallpaperStore.resolve(context, it) != null }
                                if (retainedPhoto == null) removedImage = currentPhoto
                                prefs.writeWallpaper(
                                    selection.copy(
                                        localImageName = retainedPhoto,
                                        patternRef = selection.patternRef.takeIf { library != null },
                                    ),
                                )
                            }
                        }
                    }
                    removedImage?.let { customWallpaperStore.delete(it) }
                    afterCommit()
                }
            }
        }

        private fun WallpaperLibrary.checkDesign(
            id: String,
            expected: SavedWallpaperDesign?,
        ) {
            if (designs.firstOrNull { it.id == id } != expected) throw WallpaperConflictException()
        }

        private suspend fun validateReference(
            ref: WallpaperPatternRef,
            library: WallpaperLibrary,
            expected: SavedWallpaperDesign?,
        ) {
            if (expected != null) {
                require(ref.kind == WallpaperPatternRefKind.SAVED)
                library.checkDesign(ref.id, expected)
            }
            validateBuiltins(ref.resolvePattern(library) ?: throw WallpaperConflictException())
        }

        private suspend fun validateBuiltins(pattern: WallpaperPattern) {
            if (pattern.icons.none { it.assetId.startsWith("builtin:") && it.assetId !in validatedBuiltins }) return
            withContext(Dispatchers.IO) {
                for (icon in pattern.icons) {
                    val id = icon.assetId
                    if (!id.startsWith("builtin:") || id in validatedBuiltins) continue
                    try {
                        val path = checkNotNull(WallpaperCatalog.entry(context, id)).svgPath
                        WallpaperSvg.decode(context.assets.open(path).use { it.readBytes() })
                        validatedBuiltins.add(id)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Exception) {
                        throw WallpaperLibraryException(WallpaperLibraryReadError.INVALID, error)
                    }
                }
            }
        }

        private fun WallpaperSelection.forGlobalApply(ref: WallpaperPatternRef) = copy(preset = ChatWallpaperPreset.PATTERN, localImageName = null, patternRef = ref)

        private suspend fun encodeForWrite(library: WallpaperLibrary): String = withContext(Dispatchers.Default) { encodeWallpaperLibrary(library) }
    }
