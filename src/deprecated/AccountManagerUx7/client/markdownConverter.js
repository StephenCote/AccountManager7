
window.markdownConverter = (function () {

	let turndown = new TurndownService({
		headingStyle: 'atx',
		hr: '---',
		bulletListMarker: '-',
		codeBlockStyle: 'fenced',
		emDelimiter: '*'
	});

	/// Strip styled spans that contentEditable injects
	turndown.addRule('stripStyledSpans', {
		filter: function (node) {
			return node.nodeName === 'SPAN' && node.getAttribute('style') && !node.getAttribute('class');
		},
		replacement: function (content) {
			return content;
		}
	});

	/// Strip browser-generated class "p1"
	turndown.addRule('stripP1Class', {
		filter: function (node) {
			return (node.nodeName === 'P' || node.nodeName === 'DIV') &&
				node.getAttribute('class') === 'p1';
		},
		replacement: function (content) {
			return '\n\n' + content + '\n\n';
		}
	});

	/// Legacy BB code detection
	let bbCodePattern = /\[(b|i|s|p|div|url|img|ul|ol|li|h[1-5]|blockquote)\b[^\]]*\]/i;

	/// Legacy BB code regex patterns (from bbscript.js) for one-time migration
	let bbPatterns = [
		{ exp: /\[ul(?:\s*)\]((.|\n|\r)*?)\[\/ul(?:\s*)\]/gi, replace: "<ul>$1</ul>" },
		{ exp: /\[ol(?:\s*)\]((.|\n|\r)*?)\[\/ol(?:\s*)\]/gi, replace: "<ol>$1</ol>" },
		{ exp: /\[li(?:\s*)\]((.|\n|\r)*?)\[\/li(?:\s*)\]/gi, replace: "<li>$1</li>" },
		{ exp: /\[b(?:\s*)\]((.|\n|\r)*?)\[\/b(?:\s*)\]/gi, replace: "<b>$1</b>" },
		{ exp: /\[p(?:\s*)\]((.|\n|\r)*?)\[\/p(?:\s*)\]/gi, replace: "<p>$1</p>" },
		{ exp: /\[div(?:\s*)\]((.|\n|\r)*?)\[\/div(?:\s*)\]/gi, replace: "<div>$1</div>" },
		{ exp: /\[p class=((.|\n|\r)*?)(?:\s*)\]((.|\n|\r)*?)\[\/p(?:\s*)\]/gi, replace: "<p class=\"$1\">$3</p>" },
		{ exp: /\[div class=((.|\n|\r)*?)(?:\s*)\]((.|\n|\r)*?)\[\/div(?:\s*)\]/gi, replace: "<div class=\"$1\">$3</div>" },
		{ exp: /\[div style=((.|\n|\r)*?)(?:\s*)\]((.|\n|\r)*?)\[\/div(?:\s*)\]/gi, replace: "<div style=\"$1\">$3</div>" },
		{ exp: /\[blockquote(?:\s*)\]((.|\n|\r)*?)\[\/blockquote(?:\s*)\]/gi, replace: "<blockquote>$1</blockquote>" },
		{ exp: /\[h1(?:\s*)\]((.|\n|\r)*?)\[\/h1(?:\s*)\]/gi, replace: "<h1>$1</h1>" },
		{ exp: /\[h2(?:\s*)\]((.|\n|\r)*?)\[\/h2(?:\s*)\]/gi, replace: "<h2>$1</h2>" },
		{ exp: /\[h3(?:\s*)\]((.|\n|\r)*?)\[\/h3(?:\s*)\]/gi, replace: "<h3>$1</h3>" },
		{ exp: /\[h4(?:\s*)\]((.|\n|\r)*?)\[\/h4(?:\s*)\]/gi, replace: "<h4>$1</h4>" },
		{ exp: /\[h5(?:\s*)\]((.|\n|\r)*?)\[\/h5(?:\s*)\]/gi, replace: "<h5>$1</h5>" },
		{ exp: /\[i(?:\s*)\]((.|\n|\r)*?)\[\/i(?:\s*)\]/gi, replace: "<i>$1</i>" },
		{ exp: /\[s(?:\s*)\]((.|\n|\r)*?)\[\/s(?:\s*)\]/gi, replace: "<strike>$1</strike>" },
		{ exp: /\[url(?:\s*)\]((.|\n|\r)*?)\[\/url(?:\s*)\]/gi, replace: "<a href=\"$1\">$1</a>" },
		{ exp: /\[url=\"\"((.|\n|\r)*?)(?:\s*)\"\"\]((.|\n|\r)*?)\[\/url(?:\s*)\]/gi, replace: "<a href=\"$1\">$3</a>" },
		{ exp: /\[url=((.|\n|\r)*?)(?:\s*)\]((.|\n|\r)*?)\[\/url(?:\s*)\]/gi, replace: "<a href=\"$1\">$3</a>" },
		{ exp: /\[img align=((.|\n|\r)*?)(?:\s*)\]((.|\n|\r)*?)\[\/img(?:\s*)\]/gi, replace: "<img src=\"$3\" alt=\"\" />" },
		{ exp: /\[img class=((.|\n|\r)*?)(?:\s*)\]((.|\n|\r)*?)\[\/img(?:\s*)\]/gi, replace: "<img src=\"$3\" alt=\"\" />" },
		{ exp: /\[img(?:\s*)\]((.|\n|\r)*?)\[\/img(?:\s*)\]/gi, replace: "<img src=\"$1\" alt=\"\" />" },
		{ exp: /\[img=((.|\n|\r)*?)x((.|\n|\r)*?)(?:\s*)\]((.|\n|\r)*?)\[\/img(?:\s*)\]/gi, replace: "<img src=\"$5\" alt=\"\" />" }
	];

	function isBBCode(s) {
		return bbCodePattern.test(s);
	}

	function bbToHtml(s) {
		for (let i = 0; i < bbPatterns.length; i++) {
			while (s.match(bbPatterns[i].exp)) {
				s = s.replace(bbPatterns[i].exp, bbPatterns[i].replace);
			}
		}
		return s;
	}

	function bbToMarkdown(s) {
		return turndown.turndown(bbToHtml(s));
	}

	/// Ensure content is markdown, migrating BB code if needed
	function normalize(s) {
		if (!s || !s.trim()) return s || '';
		if (isBBCode(s)) {
			console.log("markdownConverter: migrating legacy BB code to Markdown");
			return bbToMarkdown(s);
		}
		return s;
	}

	return {
		/// Markdown (or legacy BB code) → HTML string for iframe display
		toHtml: function (s) {
			if (!s || !s.trim()) return '';
			return marked.parse(normalize(s));
		},

		/// DOM childNodes array → Markdown string (drop-in for bbConverter.convertNodes)
		convertNodes: function (nodeArray) {
			let container = document.createElement('div');
			for (let i = 0; i < nodeArray.length; i++) {
				container.appendChild(nodeArray[i].cloneNode(true));
			}
			return turndown.turndown(container.innerHTML);
		},

		/// Markdown (or legacy BB code) → Mithril vnodes for read-only display
		mInto: function (s) {
			if (!s || !s.trim()) return '';
			return m.trust(marked.parse(normalize(s)));
		},

		/// Normalize stored content to canonical Markdown
		normalizeSource: normalize,

		/// Expose BB code detection for callers
		isBBCode: isBBCode
	};
})();
