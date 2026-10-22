package org.cote.accountmanager.olio.illustration;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.cote.accountmanager.olio.NarrativeUtil;
import org.cote.accountmanager.util.DocumentUtil;

/// Text helpers shared by the prompt compilers. DB-free by design (V3 §14): nothing here touches
/// IOSystem, AccessPoint or a record - the compilers work on the plan alone so they can be unit-tested
/// without a backend.
final class CompilerSupport {

	static final String SEP = ", ";

	private CompilerSupport() { }

	/// Smart-quote normalised and trimmed, or null when not meaningful (null, blank, "null", "n/a", ...).
	static String clean(String s) {
		if(s == null) {
			return null;
		}
		String t = DocumentUtil.replaceSmartQuotes(s).trim();
		return NarrativeUtil.isMeaningful(t) ? t : null;
	}

	/// a + ", " + b, or whichever one is non-null, or null.
	static String joinMeaningful(String a, String b) {
		if(a == null) {
			return b;
		}
		if(b == null) {
			return a;
		}
		return a + SEP + b;
	}

	/// The plan config's "loras" list as strings, blanks dropped.
	static List<String> loras(Map<String, Object> config) {
		List<String> out = new ArrayList<>();
		if(config == null) {
			return out;
		}
		Object o = config.get("loras");
		if(o instanceof List) {
			for(Object e : (List<?>) o) {
				if(e != null && !String.valueOf(e).isBlank()) {
					out.add(String.valueOf(e));
				}
			}
		}
		return out;
	}

	/// Style exclusions then the config negative, comma-split, de-duplicated case-insensitively, order kept.
	static String negative(String exclusions, String configNegative) {
		Map<String, String> seen = new LinkedHashMap<>();
		for(String src : new String[] {exclusions, configNegative}) {
			if(src == null) {
				continue;
			}
			for(String term : src.split(",")) {
				String t = clean(term);
				if(t == null) {
					continue;
				}
				seen.putIfAbsent(t.toLowerCase(), t);
			}
		}
		return String.join(SEP, seen.values());
	}

	/// The negative for a plan: its style exclusions and its config negativePrompt.
	static String negative(RenderPlan.Style style, Map<String, Object> config) {
		return negative(style == null ? null : style.getExclusions(), configString(config, "negativePrompt"));
	}

	static String configString(Map<String, Object> config, String key) {
		if(config == null) {
			return null;
		}
		Object o = config.get(key);
		return (o == null ? null : String.valueOf(o));
	}

	/// "first", "second", ... "tenth", then "11th"-style past ten. Shared by the compilers' reference and
	/// person ordinals.
	static String ordinal(int n) {
		switch(n) {
			case 1: return "first";
			case 2: return "second";
			case 3: return "third";
			case 4: return "fourth";
			case 5: return "fifth";
			case 6: return "sixth";
			case 7: return "seventh";
			case 8: return "eighth";
			case 9: return "ninth";
			case 10: return "tenth";
			default: return n + "th";
		}
	}

	/// The budget-drop message shape shared by the compilers: "<group> dropped: a, b (N tokens over budget B)".
	static void recordDrop(List<RenderPlan.Degradation> out, String what, List<String> items, int over, int budget, String stage) {
		if(out == null) {
			return;
		}
		String detail = what;
		if(items != null && !items.isEmpty()) {
			detail = what + ": " + String.join(SEP, items);
		}
		out.add(new RenderPlan.Degradation(RenderPlan.Degradation.PROMPT_TRUNCATED, detail + " (" + over + " tokens over budget " + budget + ")", null, stage));
	}
}
