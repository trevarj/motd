package io.github.trevarj.motd.ui.theme

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.ImageVector
import io.github.trevarj.motd.data.prefs.UiIconSet

/** Native Icon boundary: resolve on every composition, never inside remembered action metadata. */
@Composable
fun UiIcon(
    imageVector: ImageVector,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    tint: Color = LocalContentColor.current,
) {
    Icon(uiIcon(imageVector), contentDescription, modifier, tint)
}

// Painter/bitmap artwork (including brands) keeps its explicit identity and native semantics.
@Composable
fun UiIcon(
    painter: Painter,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    tint: Color = LocalContentColor.current,
) {
    Icon(painter, contentDescription, modifier, tint)
}

@Composable
fun UiIcon(
    bitmap: ImageBitmap,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    tint: Color = LocalContentColor.current,
) {
    Icon(bitmap, contentDescription, modifier, tint)
}

/** Keep the native Material dropdown's exact default artwork; switch only its glyph for Lucide. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UiDropdownTrailingIcon(
    expanded: Boolean,
    modifier: Modifier = Modifier,
) {
    if (LocalUiIconSet.current == UiIconSet.MATERIAL) {
        ExposedDropdownMenuDefaults.TrailingIcon(expanded, modifier)
    } else {
        UiIcon(Icons.Filled.KeyboardArrowDown, null, modifier.rotate(if (expanded) 180f else 0f))
    }
}

/** Ordinary UI artwork only; identity/brand artwork and saved Material folder choices are explicit. */
@Composable
fun uiIcon(material: ImageVector): ImageVector = resolveUiIcon(material, LocalUiIconSet.current)

/** Preserve the exact Filled/Outlined/AutoMirrored Material vector when Material is selected. */
internal fun resolveUiIcon(
    material: ImageVector,
    iconSet: UiIconSet,
): ImageVector {
    if (iconSet == UiIconSet.MATERIAL) return material
    val icon =
        when (material.name) {
            "Outlined.Archive",
            -> LucideIcon.ARCHIVE

            "Outlined.Unarchive",
            -> LucideIcon.ARCHIVE_RESTORE

            "Filled.ArrowDownward",
            "Outlined.ArrowDownward",
            -> LucideIcon.ARROW_DOWN

            "Outlined.ImportExport",
            -> LucideIcon.ARROW_DOWN_UP

            "AutoMirrored.Filled.ArrowBack",
            "AutoMirrored.Outlined.ArrowBack",
            -> LucideIcon.ARROW_LEFT

            "Filled.ArrowUpward",
            "Outlined.ArrowUpward",
            -> LucideIcon.ARROW_UP

            "Outlined.AlternateEmail",
            -> LucideIcon.AT_SIGN

            "Filled.Block",
            "Outlined.Block",
            -> LucideIcon.BAN

            "Filled.FormatColorText",
            -> LucideIcon.BASELINE

            "Outlined.Notifications",
            -> LucideIcon.BELL

            "Filled.NotificationsOff",
            "Outlined.NotificationsOff",
            -> LucideIcon.BELL_OFF

            "Outlined.NotificationsActive",
            -> LucideIcon.BELL_RING

            "Filled.FormatBold",
            -> LucideIcon.BOLD

            "Outlined.BusinessCenter",
            -> LucideIcon.BRIEFCASE_BUSINESS

            "Outlined.PhotoCamera",
            -> LucideIcon.CAMERA

            "Filled.Check",
            "Filled.Done",
            -> LucideIcon.CHECK

            "Outlined.DoneAll",
            -> LucideIcon.CHECK_CHECK

            "Filled.ExpandMore",
            "Filled.KeyboardArrowDown",
            -> LucideIcon.CHEVRON_DOWN

            "AutoMirrored.Filled.ArrowForwardIos",
            "Outlined.ChevronRight",
            -> LucideIcon.CHEVRON_RIGHT

            "Filled.ExpandLess",
            "Filled.KeyboardArrowUp",
            -> LucideIcon.CHEVRON_UP

            "AutoMirrored.Outlined.LastPage",
            -> LucideIcon.CHEVRONS_RIGHT

            "Filled.PlayCircle",
            -> LucideIcon.CIRCLE_PLAY

            "Outlined.AddCircleOutline",
            -> LucideIcon.CIRCLE_PLUS

            "Outlined.QuestionMark",
            -> LucideIcon.CIRCLE_QUESTION_MARK

            "Filled.Schedule",
            -> LucideIcon.CLOCK

            "Outlined.Cloud",
            -> LucideIcon.CLOUD

            "Outlined.CloudOff",
            -> LucideIcon.CLOUD_OFF

            "Outlined.CloudUpload",
            -> LucideIcon.CLOUD_UPLOAD

            "Filled.Code",
            -> LucideIcon.CODE

            "Filled.ContentCopy",
            "Outlined.ContentCopy",
            -> LucideIcon.COPY

            "Filled.Download",
            "Outlined.Download",
            -> LucideIcon.DOWNLOAD

            "Outlined.MoreHoriz",
            -> LucideIcon.ELLIPSIS

            "Filled.MoreVert",
            -> LucideIcon.ELLIPSIS_VERTICAL

            "AutoMirrored.Outlined.OpenInNew",
            -> LucideIcon.EXTERNAL_LINK

            "Filled.Visibility",
            "Outlined.Visibility",
            -> LucideIcon.EYE

            "Filled.VisibilityOff",
            "Outlined.VisibilityOff",
            -> LucideIcon.EYE_OFF

            "AutoMirrored.Outlined.InsertDriveFile",
            -> LucideIcon.FILE

            "AutoMirrored.Filled.TextSnippet",
            "Outlined.Description",
            -> LucideIcon.FILE_TEXT

            "Filled.UploadFile",
            -> LucideIcon.FILE_UP

            "Outlined.Science",
            -> LucideIcon.FLASK_CONICAL

            "Outlined.Folder",
            -> LucideIcon.FOLDER

            "Outlined.FolderOpen",
            -> LucideIcon.FOLDER_OPEN

            "Outlined.CreateNewFolder",
            -> LucideIcon.FOLDER_PLUS

            "Outlined.FolderOff",
            -> LucideIcon.FOLDER_X

            "Filled.Gavel",
            -> LucideIcon.GAVEL

            "Outlined.Public",
            -> LucideIcon.GLOBE

            "Outlined.Apps",
            -> LucideIcon.GRID_2X2

            "Filled.DragHandle",
            -> LucideIcon.GRIP_HORIZONTAL

            "Outlined.History",
            -> LucideIcon.HISTORY

            "Outlined.Image",
            -> LucideIcon.IMAGE

            "AutoMirrored.Outlined.FormatIndentDecrease",
            -> LucideIcon.INDENT_DECREASE

            "AutoMirrored.Outlined.FormatIndentIncrease",
            -> LucideIcon.INDENT_INCREASE

            "Outlined.Info",
            -> LucideIcon.INFO

            "Filled.FormatItalic",
            -> LucideIcon.ITALIC

            "Outlined.Translate",
            -> LucideIcon.LANGUAGES

            "Outlined.InsertLink",
            "Outlined.Link",
            -> LucideIcon.LINK

            "Outlined.Lock",
            -> LucideIcon.LOCK_KEYHOLE

            "AutoMirrored.Filled.Login",
            -> LucideIcon.LOG_IN

            "AutoMirrored.Outlined.Logout",
            -> LucideIcon.LOG_OUT

            "Outlined.Mail",
            -> LucideIcon.MAIL

            "Filled.Fullscreen",
            -> LucideIcon.MAXIMIZE

            "Filled.Menu",
            -> LucideIcon.MENU

            "AutoMirrored.Outlined.Message",
            "Outlined.ChatBubbleOutline",
            -> LucideIcon.MESSAGE_SQUARE

            "Outlined.MarkChatRead",
            -> LucideIcon.MESSAGE_SQUARE_CHECK

            "Outlined.MarkChatUnread",
            -> LucideIcon.MESSAGE_SQUARE_DOT

            "Outlined.Forum",
            -> LucideIcon.MESSAGES_SQUARE

            "Filled.Mic",
            "Outlined.Mic",
            -> LucideIcon.MIC

            "Filled.FullscreenExit",
            -> LucideIcon.MINIMIZE

            "Outlined.Remove",
            -> LucideIcon.MINUS

            "Outlined.Bedtime",
            "Outlined.DarkMode",
            -> LucideIcon.MOON

            "Outlined.Lan",
            -> LucideIcon.NETWORK

            "Outlined.ColorLens",
            "Outlined.Palette",
            -> LucideIcon.PALETTE

            "Outlined.DynamicFeed",
            "Outlined.ViewAgenda",
            -> LucideIcon.PANELS_TOP_LEFT

            "Outlined.AttachFile",
            -> LucideIcon.PAPERCLIP

            "Filled.Pause",
            -> LucideIcon.PAUSE

            "Outlined.Edit",
            -> LucideIcon.PENCIL

            "Filled.PushPin",
            "Outlined.PushPin",
            -> LucideIcon.PIN

            "Filled.PlayArrow",
            -> LucideIcon.PLAY

            "Filled.Add",
            "Outlined.Add",
            -> LucideIcon.PLUS

            "Outlined.PowerSettingsNew",
            -> LucideIcon.POWER

            "Filled.QrCode2",
            -> LucideIcon.QR_CODE

            "Filled.FormatQuote",
            -> LucideIcon.QUOTE

            "Outlined.Sync",
            -> LucideIcon.REFRESH_CW

            "Filled.FormatClear",
            -> LucideIcon.REMOVE_FORMATTING

            "AutoMirrored.Filled.Reply",
            -> LucideIcon.REPLY

            "Filled.Refresh",
            "Outlined.Refresh",
            -> LucideIcon.ROTATE_CW

            "Filled.QrCodeScanner",
            -> LucideIcon.SCAN_LINE

            "Outlined.Search",
            -> LucideIcon.SEARCH

            "Outlined.SearchOff",
            -> LucideIcon.SEARCH_X

            "AutoMirrored.Filled.Send",
            "AutoMirrored.Outlined.Send",
            -> LucideIcon.SEND

            "Outlined.Dns",
            -> LucideIcon.SERVER

            "Outlined.Settings",
            -> LucideIcon.SETTINGS

            "Filled.Share",
            -> LucideIcon.SHARE_2

            "Filled.Shield",
            -> LucideIcon.SHIELD

            "Outlined.Security",
            -> LucideIcon.SHIELD_CHECK

            "Filled.SkipPrevious",
            -> LucideIcon.SKIP_BACK

            "Filled.SkipNext",
            -> LucideIcon.SKIP_FORWARD

            "Outlined.Tune",
            -> LucideIcon.SLIDERS_HORIZONTAL

            "Outlined.Mood",
            "Outlined.SentimentSatisfiedAlt",
            -> LucideIcon.SMILE

            "Outlined.AutoAwesome",
            -> LucideIcon.SPARKLES

            "Filled.RecordVoiceOver",
            "Outlined.RecordVoiceOver",
            -> LucideIcon.SPEECH

            "Outlined.Spellcheck",
            -> LucideIcon.SPELL_CHECK

            "Filled.Stop",
            -> LucideIcon.SQUARE

            "Filled.Star",
            "Outlined.Star",
            "Outlined.StarBorder",
            -> LucideIcon.STAR

            "Outlined.LightMode",
            -> LucideIcon.SUN

            "Outlined.Style",
            -> LucideIcon.SWATCH_BOOK

            "Outlined.Terminal",
            -> LucideIcon.TERMINAL

            "Filled.Delete",
            "Filled.DeleteOutline",
            "Outlined.Delete",
            -> LucideIcon.TRASH_2

            "Outlined.Warning",
            -> LucideIcon.TRIANGLE_ALERT

            "Outlined.TextFields",
            -> LucideIcon.TYPE

            "Filled.FormatUnderlined",
            -> LucideIcon.UNDERLINE

            "Outlined.Person",
            "Outlined.PersonOutline",
            -> LucideIcon.USER_ROUND

            "Outlined.People",
            "Outlined.PeopleOutline",
            -> LucideIcon.USERS

            "Outlined.GroupAdd",
            -> LucideIcon.USERS_ROUND

            "Filled.AutoFixHigh",
            "Outlined.AutoFixHigh",
            -> LucideIcon.WAND_SPARKLES

            "Filled.Clear",
            "Filled.Close",
            "Outlined.Close",
            -> LucideIcon.X

            "Filled.FlashOn",
            "Outlined.Bolt",
            -> LucideIcon.ZAP

            "Filled.FlashOff",
            -> LucideIcon.ZAP_OFF

            else -> error("Unmapped ordinary UI icon: ${material.name}")
        }
    return icon.vector(autoMirror = material.autoMirror)
}
