package org.cote.accountmanager.olio.llm;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.cache.CacheUtil;
import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.io.Query;
import org.cote.accountmanager.io.QueryUtil;
import org.cote.accountmanager.olio.ApparelUtil;
import org.cote.accountmanager.olio.OlioUtil;
import org.cote.accountmanager.olio.WearLevelEnumType;
import org.cote.accountmanager.olio.schema.OlioFieldNames;
import org.cote.accountmanager.olio.schema.OlioModelNames;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.schema.FieldNames;

/**
 * Chat wardrobe tokens (aiDocs/ChatWardrobeTokensDesign.md §3). Resolved on RECEIPT, server side, once:
 * {@link Chat} runs {@link #process} on an incoming user message before the system prompt is rebuilt, and on
 * each assistant reply before it is added to history. Each applied token is rewritten to its resolved form
 * (carrying the objectId it created or changed, and an absolute level for dress up/down) and followed by a
 * system-written {@code <private>[wardrobe] ...</private>} note, so later turns read what actually happened.
 * Resolved tokens are never re-applied.
 *
 * <pre>
 *   ${outfit:ITEM,...}          new outfit for the speaker, made active        -> ${outfit.APPAREL_OID.me:ITEMS}
 *   ${outfit.you:ITEM,...}      add item(s) to the OTHER character's active outfit -> ${outfit.WEARABLE_OID.you:ITEMS}
 *   ${dress.me|you.up|down}     one layer up / down                            -> ${dress.APPAREL_OID.me.LEVEL}
 *   ${dress.me|you.LEVEL}       set a WearLevelEnumType layer                   -> ${dress.APPAREL_OID.me.LEVEL}
 *   ${give.you:ITEM,...} ${give:ITEM}  a NEW item for the other character, worn  -> ${give.WEARABLE_OID.you:ITEMS}
 *                               (chat-level: it need not exist on, and does not leave, the giver)
 * </pre>
 * ITEM is the embedded wearable format {@code category:name:LEVEL:gender:location} (LEVEL = enum name or
 * number, {@link WearLevelEnumType#fromToken}) or a bare catalog name; unknown bare names become free-form
 * SUIT-level clothing. "me" is the speaker, "you" the other chat character. A new outfit always gets a BASE
 * layer: when none of its items is at or below BASE, the gender default is added and recorded in the token.
 *
 * Writes run as the chat user: new apparel/wearables are built in the user's home (~/Apparel, ~/Wearables)
 * and persisted like PbWardrobeUtil does (RecordUtil.createRecord, then AccessPoint.member to attach); every
 * change to an existing record is an AccessPoint.update / AccessPoint.member, so PBAC applies. A token that
 * cannot be applied is left unresolved with a "Not applied" note and a WARN; nothing is thrown.
 */
public class ChatWardrobeTokenUtil {
	private static final Logger logger = LogManager.getLogger(ChatWardrobeTokenUtil.class);

	public static final String NOTE_PREFIX = "<private>[wardrobe]";
	/// ${outfit...}, ${dress...}, ${give...}: group 1 kind, group 2 dotted qualifier, group 3 colon payload
	private static final Pattern TOKEN = Pattern.compile("\\$\\{(outfit|dress|give)((?:\\.[^:}]*)?)(?::([^}]*))?\\}");
	private static final Pattern UUID = Pattern.compile("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");

	private static final String[] MODELS_TO_CLEAR = new String[] {
		OlioModelNames.MODEL_CHAR_PERSON, OlioModelNames.MODEL_STORE, OlioModelNames.MODEL_APPAREL,
		OlioModelNames.MODEL_WEARABLE, OlioModelNames.MODEL_CHAT_CONFIG
	};

	private ChatWardrobeTokenUtil() {}

	/// Result of processing one message.
	public static class Result {
		private String content;
		private boolean changed = false;
		private int applied = 0;
		private int failed = 0;
		private BaseRecord speaker;
		private BaseRecord other;
		public String getContent() { return content; }
		public boolean isChanged() { return changed; }
		public int getApplied() { return applied; }
		public int getFailed() { return failed; }
		/// Fresh full records after the writes (null when nothing was applied)
		public BaseRecord getSpeaker() { return speaker; }
		public BaseRecord getOther() { return other; }
	}

	/// Parsed token.
	static class Token {
		String match;
		String kind;
		boolean resolved;
		boolean you;
		String op;
		String payload;
	}

	public static boolean hasTokens(String content) {
		return content != null && (content.contains("${outfit") || content.contains("${dress") || content.contains("${give"));
	}

	static List<Token> parse(String content) {
		List<Token> out = new ArrayList<>();
		if (content == null) return out;
		Matcher m = TOKEN.matcher(content);
		while (m.find()) {
			Token t = new Token();
			t.match = m.group();
			t.kind = m.group(1);
			t.payload = m.group(3);
			List<String> parts = Arrays.stream((m.group(2) == null ? "" : m.group(2)).split("\\."))
				.map(String::trim).filter(s -> !s.isEmpty()).collect(Collectors.toList());
			if (!parts.isEmpty() && UUID.matcher(parts.get(0)).matches()) {
				t.resolved = true;
				parts.remove(0);
			}
			String target = (!parts.isEmpty() && (parts.get(0).equalsIgnoreCase("me") || parts.get(0).equalsIgnoreCase("you"))) ? parts.remove(0).toLowerCase() : null;
			/// Defaults: outfit and dress act on the speaker, give hands to the other
			t.you = (target != null ? target.equals("you") : "give".equals(t.kind));
			t.op = (!parts.isEmpty() ? parts.get(0) : null);
			out.add(t);
		}
		return out;
	}

	/**
	 * Apply every unresolved wardrobe token in {@code content}, spoken by {@code speaker} to {@code other}.
	 * Returns the rewritten content; on any error the original text is kept for that token.
	 */
	public static Result process(BaseRecord user, BaseRecord speaker, BaseRecord other, String content) {
		return process(user, speaker, other, content, null);
	}

	/**
	 * speakerRole ("user" / "assistant") names the chat role of {@code speaker}. When a token targets the OTHER
	 * character the speaker's message keeps only the action note, and the result is written as a
	 * {@code <private>[wardrobe-pending:ROLE] ...</private>} block for the other role: stripped from what the
	 * LLM is sent until the target speaks next, then moved into the target's own message
	 * ({@link #takePending}). This keeps "who did what" and "what happened to whom" in the right turns.
	 */
	public static Result process(BaseRecord user, BaseRecord speaker, BaseRecord other, String content, String speakerRole) {
		Result res = new Result();
		res.content = content;
		if (user == null || speaker == null || other == null || !hasTokens(content)) {
			return res;
		}
		List<Token> tokens = parse(content).stream().filter(t -> !t.resolved).collect(Collectors.toList());
		if (tokens.isEmpty()) return res;

		BaseRecord spk = loadPerson(user, speaker);
		BaseRecord oth = loadPerson(user, other);
		if (spk == null || oth == null) {
			logger.warn("Wardrobe tokens not applied: could not read " + (spk == null ? "speaker" : "other") + " character");
			return res;
		}

		StringBuilder sb = new StringBuilder();
		int cursor = 0;
		String text = content;
		for (Token t : tokens) {
			int at = text.indexOf(t.match, cursor);
			if (at < 0) continue;
			sb.append(text, cursor, at);
			cursor = at + t.match.length();
			String replacement;
			try {
				Applied a = apply(user, t, spk, oth);
				if (a.ok) {
					res.applied++;
					res.changed = true;
					replacement = a.token + NOTE_PREFIX + " " + a.note + "</private>";
					if (a.result != null && speakerRole != null) {
						replacement += pendingBlock(otherRole(speakerRole), a.result);
					}
					/// Later tokens in the same message must see this one's effect
					clearCaches();
					spk = loadPerson(user, spk);
					oth = loadPerson(user, oth);
				}
				else {
					res.failed++;
					replacement = t.match + NOTE_PREFIX + " Not applied: " + a.note + "</private>";
					logger.warn("Wardrobe token " + t.match + " not applied: " + a.note);
				}
			}
			catch (Exception e) {
				res.failed++;
				logger.error("Wardrobe token " + t.match + " failed", e);
				replacement = t.match + NOTE_PREFIX + " Not applied: an error occurred.</private>";
			}
			sb.append(replacement);
		}
		sb.append(text.substring(cursor));
		res.content = sb.toString();
		if (res.changed) {
			res.speaker = spk;
			res.other = oth;
		}
		return res;
	}

	static class Applied {
		boolean ok;
		String token;
		String note;
		/// Cross-character only: what the target sees on its next turn (null for self-targeted tokens)
		String result;
		static Applied ok(String token, String note) { Applied a = new Applied(); a.ok = true; a.token = token; a.note = note; return a; }
		/// note = the ACTION, kept in the speaker's message; result floats to the target's next message
		static Applied cross(String token, String action, String result) { Applied a = ok(token, action); a.result = result; return a; }
		static Applied fail(String note) { Applied a = new Applied(); a.ok = false; a.note = note; return a; }
	}

	private static Applied apply(BaseRecord user, Token t, BaseRecord speaker, BaseRecord other) {
		BaseRecord target = t.you ? other : speaker;
		String who = t.you ? "you" : "me";
		switch (t.kind) {
			case "outfit":
				if (t.payload == null || t.payload.trim().isEmpty()) return Applied.fail("no items listed");
				return t.you ? addItems(user, speaker, target, t.payload, who, "outfit") : newOutfit(user, target, t.payload);
			case "dress":
				return dress(user, speaker, target, t.op, who);
			case "give":
				if (t.payload == null || t.payload.trim().isEmpty()) return Applied.fail("no item named");
				if (!t.you) return Applied.fail("give must hand to the other character");
				/// Chat-level give creates the item new on the receiver (worn); it does not have to exist on, or
				/// leave, the giver. Inventory transfer is the RPG layer's job, not the chat's.
				return addItems(user, speaker, other, t.payload, "you", "give");
			default:
				return Applied.fail("unknown token");
		}
	}

	// ── outfit ─────────────────────────────────────────────────────────

	/// Split a payload on commas into embedded wearable strings for this gender; bare names resolve through
	/// the catalog (ApparelUtil.getEmbeddedOutfit), unknown bare names become free-form SUIT-level clothing.
	static List<String> toEmbedded(String payload, String gender) {
		String g = genderCode(gender);
		List<String> out = new ArrayList<>();
		for (String raw : payload.split(",")) {
			String item = raw.trim();
			if (item.isEmpty()) continue;
			if (item.indexOf(':') > -1) {
				out.add(ApparelUtil.getEmbeddedOutfit(new String[] { item }, gender)[0]);
				continue;
			}
			String lower = item.toLowerCase();
			String[] cat = ApparelUtil.getEmbeddedOutfit(new String[] { lower }, gender);
			if (cat.length == 0) {
				String near = nearestCatalogName(lower, gender);
				if (near != null) cat = ApparelUtil.getEmbeddedOutfit(new String[] { near }, gender);
			}
			if (cat.length > 0) {
				out.add(firstLocation(cat[0]));
			}
			else if (ACCESSORY_WORDS.matcher(lower).find()) {
				out.add("jewelry:" + lower + ":ACCESSORY:u:chest");
			}
			else {
				out.add("clothing:" + lower + ":SUIT:" + g + ":torso");
			}
		}
		return out;
	}

	/// Catalog entries may list alternative locations with commas ("jewelry:ring:7:u:finger,toe"). The resolved
	/// token's item list is comma-separated, so keep only the first alternative there.
	static String firstLocation(String embedded) {
		String[] p = embedded.split(":", 5);
		if (p.length == 5 && p[4].indexOf(',') > -1) {
			return p[0] + ":" + p[1] + ":" + p[2] + ":" + p[3] + ":" + p[4].substring(0, p[4].indexOf(','));
		}
		return embedded;
	}

	/// Free-form items that are worn as accessories rather than clothing layers
	private static final Pattern ACCESSORY_WORDS = Pattern.compile("\\b(corsage|boutonniere|brooch|pin|flower|ring|necklace|pendant|bracelet|earrings?|watch|tiara|crown|veil|badge)\\b");

	/// Closest catalog name for a bare item the catalog does not know verbatim: singularize a trailing "s"
	/// ("boxers" -> "boxer") and take the shortest catalog name that starts with, ends with, or equals it
	/// as a whole word ("boxer" -> "boxer shorts"). Null when nothing is close.
	public static String nearestCatalogName(String name, String gender) {
		String s = name.trim();
		if (s.endsWith("es") && s.length() > 4) s = s.substring(0, s.length() - 2);
		else if (s.endsWith("s") && s.length() > 3) s = s.substring(0, s.length() - 1);
		String best = null;
		for (String c : ApparelUtil.getApparelCatalogNames(gender)) {
			String cl = c.toLowerCase();
			if (cl.equals(s) || cl.startsWith(s + " ") || cl.endsWith(" " + s)) {
				if (best == null || cl.length() < best.length()) best = cl;
			}
		}
		return best;
	}

	/// The default BASE layer for a gender when an outfit has none.
	static List<String> baseDefaults(String gender) {
		String g = genderCode(gender);
		if ("m".equals(g)) return Arrays.asList("clothing:boxer shorts:BASE:m:waist");
		if ("f".equals(g)) return Arrays.asList("clothing:bra:BASE:f:breast", "clothing:panties:BASE:f:hip");
		return Arrays.asList("clothing:undershirt:BASE:u:torso");
	}

	static boolean hasBaseLayer(List<String> embedded) {
		for (String e : embedded) {
			String[] p = e.split(":");
			if (p.length >= 5) {
				WearLevelEnumType l = WearLevelEnumType.fromToken(p[2]);
				if (l != WearLevelEnumType.UNKNOWN && l != WearLevelEnumType.NONE && WearLevelEnumType.valueOf(l) <= WearLevelEnumType.valueOf(WearLevelEnumType.BASE)) {
					return true;
				}
			}
		}
		return false;
	}

	private static Applied newOutfit(BaseRecord user, BaseRecord person, String payload) {
		String gender = person.get(FieldNames.FIELD_GENDER);
		List<String> items = toEmbedded(payload, gender);
		List<String> added = new ArrayList<>();
		if (!hasBaseLayer(items)) {
			added.addAll(baseDefaults(gender));
			items.addAll(0, added);
		}
		BaseRecord apparel = buildApparel(user, gender, items, true);
		if (apparel == null) return Applied.fail("no wearable could be built from " + payload);
		BaseRecord store = person.get(FieldNames.FIELD_STORE);
		if (store == null) return Applied.fail(firstName(person) + " has no store");
		if (!IOSystem.getActiveContext().getRecordUtil().createRecord(apparel)) return Applied.fail("the outfit could not be saved");
		if (!IOSystem.getActiveContext().getAccessPoint().member(user, store, OlioFieldNames.FIELD_APPAREL, apparel, null, true)) {
			return Applied.fail("the outfit was saved but could not be added to " + firstName(person) + "'s wardrobe");
		}
		/// Make it the only active outfit
		List<BaseRecord> appl = store.get(OlioFieldNames.FIELD_APPAREL);
		if (appl != null) {
			for (BaseRecord a : appl) {
				Boolean in = a.get(OlioFieldNames.FIELD_IN_USE);
				if (in != null && in && !sameId(a, apparel)) {
					setInUse(user, a, false);
				}
			}
		}
		List<String> names = wearableNames(apparel.get(OlioFieldNames.FIELD_WEARABLES));
		String note = firstName(person) + " got dressed: " + join(names) + "."
			+ (added.isEmpty() ? "" : " (" + join(names.subList(0, Math.min(added.size(), names.size()))) + " added as the base layer.)");
		String tokenItems = String.join(",", items);
		return Applied.ok("${outfit." + apparel.get(FieldNames.FIELD_OBJECT_ID) + ".me:" + tokenItems + "}", note);
	}

	private static Applied addItems(BaseRecord user, BaseRecord speaker, BaseRecord target, String payload, String who, String kind) {
		String gender = target.get(FieldNames.FIELD_GENDER);
		List<String> items = toEmbedded(payload, gender);
		BaseRecord active = ApparelUtil.getWearingApparel(target);
		List<BaseRecord> wears;
		String firstOid;
		if (active == null) {
			/// Nothing active: the items become a new active outfit (no base backstop - this is "put X on them")
			BaseRecord apparel = buildApparel(user, gender, items, true);
			if (apparel == null) return Applied.fail("no wearable could be built from " + payload);
			BaseRecord store = target.get(FieldNames.FIELD_STORE);
			if (store == null || !IOSystem.getActiveContext().getRecordUtil().createRecord(apparel)
				|| !IOSystem.getActiveContext().getAccessPoint().member(user, store, OlioFieldNames.FIELD_APPAREL, apparel, null, true)) {
				return Applied.fail("could not add an outfit to " + firstName(target) + "'s wardrobe");
			}
			wears = apparel.get(OlioFieldNames.FIELD_WEARABLES);
			firstOid = (wears.isEmpty() ? (String) apparel.get(FieldNames.FIELD_OBJECT_ID) : (String) wears.get(0).get(FieldNames.FIELD_OBJECT_ID));
		}
		else {
			BaseRecord temp = buildApparel(user, gender, items, true);
			if (temp == null) return Applied.fail("no wearable could be built from " + payload);
			wears = temp.get(OlioFieldNames.FIELD_WEARABLES);
			firstOid = null;
			for (BaseRecord w : wears) {
				if (!IOSystem.getActiveContext().getRecordUtil().createRecord(w)) return Applied.fail("could not save " + w.get(FieldNames.FIELD_NAME));
				if (!IOSystem.getActiveContext().getAccessPoint().member(user, active, OlioFieldNames.FIELD_WEARABLES, w, null, true)) {
					return Applied.fail("could not add " + w.get(FieldNames.FIELD_NAME) + " to " + firstName(target) + "'s outfit");
				}
				if (firstOid == null) firstOid = w.get(FieldNames.FIELD_OBJECT_ID);
			}
		}
		String things = articled(wearableNames(wears));
		String sn = firstName(speaker), tn = firstName(target);
		String it = (wears.size() == 1 && !plural(wearableNames(wears).get(0)) ? "it." : "them.");
		boolean give = "give".equals(kind);
		/// Names, not pronouns: the action reads in the speaker's turn, the result in the target's
		String action = give ? sn + " gives " + tn + " " + things + "." : sn + " puts " + things + " on " + tn + ".";
		String result = (give ? sn + " gave " + tn + " " + things : sn + " put " + things + " on " + tn) + "; " + tn + " is now wearing " + it;
		return Applied.cross("${" + kind + "." + firstOid + "." + who + ":" + String.join(",", items) + "}", action, result);
	}

	/// Build an apparel in memory from embedded strings, all wearables in use, the apparel in use.
	private static BaseRecord buildApparel(BaseRecord user, String gender, List<String> items, boolean inUse) {
		long ownerId = user.get(FieldNames.FIELD_ID);
		BaseRecord apparel = ApparelUtil.constructApparelFromEmbedded(null, ownerId, gender, items.toArray(new String[0]));
		if (apparel == null) return null;
		List<BaseRecord> wears = apparel.get(OlioFieldNames.FIELD_WEARABLES);
		if (wears == null || wears.isEmpty()) return null;
		apparel.setValue(FieldNames.FIELD_NAME, "Chat outfit " + System.currentTimeMillis());
		apparel.setValue(OlioFieldNames.FIELD_IN_USE, inUse);
		for (BaseRecord w : wears) {
			w.setValue(OlioFieldNames.FIELD_IN_USE, true);
		}
		return apparel;
	}

	// ── dress ──────────────────────────────────────────────────────────

	private static Applied dress(BaseRecord user, BaseRecord actor, BaseRecord person, String op, String who) {
		boolean cross = "you".equals(who);
		if (op == null || op.isEmpty()) return Applied.fail("no layer given (up, down or a level)");
		BaseRecord active = ApparelUtil.getWearingApparel(person);
		if (active == null) return Applied.fail(firstName(person) + " has no active outfit");
		List<BaseRecord> wears = active.get(OlioFieldNames.FIELD_WEARABLES);
		if (wears == null || wears.isEmpty()) return Applied.fail(firstName(person) + "'s outfit is empty");

		int current = currentLevel(wears);
		TreeSet<Integer> levels = new TreeSet<>();
		for (BaseRecord w : wears) levels.add(levelOf(w));

		int targetLevel;
		if ("up".equalsIgnoreCase(op)) {
			Integer next = levels.higher(current);
			if (next == null) {
				String full = firstName(person) + " is already fully dressed.";
				return cross ? Applied.cross(resolvedDress(active, who, current), dressAction(actor, person, op), full) : Applied.ok(resolvedDress(active, who, current), full);
			}
			targetLevel = next;
		}
		else if ("down".equalsIgnoreCase(op)) {
			Integer next = levels.lower(current);
			targetLevel = (next == null ? WearLevelEnumType.valueOf(WearLevelEnumType.NONE) : next);
		}
		else {
			WearLevelEnumType l = WearLevelEnumType.fromToken(op);
			if (l == WearLevelEnumType.UNKNOWN) return Applied.fail("unknown layer '" + op + "'");
			targetLevel = WearLevelEnumType.valueOf(l);
		}

		List<String> on = new ArrayList<>();
		List<String> off = new ArrayList<>();
		for (BaseRecord w : wears) {
			boolean want = levelOf(w) <= targetLevel && levelOf(w) > 0;
			Boolean in = w.get(OlioFieldNames.FIELD_IN_USE);
			boolean is = (in != null && in);
			if (want != is) {
				if (!setInUse(user, w, want)) return Applied.fail("could not change " + w.get(FieldNames.FIELD_NAME));
				(want ? on : off).add((String) w.get(FieldNames.FIELD_NAME));
			}
		}
		List<String> now = new ArrayList<>();
		for (BaseRecord w : wears) {
			if (levelOf(w) <= targetLevel && levelOf(w) > 0) now.add((String) w.get(FieldNames.FIELD_NAME));
		}
		if (cross) {
			/// Third person with both names: "Stephen took off Rebecca's nipple ring; Rebecca is now wearing ..."
			String an = firstName(actor), tn = firstName(person);
			StringBuilder r = new StringBuilder();
			if (!off.isEmpty()) r.append(an).append(" took off ").append(tn).append("'s ").append(join(off));
			if (!on.isEmpty()) r.append(r.length() > 0 ? "; " : "").append(an).append(" put ").append(tn).append("'s ").append(join(on)).append(" back on");
			if (r.length() == 0) r.append(tn).append(" is already at that layer");
			r.append(now.isEmpty() ? "; " + tn + " is now wearing nothing." : "; " + tn + " is now wearing " + join(now) + ".");
			return Applied.cross(resolvedDress(active, who, targetLevel), dressAction(actor, person, op), r.toString());
		}
		String name = firstName(person);
		String pos = possessive(person);
		StringBuilder note = new StringBuilder();
		if (!off.isEmpty()) note.append(name).append(" took off ").append(pos).append(" ").append(join(off));
		if (!on.isEmpty()) note.append(note.length() > 0 ? "; " : "").append(name).append(" put on ").append(pos).append(" ").append(join(on));
		if (note.length() == 0) note.append(name).append(" is already at that layer");
		note.append(now.isEmpty() ? "; " + subject(person) + " is now wearing nothing." : "; " + subject(person) + " is now wearing " + join(now) + ".");
		return Applied.ok(resolvedDress(active, who, targetLevel), note.toString());
	}

	/// The action as the speaker's message records it, before anything has happened.
	static String dressAction(BaseRecord actor, BaseRecord target, String op) {
		String an = firstName(actor), tn = firstName(target);
		if ("up".equalsIgnoreCase(op)) return an + " helps " + tn + " put on another layer.";
		if ("down".equalsIgnoreCase(op)) return an + " takes off one of " + tn + "'s layers.";
		return an + " dresses " + tn + " to " + String.valueOf(op).toUpperCase() + ".";
	}

	// ── floating results ───────────────────────────────────────────────

	public static final String PENDING_PREFIX = "<private>[wardrobe-pending:";
	private static final Pattern PENDING = Pattern.compile("<private>\\[wardrobe-pending:(user|assistant)\\]\\s*([\\s\\S]*?)</private>");

	static String otherRole(String role) {
		return "user".equals(role) ? "assistant" : "user";
	}

	static String pendingBlock(String targetRole, String result) {
		return PENDING_PREFIX + targetRole + "] " + result + "</private>";
	}

	public static boolean hasPending(String content) {
		return content != null && content.contains(PENDING_PREFIX);
	}

	/**
	 * Remove every pending result addressed to {@code targetRole} from {@code contents} (calling {@code update}
	 * with each changed index so the caller can rewrite the stored message) and return those results as
	 * {@code <private>[wardrobe] ...</private>} notes to prefix the target's own message. "" when none.
	 */
	public static String takePending(List<String> contents, String targetRole, java.util.function.BiConsumer<Integer, String> update) {
		StringBuilder notes = new StringBuilder();
		for (int i = 0; i < contents.size(); i++) {
			String c = contents.get(i);
			if (!hasPending(c)) continue;
			Matcher m = PENDING.matcher(c);
			StringBuffer kept = new StringBuffer();
			boolean took = false;
			while (m.find()) {
				if (targetRole.equals(m.group(1))) {
					notes.append(NOTE_PREFIX).append(" ").append(m.group(2).trim()).append("</private>");
					m.appendReplacement(kept, "");
					took = true;
				}
				else {
					m.appendReplacement(kept, Matcher.quoteReplacement(m.group()));
				}
			}
			m.appendTail(kept);
			if (took) update.accept(i, kept.toString());
		}
		return notes.toString();
	}

	/**
	 * Wire copy only. Pending results for {@code generatingRole} are shown as "this is happening to you now";
	 * every other pending block is removed, so no participant reads the other's outcome in the wrong turn.
	 */
	public static String filterPendingForWire(String content, String generatingRole) {
		if (!hasPending(content)) return content;
		Matcher m = PENDING.matcher(content);
		StringBuffer sb = new StringBuffer();
		while (m.find()) {
			String r = generatingRole.equals(m.group(1))
				? NOTE_PREFIX + " This is happening to you now: " + m.group(2).trim() + "</private>"
				: "";
			m.appendReplacement(sb, Matcher.quoteReplacement(r));
		}
		m.appendTail(sb);
		return sb.toString();
	}

	private static String resolvedDress(BaseRecord apparel, String who, int level) {
		return "${dress." + apparel.get(FieldNames.FIELD_OBJECT_ID) + "." + who + "." + WearLevelEnumType.valueOf(level).toString() + "}";
	}

	static int currentLevel(List<BaseRecord> wears) {
		int max = 0;
		for (BaseRecord w : wears) {
			Boolean in = w.get(OlioFieldNames.FIELD_IN_USE);
			if (in != null && in) max = Math.max(max, levelOf(w));
		}
		return max;
	}

	private static int levelOf(BaseRecord w) {
		WearLevelEnumType l = w.getEnum(OlioFieldNames.FIELD_LEVEL);
		return (l == null ? 0 : WearLevelEnumType.valueOf(l));
	}

	// ── persistence helpers ────────────────────────────────────────────

	private static boolean setInUse(BaseRecord user, BaseRecord rec, boolean inUse) {
		BaseRecord patch = rec.copyRecord(new String[] { FieldNames.FIELD_ID, FieldNames.FIELD_OBJECT_ID, OlioFieldNames.FIELD_IN_USE });
		patch.setValue(OlioFieldNames.FIELD_IN_USE, inUse);
		if (IOSystem.getActiveContext().getAccessPoint().update(user, patch) == null) {
			logger.warn("Failed to set inuse=" + inUse + " on " + rec.getSchema() + " #" + rec.get(FieldNames.FIELD_ID));
			return false;
		}
		rec.setValue(OlioFieldNames.FIELD_IN_USE, inUse);
		return true;
	}

	/// Fresh, fully planned person (store.apparel.wearables), read as the user, never from cache.
	static BaseRecord loadPerson(BaseRecord user, BaseRecord person) {
		String oid = person.get(FieldNames.FIELD_OBJECT_ID);
		if (oid == null) return null;
		Query q = QueryUtil.createQuery(OlioModelNames.MODEL_CHAR_PERSON, FieldNames.FIELD_OBJECT_ID, oid);
		OlioUtil.planMost(q);
		q.setCache(false);
		return IOSystem.getActiveContext().getAccessPoint().find(user, q);
	}

	/// Parents that embed these records (chatConfig.systemCharacter, charPerson.store) are cached by their own
	/// identity only, so a per-record clear would leave them stale (model-api.md, nested cache invalidation).
	static void clearCaches() {
		for (String m : MODELS_TO_CLEAR) {
			CacheUtil.clearCacheByModel(m);
		}
	}

	// ── text helpers ───────────────────────────────────────────────────

	private static boolean sameId(BaseRecord a, BaseRecord b) {
		Object x = a.get(FieldNames.FIELD_ID);
		Object y = b.get(FieldNames.FIELD_ID);
		return x != null && x.equals(y);
	}

	private static List<String> wearableNames(List<BaseRecord> wears) {
		Set<String> names = new LinkedHashSet<>();
		if (wears != null) for (BaseRecord w : wears) {
			String n = w.get(FieldNames.FIELD_NAME);
			if (n != null) names.add(n);
		}
		return new ArrayList<>(names);
	}

	static String join(List<String> items) {
		if (items == null || items.isEmpty()) return "";
		if (items.size() == 1) return items.get(0);
		if (items.size() == 2) return items.get(0) + " and " + items.get(1);
		return String.join(", ", items.subList(0, items.size() - 1)) + " and " + items.get(items.size() - 1);
	}

	private static String articled(List<String> items) {
		List<String> a = items.stream().map(i -> plural(i) ? i : (i.matches("(?i)^[aeiou].*") ? "an " : "a ") + i).collect(Collectors.toList());
		return join(a);
	}

	/// Crude plural check for note wording ("shoes", "earrings", "pants"); not for "dress" / "glass".
	static boolean plural(String name) {
		String n = name.toLowerCase().trim();
		return n.endsWith("s") && !n.endsWith("ss");
	}

		static String genderCode(String gender) {
		if (gender == null || gender.isEmpty()) return "u";
		char c = Character.toLowerCase(gender.charAt(0));
		return (c == 'm' || c == 'f') ? String.valueOf(c) : "u";
	}

	private static String firstName(BaseRecord p) {
		String n = p.get(FieldNames.FIELD_FIRST_NAME);
		if (n == null || n.isEmpty()) n = p.get(FieldNames.FIELD_NAME);
		return (n == null ? "Someone" : n);
	}

	private static String possessive(BaseRecord p) {
		String g = genderCode((String) p.get(FieldNames.FIELD_GENDER));
		return "m".equals(g) ? "his" : ("f".equals(g) ? "her" : "their");
	}

	private static String subject(BaseRecord p) {
		String g = genderCode((String) p.get(FieldNames.FIELD_GENDER));
		return "m".equals(g) ? "he" : ("f".equals(g) ? "she" : "they");
	}
}
