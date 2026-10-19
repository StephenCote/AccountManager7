package org.cote.accountmanager.olio.picturebook;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

import org.cote.accountmanager.olio.NarrativeUtil;
import org.cote.accountmanager.schema.type.PbActorRoleEnumType;
import org.cote.accountmanager.schema.type.PbShotTypeEnumType;
import org.cote.accountmanager.schema.type.PbViewpointEnumType;

/**
 * Pure layout defaults for PictureBook V3 compositions (design §8, §9, §28, §29).
 *
 * <p>The compose prompt (§9) says where a figure sits in coarse words ("left foreground",
 * "background", "far right"), never in coordinates. This class turns those words into the normalized
 * box an {@code olio.pb.sceneActor} / {@code olio.pb.sceneElement} row carries ({@code x, y, w, h} in
 * 0-1 image space, {@code depth} 0 = front) and owns the §29 camera defaults (MEDIUM / EYE_LEVEL, WIDE
 * when a composition has more than three actors). No IO, no records, no reflection; every method
 * accepts null and never throws.
 *
 * <p>Two independent defaults, which is how the P4-2 row's "unknown word -> centre + MEDIUM" is read:
 * a placement that is absent or not understood lands in the CENTRE of whatever shot type was
 * declared, and a shot type that is null or UNKNOWN uses the MEDIUM template. They are not coupled -
 * a MEDIUM-sized box inside a WIDE composition would be wrong.
 *
 * <p>{@link Plane} is a depth band. It is unrelated to {@code RenderPlan.ROLE_FOREGROUND} /
 * {@code ROLE_BACKGROUND}, which name reference eligibility; never map one onto the other. The
 * actor count that selects WIDE is the number of {@code sceneActor} rows, not the sum of CROWD
 * {@code count}. Vertical words (top, bottom, upper, lower) are not interpreted; "stage left" is camera
 * left; "centre left" is CENTRE (first word wins). Depth ties inside one plane are expected - ordering
 * figures is {@code sceneActor.priority}'s job, not this class's. The template numbers are first
 * guesses to be tuned from the review board; output is rounded to four decimals so a re-default
 * produces identical bits for the watched-field hash.
 */
public final class PbLayoutUtil {

	private PbLayoutUtil() {}

	/** Horizontal third of the frame a figure occupies. */
	public enum Horizontal { LEFT, CENTRE, RIGHT }

	/** Depth band; {@link #depth()} is the {@code depth} column value (0 = front). */
	public enum Plane {
		FOREGROUND(0), MIDGROUND(1), BACKGROUND(2);

		private final int depth;

		Plane(int depth) { this.depth = depth; }

		public int depth() { return depth; }

		/** This plane moved {@code steps} further back, clamped to {@link #BACKGROUND}. */
		Plane further(int steps) {
			Plane[] all = values();
			return all[Math.min(all.length - 1, Math.max(0, ordinal() + steps))];
		}
	}

	/** A normalized box plus how it was derived. Immutable. */
	public static final class Placement {
		private final double x;
		private final double y;
		private final double w;
		private final double h;
		private final int depth;
		private final Horizontal horizontal;
		private final Plane plane;
		private final PbShotTypeEnumType shotType;
		private final boolean recognized;

		Placement(double x, double y, double w, double h, int depth, Horizontal horizontal, Plane plane, PbShotTypeEnumType shotType, boolean recognized) {
			this.x = x;
			this.y = y;
			this.w = w;
			this.h = h;
			this.depth = depth;
			this.horizontal = horizontal;
			this.plane = plane;
			this.shotType = shotType;
			this.recognized = recognized;
		}

		public double x() { return x; }
		public double y() { return y; }
		public double w() { return w; }
		public double h() { return h; }
		/** {@code sceneActor.depth} / {@code sceneElement.depth}: 0 = front. */
		public int depth() { return depth; }
		public Horizontal horizontal() { return horizontal; }
		public Plane plane() { return plane; }
		/** The template actually used - MEDIUM when the declared shot type was null or UNKNOWN. */
		public PbShotTypeEnumType shotType() { return shotType; }
		/**
		 * True when placement words were understood and shaped this box. False when the text was absent,
		 * a placeholder or not understood (the box is then the centre default), and always false from
		 * {@link PbLayoutUtil#defaultPlacement}. A composer that supplied meaningful text and reads false
		 * must surface it rather than drop it silently (design §19).
		 */
		public boolean recognized() { return recognized; }

		@Override
		public String toString() {
			return String.format(Locale.ROOT, "%s %s %s [x=%.4f y=%.4f w=%.4f h=%.4f depth=%d]%s", shotType, horizontal, plane, x, y, w, h, depth, recognized ? "" : " (default)");
		}
	}

	/** Per-shot-type box sizes: centre-x per {@link Horizontal} ordinal, then w / h / y per {@link Plane} ordinal. */
	static final class Template {
		final double[] centreX;
		final double[] w;
		final double[] h;
		final double[] y;

		Template(double[] centreX, double[] w, double[] h, double[] y) {
			this.centreX = centreX;
			this.w = w;
			this.h = h;
			this.y = y;
		}
	}

	static final Template T_CLOSE_UP = new Template(
		new double[] {0.32, 0.50, 0.68},
		new double[] {0.60, 0.40, 0.30},
		new double[] {0.90, 0.60, 0.40},
		new double[] {0.10, 0.15, 0.10});

	static final Template T_MEDIUM = new Template(
		new double[] {0.25, 0.50, 0.75},
		new double[] {0.40, 0.30, 0.20},
		new double[] {0.80, 0.55, 0.35},
		new double[] {0.20, 0.15, 0.10});

	static final Template T_FULL_BODY = new Template(
		new double[] {0.25, 0.50, 0.75},
		new double[] {0.30, 0.22, 0.15},
		new double[] {0.90, 0.60, 0.35},
		new double[] {0.10, 0.15, 0.12});

	static final Template T_WIDE = new Template(
		new double[] {0.18, 0.50, 0.82},
		new double[] {0.22, 0.15, 0.10},
		new double[] {0.60, 0.40, 0.22},
		new double[] {0.38, 0.30, 0.25});

	static final Template T_ESTABLISHING = new Template(
		new double[] {0.15, 0.50, 0.85},
		new double[] {0.15, 0.10, 0.07},
		new double[] {0.40, 0.26, 0.15},
		new double[] {0.55, 0.45, 0.40});

	/** How far "far left" / "far right" push the box centre outward before clamping. */
	static final double FAR_NUDGE = 0.08;

	/** Actor count above which the default shot type becomes WIDE (design §29). */
	static final int WIDE_ACTOR_THRESHOLD = 3;

	private static final Horizontal[] SPREAD = { Horizontal.CENTRE, Horizontal.LEFT, Horizontal.RIGHT };

	private static final Pattern NON_WORD = Pattern.compile("[^a-z0-9]+");

	/** Filler the compose prompt and humans put around placement and camera words. */
	static final Set<String> STOP_WORDS = Set.of(
		"in", "on", "the", "of", "at", "to", "a", "an", "and", "is", "side", "hand", "frame", "image", "picture",
		"shot", "stage", "position", "positioned", "placed", "standing", "view", "angle");

	/** Two-word phrases folded to one token after stop-word removal; pairs of (phrase, token). */
	private static final String[] COMPOUNDS = {
		"far left", "farleft", "extreme left", "farleft", "far right", "farright", "extreme right", "farright",
		"middle distance", "midground", "mid distance", "midground", "middle ground", "midground", "mid ground", "midground",
		"fore ground", "foreground", "back ground", "background", "far away", "far", "far back", "background", "far behind", "background",
		"close up", "closeup", "full body", "fullbody", "full length", "fullbody",
		"eye level", "eyelevel", "bird eye", "birdseye", "birds eye", "birdseye", "top down", "birdseye", "over shoulder", "overshoulder"
	};

	/**
	 * Box for a placement phrase under a shot type, the plane defaulting by role when the phrase names
	 * none. Garbage, null and placeholders ("none", "n/a") give the CENTRE of the role's default plane
	 * with {@link Placement#recognized()} false. Never throws.
	 */
	public static Placement place(String placement, PbShotTypeEnumType shotType, PbActorRoleEnumType role) {
		Parsed p = parse(placement);
		Horizontal h = (p.horizontal != null) ? p.horizontal : Horizontal.CENTRE;
		Plane pl = (p.plane != null) ? p.plane : defaultPlane(role);
		return build(shotType, h, pl, p.far, p.recognized());
	}

	/** {@link #place(String, PbShotTypeEnumType, PbActorRoleEnumType)} for elements and other role-less figures: the plane defaults to MIDGROUND. */
	public static Placement place(String placement, PbShotTypeEnumType shotType) {
		return place(placement, shotType, null);
	}

	/**
	 * Box for the {@code ordinal}-th of {@code actorCount} figures when no placement words were given.
	 * Design §28 step 4 asks this class to default migrated placements; the spread itself is not in the
	 * design and is this class's choice: one figure is centred; two sit left and right; three or more go
	 * centre, left, right and then repeat one plane further back per full cycle, clamped at BACKGROUND. A count below one is
	 * treated as one and the ordinal is clamped into range. {@link Placement#recognized()} is false.
	 */
	public static Placement defaultPlacement(int ordinal, int actorCount, PbShotTypeEnumType shotType, PbActorRoleEnumType role) {
		int count = Math.max(1, actorCount);
		int ord = Math.min(Math.max(0, ordinal), count - 1);
		Horizontal h;
		int cycle = 0;
		if (count == 1) {
			h = Horizontal.CENTRE;
		} else if (count == 2) {
			h = (ord == 0) ? Horizontal.LEFT : Horizontal.RIGHT;
		} else {
			h = SPREAD[ord % SPREAD.length];
			cycle = ord / SPREAD.length;
		}
		return build(shotType, h, defaultPlane(role).further(cycle), false, false);
	}

	/** Plane a role sits in when the placement names none: HERO / SUPPORTING front, EXTRA / CROWD back, everything else (including null) MIDGROUND. */
	public static Plane defaultPlane(PbActorRoleEnumType role) {
		if (role == null) return Plane.MIDGROUND;
		switch (role) {
			case HERO:
			case SUPPORTING:
				return Plane.FOREGROUND;
			case EXTRA:
			case CROWD:
				return Plane.BACKGROUND;
			default:
				return Plane.MIDGROUND;
		}
	}

	/** Design §29: MEDIUM, or WIDE when there are more than three actors. */
	public static PbShotTypeEnumType defaultShotType(int actorCount) {
		return (actorCount > WIDE_ACTOR_THRESHOLD) ? PbShotTypeEnumType.WIDE : PbShotTypeEnumType.MEDIUM;
	}

	/** Design §29: EYE_LEVEL. */
	public static PbViewpointEnumType defaultViewpoint() {
		return PbViewpointEnumType.EYE_LEVEL;
	}

	/** The declared shot type, or {@link #defaultShotType} when it is null or UNKNOWN. */
	public static PbShotTypeEnumType resolveShotType(PbShotTypeEnumType declared, int actorCount) {
		return (declared == null || declared == PbShotTypeEnumType.UNKNOWN) ? defaultShotType(actorCount) : declared;
	}

	/** The declared viewpoint, or {@link #defaultViewpoint} when it is null or UNKNOWN. */
	public static PbViewpointEnumType resolveViewpoint(PbViewpointEnumType declared) {
		return (declared == null || declared == PbViewpointEnumType.UNKNOWN) ? defaultViewpoint() : declared;
	}

	/**
	 * Shot type from a word the prompt or a human wrote: the enum names in any case, the §9 wire form
	 * ("wide", "close_up"), and the obvious phrases ("close-up", "wide shot", "full length"). Anything
	 * else, null included, is UNKNOWN. Precedence when several match: close-up, establishing, wide,
	 * full body, medium. Never calls {@code fromValue}, which throws.
	 */
	public static PbShotTypeEnumType parseShotType(String text) {
		List<String> t = tokens(text);
		if (t.contains("closeup") || t.contains("close")) return PbShotTypeEnumType.CLOSE_UP;
		if (t.contains("establishing")) return PbShotTypeEnumType.ESTABLISHING;
		if (t.contains("wide")) return PbShotTypeEnumType.WIDE;
		if (t.contains("fullbody") || t.contains("full")) return PbShotTypeEnumType.FULL_BODY;
		if (t.contains("medium") || t.contains("mid")) return PbShotTypeEnumType.MEDIUM;
		return PbShotTypeEnumType.UNKNOWN;
	}

	/**
	 * Viewpoint from a word: the enum names in any case, the §9 wire form ("eye_level"), and the obvious
	 * phrases ("eye level", "bird's eye", "over the shoulder", "high angle"). Anything else, null
	 * included, is UNKNOWN. Never calls {@code fromValue}, which throws.
	 */
	public static PbViewpointEnumType parseViewpoint(String text) {
		List<String> t = tokens(text);
		if (t.contains("birdseye") || t.contains("aerial") || t.contains("overhead")) return PbViewpointEnumType.BIRDS_EYE;
		if (t.contains("overshoulder") || t.contains("ots")) return PbViewpointEnumType.OVER_SHOULDER;
		if (t.contains("eyelevel")) return PbViewpointEnumType.EYE_LEVEL;
		if (t.contains("high") || t.contains("elevated")) return PbViewpointEnumType.HIGH;
		if (t.contains("low")) return PbViewpointEnumType.LOW;
		return PbViewpointEnumType.UNKNOWN;
	}

	/** The template for a shot type; MEDIUM for null or UNKNOWN. */
	static Template template(PbShotTypeEnumType shotType) {
		switch (effectiveShotType(shotType)) {
			case CLOSE_UP: return T_CLOSE_UP;
			case FULL_BODY: return T_FULL_BODY;
			case WIDE: return T_WIDE;
			case ESTABLISHING: return T_ESTABLISHING;
			default: return T_MEDIUM;
		}
	}

	static PbShotTypeEnumType effectiveShotType(PbShotTypeEnumType shotType) {
		return (shotType == null || shotType == PbShotTypeEnumType.UNKNOWN) ? PbShotTypeEnumType.MEDIUM : shotType;
	}

	/**
	 * Lower-cased words of {@code text} with punctuation removed, stop words and single characters
	 * dropped and two-word phrases folded ({@code "far left"} -> {@code farleft}); empty for null, blank
	 * and placeholder text per {@link NarrativeUtil#isMeaningful}.
	 */
	static List<String> tokens(String text) {
		if (!NarrativeUtil.isMeaningful(text)) return List.of();
		List<String> raw = new ArrayList<>();
		for (String t : NON_WORD.split(text.toLowerCase(Locale.ROOT).trim())) {
			if (t.length() < 2 || STOP_WORDS.contains(t)) continue;
			raw.add(t);
		}
		String joined = " " + String.join(" ", raw) + " ";
		for (int i = 0; i < COMPOUNDS.length; i += 2) {
			joined = joined.replace(" " + COMPOUNDS[i] + " ", " " + COMPOUNDS[i + 1] + " ");
		}
		List<String> out = new ArrayList<>();
		for (String t : joined.trim().split(" ")) {
			if (!t.isEmpty()) out.add(t);
		}
		return out;
	}

	static final class Parsed {
		Horizontal horizontal;
		Plane plane;
		boolean far;

		boolean recognized() { return horizontal != null || plane != null; }
	}

	/** First horizontal word and first plane word in the phrase; later ones are ignored. */
	static Parsed parse(String placement) {
		Parsed p = new Parsed();
		for (String t : tokens(placement)) {
			if (p.horizontal == null) {
				Horizontal h = horizontal(t);
				if (h != null) {
					p.horizontal = h;
					p.far = t.startsWith("far");
					continue;
				}
			}
			if (p.plane == null) {
				Plane pl = plane(t);
				if (pl != null) p.plane = pl;
			}
		}
		return p;
	}

	private static Horizontal horizontal(String t) {
		switch (t) {
			case "left": case "farleft":
				return Horizontal.LEFT;
			case "right": case "farright":
				return Horizontal.RIGHT;
			case "centre": case "center": case "central": case "centred": case "centered": case "middle": case "mid":
				return Horizontal.CENTRE;
			default:
				return null;
		}
	}

	private static Plane plane(String t) {
		switch (t) {
			case "foreground": case "fore": case "front": case "near": case "nearest": case "close": case "closest": case "forward":
				return Plane.FOREGROUND;
			case "midground":
				return Plane.MIDGROUND;
			case "background": case "back": case "rear": case "far": case "distant": case "distance": case "behind": case "beyond":
				return Plane.BACKGROUND;
			default:
				return null;
		}
	}

	private static Placement build(PbShotTypeEnumType shotType, Horizontal h, Plane pl, boolean far, boolean recognized) {
		PbShotTypeEnumType eff = effectiveShotType(shotType);
		Template t = template(eff);
		int hi = h.ordinal();
		int pi = pl.ordinal();
		double w = t.w[pi];
		double hh = t.h[pi];
		double cx = t.centreX[hi];
		if (far && h == Horizontal.LEFT) cx -= FAR_NUDGE;
		else if (far && h == Horizontal.RIGHT) cx += FAR_NUDGE;
		double x = clamp(cx - w / 2d, 0d, 1d - w);
		double y = clamp(t.y[pi], 0d, 1d - hh);
		return new Placement(round4(x), round4(y), round4(w), round4(hh), pl.depth(), h, pl, eff, recognized);
	}

	private static double clamp(double v, double lo, double hi) {
		return Math.max(lo, Math.min(hi, v));
	}

	private static double round4(double v) {
		return Math.round(v * 10000d) / 10000d;
	}
}
