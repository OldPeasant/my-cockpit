package ch.sonensei.cockpit.notes

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckBox
import androidx.compose.material.icons.outlined.CheckBoxOutlineBlank
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import ch.sonensei.cockpit.R
import coil3.ImageLoader
import coil3.compose.AsyncImage

internal object MarkdownTags {
    const val IMAGE = "markdown-image"
}

/** A note's Markdown, rendered: drive images are loaded with [imageLoader] (`null`, e.g. in tests: a placeholder). */
@Composable
fun MarkdownView(
    markdown: String,
    imageLoader: ImageLoader?,
    modifier: Modifier = Modifier,
) {
    val blocks = remember(markdown) { Markdown.parse(markdown) }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        blocks.forEach { block -> MdBlockView(block, imageLoader) }
    }
}

@Composable
private fun MdBlockView(
    block: MdBlock,
    imageLoader: ImageLoader?,
) {
    val typography = MaterialTheme.typography
    when (block) {
        is MdBlock.Heading -> {
            val style =
                when (block.level) {
                    1 -> typography.headlineSmall
                    2 -> typography.titleLarge
                    3 -> typography.titleMedium
                    else -> typography.titleSmall
                }
            Text(inline(block.text), style = style, modifier = Modifier.padding(top = 4.dp))
        }
        is MdBlock.Paragraph -> Text(inline(block.text), style = typography.bodyLarge)
        is MdBlock.ListItem ->
            Row(Modifier.padding(start = (block.depth * 16).dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                when (block.checked) {
                    null ->
                        Text(
                            if (block.marker.first().isDigit()) block.marker else "•",
                            style = typography.bodyLarge,
                            modifier = Modifier.width(24.dp),
                        )
                    else ->
                        Icon(
                            if (block.checked) Icons.Outlined.CheckBox else Icons.Outlined.CheckBoxOutlineBlank,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.width(24.dp),
                        )
                }
                val text = inline(block.text)
                Text(
                    text,
                    style =
                        if (block.checked == true) {
                            typography.bodyLarge.copy(
                                textDecoration = TextDecoration.LineThrough,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        } else {
                            typography.bodyLarge
                        },
                    modifier = Modifier.weight(1f),
                )
            }
        is MdBlock.Quote ->
            Row {
                Box(
                    Modifier
                        .width(3.dp)
                        .heightIn(min = 24.dp)
                        .background(MaterialTheme.colorScheme.outlineVariant),
                )
                Text(
                    inline(block.text),
                    style = typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurfaceVariant),
                    modifier = Modifier.padding(start = 12.dp),
                )
            }
        is MdBlock.Code ->
            Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = RoundedCornerShape(8.dp)) {
                Text(
                    block.text,
                    style = typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                    modifier = Modifier.fillMaxWidth().padding(12.dp),
                )
            }
        is MdBlock.Image -> MdImage(block, imageLoader)
        MdBlock.Rule -> HorizontalDivider()
    }
}

@Composable
private fun MdImage(
    image: MdBlock.Image,
    imageLoader: ImageLoader?,
) {
    val description = image.alt.ifBlank { stringResource(R.string.notes_image) }
    val shape = RoundedCornerShape(12.dp)
    val modifier =
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .testTag(MarkdownTags.IMAGE)
            .semantics { contentDescription = description }
    if (imageLoader == null) {
        Box(
            modifier.heightIn(min = 120.dp).background(MaterialTheme.colorScheme.surfaceContainerHigh),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Outlined.Image, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    } else {
        AsyncImage(
            model = image.source.url(),
            contentDescription = description,
            imageLoader = imageLoader,
            contentScale = ContentScale.FillWidth,
            modifier = modifier.heightIn(min = 48.dp).background(MaterialTheme.colorScheme.surfaceContainerHigh),
        )
    }
}

/** [text]'s inline Markdown as styled text. */
@Composable
private fun inline(text: String): AnnotatedString {
    val linkColor = MaterialTheme.colorScheme.primary
    val codeBackground = MaterialTheme.colorScheme.surfaceContainerHigh
    return remember(text, linkColor, codeBackground) {
        buildAnnotatedString {
            MdInline.parse(text).forEach { span ->
                withStyle(span.style(linkColor, codeBackground)) { append(span.text) }
            }
        }
    }
}

private fun MdSpan.style(
    linkColor: Color,
    codeBackground: Color,
): SpanStyle {
    val decorations = listOfNotNull(TextDecoration.LineThrough.takeIf { strike }, TextDecoration.Underline.takeIf { link != null })
    return SpanStyle(
        fontWeight = if (bold) FontWeight.SemiBold else null,
        fontStyle = if (italic) FontStyle.Italic else null,
        fontFamily = if (code) FontFamily.Monospace else null,
        background = if (code) codeBackground else Color.Unspecified,
        color = if (link != null) linkColor else Color.Unspecified,
        textDecoration = if (decorations.isEmpty()) null else TextDecoration.combine(decorations),
    )
}

/** [markdown] as plain text, for the excerpt in the list: no markers, no images. */
fun plainExcerpt(markdown: String): String =
    Markdown
        .parse(markdown)
        .mapNotNull { block ->
            when (block) {
                is MdBlock.Heading -> block.text
                is MdBlock.Paragraph -> block.text
                is MdBlock.ListItem -> block.text
                is MdBlock.Quote -> block.text
                is MdBlock.Code -> block.text
                else -> null
            }
        }.joinToString(" ") { text -> MdInline.parse(text).joinToString("") { it.text } }
        .replace(Regex("\\s+"), " ")
        .trim()
