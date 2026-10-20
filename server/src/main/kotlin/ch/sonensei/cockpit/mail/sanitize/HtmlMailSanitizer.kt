package ch.sonensei.cockpit.mail.sanitize

import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.safety.Cleaner
import org.jsoup.safety.Safelist
import org.springframework.stereotype.Component

/** What happens to remote (`http`/`https`) images in a mail (SPEC.md §4.1). */
enum class RemoteImagePolicy {
	/** Default: the `src` is removed, so nothing is fetched until the user loads images. */
	BLOCK,

	/** The `src` is rewritten to the server's image proxy ("load images", allowlisted sender). */
	PROXY,
}

/** Builds the URL of the server's image proxy for a remote image URL. */
fun interface ImageProxyUrlBuilder {
	fun proxyUrl(remoteUrl: String): String
}

data class SanitizeOptions(
	val remoteImages: RemoteImagePolicy = RemoteImagePolicy.BLOCK,
	/** Required for [RemoteImagePolicy.PROXY]. */
	val imageProxy: ImageProxyUrlBuilder? = null,
) {
	init {
		require(remoteImages != RemoteImagePolicy.PROXY || imageProxy != null) {
			"RemoteImagePolicy.PROXY needs an imageProxy"
		}
	}
}

/**
 * @property html the safe body HTML, to be rendered in a sandboxed iframe
 * @property remoteImageCount how many remote images the mail references (blocked or proxied);
 *   the client shows "load images" when it is > 0 and images were blocked
 */
data class SanitizedHtml(
	val html: String,
	val remoteImageCount: Int,
	val remoteImagesBlocked: Boolean,
)

/**
 * Makes untrusted HTML mail safe to display (SPEC.md §4.1): a jsoup [Safelist] plus rules for
 * inline styles, images and links.
 *
 * Removes scripts, event handlers, forms, frames, embedded objects, `<style>`/`<link>` and every
 * `javascript:`/`data:` URL except inline raster images. Keeps layout tables, presentational
 * attributes and inline styles minus `position` and `url()`. Remote images are blocked or
 * proxied per [SanitizeOptions]; `cid:` images (inline attachments) are kept for the caller to
 * resolve.
 */
@Component
class HtmlMailSanitizer {
	fun sanitize(
		html: String,
		options: SanitizeOptions = SanitizeOptions(),
	): SanitizedHtml {
		val dirty = Jsoup.parse(html)
		dirty.select(DROPPED_WITH_CONTENT).remove()
		val clean = Cleaner(safelist).clean(dirty)
		clean.outputSettings().prettyPrint(false).charset(Charsets.UTF_8)

		clean.select("[style]").forEach(::sanitizeStyle)
		clean.select("a[href]").forEach {
			it.attr("target", "_blank")
			it.attr("rel", "noopener noreferrer nofollow")
		}
		val remoteImages = clean.select("img").count { rewriteImage(it, options) }
		return SanitizedHtml(
			html = clean.body().html(),
			remoteImageCount = remoteImages,
			remoteImagesBlocked = remoteImages > 0 && options.remoteImages == RemoteImagePolicy.BLOCK,
		)
	}

	private fun sanitizeStyle(element: Element) {
		val style = InlineStyleSanitizer.sanitize(element.attr("style"))
		if (style.isEmpty()) element.removeAttr("style") else element.attr("style", style)
	}

	/** Returns true if [img] references a remote image. */
	private fun rewriteImage(
		img: Element,
		options: SanitizeOptions,
	): Boolean {
		val src = img.attr("src").trim()
		val scheme = src.substringBefore(':', "").lowercase()
		when (scheme) {
			"http", "https" -> {
				when (options.remoteImages) {
					RemoteImagePolicy.BLOCK -> {
						img.removeAttr("src")
						img.attr(BLOCKED_ATTRIBUTE, "true")
					}
					RemoteImagePolicy.PROXY -> img.attr("src", options.imageProxy!!.proxyUrl(src))
				}
				return true
			}
			"cid" -> {}
			"data" -> if (!INLINE_IMAGE.matches(src)) img.remove()
			else -> img.remove()
		}
		return false
	}

	private companion object {
		const val BLOCKED_ATTRIBUTE = "data-cockpit-blocked-image"

		/** Removed before cleaning so their text (script source, CSS, form labels) doesn't leak into the body. */
		const val DROPPED_WITH_CONTENT =
			"script, style, link, meta, base, title, iframe, frame, frameset, object, embed, applet, " +
				"form, input, button, select, textarea, option, optgroup, datalist, output, " +
				"svg, math, template, audio, video, source, track, canvas, noembed, noframes, portal"

		/** Raster images only: SVG can carry scripts and external references. */
		val INLINE_IMAGE = Regex("^data:image/(png|jpe?g|gif|webp|bmp);base64,[a-z0-9+/=\\s]+$", RegexOption.IGNORE_CASE)

		private val TABLE_ATTRIBUTES =
			arrayOf("align", "valign", "width", "height", "bgcolor", "border", "cellpadding", "cellspacing", "colspan", "rowspan", "nowrap")

		val safelist: Safelist =
			Safelist()
				.addTags(
					"a",
					"abbr",
					"address",
					"article",
					"aside",
					"b",
					"bdi",
					"bdo",
					"big",
					"blockquote",
					"br",
					"caption",
					"center",
					"cite",
					"code",
					"col",
					"colgroup",
					"dd",
					"del",
					"details",
					"dfn",
					"div",
					"dl",
					"dt",
					"em",
					"figcaption",
					"figure",
					"font",
					"footer",
					"h1",
					"h2",
					"h3",
					"h4",
					"h5",
					"h6",
					"header",
					"hr",
					"i",
					"img",
					"ins",
					"kbd",
					"li",
					"main",
					"mark",
					"ol",
					"p",
					"pre",
					"q",
					"s",
					"samp",
					"section",
					"small",
					"span",
					"strike",
					"strong",
					"sub",
					"summary",
					"sup",
					"table",
					"tbody",
					"td",
					"tfoot",
					"th",
					"thead",
					"time",
					"tr",
					"tt",
					"u",
					"ul",
					"var",
					"wbr",
				).addAttributes(":all", "style", "class", "dir", "lang", "title", "align", "valign", "width", "height", "bgcolor")
				.addAttributes("table", *TABLE_ATTRIBUTES)
				.addAttributes("td", *TABLE_ATTRIBUTES)
				.addAttributes("th", *TABLE_ATTRIBUTES, "scope")
				.addAttributes("tr", *TABLE_ATTRIBUTES)
				.addAttributes("col", "span", "width")
				.addAttributes("colgroup", "span", "width")
				.addAttributes("font", "color", "face", "size")
				.addAttributes("hr", "size", "noshade", "color")
				.addAttributes("ol", "start", "type", "reversed")
				.addAttributes("ul", "type")
				.addAttributes("li", "value")
				.addAttributes("img", "src", "alt", "border", "hspace", "vspace")
				.addAttributes("a", "href")
				.addProtocols("a", "href", "http", "https", "mailto", "tel")
				.addProtocols("img", "src", "http", "https", "cid", "data")
	}
}
