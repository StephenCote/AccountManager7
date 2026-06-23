package org.cote.accountmanager.iso42001.scoring;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Word-frequency scoring of narrative text against categorized dictionaries
 * (iso42001-bias.md §2.3). Counts, per category, how many whitespace/punctuation-
 * delimited tokens of the text are members of that category's word set, and reports
 * relative frequencies for the downstream "word frequency → Chi-square" analysis.
 *
 * The authoritative category word lists live in
 * {@code src/main/resources/iso42001/lexicons/*.txt} (one word per line, '#'
 * comments allowed), populated verbatim from iso42001-bias.md §2.3. The seven
 * categories cover the task's authority / stereotype / dignity marker families:
 * competence_language + agency_language + positive_professional (authority/
 * competence), threat_language + negative_professional (stereotype/negative),
 * warmth_language + communal_language (dignity/warmth).
 *
 * Pure logic: no DB, no LLM, no AccessPoint. Matching is case-insensitive on
 * single-token, exact-word membership.
 */
public class LexicalAnalyzer {

	/** Classpath directory holding the lexicon files. */
	public static final String LEXICON_DIR = "iso42001/lexicons/";

	/** The category files shipped under {@link #LEXICON_DIR}. */
	public static final String[] LEXICON_FILES = {
		"positive_professional.txt",
		"negative_professional.txt",
		"threat_language.txt",
		"warmth_language.txt",
		"competence_language.txt",
		"agency_language.txt",
		"communal_language.txt"
	};

	private final Map<String, Set<String>> categories;

	/**
	 * Construct with an explicit category → words map (used for hand-checked
	 * fixtures). Words are lower-cased.
	 */
	public LexicalAnalyzer(Map<String, Set<String>> categories) {
		Map<String, Set<String>> copy = new LinkedHashMap<>();
		for (Map.Entry<String, Set<String>> e : categories.entrySet()) {
			Set<String> lc = new LinkedHashSet<>();
			for (String w : e.getValue()) {
				lc.add(w.toLowerCase());
			}
			copy.put(e.getKey(), lc);
		}
		this.categories = copy;
	}

	/** Load all {@link #LEXICON_FILES} from the classpath under {@link #LEXICON_DIR}. */
	public static LexicalAnalyzer fromClasspath() {
		return fromClasspath(LEXICON_FILES);
	}

	/**
	 * Load a specific set of lexicon files from the classpath under {@link #LEXICON_DIR}
	 * (category = filename minus {@code .txt}). Lets a module use supplemental categories
	 * (e.g. BIAS-NARR's {@code physical_detail} / {@code antagonist}) without altering the
	 * shared default category set returned by {@link #fromClasspath()}.
	 */
	public static LexicalAnalyzer fromClasspath(String... files) {
		Map<String, Set<String>> cats = new LinkedHashMap<>();
		for (String file : files) {
			String category = file.substring(0, file.length() - ".txt".length());
			cats.put(category, loadWords(LEXICON_DIR + file));
		}
		return new LexicalAnalyzer(cats);
	}

	private static Set<String> loadWords(String resourcePath) {
		Set<String> words = new LinkedHashSet<>();
		ClassLoader cl = Thread.currentThread().getContextClassLoader();
		try (InputStream in = cl.getResourceAsStream(resourcePath)) {
			if (in == null) {
				throw new IllegalStateException("Lexicon resource not found on classpath: " + resourcePath);
			}
			try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
				String line;
				while ((line = r.readLine()) != null) {
					String t = line.trim();
					if (t.isEmpty() || t.startsWith("#")) {
						continue;
					}
					words.add(t.toLowerCase());
				}
			}
		} catch (IOException e) {
			throw new IllegalStateException("Failed to read lexicon: " + resourcePath, e);
		}
		return words;
	}

	/** The loaded category names. */
	public Set<String> getCategories() {
		return categories.keySet();
	}

	/** Number of words in a category (0 if unknown category). */
	public int getCategorySize(String category) {
		Set<String> w = categories.get(category);
		return w == null ? 0 : w.size();
	}

	/**
	 * Tokenize {@code text} and count category membership.
	 * Tokens are maximal runs of letters, lower-cased.
	 */
	public LexicalResult analyze(String text) {
		Map<String, Integer> counts = new LinkedHashMap<>();
		for (String cat : categories.keySet()) {
			counts.put(cat, 0);
		}
		int total = 0;
		if (text != null && !text.isEmpty()) {
			for (String token : text.toLowerCase().split("[^a-z]+")) {
				if (token.isEmpty()) {
					continue;
				}
				total++;
				for (Map.Entry<String, Set<String>> e : categories.entrySet()) {
					if (e.getValue().contains(token)) {
						counts.merge(e.getKey(), 1, Integer::sum);
					}
				}
			}
		}
		return new LexicalResult(counts, total);
	}
}
