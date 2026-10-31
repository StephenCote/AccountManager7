package org.cote.accountmanager.olio.illustration;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.cote.accountmanager.util.JSONUtil;

/// The verdict a {@link QaProvider} returns for one rendered image against its {@link RenderPlan}
/// (PictureBook V3 design §17; ledger §6.C 220 b). Pure value object: no DB, no SD, no LLM, nothing
/// from olio.picturebook. Persisted as the JSON text of a {@code qa} artifact on the image's node
/// (220 a) and read back with {@link #fromJson}.
///
/// A report never blocks or deletes anything (§17): FAIL orders an attempt's variants for auto-approve
/// and flags the image for review; WARN flags only; UNAVAILABLE means no check could run (the provider
/// was down or the plan gave it nothing to check) and is treated as "unchecked", never as a verdict.
public final class QaReport {

	/// Per-check and rolled-up result. Ordered by severity for the rollup; never compare by ordinal
	/// elsewhere.
	public enum Result {
		PASS, WARN, FAIL, UNAVAILABLE
	}

	/// Check kinds (220 b). A provider reports the kinds it can evaluate and omits the rest.
	public static final String KIND_PERSON_COUNT = "PERSON_COUNT";
	/// One per referenced foreground actor of the plan; absent = FAIL (the render dropped a character).
	public static final String KIND_ACTOR_PRESENT = "ACTOR_PRESENT";
	public static final String KIND_IDENTITY = "IDENTITY";
	public static final String KIND_OUTFIT = "OUTFIT";
	public static final String KIND_LOCATION = "LOCATION";
	public static final String KIND_COMPOSITION = "COMPOSITION";
	public static final Set<String> KINDS = Set.of(KIND_PERSON_COUNT, KIND_ACTOR_PRESENT, KIND_IDENTITY, KIND_OUTFIT,
		KIND_LOCATION, KIND_COMPOSITION);

	/// One check. {@code actorId} is set on ACTOR_PRESENT / IDENTITY / OUTFIT checks that concern one actor
	/// of the plan, so a consumer can mark the actor rather than only the image; null otherwise.
	public static final class Check {
		private final String kind;
		private final Result result;
		private final String expected;
		private final String observed;
		private final String detail;
		private final String actorId;

		public Check(String kind, Result result, String expected, String observed, String detail) {
			this(kind, result, expected, observed, detail, null);
		}

		public Check(String kind, Result result, String expected, String observed, String detail, String actorId) {
			if(kind == null || kind.isBlank()) {
				throw new IllegalArgumentException("A QA check needs a kind");
			}
			this.kind = kind;
			this.result = (result == null ? Result.UNAVAILABLE : result);
			this.expected = expected;
			this.observed = observed;
			this.detail = detail;
			this.actorId = actorId;
		}

		public String getKind() {
			return kind;
		}

		public Result getResult() {
			return result;
		}

		public String getExpected() {
			return expected;
		}

		public String getObserved() {
			return observed;
		}

		public String getDetail() {
			return detail;
		}

		public String getActorId() {
			return actorId;
		}

		Map<String, Object> toMap() {
			Map<String, Object> m = new LinkedHashMap<>();
			m.put("kind", kind);
			m.put("result", result.name());
			m.put("expected", expected);
			m.put("observed", observed);
			m.put("detail", detail);
			m.put("actorId", actorId);
			return m;
		}

		static Check fromMap(Map<?, ?> m) {
			String kind = RenderPlan.str(m, "kind");
			if(kind == null || kind.isBlank()) {
				return null;
			}
			return new Check(kind, parseResult(RenderPlan.str(m, "result")), RenderPlan.str(m, "expected"),
				RenderPlan.str(m, "observed"), RenderPlan.str(m, "detail"), RenderPlan.str(m, "actorId"));
		}

		@Override
		public String toString() {
			return kind + (actorId != null ? "[" + actorId + "]" : "") + "=" + result
				+ (expected != null || observed != null ? " (expected " + expected + ", observed " + observed + ")" : "")
				+ (detail != null ? ": " + detail : "");
		}
	}

	private final Result result;
	private final String provider;
	private final String model;
	private final List<Check> checks;
	private final long durationMs;
	private final String note;
	private final String imageArtifactObjectId;

	private QaReport(Builder b) {
		this.provider = b.provider;
		this.model = b.model;
		this.checks = Collections.unmodifiableList(new ArrayList<>(b.checks));
		this.durationMs = b.durationMs;
		this.note = b.note;
		this.imageArtifactObjectId = b.imageArtifactObjectId;
		this.result = (b.result != null ? b.result : rollup(this.checks));
	}

	/// The rolled-up verdict: {@link #rollup} of the checks unless the provider set one explicitly (an
	/// UNAVAILABLE report with a note and no checks, for instance).
	public Result getResult() {
		return result;
	}

	/// {@link QaProvider#name()} of the provider that produced the report.
	public String getProvider() {
		return provider;
	}

	/// The model the provider used (a VLM name, a YOLO weights file); null when it has none.
	public String getModel() {
		return model;
	}

	public List<Check> getChecks() {
		return checks;
	}

	/// How long the check took, for display only - never asserted (§17, §21).
	public long getDurationMs() {
		return durationMs;
	}

	public String getNote() {
		return note;
	}

	/// The objectId of the image artifact this report judged (220 a); set when the report is persisted.
	public String getImageArtifactObjectId() {
		return imageArtifactObjectId;
	}

	/// The checks of one kind, in report order.
	public List<Check> checksOf(String kind) {
		List<Check> out = new ArrayList<>();
		for(Check c : checks) {
			if(c.kind.equals(kind)) {
				out.add(c);
			}
		}
		return out;
	}

	/// This report with {@code imageArtifactObjectId} set; everything else copied. The explicit result is
	/// carried over too, so a provider's UNAVAILABLE stays UNAVAILABLE.
	public QaReport forImage(String imageArtifactObjectId) {
		Builder b = builder(provider).model(model).durationMs(durationMs).note(note).result(result)
			.imageArtifactObjectId(imageArtifactObjectId);
		b.checks.addAll(checks);
		return new QaReport(b);
	}

	/// 220 b: FAIL when any check failed (a referenced foreground actor absent, fewer people than the plan's
	/// foreground actors), else WARN when any check warned, UNAVAILABLE when nothing could be checked (no
	/// checks at all, or every check UNAVAILABLE), else PASS. A check's own result is decided by the
	/// builder's semantic helpers; this only combines them.
	public static Result rollup(List<Check> checks) {
		if(checks == null || checks.isEmpty()) {
			return Result.UNAVAILABLE;
		}
		boolean warn = false;
		boolean any = false;
		for(Check c : checks) {
			if(c == null) {
				continue;
			}
			switch(c.result) {
				case FAIL:
					return Result.FAIL;
				case WARN:
					warn = true;
					any = true;
					break;
				case PASS:
					any = true;
					break;
				default:
					break;
			}
		}
		if(!any) {
			return Result.UNAVAILABLE;
		}
		return (warn ? Result.WARN : Result.PASS);
	}

	public static Result parseResult(String s) {
		if(s == null) {
			return Result.UNAVAILABLE;
		}
		for(Result r : Result.values()) {
			if(r.name().equalsIgnoreCase(s.trim())) {
				return r;
			}
		}
		return Result.UNAVAILABLE;
	}

	/// An UNAVAILABLE report with no checks: the provider could not run (down, no model, threw). The hook
	/// records it as a {@code QA_UNAVAILABLE} degradation (220 g), never as a failed render.
	public static QaReport unavailable(String provider, String model, String note, long durationMs) {
		return builder(provider).model(model).note(note).durationMs(durationMs).result(Result.UNAVAILABLE).build();
	}

	public Map<String, Object> toMap() {
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("result", result.name());
		m.put("provider", provider);
		m.put("model", model);
		List<Map<String, Object>> cl = new ArrayList<>();
		for(Check c : checks) {
			cl.add(c.toMap());
		}
		m.put("checks", cl);
		m.put("durationMs", durationMs);
		m.put("note", note);
		m.put("imageArtifactObjectId", imageArtifactObjectId);
		return m;
	}

	/// Compact JSON of {@link #toMap()}; the form persisted as the {@code qa} artifact's text.
	public String toJson() {
		return JSONUtil.exportObject(toMap(), null, true, false);
	}

	public static QaReport fromJson(String json) {
		if(json == null || json.isBlank()) {
			return null;
		}
		@SuppressWarnings("unchecked")
		Map<String, Object> m = JSONUtil.importObject(json, LinkedHashMap.class);
		if(m == null) {
			return null;
		}
		return fromMap(m);
	}

	public static QaReport fromMap(Map<?, ?> m) {
		if(m == null) {
			return null;
		}
		Builder b = builder(RenderPlan.str(m, "provider"));
		b.model(RenderPlan.str(m, "model"));
		b.note(RenderPlan.str(m, "note"));
		b.imageArtifactObjectId(RenderPlan.str(m, "imageArtifactObjectId"));
		Object d = m.get("durationMs");
		b.durationMs(d instanceof Number ? ((Number) d).longValue() : 0L);
		for(Map<?, ?> cm : RenderPlan.listOfMaps(m, "checks")) {
			Check c = Check.fromMap(cm);
			if(c != null) {
				b.checks.add(c);
			}
		}
		/// The persisted result is authoritative: a provider's explicit UNAVAILABLE (no checks) and the
		/// rollup of its checks both round-trip as written.
		String r = RenderPlan.str(m, "result");
		if(r != null) {
			b.result(parseResult(r));
		}
		return new QaReport(b);
	}

	@Override
	public String toString() {
		return "QaReport{" + result + " by " + provider + (model != null ? "/" + model : "") + ", " + checks.size()
			+ " check(s)" + (note != null ? ", " + note : "") + "}";
	}

	public static Builder builder(String provider) {
		return new Builder(provider);
	}

	/// Builds a report. The semantic helpers decide each check's result from what the provider observed,
	/// so every provider applies the same thresholds (220 b): a shortfall in people or a missing referenced
	/// foreground actor is FAIL; any other mismatch is WARN; nothing observed is UNAVAILABLE.
	public static final class Builder {
		private final String provider;
		private String model;
		private long durationMs;
		private String note;
		private String imageArtifactObjectId;
		private Result result;
		private final List<Check> checks = new ArrayList<>();

		private Builder(String provider) {
			this.provider = provider;
		}

		public Builder model(String model) {
			this.model = model;
			return this;
		}

		public Builder durationMs(long durationMs) {
			this.durationMs = (durationMs < 0L ? 0L : durationMs);
			return this;
		}

		public Builder note(String note) {
			this.note = note;
			return this;
		}

		public Builder imageArtifactObjectId(String imageArtifactObjectId) {
			this.imageArtifactObjectId = imageArtifactObjectId;
			return this;
		}

		/// Force the rolled-up result instead of {@link QaReport#rollup}. Reserved for UNAVAILABLE reports
		/// and for round-tripping a persisted report; a provider with checks should let the rollup decide.
		public Builder result(Result result) {
			this.result = result;
			return this;
		}

		public Builder check(Check check) {
			if(check != null) {
				checks.add(check);
			}
			return this;
		}

		/// PERSON_COUNT: fewer people than the plan's foreground actors is FAIL (someone was dropped), more is
		/// WARN (an extra figure), equal is PASS; a null observation is UNAVAILABLE.
		public Builder personCount(int expected, Integer observed, String detail) {
			Result r;
			if(observed == null) {
				r = Result.UNAVAILABLE;
			}
			else if(observed.intValue() < expected) {
				r = Result.FAIL;
			}
			else if(observed.intValue() > expected) {
				r = Result.WARN;
			}
			else {
				r = Result.PASS;
			}
			return check(new Check(KIND_PERSON_COUNT, r, String.valueOf(expected),
				(observed == null ? null : String.valueOf(observed)), detail));
		}

		/// ACTOR_PRESENT for one referenced foreground actor: absent is FAIL; a null observation is UNAVAILABLE.
		public Builder actorPresent(String actorId, String name, Boolean present, String detail) {
			Result r = (present == null ? Result.UNAVAILABLE : (present.booleanValue() ? Result.PASS : Result.FAIL));
			return check(new Check(KIND_ACTOR_PRESENT, r, name, (present == null ? null : (present.booleanValue() ? "present" : "absent")),
				detail, actorId));
		}

		/// IDENTITY / OUTFIT / LOCATION / COMPOSITION: a mismatch is WARN, never FAIL (220 b); a null
		/// observation is UNAVAILABLE.
		public Builder soft(String kind, String actorId, String expected, String observed, Boolean matches, String detail) {
			if(kind == null || KIND_PERSON_COUNT.equals(kind) || KIND_ACTOR_PRESENT.equals(kind)) {
				throw new IllegalArgumentException("soft() takes IDENTITY, OUTFIT, LOCATION or COMPOSITION; got " + kind);
			}
			Result r = (matches == null ? Result.UNAVAILABLE : (matches.booleanValue() ? Result.PASS : Result.WARN));
			return check(new Check(kind, r, expected, observed, detail, actorId));
		}

		public QaReport build() {
			return new QaReport(this);
		}
	}
}
