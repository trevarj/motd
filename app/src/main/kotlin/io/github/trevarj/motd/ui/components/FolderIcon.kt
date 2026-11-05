package io.github.trevarj.motd.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Chat
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.Computer
import androidx.compose.material.icons.outlined.Favorite
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.material.icons.outlined.Games
import androidx.compose.material.icons.outlined.Group
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.School
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material.icons.outlined.ShoppingCart
import androidx.compose.material.icons.outlined.SportsEsports
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material.icons.outlined.TravelExplore
import androidx.compose.material.icons.outlined.Work
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import io.github.trevarj.motd.data.db.FolderIconKind
import io.github.trevarj.motd.data.repo.FolderIconRef
import io.github.trevarj.motd.ui.theme.uiIcon

data class FolderIconChoice(
    val ref: FolderIconRef,
    val name: String,
    val aliases: Set<String>,
)

private data class MaterialFolderIcon(
    val key: String,
    val image: ImageVector,
    val aliases: Set<String>,
)

private val materialFolderIcons =
    listOf(
        MaterialFolderIcon("folder", Icons.Outlined.Folder, setOf("generic", "files")),
        MaterialFolderIcon("chat", Icons.AutoMirrored.Outlined.Chat, setOf("message", "dm")),
        MaterialFolderIcon("forum", Icons.Outlined.Forum, setOf("community", "channel")),
        MaterialFolderIcon("group", Icons.Outlined.Group, setOf("people", "team")),
        MaterialFolderIcon("code", Icons.Outlined.Code, setOf("development", "programming")),
        MaterialFolderIcon("terminal", Icons.Outlined.Terminal, setOf("shell", "cli")),
        MaterialFolderIcon("computer", Icons.Outlined.Computer, setOf("desktop", "tech")),
        MaterialFolderIcon("memory", Icons.Outlined.Memory, setOf("hardware", "chip")),
        MaterialFolderIcon("build", Icons.Outlined.Build, setOf("tools", "workshop")),
        MaterialFolderIcon("home", Icons.Outlined.Home, setOf("house", "personal")),
        MaterialFolderIcon("work", Icons.Outlined.Work, setOf("job", "office")),
        MaterialFolderIcon("school", Icons.Outlined.School, setOf("learn", "education")),
        MaterialFolderIcon("favorite", Icons.Outlined.Favorite, setOf("heart", "love")),
        MaterialFolderIcon("star", Icons.Outlined.Star, setOf("favorite", "important")),
        MaterialFolderIcon("lightbulb", Icons.Outlined.Lightbulb, setOf("idea", "project")),
        MaterialFolderIcon("language", Icons.Outlined.Language, setOf("web", "world")),
        MaterialFolderIcon("public", Icons.Outlined.Public, setOf("global", "earth")),
        MaterialFolderIcon("travel", Icons.Outlined.TravelExplore, setOf("explore", "trip")),
        MaterialFolderIcon("games", Icons.Outlined.Games, setOf("game", "controller")),
        MaterialFolderIcon("esports", Icons.Outlined.SportsEsports, setOf("gaming", "console")),
        MaterialFolderIcon("music", Icons.Outlined.MusicNote, setOf("audio", "song")),
        MaterialFolderIcon("palette", Icons.Outlined.Palette, setOf("art", "design")),
        MaterialFolderIcon("security", Icons.Outlined.Security, setOf("shield", "safe")),
        MaterialFolderIcon("lock", Icons.Outlined.Lock, setOf("private", "secure")),
        MaterialFolderIcon("shopping", Icons.Outlined.ShoppingCart, setOf("cart", "store")),
    )

/** Metadata only: Devicon paths remain lazy until selected. */
fun folderIconChoices(query: String = ""): List<FolderIconChoice> {
    val needle = query.trim().lowercase()

    fun matches(
        name: String,
        aliases: Set<String>,
    ) = needle.isEmpty() || name.contains(needle) || aliases.any { it.contains(needle) }
    val generic = FolderIconChoice(FolderIconRef(), "Folder", setOf("generic"))
    val material =
        materialFolderIcons.mapNotNull { icon ->
            val aliases = icon.aliases + icon.key
            FolderIconChoice(FolderIconRef(FolderIconKind.MATERIAL, icon.key), icon.key, aliases).takeIf { matches(icon.key, aliases) }
        }
    val devicons =
        allChannelMarks.mapNotNull { mark ->
            FolderIconChoice(FolderIconRef(FolderIconKind.DEVICON, mark.markName), mark.markName, mark.aliases)
                .takeIf { matches(mark.markName, mark.aliases) }
        }
    return listOfNotNull(generic.takeIf { matches(generic.name.lowercase(), generic.aliases) }) + material + devicons
}

@Composable
fun FolderIcon(
    ref: FolderIconRef,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    tint: Color = LocalContentColor.current,
) {
    val material = remember(ref) { materialFolderIcons.firstOrNull { it.key == ref.key } }
    val devicon = remember(ref) { allChannelMarks.firstOrNull { it.markName == ref.key } }
    when {
        ref.kind == FolderIconKind.DEVICON && devicon != null -> {
            Canvas(modifier = modifier) { drawChannelDevicon(devicon, tint) }
        }

        ref.kind == FolderIconKind.MATERIAL && material != null -> {
            Icon(material.image, contentDescription, modifier = modifier, tint = tint)
        }

        else -> {
            Icon(uiIcon(Icons.Outlined.Folder), contentDescription, modifier = modifier, tint = tint)
        }
    }
}
