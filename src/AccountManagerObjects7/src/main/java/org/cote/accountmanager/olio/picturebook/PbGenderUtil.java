package org.cote.accountmanager.olio.picturebook;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.io.Query;
import org.cote.accountmanager.io.QueryResult;
import org.cote.accountmanager.io.QueryUtil;
import org.cote.accountmanager.olio.OlioContext;
import org.cote.accountmanager.olio.schema.OlioFieldNames;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.schema.type.ComparatorEnumType;
import org.cote.accountmanager.schema.type.OrderEnumType;

/**
 * Deterministic gender resolution for extracted PictureBook characters.
 *
 * <p>Replaces the random-baseline fallback that {@code PictureBookUtil.createCharPerson} used when
 * the LLM left {@code gender} empty. Under the emulator every replay of the same manuscript must
 * produce the same characters, so nothing in this chain consults {@code Math.random()}:
 * <ol>
 *   <li><b>llm</b> - the extracted value, clamped by {@link #normalize(String)};</li>
 *   <li><b>name</b> - the character's first name looked up in the world's names word list
 *       ({@code data.word}, {@code gender} "M"/"F"), see {@link #genderFromName(OlioContext, String)};</li>
 *   <li><b>pronoun</b> - he/him/his vs she/her/hers counted in the sentences that mention the
 *       character plus the sentence immediately after each, see
 *       {@link #genderFromPronouns(String, String, String)};</li>
 *   <li><b>hash</b> - a stable parity of the lower-cased name, see {@link #hashGender(String)}.</li>
 * </ol>
 * Every step returns the ecosystem-canonical lowercase {@code "male"}/{@code "female"} or the empty
 * string for "undetermined"; only the final step never returns empty.
 */
public final class PbGenderUtil {
	private static final Logger logger = LogManager.getLogger(PbGenderUtil.class);

	public static final String MALE = "male";
	public static final String FEMALE = "female";

	/** Which step of the chain produced the answer; logged once per character by the caller. */
	public enum Source { LLM, NAME, PRONOUN, HASH }

	/** The resolved gender ("male"/"female", never empty) and the chain step that produced it. */
	public static final class Resolution {
		private final String gender;
		private final Source source;

		Resolution(String gender, Source source) {
			this.gender = gender;
			this.source = source;
		}

		public String gender() { return gender; }
		public Source source() { return source; }

		@Override
		public String toString() { return gender + " via " + source.name().toLowerCase(Locale.ROOT); }
	}

	/**
	 * Tokens that are not given names even though the name splitter hands them back as a "first
	 * name" ("The Stranger" -> "The"; "Mrs Dalloway" -> "Mrs"). Lower-cased, compared without a
	 * trailing period so "Mr." and "Mr" both match.
	 */
	static final Set<String> NAME_LOOKUP_STOP_WORDS = Set.of(
		"the", "a", "an",
		"mr", "mrs", "ms", "miss", "mx", "dr", "doctor", "prof", "professor",
		"sir", "dame", "lady", "lord", "madam", "madame", "master", "mister",
		"captain", "capt", "sergeant", "sgt", "lieutenant", "lt", "colonel", "col", "general", "gen",
		"major", "admiral", "commander", "officer", "detective", "inspector", "constable", "agent",
		"king", "queen", "prince", "princess", "duke", "duchess", "count", "countess", "baron", "baroness",
		"father", "mother", "brother", "sister", "aunt", "uncle", "grandma", "grandpa", "old", "young",
		"saint", "st", "reverend", "rev", "pastor", "bishop", "nurse", "judge", "coach", "chief", "boss");

	private static final Pattern MALE_PRONOUNS = Pattern.compile("(?i)(?<![A-Za-z'])(?:he|him|his|himself)(?![A-Za-z])");
	private static final Pattern FEMALE_PRONOUNS = Pattern.compile("(?i)(?<![A-Za-z'])(?:she|her|hers|herself)(?![A-Za-z])");
	/// Sentence boundary: terminal punctuation (optionally followed by a closing quote) then
	/// whitespace, or any line break. Paragraphs and the "\n---\n" separators boundedPassages
	/// emits therefore also end a sentence.
	private static final Pattern SENTENCE_SPLIT = Pattern.compile("(?<=[.!?][\"'”’)]?)\\s+|\\r?\\n+");

	private PbGenderUtil() {
	}

	/**
	 * Clamp a raw (usually LLM-emitted) gender to exactly {@code "male"}, {@code "female"} or
	 * {@code ""}. Case-insensitive; accepts the single-letter forms. Anything else - including the
	 * literal strings "null"/"unknown"/"n/a" an LLM emits when it cannot tell - is undetermined.
	 */
	public static String normalize(String raw) {
		if (raw == null) return "";
		String g = raw.trim().toLowerCase(Locale.ROOT);
		if (g.equals(MALE) || g.equals("m")) return MALE;
		if (g.equals(FEMALE) || g.equals("f")) return FEMALE;
		return "";
	}

	/**
	 * Whether {@code firstName} is worth looking up in the names list: non-empty, a single token
	 * (no whitespace), at least two letters, and not an article/title from
	 * {@link #NAME_LOOKUP_STOP_WORDS}.
	 */
	public static boolean isNameLookupCandidate(String firstName) {
		if (firstName == null) return false;
		String t = firstName.trim();
		if (t.length() < 2) return false;
		for (int i = 0; i < t.length(); i++) {
			if (Character.isWhitespace(t.charAt(i))) return false;
		}
		if (t.endsWith(".")) t = t.substring(0, t.length() - 1);
		if (t.isEmpty() || !Character.isLetter(t.charAt(0))) return false;
		return !NAME_LOOKUP_STOP_WORDS.contains(t.toLowerCase(Locale.ROOT));
	}

	/**
	 * Full chain. {@code nameLookup} is the DB-backed step (normally
	 * {@code fn -> genderFromName(octx, fn)}); pass {@code null} to skip it. Never returns null and
	 * the returned gender is never empty.
	 */
	public static Resolution resolve(String llmGender, String name, String firstName, String passages,
			Function<String, String> nameLookup) {
		String g = normalize(llmGender);
		if (!g.isEmpty()) return new Resolution(g, Source.LLM);

		if (nameLookup != null && isNameLookupCandidate(firstName)) {
			try {
				g = normalize(nameLookup.apply(firstName.trim()));
			} catch (Exception e) {
				logger.warn("Name-based gender lookup failed for '" + firstName + "': " + e.getMessage());
				g = "";
			}
			if (!g.isEmpty()) return new Resolution(g, Source.NAME);
		}

		g = genderFromPronouns(name, firstName, passages);
		if (!g.isEmpty()) return new Resolution(g, Source.PRONOUN);

		return new Resolution(hashGender(name), Source.HASH);
	}

	// ------------------------------------------------------------------------------------------------
	// (b) name lookup against the world's names word list
	// ------------------------------------------------------------------------------------------------

	/**
	 * Look {@code firstName} up in the names word list of the context's universe. The names group
	 * lives on the <b>universe/basis</b> world ({@code WorldUtil.loadNames} loads into
	 * {@code ctx.getUniverse()}; {@code CharacterUtil.randomPerson} reads
	 * {@code world.basis.names}), so that is resolved first, then {@code world.basis}, then the
	 * world itself. Returns {@code "male"} when only "M" rows match, {@code "female"} when only
	 * "F" rows match, and {@code ""} when both, neither, or the group cannot be found.
	 */
	public static String genderFromName(OlioContext octx, String firstName) {
		if (octx == null || !isNameLookupCandidate(firstName)) return "";
		Long gid = namesGroupId(octx);
		if (gid == null) {
			logger.debug("No names group on the OlioContext universe/world; skipping name-based gender for '" + firstName + "'");
			return "";
		}
		return genderFromNamesGroup(gid, firstName);
	}

	/**
	 * Case-insensitive exact-name lookup in one {@code data.word} group. Two queries, the second only
	 * on a miss:
	 * <ol>
	 * <li>an indexed {@code IN} over the common casings of the token (as typed, Titlecase, lower,
	 *     UPPER) — the fast path that satisfies almost every real name;</li>
	 * <li>an anchored {@code ILIKE '<token>%'} for spellings the casings cannot produce
	 *     ({@code MaryAnne}, {@code DeAndre}, {@code McKenna} looked up as {@code maryanne} …).
	 *     A bare {@code ILIKE} value is wrapped {@code %value%} by {@code StatementUtil}, so the
	 *     trailing {@code %} is supplied explicitly to keep it a prefix match; the prefix superset
	 *     is then filtered to an exact {@code equalsIgnoreCase} on the way out.</li>
	 * </ol>
	 * Reads through {@code getSearch()} like {@code OlioUtil.randomSelections} does for the same
	 * reference data. Tokens carrying SQL pattern characters ({@code % _ \}) or a comma are never
	 * real first names and are treated as undetermined without touching the database.
	 */
	static String genderFromNamesGroup(long namesGroupId, String firstName) {
		if (namesGroupId <= 0L || !isNameLookupCandidate(firstName)) return "";
		String t = firstName.trim();
		if (t.endsWith(".")) t = t.substring(0, t.length() - 1);
		if (t.isEmpty() || t.indexOf(',') >= 0 || t.indexOf('%') >= 0 || t.indexOf('_') >= 0 || t.indexOf('\\') >= 0) return "";

		LinkedHashSet<String> variants = new LinkedHashSet<>();
		variants.add(t);
		variants.add(t.substring(0, 1).toUpperCase(Locale.ROOT) + t.substring(1).toLowerCase(Locale.ROOT));
		variants.add(t.toLowerCase(Locale.ROOT));
		variants.add(t.toUpperCase(Locale.ROOT));

		try {
			Query exact = QueryUtil.createQuery(ModelNames.MODEL_WORD, FieldNames.FIELD_GROUP_ID, namesGroupId);
			exact.field(FieldNames.FIELD_NAME, ComparatorEnumType.IN, String.join(",", variants));
			int flags = scanGenderRows(exact, t, 32);
			if (flags == 0) {
				/// No row under any common casing: widen to a case-insensitive PREFIX match and filter exactly.
				Query prefix = QueryUtil.createQuery(ModelNames.MODEL_WORD, FieldNames.FIELD_GROUP_ID, namesGroupId);
				prefix.field(FieldNames.FIELD_NAME, ComparatorEnumType.ILIKE, t + "%");
				flags = scanGenderRows(prefix, t, 512);
			}
			boolean male = (flags & GENDER_ROW_MALE) != 0;
			boolean female = (flags & GENDER_ROW_FEMALE) != 0;
			if (male && !female) return MALE;
			if (female && !male) return FEMALE;
			return "";
		} catch (Exception e) {
			logger.warn("Names-list gender lookup failed for '" + firstName + "' in group " + namesGroupId + ": " + e.getMessage());
			return "";
		}
	}

	private static final int GENDER_ROW_MALE = 1;
	private static final int GENDER_ROW_FEMALE = 2;

	/**
	 * Run {@code q} (projected to id/name/gender, first {@code limit} rows) and OR together
	 * {@link #GENDER_ROW_MALE}/{@link #GENDER_ROW_FEMALE} for every row whose {@code name} equals
	 * {@code token} ignoring case. Rows with any other name (a prefix superset) are ignored.
	 *
	 * <p>The page is ordered by {@code name} ascending (the {@code EventUtil}/{@code ChapBookUtil}
	 * idiom) so that when a prefix matches more than {@code limit} rows the page is the SAME rows every
	 * run rather than an unordered superset that could differ between replays. Rows sharing an identical
	 * name are interchangeable for the OR, so a total order on distinct names is sufficient.
	 */
	private static int scanGenderRows(Query q, String token, int limit) throws org.cote.accountmanager.exceptions.ReaderException {
		q.setRequest(new String[] { FieldNames.FIELD_ID, FieldNames.FIELD_NAME, FieldNames.FIELD_GENDER });
		q.setRequestRange(0, limit);
		q.setValue(FieldNames.FIELD_SORT_FIELD, FieldNames.FIELD_NAME);
		q.setValue(FieldNames.FIELD_ORDER, OrderEnumType.ASCENDING.toString());
		QueryResult qr = IOSystem.getActiveContext().getSearch().find(q);
		if (qr == null || qr.getResults() == null) return 0;
		int flags = 0;
		for (BaseRecord w : qr.getResults()) {
			String wn = w.get(FieldNames.FIELD_NAME);
			if (wn == null || !wn.equalsIgnoreCase(token)) continue;
			String wg = w.get(FieldNames.FIELD_GENDER);
			if (wg == null) continue;
			String g = wg.trim().toUpperCase(Locale.ROOT);
			if (g.startsWith("M")) flags |= GENDER_ROW_MALE;
			else if (g.startsWith("F")) flags |= GENDER_ROW_FEMALE;
		}
		return flags;
	}

	private static Long namesGroupId(OlioContext octx) {
		Long id = namesIdOf(octx.getUniverse());
		if (id != null) return id;
		BaseRecord world = octx.getWorld();
		if (world == null) return null;
		try {
			BaseRecord basis = world.get(OlioFieldNames.FIELD_BASIS);
			id = namesIdOf(basis);
		} catch (Exception e) {
			id = null;
		}
		if (id != null) return id;
		return namesIdOf(world);
	}

	private static Long namesIdOf(BaseRecord worldLike) {
		if (worldLike == null) return null;
		try {
			BaseRecord names = worldLike.get(OlioFieldNames.FIELD_NAMES);
			if (names == null) return null;
			Object id = names.get(FieldNames.FIELD_ID);
			if (id instanceof Number && ((Number) id).longValue() > 0L) return ((Number) id).longValue();
		} catch (Exception e) {
			/// Field not present on this record shape; the caller tries the next candidate.
		}
		return null;
	}

	// ------------------------------------------------------------------------------------------------
	// (c) pronoun majority in the character's own sentences
	// ------------------------------------------------------------------------------------------------

	/**
	 * Count masculine vs feminine third-person pronouns in the window of sentences that mention
	 * {@code name} or {@code firstName} (case-insensitive, whole-token), each plus the sentence that
	 * immediately follows it. Pronouns anywhere else in {@code passages} are ignored so a
	 * two-hander scene does not vote the wrong way. Majority wins; a tie or no pronouns at all is
	 * undetermined ({@code ""}). A {@code firstName} that fails {@link #isNameLookupCandidate}
	 * (e.g. "The") is not used to select sentences.
	 */
	public static String genderFromPronouns(String name, String firstName, String passages) {
		if (passages == null || passages.isBlank()) return "";
		List<Pattern> mentions = new ArrayList<>(3);
		if (name != null && !name.isBlank()) {
			mentions.add(tokenPattern(name.trim()));
			// The LLM names an unnamed character by role with an article ("The shopkeeper"), but the
			// manuscript rarely repeats that exact phrase ("A dark skinned shopkeeper ... She smoothed
			// her apron"), so the role word alone must also select sentences.
			String residual = stripLeadingStopWord(name.trim());
			if (residual != null) mentions.add(tokenPattern(residual));
		}
		if (firstName != null && isNameLookupCandidate(firstName)
				&& (name == null || !firstName.trim().equalsIgnoreCase(name.trim()))) {
			mentions.add(tokenPattern(firstName.trim()));
		}
		if (mentions.isEmpty()) return "";

		String[] sentences = SENTENCE_SPLIT.split(passages);
		boolean[] inWindow = new boolean[sentences.length];
		for (int i = 0; i < sentences.length; i++) {
			String s = sentences[i];
			if (s.isBlank()) continue;
			for (Pattern p : mentions) {
				if (p.matcher(s).find()) {
					inWindow[i] = true;
					if (i + 1 < sentences.length) inWindow[i + 1] = true;
					break;
				}
			}
		}

		int male = 0;
		int female = 0;
		for (int i = 0; i < sentences.length; i++) {
			if (!inWindow[i]) continue;
			male += count(MALE_PRONOUNS, sentences[i]);
			female += count(FEMALE_PRONOUNS, sentences[i]);
		}
		if (male > female) return MALE;
		if (female > male) return FEMALE;
		return "";
	}

	private static Pattern tokenPattern(String token) {
		return Pattern.compile("(?i)(?<![A-Za-z])" + Pattern.quote(token) + "(?![A-Za-z])");
	}

	/**
	 * {@code "The shopkeeper"} -> {@code "shopkeeper"}, {@code "Mrs Dalloway"} -> {@code "Dalloway"}.
	 * Exactly one leading {@link #NAME_LOOKUP_STOP_WORDS} token is removed; null when the name has no
	 * such prefix, is a single token, or the remainder is shorter than two letters.
	 */
	static String stripLeadingStopWord(String name) {
		int sp = -1;
		for (int i = 0; i < name.length(); i++) {
			if (Character.isWhitespace(name.charAt(i))) {
				sp = i;
				break;
			}
		}
		if (sp < 1) return null;
		String head = name.substring(0, sp);
		if (head.endsWith(".")) head = head.substring(0, head.length() - 1);
		if (!NAME_LOOKUP_STOP_WORDS.contains(head.toLowerCase(Locale.ROOT))) return null;
		String rest = name.substring(sp).trim();
		return rest.length() >= 2 ? rest : null;
	}

	private static int count(Pattern p, String s) {
		int n = 0;
		Matcher m = p.matcher(s);
		while (m.find()) n++;
		return n;
	}

	// ------------------------------------------------------------------------------------------------
	// (d) deterministic last resort
	// ------------------------------------------------------------------------------------------------

	/**
	 * Stable parity of the trimmed, lower-cased name: even -> {@code "male"}, odd -> {@code "female"}.
	 * Not a guess about the name - a tie-breaker that is identical on every replay of the same
	 * manuscript, which the random baseline it replaces was not.
	 */
	public static String hashGender(String name) {
		String n = name == null ? "" : name.trim().toLowerCase(Locale.ROOT);
		return Math.floorMod(n.hashCode(), 2) == 0 ? MALE : FEMALE;
	}
}
