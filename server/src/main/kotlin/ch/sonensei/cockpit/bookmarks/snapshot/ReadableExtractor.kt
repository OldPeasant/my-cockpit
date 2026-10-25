package ch.sonensei.cockpit.bookmarks.snapshot

import ch.sonensei.cockpit.bookmarks.infra.FetchedPage
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.nodes.TextNode
import org.jsoup.safety.Cleaner
import org.jsoup.safety.Safelist
import java.io.ByteArrayInputStream
import java.nio.charset.Charset

/** The readable copy of a page: its [title] and a complete, sanitized HTML document ([html]) with its main content. */
class ReadablePage(
	val title: String,
	val html: String,
	/** The text of the main content, whitespace collapsed; empty when nothing readable was found. */
	val text: String,
)

/**
 * Extracts the main content of an HTML page, like Mozilla's Readability: elements that are never content (scripts,
 * navigation, forms, ...) and those whose class or id says they are not (`sidebar`, `comment`, `share`, ...) are
 * removed; every paragraph scores its parent (fully) and grandparent (half) by its length and commas; the scores are
 * weighted by the container's tag, class and id and reduced by its share of link text. The best container wins, with
 * those of its siblings that score close to it or are paragraphs of prose. Without any candidate the whole body is used.
 * The page's main element (`main` or `[role=main]`, otherwise its outermost `article` with the most text) and the forms
 * or unlikely elements around it are kept (they wrap the page); other articles, such as comments, are not protected.
 * When the result has less than [MIN_TEXT_LENGTH] characters, the extraction runs again on a fresh parse without
 * removing forms and unlikely class/id elements (they may wrap the content), and the longer result wins.
 *
 * The result is sanitized with jsoup's [Cleaner] against a list of text, list, table and link elements: no scripts,
 * styles, event handlers, forms, frames or images (they would load remote content when the copy is opened), links are
 * absolute http(s) or `mailto:` URLs. It is wrapped in a document of its own with the title, a link to the source and
 * a Content-Security-Policy that blocks every remote load, should it be opened directly in a browser.
 */
object ReadableExtractor {
	fun extract(
		page: FetchedPage,
		fallbackTitle: String,
	): ReadablePage {
		// An unknown charset name: let jsoup detect it from the page (meta charset, BOM) instead.
		val charset = page.charset?.takeIf { runCatching { Charset.isSupported(it) }.getOrDefault(false) }
		val parse = { Jsoup.parse(ByteArrayInputStream(page.bytes), charset, page.uri.toString()) }
		val document = parse()
		val title = title(document) ?: fallbackTitle
		var content = cleaned(mainContent(document, stripUnlikely = true), page)
		if (content.text().trim().length < MIN_TEXT_LENGTH) {
			val retry = cleaned(mainContent(parse(), stripUnlikely = false), page)
			if (retry.text().trim().length > content.text().trim().length) content = retry
		}
		return ReadablePage(title, render(title, page.uri.toString(), content), content.text().trim())
	}

	/** The sanitized body of a document holding [content], without elements the cleaning left empty. */
	private fun cleaned(
		content: Element,
		page: FetchedPage,
	): Element {
		val cleaned = Cleaner(SAFELIST).clean(Document.createShell(page.uri.toString()).also { it.body().appendChild(content) })
		removeEmpty(cleaned.body())
		return cleaned.body()
	}

	private fun title(document: Document): String? =
		listOf(
			document.selectFirst("meta[property=og:title][content]")?.attr("content"),
			document.title(),
			document.selectFirst("h1")?.text(),
		).firstNotNullOfOrNull { it?.replace(WHITESPACE, " ")?.trim()?.ifEmpty { null } }

	/**
	 * The main content of [document] (detached from it), see [ReadableExtractor]. With [stripUnlikely], forms and
	 * elements whose class or id says they are not content are removed first, unless they wrap the page's main element.
	 */
	private fun mainContent(
		document: Document,
		stripUnlikely: Boolean,
	): Element {
		val body = document.body()
		body.select(NEVER_CONTENT).remove()
		body.select("[hidden], [aria-hidden=true], [style~=(?i)display\\s*:\\s*none], [style~=(?i)visibility\\s*:\\s*hidden]").remove()
		// listOf: an Element is an Iterable of its descendants, `parents() + it` would keep the whole subtree.
		val kept = mainElements(body).flatMapTo(HashSet<Element>()) { listOf(it) + it.parents() }
		body
			.select("*")
			.filter { it.tagName() !in KEEP_TAGS && it !in kept && it.attr("role") in UNLIKELY_ROLES }
			.forEach { if (it.parent() != null) it.remove() }
		if (stripUnlikely) {
			body
				.select("*")
				.filter { it.tagName() !in KEEP_TAGS && it !in kept && (it.tagName() == "form" || unlikely(it)) }
				.forEach { if (it.parent() != null) it.remove() }
		}
		divsToParagraphs(body)

		val scores = HashMap<Element, Double>()
		for (paragraph in body.select("p, pre, td, blockquote")) {
			val text = paragraph.text()
			if (text.length < MIN_PARAGRAPH_LENGTH) continue
			val score = 1.0 + text.count { it == ',' } + minOf(text.length / CHARS_PER_POINT, MAX_LENGTH_POINTS)
			paragraph.parent()?.takeIf { it != body.parent() }?.let { scores.merge(it, score + initialScore(it), Double::plus) }
			paragraph.parent()?.parent()?.takeIf { it != body.parent() && it.parent() != null }?.let {
				scores.merge(it, score / 2 + initialScore(it), Double::plus)
			}
		}
		val top =
			scores.entries
				.map { (element, score) -> element to score * (1 - linkDensity(element)) }
				.maxByOrNull { it.second }
		if (top == null) return Element("div").appendChildren(body.childNodesCopy())
		val (candidate, topScore) = top
		val threshold = maxOf(MIN_SIBLING_SCORE, topScore * SIBLING_SHARE)
		val article = Element("div")
		val siblings = candidate.parent()?.children()?.toList() ?: listOf(candidate)
		for (sibling in siblings) {
			val keep =
				sibling == candidate ||
					(scores[sibling]?.let { it * (1 - linkDensity(sibling)) >= threshold } ?: false) ||
					(sibling.tagName() == "p" && prose(sibling))
			if (keep) article.appendChild(sibling.clone())
		}
		return article
	}

	/** Whether the class or id of [element] says it is not content (and does not also say it may be). */
	private fun unlikely(element: Element): Boolean {
		val names = "${element.className()} ${element.id()}"
		return names.isNotBlank() && UNLIKELY.containsMatchIn(names) && !MAYBE.containsMatchIn(names)
	}

	/**
	 * The page's main elements: every `main` and `[role=main]` element, without them the outermost `article` (not inside
	 * another one) with the most text. They and their ancestors are never removed, so wrappers keep the content, while
	 * other articles (comments, teasers) go with the unlikely blocks around them.
	 */
	private fun mainElements(body: Element): List<Element> {
		val main = body.select(MAIN_ELEMENTS)
		if (main.isNotEmpty()) return main
		return listOfNotNull(
			body
				.select("article")
				.filter { article -> article.parents().none { it.tagName() == "article" } }
				.maxByOrNull { it.text().length },
		)
	}

	/** Turns `<div>`s that only hold text and inline elements into paragraphs, so they score like them. */
	private fun divsToParagraphs(body: Element) {
		body.select("div").forEach { div ->
			if (div.children().none { it.tagName() in BLOCK_TAGS } && div.textNodes().any { !it.isBlank }) div.tagName("p")
		}
	}

	/** Points for the kind of container: a `<div>` is likely content, a list or heading likely not; its class and id. */
	private fun initialScore(element: Element): Double {
		val tag =
			when (element.tagName()) {
				"article" -> ARTICLE_POINTS
				"div", "main", "section" -> DIV_POINTS
				"pre", "td", "blockquote" -> CELL_POINTS
				"address", "ol", "ul", "dl", "dd", "dt", "li", "form" -> -CELL_POINTS
				"h1", "h2", "h3", "h4", "h5", "h6", "th" -> -DIV_POINTS
				else -> 0.0
			}
		val names = "${element.className()} ${element.id()}"
		val weight =
			(if (NEGATIVE.containsMatchIn(names)) -CLASS_POINTS else 0.0) + (if (POSITIVE.containsMatchIn(names)) CLASS_POINTS else 0.0)
		return tag + weight
	}

	/** Share of the text of [element] that is inside links. */
	private fun linkDensity(element: Element): Double {
		val length = element.text().length
		if (length == 0) return 0.0
		return element.select("a").sumOf { it.text().length }.toDouble() / length
	}

	private fun prose(paragraph: Element): Boolean {
		val text = paragraph.text()
		val density = linkDensity(paragraph)
		return (text.length > PROSE_LENGTH && density < PROSE_LINK_DENSITY) ||
			(text.isNotEmpty() && density == 0.0 && SENTENCE_END.containsMatchIn(text))
	}

	/** Removes elements the cleaning left without text (e.g. a paragraph that only held an image), bottom up. */
	private fun removeEmpty(root: Element) {
		root
			.select("p, div, span, li, ul, ol, blockquote, figure, figcaption, h1, h2, h3, h4, h5, h6, a, em, strong, b, i, section")
			.reversed()
			.filter { it.text().isBlank() && it.select("br, hr").isEmpty() }
			.forEach { it.remove() }
	}

	private fun render(
		title: String,
		source: String,
		content: Element,
	): String {
		val output = Document.createShell("")
		output.outputSettings().charset(Charsets.UTF_8).prettyPrint(true)
		val head = output.head()
		head.appendElement("meta").attr("charset", "utf-8")
		head.appendElement("meta").attr("http-equiv", "Content-Security-Policy").attr("content", CSP)
		head.appendElement("meta").attr("name", "referrer").attr("content", "no-referrer")
		head.appendElement("meta").attr("name", "viewport").attr("content", "width=device-width, initial-scale=1")
		output.title(title)
		val article = output.body().appendElement("article")
		article.appendElement("h1").appendChild(TextNode(title))
		article
			.appendElement("p")
			.appendText("Source: ")
			.appendElement("a")
			.attr("href", source)
			.attr("rel", "noopener noreferrer nofollow")
			.text(source)
		article.appendChildren(content.childNodesCopy())
		return "<!DOCTYPE html>\n" + output.html()
	}

	private val WHITESPACE = Regex("\\s+")
	private val SENTENCE_END = Regex("\\.( |$)")

	/** Never content: scripts, styles, embedded objects, form controls and the page's navigation. */
	private const val NEVER_CONTENT =
		"script, style, noscript, template, iframe, frame, frameset, object, embed, applet, input, button, select, " +
			"textarea, svg, canvas, nav, footer, aside, dialog, link, meta"

	/** The page's main element when it marks one; otherwise its main article is used, see [mainElements]. */
	private const val MAIN_ELEMENTS = "main, [role=main]"

	/** Below this many characters of text, the extraction is retried without removing forms and unlikely elements. */
	private const val MIN_TEXT_LENGTH = 500

	private val KEEP_TAGS = setOf("html", "body")
	private val UNLIKELY_ROLES = setOf("navigation", "banner", "complementary", "contentinfo", "menu", "menubar", "dialog", "alert")
	private val UNLIKELY =
		Regex(
			"-ad-|ai2html|banner|breadcrumb|combx|comment|community|cover-wrap|disqus|extra|footer|gdpr|header|legends|menu|" +
				"related|remark|replies|rss|shoutbox|sidebar|skyscraper|social|sponsor|supplemental|ad-break|agegate|" +
				"pagination|pager|popup|yom-remote|cookie|consent|newsletter|subscribe|share|modal",
			RegexOption.IGNORE_CASE,
		)
	private val MAYBE = Regex("and|article|body|column|content|main|shadow|post|story|entry", RegexOption.IGNORE_CASE)
	private val POSITIVE =
		Regex("article|body|content|entry|hentry|h-entry|main|page|pagination|post|text|blog|story", RegexOption.IGNORE_CASE)
	private val NEGATIVE =
		Regex(
			"-ad-|hidden|^hid$| hid$| hid |^hid |banner|combx|comment|com-|contact|foot|footer|footnote|gdpr|masthead|media|" +
				"meta|outbrain|promo|related|scroll|share|shoutbox|sidebar|skyscraper|sponsor|shopping|tags|tool|widget",
			RegexOption.IGNORE_CASE,
		)
	private val BLOCK_TAGS =
		setOf(
			"a",
			"blockquote",
			"dl",
			"div",
			"img",
			"ol",
			"p",
			"pre",
			"table",
			"ul",
			"section",
			"article",
			"header",
			"h1",
			"h2",
			"h3",
			"h4",
			"h5",
			"h6",
			"figure",
			"main",
		)

	private const val MIN_PARAGRAPH_LENGTH = 25
	private const val CHARS_PER_POINT = 100.0
	private const val MAX_LENGTH_POINTS = 3.0
	private const val ARTICLE_POINTS = 10.0
	private const val DIV_POINTS = 5.0
	private const val CELL_POINTS = 3.0
	private const val CLASS_POINTS = 25.0
	private const val MIN_SIBLING_SCORE = 10.0
	private const val SIBLING_SHARE = 0.2
	private const val PROSE_LENGTH = 80
	private const val PROSE_LINK_DENSITY = 0.25

	private const val CSP = "default-src 'none'; style-src 'unsafe-inline'; base-uri 'none'; form-action 'none'"

	/** What a readable copy may contain: text structure, lists, tables, quotes, code and links. */
	private val SAFELIST: Safelist =
		Safelist()
			.addTags(
				"p",
				"br",
				"hr",
				"h1",
				"h2",
				"h3",
				"h4",
				"h5",
				"h6",
				"ul",
				"ol",
				"li",
				"dl",
				"dt",
				"dd",
				"blockquote",
				"pre",
				"code",
				"kbd",
				"samp",
				"var",
				"em",
				"strong",
				"b",
				"i",
				"u",
				"s",
				"del",
				"ins",
				"sub",
				"sup",
				"small",
				"mark",
				"q",
				"cite",
				"abbr",
				"dfn",
				"time",
				"figure",
				"figcaption",
				"table",
				"caption",
				"thead",
				"tbody",
				"tfoot",
				"tr",
				"th",
				"td",
				"div",
				"span",
				"section",
				"a",
			).addAttributes("a", "href", "title")
			.addAttributes("abbr", "title")
			.addAttributes("time", "datetime")
			.addAttributes("ol", "start")
			.addAttributes("th", "colspan", "rowspan", "scope")
			.addAttributes("td", "colspan", "rowspan")
			.addAttributes("blockquote", "cite")
			.addAttributes("q", "cite")
			.addProtocols("a", "href", "http", "https", "mailto")
			.addProtocols("blockquote", "cite", "http", "https")
			.addProtocols("q", "cite", "http", "https")
			.addEnforcedAttribute("a", "rel", "noopener noreferrer nofollow")
}
