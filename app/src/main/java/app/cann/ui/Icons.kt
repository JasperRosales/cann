package app.cann.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.SubcomposeAsyncImage
import app.cann.data.Step

/** Built-in glyphs a node can use instead of its app logo. Keys are stored as "mi:<key>". */
val Glyphs: Map<String, ImageVector> = linkedMapOf(
    "bolt" to Icons.Outlined.Bolt, "mail" to Icons.Outlined.Email, "chat" to Icons.Outlined.ChatBubbleOutline,
    "send" to Icons.AutoMirrored.Outlined.Send, "calendar" to Icons.Outlined.CalendarMonth, "doc" to Icons.Outlined.Description,
    "table" to Icons.Outlined.TableChart, "folder" to Icons.Outlined.Folder, "cloud" to Icons.Outlined.Cloud,
    "code" to Icons.Outlined.Code, "db" to Icons.Outlined.Storage, "web" to Icons.Outlined.Language,
    "search" to Icons.Outlined.Search, "bell" to Icons.Outlined.Notifications, "user" to Icons.Outlined.Person,
    "task" to Icons.Outlined.TaskAlt, "money" to Icons.Outlined.Payments, "cart" to Icons.Outlined.ShoppingCart,
    "image" to Icons.Outlined.Image, "music" to Icons.Outlined.MusicNote, "video" to Icons.Outlined.Videocam,
    "ai" to Icons.Outlined.AutoAwesome, "filter" to Icons.Outlined.FilterAlt, "link" to Icons.Outlined.Link,
)

fun logoUrl(app: String) = "https://logos.composio.dev/api/${app.lowercase()}"

/** Brand glyph from the Simple Icons package (served by its CDN), in brand colour. */
fun simpleIconUrl(app: String) = "https://cdn.simpleicons.org/${app.lowercase().filter { it.isLetterOrDigit() }}"

/**
 * Rounded n8n-style icon tile. [icon] is a URL, "mi:<glyph>", or null.
 * Image sources are tried in order: [icon], [fallbackUrl], Simple Icons for [slug], then a letter.
 */
@Composable
fun IconTile(
    icon: String?, label: String, size: Dp = 44.dp, fallbackUrl: String? = null,
    tint: Color = P.accent, bg: Color = P.surfaceHi, slug: String? = null,
) {
    val shape = RoundedCornerShape(size * 0.28f)
    val glyph = icon?.removePrefix("mi:")?.takeIf { icon.startsWith("mi:") }?.let(Glyphs::get)
    val urls = remember(icon, fallbackUrl, slug) {
        listOfNotNull(icon?.takeUnless { it.startsWith("mi:") }, fallbackUrl, slug?.let(::simpleIconUrl)).distinct()
    }
    var at by remember(urls) { mutableIntStateOf(0) }
    val url = urls.getOrNull(at)
    // Logos sit on a light plate so dark marks (GitHub, Notion, X) stay visible.
    val plate = if (glyph == null && url != null) LogoPlate else bg
    Box(
        Modifier.size(size).clip(shape).background(plate).border(1.dp, P.stroke, shape),
        contentAlignment = Alignment.Center,
    ) {
        when {
            glyph != null -> Icon(glyph, null, Modifier.size(size * 0.55f), tint = tint)
            url != null -> SubcomposeAsyncImage(
                model = url, contentDescription = null, modifier = Modifier.padding(size * 0.2f).size(size),
                onError = { at++ }, loading = { Letter(label, size, P.muted) },
            )
            else -> Letter(label, size, tint)
        }
    }
}

private val LogoPlate = Color(0xFFF3F3F6)

@Composable
private fun Letter(label: String, size: Dp, tint: Color) = Box(contentAlignment = Alignment.Center) {
    Text(label.firstOrNull { it.isLetterOrDigit() }?.uppercase() ?: "?", color = tint,
        fontWeight = FontWeight.Bold, fontSize = (size.value * 0.42f).sp)
}

@Composable
fun StepIcon(s: Step, size: Dp = 44.dp) =
    IconTile(s.icon, s.app, size, fallbackUrl = s.logo ?: logoUrl(s.app), slug = s.app)
