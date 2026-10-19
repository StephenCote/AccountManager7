package org.cote.accountmanager.olio.picturebook;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

import org.cote.accountmanager.olio.picturebook.PbLayoutUtil.Horizontal;
import org.cote.accountmanager.olio.picturebook.PbLayoutUtil.Parsed;
import org.cote.accountmanager.olio.picturebook.PbLayoutUtil.Placement;
import org.cote.accountmanager.olio.picturebook.PbLayoutUtil.Plane;
import org.cote.accountmanager.olio.picturebook.PbLayoutUtil.Template;
import org.cote.accountmanager.schema.type.PbActorRoleEnumType;
import org.cote.accountmanager.schema.type.PbShotTypeEnumType;
import org.cote.accountmanager.schema.type.PbViewpointEnumType;
import org.junit.Test;

/**
 * Pure tests for {@link PbLayoutUtil} (P4-2, design §8/§9/§28/§29): placement words to a normalized
 * box, role / camera defaults, the §28 actor spread and the §9 camera-word parsers. No DB, no
 * IOSystem, no mocks - every assertion calls the real static method. Expected boxes are computed
 * from the template arrays ({@link PbLayoutUtil#T_MEDIUM} etc.) so a retune only breaks the
 * assertions that encode a design property (in bounds, nearer is taller, WIDE is smaller, ...).
 */
public class TestPbLayoutUtil {

	private static final double EPS = 1e-9;

	/** Horizontal word, expected {@link Horizontal}, expected {@code far} flag. */
	private static final Object[][] H_WORDS = {
		{ "left", Horizontal.LEFT, false },
		{ "centre", Horizontal.CENTRE, false },
		{ "right", Horizontal.RIGHT, false },
		{ "far left", Horizontal.LEFT, true },
		{ "far right", Horizontal.RIGHT, true },
	};

	/** Plane word, expected {@link Plane}. */
	private static final Object[][] P_WORDS = {
		{ "foreground", Plane.FOREGROUND },
		{ "midground", Plane.MIDGROUND },
		{ "background", Plane.BACKGROUND },
	};

	// ------------------------------------------------------------------------------------------------
	// helpers
	// ------------------------------------------------------------------------------------------------

	/** Every shot type plus null (null and UNKNOWN must both lay out as MEDIUM). */
	private static List<PbShotTypeEnumType> allShots() {
		List<PbShotTypeEnumType> l = new ArrayList<>(Arrays.asList(PbShotTypeEnumType.values()));
		l.add(null);
		return l;
	}

	/** Every actor role plus null (role-less elements). */
	private static List<PbActorRoleEnumType> allRoles() {
		List<PbActorRoleEnumType> l = new ArrayList<>(Arrays.asList(PbActorRoleEnumType.values()));
		l.add(null);
		return l;
	}

	/** The shot type the util must report as used: MEDIUM for null / UNKNOWN. Independent of the util. */
	private static PbShotTypeEnumType effective(PbShotTypeEnumType shot) {
		return (shot == null || shot == PbShotTypeEnumType.UNKNOWN) ? PbShotTypeEnumType.MEDIUM : shot;
	}

	/** The template the util must pick for a shot type. Independent of {@link PbLayoutUtil#template}. */
	private static Template expectedTemplate(PbShotTypeEnumType shot) {
		switch (effective(shot)) {
			case CLOSE_UP: return PbLayoutUtil.T_CLOSE_UP;
			case FULL_BODY: return PbLayoutUtil.T_FULL_BODY;
			case WIDE: return PbLayoutUtil.T_WIDE;
			case ESTABLISHING: return PbLayoutUtil.T_ESTABLISHING;
			default: return PbLayoutUtil.T_MEDIUM;
		}
	}

	/** The plane {@code steps} further back than {@code base}, clamped at BACKGROUND. Independent of {@code Plane.further}. */
	private static Plane stepBack(Plane base, int steps) {
		return Plane.values()[Math.min(Plane.values().length - 1, base.ordinal() + steps)];
	}

	private static double round4(double v) {
		return Math.round(v * 10000d) / 10000d;
	}

	/** The box {x, y, w, h} the design says a template yields for a horizontal / plane / far nudge. */
	private static double[] expectedBox(Template t, Horizontal h, Plane pl, boolean far) {
		int pi = pl.ordinal();
		double w = t.w[pi];
		double hh = t.h[pi];
		double cx = t.centreX[h.ordinal()];
		if (far && h == Horizontal.LEFT) cx -= PbLayoutUtil.FAR_NUDGE;
		if (far && h == Horizontal.RIGHT) cx += PbLayoutUtil.FAR_NUDGE;
		double x = Math.max(0d, Math.min(1d - w, cx - w / 2d));
		double y = Math.max(0d, Math.min(1d - hh, t.y[pi]));
		return new double[] { round4(x), round4(y), round4(w), round4(hh) };
	}

	private static void assertBox(String msg, double[] exp, Placement p) {
		assertEquals(msg + " x", exp[0], p.x(), EPS);
		assertEquals(msg + " y", exp[1], p.y(), EPS);
		assertEquals(msg + " w", exp[2], p.w(), EPS);
		assertEquals(msg + " h", exp[3], p.h(), EPS);
	}

	private static void assertFourDecimals(String msg, double v) {
		assertEquals(msg + " must be rounded to four decimals: " + v, round4(v), v, 0d);
	}

	private static void assertInBounds(String msg, Placement p) {
		assertNotNull(msg + " placement", p);
		assertTrue(msg + " x >= 0: " + p, p.x() >= 0d);
		assertTrue(msg + " y >= 0: " + p, p.y() >= 0d);
		assertTrue(msg + " 0 < w <= 1: " + p, p.w() > 0d && p.w() <= 1d);
		assertTrue(msg + " 0 < h <= 1: " + p, p.h() > 0d && p.h() <= 1d);
		assertTrue(msg + " x + w <= 1: " + p, p.x() + p.w() <= 1d + EPS);
		assertTrue(msg + " y + h <= 1: " + p, p.y() + p.h() <= 1d + EPS);
		assertNotNull(msg + " horizontal", p.horizontal());
		assertNotNull(msg + " plane", p.plane());
		assertNotNull(msg + " shotType", p.shotType());
		assertNotEquals(msg + " shotType is never UNKNOWN", PbShotTypeEnumType.UNKNOWN, p.shotType());
		assertEquals(msg + " depth is the plane's depth", p.plane().depth(), p.depth());
		assertFourDecimals(msg + " x", p.x());
		assertFourDecimals(msg + " y", p.y());
		assertFourDecimals(msg + " w", p.w());
		assertFourDecimals(msg + " h", p.h());
	}

	/** Every field of two placements, including the derivation fields. */
	private static void assertSamePlacement(String msg, Placement a, Placement b) {
		assertEquals(msg + " x", a.x(), b.x(), 0d);
		assertEquals(msg + " y", a.y(), b.y(), 0d);
		assertEquals(msg + " w", a.w(), b.w(), 0d);
		assertEquals(msg + " h", a.h(), b.h(), 0d);
		assertEquals(msg + " depth", a.depth(), b.depth());
		assertEquals(msg + " horizontal", a.horizontal(), b.horizontal());
		assertEquals(msg + " plane", a.plane(), b.plane());
		assertEquals(msg + " shotType", a.shotType(), b.shotType());
		assertEquals(msg + " recognized", a.recognized(), b.recognized());
		assertEquals(msg + " toString", a.toString(), b.toString());
	}

	/** Same box, ignoring how it was derived (recognized / toString). */
	private static void assertSameBox(String msg, Placement a, Placement b) {
		assertEquals(msg + " x", a.x(), b.x(), 0d);
		assertEquals(msg + " y", a.y(), b.y(), 0d);
		assertEquals(msg + " w", a.w(), b.w(), 0d);
		assertEquals(msg + " h", a.h(), b.h(), 0d);
		assertEquals(msg + " depth", a.depth(), b.depth());
		assertEquals(msg + " horizontal", a.horizontal(), b.horizontal());
		assertEquals(msg + " plane", a.plane(), b.plane());
		assertEquals(msg + " shotType", a.shotType(), b.shotType());
	}

	private static double centreX(Placement p) {
		return p.x() + p.w() / 2d;
	}

	/** 2 kB of deterministic junk with no vowels, so no vocabulary word can occur by accident; includes digits, punctuation, whitespace and non-ASCII. */
	private static String randomGarbage(int len) {
		String alphabet = "bcdfghjklmnpqrstvwxz0123456789 .,;:!?-_/()[]{}'\"\t\n\u00f1\u00fc\u6f22";
		Random r = new Random(42L);
		StringBuilder sb = new StringBuilder(len);
		for (int i = 0; i < len; i++) sb.append(alphabet.charAt(r.nextInt(alphabet.length())));
		return sb.toString();
	}

	// ------------------------------------------------------------------------------------------------
	// 1. boxes
	// ------------------------------------------------------------------------------------------------

	@Test
	public void everyShotTypeProducesInBoundsBoxes() {
		int n = 0;
		for (PbShotTypeEnumType shot : allShots()) {
			Template t = expectedTemplate(shot);
			assertTrue("template(" + shot + ")", PbLayoutUtil.template(shot) == t);
			for (Object[] hw : H_WORDS) {
				for (Object[] pw : P_WORDS) {
					for (PbActorRoleEnumType role : allRoles()) {
						String phrase = hw[0] + " " + pw[0];
						String msg = "[" + shot + " / '" + phrase + "' / " + role + "]";
						Placement p = PbLayoutUtil.place(phrase, shot, role);
						assertInBounds(msg, p);
						assertEquals(msg + " horizontal", hw[1], p.horizontal());
						assertEquals(msg + " plane word beats the role default", pw[1], p.plane());
						assertEquals(msg + " depth", ((Plane) pw[1]).depth(), p.depth());
						assertEquals(msg + " shotType", effective(shot), p.shotType());
						assertTrue(msg + " recognized", p.recognized());
						assertBox(msg, expectedBox(t, (Horizontal) hw[1], (Plane) pw[1], (Boolean) hw[2]), p);
						n++;
					}
				}
			}
		}
		assertEquals("every combination exercised", allShots().size() * H_WORDS.length * P_WORDS.length * allRoles().size(), n);
	}

	@Test
	public void nullAndUnknownShotLayOutAsMedium() {
		Placement medium = PbLayoutUtil.place("left foreground", PbShotTypeEnumType.MEDIUM, PbActorRoleEnumType.HERO);
		Placement nul = PbLayoutUtil.place("left foreground", null, PbActorRoleEnumType.HERO);
		Placement unknown = PbLayoutUtil.place("left foreground", PbShotTypeEnumType.UNKNOWN, PbActorRoleEnumType.HERO);
		assertEquals(PbShotTypeEnumType.MEDIUM, medium.shotType());
		assertEquals("null shot reports MEDIUM", PbShotTypeEnumType.MEDIUM, nul.shotType());
		assertEquals("UNKNOWN shot reports MEDIUM", PbShotTypeEnumType.MEDIUM, unknown.shotType());
		assertSamePlacement("null shot vs MEDIUM", medium, nul);
		assertSamePlacement("UNKNOWN shot vs MEDIUM", medium, unknown);
		assertBox("MEDIUM left foreground", expectedBox(PbLayoutUtil.T_MEDIUM, Horizontal.LEFT, Plane.FOREGROUND, false), medium);
		assertTrue(PbLayoutUtil.template(null) == PbLayoutUtil.T_MEDIUM);
		assertTrue(PbLayoutUtil.template(PbShotTypeEnumType.UNKNOWN) == PbLayoutUtil.T_MEDIUM);
		assertEquals(PbShotTypeEnumType.MEDIUM, PbLayoutUtil.effectiveShotType(null));
		assertEquals(PbShotTypeEnumType.MEDIUM, PbLayoutUtil.effectiveShotType(PbShotTypeEnumType.UNKNOWN));
		assertEquals(PbShotTypeEnumType.WIDE, PbLayoutUtil.effectiveShotType(PbShotTypeEnumType.WIDE));
	}

	@Test
	public void unknownWordIsCentreOfDeclaredShot() {
		Placement p = PbLayoutUtil.place("flibbertigibbet", PbShotTypeEnumType.WIDE, PbActorRoleEnumType.HERO);
		assertInBounds("garbage under WIDE", p);
		assertEquals(Horizontal.CENTRE, p.horizontal());
		assertEquals("hero default plane", Plane.FOREGROUND, p.plane());
		assertEquals(0, p.depth());
		assertEquals("the DECLARED shot is kept; MEDIUM is only for a missing shot", PbShotTypeEnumType.WIDE, p.shotType());
		assertFalse("not understood", p.recognized());
		/// The WIDE / CENTRE / FOREGROUND template box, computed from the arrays.
		Template t = PbLayoutUtil.T_WIDE;
		assertEquals(round4(t.centreX[Horizontal.CENTRE.ordinal()] - t.w[0] / 2d), p.x(), EPS);
		assertEquals(round4(t.y[0]), p.y(), EPS);
		assertEquals(t.w[0], p.w(), EPS);
		assertEquals(t.h[0], p.h(), EPS);
		assertBox("WIDE centre FG", expectedBox(t, Horizontal.CENTRE, Plane.FOREGROUND, false), p);
		/// It is the WIDE box, not the MEDIUM one.
		Placement asMedium = PbLayoutUtil.place("flibbertigibbet", PbShotTypeEnumType.MEDIUM, PbActorRoleEnumType.HERO);
		assertNotEquals("a WIDE figure is not MEDIUM-sized", asMedium.w(), p.w(), EPS);
		assertNotEquals("a WIDE figure is not MEDIUM-sized", asMedium.h(), p.h(), EPS);
		/// Same box as an explicit "centre foreground" under WIDE, only the recognized flag differs.
		Placement explicit = PbLayoutUtil.place("centre foreground", PbShotTypeEnumType.WIDE, PbActorRoleEnumType.HERO);
		assertTrue(explicit.recognized());
		assertSameBox("garbage == explicit centre of the role plane", explicit, p);
		/// Only a MISSING shot falls to MEDIUM.
		assertEquals(PbShotTypeEnumType.MEDIUM, PbLayoutUtil.place("flibbertigibbet", null, PbActorRoleEnumType.HERO).shotType());
		assertEquals(PbShotTypeEnumType.MEDIUM, PbLayoutUtil.place("flibbertigibbet", PbShotTypeEnumType.UNKNOWN, PbActorRoleEnumType.HERO).shotType());
	}

	// ------------------------------------------------------------------------------------------------
	// 2. vocabulary
	// ------------------------------------------------------------------------------------------------

	@Test
	public void everyHorizontalWordParses() {
		Object[][] words = {
			{ "left", Horizontal.LEFT, false },
			{ "right", Horizontal.RIGHT, false },
			{ "centre", Horizontal.CENTRE, false },
			{ "center", Horizontal.CENTRE, false },
			{ "central", Horizontal.CENTRE, false },
			{ "centred", Horizontal.CENTRE, false },
			{ "centered", Horizontal.CENTRE, false },
			{ "middle", Horizontal.CENTRE, false },
			{ "mid", Horizontal.CENTRE, false },
			{ "far left", Horizontal.LEFT, true },
			{ "extreme left", Horizontal.LEFT, true },
			{ "far right", Horizontal.RIGHT, true },
			{ "extreme right", Horizontal.RIGHT, true },
		};
		for (Object[] w : words) {
			Parsed p = PbLayoutUtil.parse((String) w[0]);
			assertEquals("'" + w[0] + "' horizontal", w[1], p.horizontal);
			assertEquals("'" + w[0] + "' far", w[2], p.far);
			assertNull("'" + w[0] + "' names no plane", p.plane);
			assertTrue("'" + w[0] + "' recognized", p.recognized());
			Placement pl = PbLayoutUtil.place((String) w[0], PbShotTypeEnumType.MEDIUM, null);
			assertEquals("'" + w[0] + "' placed", w[1], pl.horizontal());
			assertEquals("'" + w[0] + "' plane defaults to MIDGROUND for a null role", Plane.MIDGROUND, pl.plane());
			assertTrue(pl.recognized());
		}

		/// The far nudge: strictly further out than the plain side under every shot and plane, still in bounds.
		for (PbShotTypeEnumType shot : allShots()) {
			for (Object[] pw : P_WORDS) {
				String msg = "[" + shot + " / " + pw[0] + "]";
				Placement left = PbLayoutUtil.place("left " + pw[0], shot, null);
				Placement farLeft = PbLayoutUtil.place("far left " + pw[0], shot, null);
				Placement extremeLeft = PbLayoutUtil.place("extreme left " + pw[0], shot, null);
				Placement right = PbLayoutUtil.place("right " + pw[0], shot, null);
				Placement farRight = PbLayoutUtil.place("far right " + pw[0], shot, null);
				Placement extremeRight = PbLayoutUtil.place("extreme right " + pw[0], shot, null);
				assertInBounds(msg + " far left", farLeft);
				assertInBounds(msg + " far right", farRight);
				assertEquals(Horizontal.LEFT, farLeft.horizontal());
				assertEquals(Horizontal.RIGHT, farRight.horizontal());
				assertTrue(msg + " far left x " + farLeft.x() + " < left x " + left.x(), farLeft.x() < left.x());
				assertTrue(msg + " far right x " + farRight.x() + " > right x " + right.x(), farRight.x() > right.x());
				assertEquals(msg + " far does not change the plane", left.plane(), farLeft.plane());
				assertEquals(msg + " far does not change the size", left.w(), farLeft.w(), 0d);
				assertEquals(msg + " far does not change the size", left.h(), farLeft.h(), 0d);
				assertSamePlacement(msg + " extreme left == far left", farLeft, extremeLeft);
				assertSamePlacement(msg + " extreme right == far right", farRight, extremeRight);
				/// The nudge is FAR_NUDGE on the centre before clamping, so the shift is at most FAR_NUDGE.
				assertTrue(msg + " left shift <= FAR_NUDGE", left.x() - farLeft.x() <= PbLayoutUtil.FAR_NUDGE + 1e-4);
				assertTrue(msg + " right shift <= FAR_NUDGE", farRight.x() - right.x() <= PbLayoutUtil.FAR_NUDGE + 1e-4);
			}
		}
		/// "far" on a CENTRE word is not a nudge; and a lone "far" is depth, not a side.
		Placement farCentre = PbLayoutUtil.place("far centre", PbShotTypeEnumType.MEDIUM, null);
		assertEquals(Horizontal.CENTRE, farCentre.horizontal());
		assertEquals("'far centre' = centre of the background", Plane.BACKGROUND, farCentre.plane());
	}

	@Test
	public void everyDepthWordParses() {
		Object[][] words = {
			{ "foreground", Plane.FOREGROUND }, { "fore", Plane.FOREGROUND }, { "fore ground", Plane.FOREGROUND },
			{ "front", Plane.FOREGROUND }, { "near", Plane.FOREGROUND }, { "nearest", Plane.FOREGROUND },
			{ "close", Plane.FOREGROUND }, { "closest", Plane.FOREGROUND }, { "forward", Plane.FOREGROUND },
			{ "midground", Plane.MIDGROUND }, { "middle distance", Plane.MIDGROUND }, { "mid distance", Plane.MIDGROUND },
			{ "middle ground", Plane.MIDGROUND }, { "mid ground", Plane.MIDGROUND },
			{ "background", Plane.BACKGROUND }, { "back ground", Plane.BACKGROUND }, { "back", Plane.BACKGROUND },
			{ "rear", Plane.BACKGROUND }, { "far", Plane.BACKGROUND }, { "distant", Plane.BACKGROUND },
			{ "distance", Plane.BACKGROUND }, { "behind", Plane.BACKGROUND }, { "beyond", Plane.BACKGROUND },
			{ "far away", Plane.BACKGROUND }, { "far back", Plane.BACKGROUND }, { "far behind", Plane.BACKGROUND },
		};
		for (Object[] w : words) {
			Parsed p = PbLayoutUtil.parse((String) w[0]);
			assertEquals("'" + w[0] + "' plane", w[1], p.plane);
			assertNull("'" + w[0] + "' names no side", p.horizontal);
			assertFalse("'" + w[0] + "' is not a far-side nudge", p.far);
			assertTrue("'" + w[0] + "' recognized", p.recognized());
			for (PbActorRoleEnumType role : allRoles()) {
				Placement pl = PbLayoutUtil.place((String) w[0], PbShotTypeEnumType.MEDIUM, role);
				assertEquals("'" + w[0] + "' / " + role + " plane word beats the role default", w[1], pl.plane());
				assertEquals("'" + w[0] + "' / " + role + " depth", ((Plane) w[1]).depth(), pl.depth());
				assertEquals("'" + w[0] + "' / " + role + " no side -> CENTRE", Horizontal.CENTRE, pl.horizontal());
				assertTrue(pl.recognized());
			}
		}
		/// "far" alone is BACKGROUND with far == false: it is a plane word, not an outward nudge.
		Parsed far = PbLayoutUtil.parse("far");
		assertEquals(Plane.BACKGROUND, far.plane);
		assertNull(far.horizontal);
		assertFalse(far.far);
		/// "middle" alone is the horizontal CENTRE (not MIDGROUND); the compound is the plane.
		Parsed middle = PbLayoutUtil.parse("middle");
		assertEquals(Horizontal.CENTRE, middle.horizontal);
		assertNull(middle.plane);
		assertEquals(List.of("midground"), PbLayoutUtil.tokens("middle distance"));
		assertEquals(List.of("far"), PbLayoutUtil.tokens("far away"));
		assertEquals(List.of("background"), PbLayoutUtil.tokens("far back"));
	}

	@Test
	public void phrasesCombine() {
		Object[][] phrases = {
			{ "left foreground", Horizontal.LEFT, Plane.FOREGROUND, false },
			{ "right, background", Horizontal.RIGHT, Plane.BACKGROUND, false },
			{ "far-right midground", Horizontal.RIGHT, Plane.MIDGROUND, true },
			{ "centre front", Horizontal.CENTRE, Plane.FOREGROUND, false },
			{ "in the background on the left", Horizontal.LEFT, Plane.BACKGROUND, false },
			{ "far left, far away", Horizontal.LEFT, Plane.BACKGROUND, true },
			{ "stage left, middle distance", Horizontal.LEFT, Plane.MIDGROUND, false },
			{ "left foreground right background", Horizontal.LEFT, Plane.FOREGROUND, false },
			{ "behind, to the right", Horizontal.RIGHT, Plane.BACKGROUND, false },
		};
		for (Object[] ph : phrases) {
			String s = (String) ph[0];
			Parsed p = PbLayoutUtil.parse(s);
			assertEquals("'" + s + "' horizontal", ph[1], p.horizontal);
			assertEquals("'" + s + "' plane", ph[2], p.plane);
			assertEquals("'" + s + "' far", ph[3], p.far);
			for (PbShotTypeEnumType shot : allShots()) {
				for (PbActorRoleEnumType role : allRoles()) {
					String msg = "'" + s + "' / " + shot + " / " + role;
					Placement pl = PbLayoutUtil.place(s, shot, role);
					assertInBounds(msg, pl);
					assertEquals(msg + " horizontal", ph[1], pl.horizontal());
					assertEquals(msg + " plane", ph[2], pl.plane());
					assertEquals(msg + " depth", ((Plane) ph[2]).depth(), pl.depth());
					assertTrue(msg + " recognized", pl.recognized());
					assertBox(msg, expectedBox(expectedTemplate(shot), (Horizontal) ph[1], (Plane) ph[2], (Boolean) ph[3]), pl);
				}
			}
		}
		/// First horizontal word wins: "centre left" is CENTRE and names no plane.
		Parsed cl = PbLayoutUtil.parse("centre left");
		assertEquals(Horizontal.CENTRE, cl.horizontal);
		assertNull(cl.plane);
		assertEquals(Plane.FOREGROUND, PbLayoutUtil.place("centre left", PbShotTypeEnumType.MEDIUM, PbActorRoleEnumType.HERO).plane());
		assertEquals(Plane.BACKGROUND, PbLayoutUtil.place("centre left", PbShotTypeEnumType.MEDIUM, PbActorRoleEnumType.CROWD).plane());
	}

	@Test
	public void normalisationIsForgiving() {
		Placement ref = PbLayoutUtil.place("left foreground", PbShotTypeEnumType.MEDIUM, PbActorRoleEnumType.HERO);
		assertEquals(List.of("left", "foreground"), PbLayoutUtil.tokens("left foreground"));
		String[] variants = {
			"LEFT FOREGROUND",
			"Left Foreground",
			"left_foreground",
			"left-foreground",
			"left/foreground",
			"left foreground.",
			"left foreground!!!",
			"  left   foreground  ",
			"...left, foreground...",
			"left (foreground)",
			"left\tforeground",
			"left\nforeground",
			"Standing at stage left in the FOREGROUND of the frame.",
			"placed on the left hand side, in the foreground of the picture",
			"positioned at the left of the image, in the front",
		};
		for (String v : variants) {
			String msg = "'" + v.replace("\n", "\\n").replace("\t", "\\t") + "'";
			Placement p = PbLayoutUtil.place(v, PbShotTypeEnumType.MEDIUM, PbActorRoleEnumType.HERO);
			assertSamePlacement(msg + " == 'left foreground'", ref, p);
			Parsed parsed = PbLayoutUtil.parse(v);
			assertEquals(msg + " horizontal", Horizontal.LEFT, parsed.horizontal);
			assertEquals(msg + " plane", Plane.FOREGROUND, parsed.plane);
			assertFalse(msg + " far", parsed.far);
		}
		/// Stop words and single characters never survive tokenisation.
		for (String stop : PbLayoutUtil.STOP_WORDS) {
			assertEquals("stop word '" + stop + "' dropped", List.of("left"), PbLayoutUtil.tokens(stop + " left " + stop));
		}
		assertEquals(List.of("left"), PbLayoutUtil.tokens("x left y 7"));
		assertEquals(List.of("left", "foreground"), PbLayoutUtil.tokens("Standing at stage left in the FOREGROUND of the frame."));
	}

	@Test
	public void garbageIsCentreAndNeverThrows() {
		String junk = randomGarbage(2048);
		assertEquals(2048, junk.length());
		String[] garbage = {
			null, "", "   ", "\t\n", "null", "NULL", "n/a", "N/A", "none", "unknown", "UNKNOWN", "unspecified",
			"???", "----", "_", "12345", junk, "top", "bottom", "upper", "lower", "flibbertigibbet", "sideways"
		};
		for (String g : garbage) {
			String label = (g == null) ? "null" : (g.length() > 24 ? "'" + g.substring(0, 24).replaceAll("\\s", " ") + "...'(" + g.length() + ")" : "'" + g + "'");
			List<String> tokens = PbLayoutUtil.tokens(g);
			assertNotNull(label + " tokens", tokens);
			Parsed parsed = PbLayoutUtil.parse(g);
			assertNotNull(label + " parsed", parsed);
			assertNull(label + " horizontal", parsed.horizontal);
			assertNull(label + " plane", parsed.plane);
			assertFalse(label + " far", parsed.far);
			assertFalse(label + " recognized", parsed.recognized());
			assertEquals(label + " shot", PbShotTypeEnumType.UNKNOWN, PbLayoutUtil.parseShotType(g));
			assertEquals(label + " viewpoint", PbViewpointEnumType.UNKNOWN, PbLayoutUtil.parseViewpoint(g));
			for (PbShotTypeEnumType shot : allShots()) {
				for (PbActorRoleEnumType role : allRoles()) {
					String msg = label + " / " + shot + " / " + role;
					Placement p = PbLayoutUtil.place(g, shot, role);
					assertInBounds(msg, p);
					assertEquals(msg + " horizontal", Horizontal.CENTRE, p.horizontal());
					assertEquals(msg + " plane", PbLayoutUtil.defaultPlane(role), p.plane());
					assertEquals(msg + " depth", PbLayoutUtil.defaultPlane(role).depth(), p.depth());
					assertEquals(msg + " shotType", effective(shot), p.shotType());
					assertFalse(msg + " recognized", p.recognized());
					assertBox(msg, expectedBox(expectedTemplate(shot), Horizontal.CENTRE, PbLayoutUtil.defaultPlane(role), false), p);
					assertTrue(msg + " toString flags the default", p.toString().contains("(default)"));
				}
				Placement e = PbLayoutUtil.place(g, shot);
				assertInBounds(label + " / " + shot + " element overload", e);
				assertEquals(Horizontal.CENTRE, e.horizontal());
				assertEquals(Plane.MIDGROUND, e.plane());
				assertFalse(e.recognized());
			}
		}
		/// Numeric extremes on the §28 spread never throw and stay in bounds.
		int[] extremes = { Integer.MIN_VALUE, -1000, -1, 0, 1, 2, 3, 1000, Integer.MAX_VALUE };
		for (int ord : extremes) {
			for (int count : extremes) {
				for (PbShotTypeEnumType shot : allShots()) {
					Placement p = PbLayoutUtil.defaultPlacement(ord, count, shot, PbActorRoleEnumType.HERO);
					assertInBounds("defaultPlacement(" + ord + ", " + count + ", " + shot + ")", p);
					assertFalse(p.recognized());
				}
			}
		}
	}

	// ------------------------------------------------------------------------------------------------
	// 3. defaults
	// ------------------------------------------------------------------------------------------------

	@Test
	public void roleDefaultsPlane() {
		assertEquals(Plane.FOREGROUND, PbLayoutUtil.defaultPlane(PbActorRoleEnumType.HERO));
		assertEquals(Plane.FOREGROUND, PbLayoutUtil.defaultPlane(PbActorRoleEnumType.SUPPORTING));
		assertEquals(Plane.BACKGROUND, PbLayoutUtil.defaultPlane(PbActorRoleEnumType.EXTRA));
		assertEquals(Plane.BACKGROUND, PbLayoutUtil.defaultPlane(PbActorRoleEnumType.CROWD));
		assertEquals(Plane.MIDGROUND, PbLayoutUtil.defaultPlane(PbActorRoleEnumType.NAMED_BACKGROUND));
		assertEquals(Plane.MIDGROUND, PbLayoutUtil.defaultPlane(PbActorRoleEnumType.UNKNOWN));
		assertEquals(Plane.MIDGROUND, PbLayoutUtil.defaultPlane(null));
		assertEquals("every role value is covered above", 6, PbActorRoleEnumType.values().length);
		for (PbActorRoleEnumType role : allRoles()) {
			Plane expected = PbLayoutUtil.defaultPlane(role);
			assertNotNull("defaultPlane(" + role + ")", expected);
			Placement p = PbLayoutUtil.place("left", PbShotTypeEnumType.MEDIUM, role);
			assertEquals("place('left', MEDIUM, " + role + ") plane", expected, p.plane());
			assertEquals("place('left', MEDIUM, " + role + ") depth", expected.depth(), p.depth());
			assertEquals(Horizontal.LEFT, p.horizontal());
			assertTrue("a side word alone is still understood", p.recognized());
			assertBox("left / " + role, expectedBox(PbLayoutUtil.T_MEDIUM, Horizontal.LEFT, expected, false), p);
		}
	}

	@Test
	public void elementOverloadDefaultsToMidground() {
		for (PbShotTypeEnumType shot : allShots()) {
			Placement two = PbLayoutUtil.place("right", shot);
			Placement three = PbLayoutUtil.place("right", shot, null);
			assertSamePlacement("place(s, " + shot + ") == place(s, " + shot + ", null)", three, two);
			assertEquals(Plane.MIDGROUND, two.plane());
			assertEquals(1, two.depth());
			assertEquals(Horizontal.RIGHT, two.horizontal());
			assertTrue(two.recognized());
		}
		Placement w = PbLayoutUtil.place("right", PbShotTypeEnumType.WIDE);
		assertEquals(PbShotTypeEnumType.WIDE, w.shotType());
		assertBox("WIDE right MG", expectedBox(PbLayoutUtil.T_WIDE, Horizontal.RIGHT, Plane.MIDGROUND, false), w);
		/// A plane word still overrides the MIDGROUND default on the element overload.
		assertEquals(Plane.BACKGROUND, PbLayoutUtil.place("right background", PbShotTypeEnumType.WIDE).plane());
	}

	@Test
	public void wideIsSmallerAndMoreSeparatedThanMedium() {
		for (Object[] pw : P_WORDS) {
			for (PbActorRoleEnumType role : allRoles()) {
				String msg = "[" + pw[0] + " / " + role + "]";
				for (String side : new String[] { "left", "centre", "right" }) {
					Placement wide = PbLayoutUtil.place(side + " " + pw[0], PbShotTypeEnumType.WIDE, role);
					Placement medium = PbLayoutUtil.place(side + " " + pw[0], PbShotTypeEnumType.MEDIUM, role);
					assertTrue(msg + " " + side + " WIDE w " + wide.w() + " < MEDIUM w " + medium.w(), wide.w() < medium.w());
					assertTrue(msg + " " + side + " WIDE h " + wide.h() + " < MEDIUM h " + medium.h(), wide.h() < medium.h());
				}
				Placement wl = PbLayoutUtil.place("left " + pw[0], PbShotTypeEnumType.WIDE, role);
				Placement wr = PbLayoutUtil.place("right " + pw[0], PbShotTypeEnumType.WIDE, role);
				Placement ml = PbLayoutUtil.place("left " + pw[0], PbShotTypeEnumType.MEDIUM, role);
				Placement mr = PbLayoutUtil.place("right " + pw[0], PbShotTypeEnumType.MEDIUM, role);
				double wideSep = centreX(wr) - centreX(wl);
				double mediumSep = centreX(mr) - centreX(ml);
				assertTrue(msg + " WIDE separation " + wideSep + " > MEDIUM separation " + mediumSep, wideSep > mediumSep);
				assertTrue(msg + " MEDIUM left is left of centre", centreX(ml) < 0.5d);
				assertTrue(msg + " MEDIUM right is right of centre", centreX(mr) > 0.5d);
			}
		}
	}

	@Test
	public void nearerPlaneIsLowerAndTaller() {
		for (PbShotTypeEnumType shot : allShots()) {
			for (Object[] hw : H_WORDS) {
				String msg = "[" + shot + " / " + hw[0] + "]";
				Placement fg = PbLayoutUtil.place(hw[0] + " foreground", shot, null);
				Placement mg = PbLayoutUtil.place(hw[0] + " midground", shot, null);
				Placement bg = PbLayoutUtil.place(hw[0] + " background", shot, null);
				assertEquals(msg + " fg depth", 0, fg.depth());
				assertEquals(msg + " mg depth", 1, mg.depth());
				assertEquals(msg + " bg depth", 2, bg.depth());
				double fgBottom = fg.y() + fg.h();
				double mgBottom = mg.y() + mg.h();
				double bgBottom = bg.y() + bg.h();
				assertTrue(msg + " FG bottom " + fgBottom + " >= MG bottom " + mgBottom, fgBottom >= mgBottom - EPS);
				assertTrue(msg + " MG bottom " + mgBottom + " >= BG bottom " + bgBottom, mgBottom >= bgBottom - EPS);
				assertTrue(msg + " FG h " + fg.h() + " > MG h " + mg.h(), fg.h() > mg.h());
				assertTrue(msg + " MG h " + mg.h() + " > BG h " + bg.h(), mg.h() > bg.h());
				assertTrue(msg + " FG w " + fg.w() + " > MG w " + mg.w(), fg.w() > mg.w());
				assertTrue(msg + " MG w " + mg.w() + " > BG w " + bg.w(), mg.w() > bg.w());
			}
		}
		assertTrue(Plane.FOREGROUND.depth() < Plane.MIDGROUND.depth());
		assertTrue(Plane.MIDGROUND.depth() < Plane.BACKGROUND.depth());
	}

	@Test
	public void defaultPlacementSpreadsActors() {
		for (PbShotTypeEnumType shot : allShots()) {
			for (PbActorRoleEnumType role : allRoles()) {
				Plane base = PbLayoutUtil.defaultPlane(role);
				String msg = "[" + shot + " / " + role + "]";

				/// 1 actor: centre of the role's plane.
				Placement solo = PbLayoutUtil.defaultPlacement(0, 1, shot, role);
				assertInBounds(msg + " solo", solo);
				assertEquals(msg + " solo", Horizontal.CENTRE, solo.horizontal());
				assertEquals(msg + " solo plane", base, solo.plane());
				assertFalse(msg + " solo recognized", solo.recognized());
				assertEquals(msg + " solo shotType", effective(shot), solo.shotType());
				assertSameBox(msg + " solo == explicit centre", PbLayoutUtil.place("centre", shot, role), solo);

				/// 2 actors: left then right, same plane, never overlapping horizontally under MEDIUM.
				Placement two0 = PbLayoutUtil.defaultPlacement(0, 2, shot, role);
				Placement two1 = PbLayoutUtil.defaultPlacement(1, 2, shot, role);
				assertInBounds(msg + " two0", two0);
				assertInBounds(msg + " two1", two1);
				assertEquals(msg + " two0", Horizontal.LEFT, two0.horizontal());
				assertEquals(msg + " two1", Horizontal.RIGHT, two1.horizontal());
				assertEquals(msg + " two0 plane", base, two0.plane());
				assertEquals(msg + " two1 plane", base, two1.plane());
				assertFalse(two0.recognized());
				assertFalse(two1.recognized());
				assertSameBox(msg + " two0 == explicit left", PbLayoutUtil.place("left", shot, role), two0);
				assertSameBox(msg + " two1 == explicit right", PbLayoutUtil.place("right", shot, role), two1);
				if (effective(shot) == PbShotTypeEnumType.MEDIUM) {
					assertTrue(msg + " two MEDIUM actors do not overlap: " + two0 + " vs " + two1, two0.x() + two0.w() <= two1.x() + EPS);
				}

				/// 3 actors: centre, left, right in the role's plane.
				Horizontal[] spread = { Horizontal.CENTRE, Horizontal.LEFT, Horizontal.RIGHT };
				for (int i = 0; i < 3; i++) {
					Placement p = PbLayoutUtil.defaultPlacement(i, 3, shot, role);
					assertInBounds(msg + " three[" + i + "]", p);
					assertEquals(msg + " three[" + i + "]", spread[i], p.horizontal());
					assertEquals(msg + " three[" + i + "] plane", base, p.plane());
					assertFalse(p.recognized());
				}

				/// 4 and 5 actors: ordinals 3 and 4 are one plane further back; 0..2 unchanged.
				for (int count = 4; count <= 5; count++) {
					for (int i = 0; i < 3; i++) {
						Placement p = PbLayoutUtil.defaultPlacement(i, count, shot, role);
						assertEquals(msg + " count " + count + " [" + i + "]", spread[i], p.horizontal());
						assertEquals(msg + " count " + count + " [" + i + "] plane", base, p.plane());
					}
				}
				Placement fourth = PbLayoutUtil.defaultPlacement(3, 4, shot, role);
				assertInBounds(msg + " fourth", fourth);
				assertEquals(msg + " fourth", Horizontal.CENTRE, fourth.horizontal());
				assertEquals(msg + " fourth plane", stepBack(base, 1), fourth.plane());
				assertEquals(msg + " fourth depth", stepBack(base, 1).depth(), fourth.depth());
				Placement fifth = PbLayoutUtil.defaultPlacement(4, 5, shot, role);
				assertInBounds(msg + " fifth", fifth);
				assertEquals(msg + " fifth", Horizontal.LEFT, fifth.horizontal());
				assertEquals(msg + " fifth plane", stepBack(base, 1), fifth.plane());

				/// 10 actors: cycles 0, 1, 2, 3 -> base, base+1, base+2 (clamped), clamped; never beyond BACKGROUND.
				for (int i = 0; i < 10; i++) {
					Placement p = PbLayoutUtil.defaultPlacement(i, 10, shot, role);
					assertInBounds(msg + " ten[" + i + "]", p);
					assertEquals(msg + " ten[" + i + "]", spread[i % 3], p.horizontal());
					assertEquals(msg + " ten[" + i + "] plane", stepBack(base, i / 3), p.plane());
					assertTrue(msg + " ten[" + i + "] depth <= 2", p.depth() <= 2);
					assertFalse(p.recognized());
				}

				/// Count <= 0 behaves as one actor; ordinal clamped into range, no throw.
				for (int count : new int[] { 0, -3 }) {
					for (int ord : new int[] { -1, 0, 1, 5 }) {
						assertSamePlacement(msg + " defaultPlacement(" + ord + ", " + count + ") == solo", solo, PbLayoutUtil.defaultPlacement(ord, count, shot, role));
					}
				}
				assertSamePlacement(msg + " ordinal -1 of 2 == 0 of 2", two0, PbLayoutUtil.defaultPlacement(-1, 2, shot, role));
				assertSamePlacement(msg + " ordinal 2 of 2 == 1 of 2", two1, PbLayoutUtil.defaultPlacement(2, 2, shot, role));
				assertSamePlacement(msg + " ordinal 99 of 2 == 1 of 2", two1, PbLayoutUtil.defaultPlacement(99, 2, shot, role));
				assertSamePlacement(msg + " ordinal -5 of 3 == 0 of 3", PbLayoutUtil.defaultPlacement(0, 3, shot, role), PbLayoutUtil.defaultPlacement(-5, 3, shot, role));
				/// Clamping to count-1 keeps the plane: ordinal 3 of 3 is the RIGHT of the base plane, not a cycle back.
				Placement clamped = PbLayoutUtil.defaultPlacement(3, 3, shot, role);
				assertSamePlacement(msg + " ordinal 3 of 3 == 2 of 3", PbLayoutUtil.defaultPlacement(2, 3, shot, role), clamped);
				assertEquals(msg + " clamped plane", base, clamped.plane());
			}
		}

		/// Concrete §28 ladder for a HERO: FG x3, MG x3, BG x3, then clamped at BG.
		Plane[] hero10 = new Plane[10];
		for (int i = 0; i < 10; i++) hero10[i] = PbLayoutUtil.defaultPlacement(i, 10, PbShotTypeEnumType.WIDE, PbActorRoleEnumType.HERO).plane();
		assertEquals(Arrays.asList(
			Plane.FOREGROUND, Plane.FOREGROUND, Plane.FOREGROUND,
			Plane.MIDGROUND, Plane.MIDGROUND, Plane.MIDGROUND,
			Plane.BACKGROUND, Plane.BACKGROUND, Plane.BACKGROUND, Plane.BACKGROUND), Arrays.asList(hero10));
		for (int i = 0; i < 10; i++) {
			assertEquals("EXTRA stays in the background", Plane.BACKGROUND, PbLayoutUtil.defaultPlacement(i, 10, PbShotTypeEnumType.WIDE, PbActorRoleEnumType.EXTRA).plane());
		}
	}

	@Test
	public void cameraDefaults() {
		assertEquals(3, PbLayoutUtil.WIDE_ACTOR_THRESHOLD);
		for (int n : new int[] { Integer.MIN_VALUE, -1, 0, 1, 2, 3 }) {
			assertEquals("defaultShotType(" + n + ")", PbShotTypeEnumType.MEDIUM, PbLayoutUtil.defaultShotType(n));
		}
		for (int n : new int[] { 4, 5, 10, Integer.MAX_VALUE }) {
			assertEquals("defaultShotType(" + n + ")", PbShotTypeEnumType.WIDE, PbLayoutUtil.defaultShotType(n));
		}
		assertEquals(PbViewpointEnumType.EYE_LEVEL, PbLayoutUtil.defaultViewpoint());

		assertEquals(PbShotTypeEnumType.MEDIUM, PbLayoutUtil.resolveShotType(null, 2));
		assertEquals(PbShotTypeEnumType.MEDIUM, PbLayoutUtil.resolveShotType(PbShotTypeEnumType.UNKNOWN, 2));
		assertEquals(PbShotTypeEnumType.MEDIUM, PbLayoutUtil.resolveShotType(null, 3));
		assertEquals(PbShotTypeEnumType.WIDE, PbLayoutUtil.resolveShotType(null, 4));
		assertEquals(PbShotTypeEnumType.WIDE, PbLayoutUtil.resolveShotType(PbShotTypeEnumType.UNKNOWN, 4));
		assertEquals(PbShotTypeEnumType.CLOSE_UP, PbLayoutUtil.resolveShotType(PbShotTypeEnumType.CLOSE_UP, 9));
		for (PbShotTypeEnumType declared : PbShotTypeEnumType.values()) {
			if (declared == PbShotTypeEnumType.UNKNOWN) continue;
			assertEquals("declared " + declared + " wins at 1 actor", declared, PbLayoutUtil.resolveShotType(declared, 1));
			assertEquals("declared " + declared + " wins at 9 actors", declared, PbLayoutUtil.resolveShotType(declared, 9));
		}

		assertEquals(PbViewpointEnumType.EYE_LEVEL, PbLayoutUtil.resolveViewpoint(null));
		assertEquals(PbViewpointEnumType.EYE_LEVEL, PbLayoutUtil.resolveViewpoint(PbViewpointEnumType.UNKNOWN));
		assertEquals(PbViewpointEnumType.LOW, PbLayoutUtil.resolveViewpoint(PbViewpointEnumType.LOW));
		for (PbViewpointEnumType declared : PbViewpointEnumType.values()) {
			if (declared == PbViewpointEnumType.UNKNOWN) continue;
			assertEquals("declared " + declared + " wins", declared, PbLayoutUtil.resolveViewpoint(declared));
		}
	}

	@Test
	public void parseCameraWords() {
		/// Enum names, any case, and the §9 wire form.
		for (PbShotTypeEnumType s : PbShotTypeEnumType.values()) {
			assertEquals(s.name(), s, PbLayoutUtil.parseShotType(s.name()));
			assertEquals(s.name().toLowerCase(), s, PbLayoutUtil.parseShotType(s.name().toLowerCase()));
			assertEquals(s.value(), s, PbLayoutUtil.parseShotType(s.value()));
		}
		for (PbViewpointEnumType v : PbViewpointEnumType.values()) {
			assertEquals(v.name(), v, PbLayoutUtil.parseViewpoint(v.name()));
			assertEquals(v.name().toLowerCase(), v, PbLayoutUtil.parseViewpoint(v.name().toLowerCase()));
			assertEquals(v.value(), v, PbLayoutUtil.parseViewpoint(v.value()));
		}
		assertEquals(PbShotTypeEnumType.WIDE, PbLayoutUtil.parseShotType("wide"));
		assertEquals(PbShotTypeEnumType.CLOSE_UP, PbLayoutUtil.parseShotType("close_up"));
		assertEquals(PbShotTypeEnumType.FULL_BODY, PbLayoutUtil.parseShotType("full_body"));
		assertEquals(PbViewpointEnumType.EYE_LEVEL, PbLayoutUtil.parseViewpoint("eye_level"));
		assertEquals(PbViewpointEnumType.OVER_SHOULDER, PbLayoutUtil.parseViewpoint("over_shoulder"));
		assertEquals(PbViewpointEnumType.BIRDS_EYE, PbLayoutUtil.parseViewpoint("birds_eye"));

		/// Phrases a prompt or a human writes.
		Object[][] shots = {
			{ "close-up", PbShotTypeEnumType.CLOSE_UP }, { "Close Up", PbShotTypeEnumType.CLOSE_UP }, { "a close shot", PbShotTypeEnumType.CLOSE_UP },
			{ "wide shot", PbShotTypeEnumType.WIDE }, { "Wide angle", PbShotTypeEnumType.WIDE },
			{ "full length", PbShotTypeEnumType.FULL_BODY }, { "full body", PbShotTypeEnumType.FULL_BODY }, { "full-body shot", PbShotTypeEnumType.FULL_BODY },
			{ "medium shot", PbShotTypeEnumType.MEDIUM }, { "mid shot", PbShotTypeEnumType.MEDIUM },
			{ "establishing shot", PbShotTypeEnumType.ESTABLISHING },
			{ "sideways", PbShotTypeEnumType.UNKNOWN }, { "", PbShotTypeEnumType.UNKNOWN }, { "unknown", PbShotTypeEnumType.UNKNOWN },
			{ "n/a", PbShotTypeEnumType.UNKNOWN }, { "   ", PbShotTypeEnumType.UNKNOWN }, { "portrait", PbShotTypeEnumType.UNKNOWN },
		};
		for (Object[] c : shots) {
			assertEquals("parseShotType('" + c[0] + "')", c[1], PbLayoutUtil.parseShotType((String) c[0]));
		}
		assertEquals(PbShotTypeEnumType.UNKNOWN, PbLayoutUtil.parseShotType(null));

		Object[][] views = {
			{ "eye level", PbViewpointEnumType.EYE_LEVEL }, { "Eye-Level", PbViewpointEnumType.EYE_LEVEL }, { "at eye level", PbViewpointEnumType.EYE_LEVEL },
			{ "bird's eye view", PbViewpointEnumType.BIRDS_EYE }, { "birds eye", PbViewpointEnumType.BIRDS_EYE }, { "top down", PbViewpointEnumType.BIRDS_EYE },
			{ "aerial", PbViewpointEnumType.BIRDS_EYE }, { "overhead", PbViewpointEnumType.BIRDS_EYE },
			{ "over the shoulder", PbViewpointEnumType.OVER_SHOULDER }, { "over-the-shoulder shot", PbViewpointEnumType.OVER_SHOULDER }, { "OTS", PbViewpointEnumType.OVER_SHOULDER },
			{ "high angle", PbViewpointEnumType.HIGH }, { "High", PbViewpointEnumType.HIGH }, { "elevated", PbViewpointEnumType.HIGH },
			{ "low angle", PbViewpointEnumType.LOW }, { "from a low angle", PbViewpointEnumType.LOW },
			{ "sideways", PbViewpointEnumType.UNKNOWN }, { "", PbViewpointEnumType.UNKNOWN }, { "unknown", PbViewpointEnumType.UNKNOWN },
			{ "none", PbViewpointEnumType.UNKNOWN }, { "   ", PbViewpointEnumType.UNKNOWN }, { "dutch", PbViewpointEnumType.UNKNOWN },
		};
		for (Object[] c : views) {
			assertEquals("parseViewpoint('" + c[0] + "')", c[1], PbLayoutUtil.parseViewpoint((String) c[0]));
		}
		assertEquals(PbViewpointEnumType.UNKNOWN, PbLayoutUtil.parseViewpoint(null));

		/// Why the util must not use the enums' fromValue: it is a bare valueOf and throws on the wire form.
		try {
			PbShotTypeEnumType.fromValue("wide");
			fail("PbShotTypeEnumType.fromValue(\"wide\") must throw - the util's parser exists because it does");
		} catch (IllegalArgumentException expected) {
			/// documented
		}
		try {
			PbViewpointEnumType.fromValue("eye_level");
			fail("PbViewpointEnumType.fromValue(\"eye_level\") must throw - the util's parser exists because it does");
		} catch (IllegalArgumentException expected) {
			/// documented
		}
	}

	// ------------------------------------------------------------------------------------------------
	// 4. stability
	// ------------------------------------------------------------------------------------------------

	@Test
	public void deterministic() {
		Object[][] inputs = {
			{ "far left background", PbShotTypeEnumType.WIDE, PbActorRoleEnumType.HERO },
			{ "in the background on the left", PbShotTypeEnumType.ESTABLISHING, PbActorRoleEnumType.CROWD },
			{ "centre", null, PbActorRoleEnumType.SUPPORTING },
			{ "flibbertigibbet", PbShotTypeEnumType.CLOSE_UP, null },
			{ null, PbShotTypeEnumType.UNKNOWN, PbActorRoleEnumType.EXTRA },
		};
		for (Object[] in : inputs) {
			String text = (String) in[0];
			PbShotTypeEnumType shot = (PbShotTypeEnumType) in[1];
			PbActorRoleEnumType role = (PbActorRoleEnumType) in[2];
			Placement first = PbLayoutUtil.place(text, shot, role);
			Placement firstDefault = PbLayoutUtil.defaultPlacement(4, 7, shot, role);
			assertNotNull(first.toString());
			assertTrue("toString names the shot type used: " + first, first.toString().contains(first.shotType().name()));
			assertTrue("toString names the shot type used: " + first, first.toString().contains(effective(shot).name()));
			assertTrue("toString names the horizontal: " + first, first.toString().contains(first.horizontal().name()));
			assertTrue("toString names the plane: " + first, first.toString().contains(first.plane().name()));
			assertEquals("toString flags defaults: " + first, !first.recognized(), first.toString().contains("(default)"));
			assertTrue(firstDefault.toString().contains("(default)"));
			for (int i = 0; i < 1000; i++) {
				assertSamePlacement("iteration " + i + " of '" + text + "'", first, PbLayoutUtil.place(text, shot, role));
				assertSamePlacement("iteration " + i + " default of '" + text + "'", firstDefault, PbLayoutUtil.defaultPlacement(4, 7, shot, role));
			}
		}
		/// Parsing is pure too.
		for (int i = 0; i < 1000; i++) {
			assertEquals(PbShotTypeEnumType.CLOSE_UP, PbLayoutUtil.parseShotType("close-up"));
			assertEquals(PbViewpointEnumType.BIRDS_EYE, PbLayoutUtil.parseViewpoint("bird's eye view"));
			assertEquals(List.of("farleft", "background"), PbLayoutUtil.tokens("Far left, in the background"));
		}
	}

	@Test
	public void templatesAreInternallyConsistent() {
		Object[][] templates = {
			{ "CLOSE_UP", PbLayoutUtil.T_CLOSE_UP }, { "MEDIUM", PbLayoutUtil.T_MEDIUM }, { "FULL_BODY", PbLayoutUtil.T_FULL_BODY },
			{ "WIDE", PbLayoutUtil.T_WIDE }, { "ESTABLISHING", PbLayoutUtil.T_ESTABLISHING },
		};
		for (Object[] e : templates) {
			String name = (String) e[0];
			Template t = (Template) e[1];
			assertEquals(name + " centreX per Horizontal", Horizontal.values().length, t.centreX.length);
			assertEquals(name + " w per Plane", Plane.values().length, t.w.length);
			assertEquals(name + " h per Plane", Plane.values().length, t.h.length);
			assertEquals(name + " y per Plane", Plane.values().length, t.y.length);
			assertTrue(name + " centreX L < C", t.centreX[Horizontal.LEFT.ordinal()] < t.centreX[Horizontal.CENTRE.ordinal()]);
			assertTrue(name + " centreX C < R", t.centreX[Horizontal.CENTRE.ordinal()] < t.centreX[Horizontal.RIGHT.ordinal()]);
			for (Horizontal h : Horizontal.values()) {
				assertTrue(name + " centreX " + h + " inside the frame", t.centreX[h.ordinal()] > 0d && t.centreX[h.ordinal()] < 1d);
			}
			for (Plane pl : Plane.values()) {
				int i = pl.ordinal();
				assertTrue(name + " " + pl + " 0 < w <= 1: " + t.w[i], t.w[i] > 0d && t.w[i] <= 1d);
				assertTrue(name + " " + pl + " 0 < h <= 1: " + t.h[i], t.h[i] > 0d && t.h[i] <= 1d);
				assertTrue(name + " " + pl + " y >= 0: " + t.y[i], t.y[i] >= 0d);
				assertTrue(name + " " + pl + " y + h <= 1: " + (t.y[i] + t.h[i]), t.y[i] + t.h[i] <= 1d + EPS);
			}
			assertTrue(name + " w FG > MG", t.w[0] > t.w[1]);
			assertTrue(name + " w MG > BG", t.w[1] > t.w[2]);
			assertTrue(name + " h FG > MG", t.h[0] > t.h[1]);
			assertTrue(name + " h MG > BG", t.h[1] > t.h[2]);
		}
		/// Each declared shot maps to its own template; null / UNKNOWN to MEDIUM.
		assertTrue(PbLayoutUtil.template(PbShotTypeEnumType.CLOSE_UP) == PbLayoutUtil.T_CLOSE_UP);
		assertTrue(PbLayoutUtil.template(PbShotTypeEnumType.MEDIUM) == PbLayoutUtil.T_MEDIUM);
		assertTrue(PbLayoutUtil.template(PbShotTypeEnumType.FULL_BODY) == PbLayoutUtil.T_FULL_BODY);
		assertTrue(PbLayoutUtil.template(PbShotTypeEnumType.WIDE) == PbLayoutUtil.T_WIDE);
		assertTrue(PbLayoutUtil.template(PbShotTypeEnumType.ESTABLISHING) == PbLayoutUtil.T_ESTABLISHING);
		assertTrue(PbLayoutUtil.template(PbShotTypeEnumType.UNKNOWN) == PbLayoutUtil.T_MEDIUM);
		assertTrue(PbLayoutUtil.template(null) == PbLayoutUtil.T_MEDIUM);
		assertTrue("FAR_NUDGE is a small positive fraction of the frame", PbLayoutUtil.FAR_NUDGE > 0d && PbLayoutUtil.FAR_NUDGE < 0.5d);
	}
}
