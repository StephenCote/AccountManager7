package org.cote.accountmanager.olio.picturebook;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.TreeMap;
import java.util.UUID;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.io.OrganizationContext;
import org.cote.accountmanager.io.ParameterList;
import org.cote.accountmanager.io.Query;
import org.cote.accountmanager.io.QueryUtil;
import org.cote.accountmanager.objects.tests.BaseTest;
import org.cote.accountmanager.olio.OlioContextUtil;
import org.cote.accountmanager.olio.picturebook.PbComposeUtil.Cast;
import org.cote.accountmanager.olio.schema.OlioModelNames;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.util.AttributeUtil;
import org.junit.Before;
import org.junit.Test;

/**
 * UAT-1 / KI-92 (PictureBook V3 §24): a series shares ONE {@code olio.charPerson} per cast member, and
 * every chapter's extraction used to append an unscoped {@code pbDescription} / {@code pbSceneRefs} row,
 * so {@code AttributeUtil.getAttribute} - first equal-name row, ordered by name only - handed every
 * chapter's readers whichever chapter's 2-wide job had written first.
 * <p>
 * Now each chapter writes its own {@code <attr>:<bookObjectId>} row (upserted on re-extraction), the
 * unscoped row is seeded once as the fallback, and every reader that knows the scene's book
 * ({@code PbRenderPlanUtil.actorDescription}, {@code PbComposeUtil.castBlock} / {@code collectCast}
 * tier (b), the portrait prompt in {@code resolveSceneCharacter}) reads the book's row first. This test
 * drives the package-private writer directly against the live DB as a non-admin user and reads back
 * through the same readers production uses. It also pins the {@code PbWatchedFields} projection fix:
 * the {@code olio.charPerson} refHash was blind to {@code pbDescription} (the attributes were never
 * projected), so a description edit never staled a portrait.
 */
public class TestPbSharedCharacterChapterAttributes extends BaseTest {
	public static final Logger logger = LogManager.getLogger(TestPbSharedCharacterChapterAttributes.class);

	private static final String ORG_A = "/Development/World Building";
	private static final String TEST_USER = "testUser1";
	private static final String DESC_A = "chapter A look: a tall woman with copper hair braided back and a travel-worn green cloak";
	private static final String DESC_B = "chapter B look: the same woman, hair cropped short and dyed black, in a grey court gown";
	private static final String DESC_A2 = "chapter A re-extracted: a tall woman with copper hair, a green cloak and mud-caked boots";

	@Before
	public void setupEach() {
		OlioModelNames.use();
		OlioContextUtil.clearCache();
	}

	// ─────────────────────────────── 1. per-book rows, either write order ───────────────────────────────

	@Test
	public void perBookRowsWinInEitherWriteOrderAndReExtractionUpserts() throws Exception {
		BaseRecord u = user();
		String bookA = UUID.randomUUID().toString();
		String bookB = UUID.randomUUID().toString();
		BaseRecord cpAB = null;
		BaseRecord cpBA = null;
		try {
			/// (1) A then B - the order KI-92 happened to record in the emulator fixtures.
			cpAB = newCharacter(u, "UAT1 AB");
			PictureBookUtil.persistCharacterSceneAttributes(u, cpAB, Arrays.asList(0, 2), DESC_A, bookA);
			PictureBookUtil.persistCharacterSceneAttributes(u, cpAB, Arrays.asList(1), DESC_B, bookB);
			BaseRecord r = reread(u, cpAB);
			TreeMap<String, Integer> counts = rowCounts(r);
			log("rows after A,B: " + counts);
			assertEquals("one unscoped pbDescription (seeded once)", Integer.valueOf(1), counts.get(PictureBookUtil.ATTR_DESCRIPTION));
			assertEquals("one unscoped pbSceneRefs (seeded once)", Integer.valueOf(1), counts.get(PictureBookUtil.ATTR_SCENE_REFS));
			assertEquals(Integer.valueOf(1), counts.get(scoped(PictureBookUtil.ATTR_DESCRIPTION, bookA)));
			assertEquals(Integer.valueOf(1), counts.get(scoped(PictureBookUtil.ATTR_DESCRIPTION, bookB)));
			assertEquals(Integer.valueOf(1), counts.get(scoped(PictureBookUtil.ATTR_SCENE_REFS, bookA)));
			assertEquals(Integer.valueOf(1), counts.get(scoped(PictureBookUtil.ATTR_SCENE_REFS, bookB)));

			/// the reader each chapter uses finds ITS chapter's text
			assertEquals(DESC_A, PictureBookUtil.readBookScopedAttribute(r, PictureBookUtil.ATTR_DESCRIPTION, bookA));
			assertEquals(DESC_B, PictureBookUtil.readBookScopedAttribute(r, PictureBookUtil.ATTR_DESCRIPTION, bookB));
			assertEquals("0,2", PictureBookUtil.readBookScopedAttribute(r, PictureBookUtil.ATTR_SCENE_REFS, bookA));
			assertEquals("1", PictureBookUtil.readBookScopedAttribute(r, PictureBookUtil.ATTR_SCENE_REFS, bookB));
			/// the unscoped fallback is the first writer's text - for a book-less reader, and for a book with no row of its own
			assertEquals(DESC_A, PictureBookUtil.readBookScopedAttribute(r, PictureBookUtil.ATTR_DESCRIPTION, null));
			assertEquals(DESC_A, PictureBookUtil.readBookScopedAttribute(r, PictureBookUtil.ATTR_DESCRIPTION, "   "));
			assertEquals(DESC_A, PictureBookUtil.readBookScopedAttribute(r, PictureBookUtil.ATTR_DESCRIPTION, UUID.randomUUID().toString()));
			assertEquals("0,2", PictureBookUtil.readBookScopedAttribute(r, PictureBookUtil.ATTR_SCENE_REFS, null));

			/// the production readers: the render plan's actor line and the compose-scene cast block
			assertEquals(DESC_B, PbRenderPlanUtil.actorDescription(r, bookB));
			assertEquals(DESC_A, PbRenderPlanUtil.actorDescription(r, bookA));
			assertEquals("one-arg form stays on the unscoped row", DESC_A, PbRenderPlanUtil.actorDescription(r));
			Cast cast = new Cast();
			cast.members = new ArrayList<>(Arrays.asList(r));
			String blockB = PbComposeUtil.castBlock(cast, bookB);
			String blockA = PbComposeUtil.castBlock(cast, bookA);
			String blockNone = PbComposeUtil.castBlock(cast);
			log("castBlock(B) -> " + blockB);
			assertTrue("cast block for B speaks B's description", blockB.contains(DESC_B));
			assertFalse("cast block for B does not speak A's description", blockB.contains(DESC_A));
			assertTrue("cast block for A speaks A's description", blockA.contains(DESC_A));
			assertTrue("book-less cast block speaks the unscoped (first writer's) description", blockNone.contains(DESC_A));

			/// (2) re-extracting A overwrites A's row in place - no duplicate, and the unscoped seed is untouched
			PictureBookUtil.persistCharacterSceneAttributes(u, cpAB, Arrays.asList(0, 2, 3), DESC_A2, bookA);
			BaseRecord r2 = reread(u, cpAB);
			TreeMap<String, Integer> counts2 = rowCounts(r2);
			log("rows after re-extracting A: " + counts2);
			assertEquals("still one row for A", Integer.valueOf(1), counts2.get(scoped(PictureBookUtil.ATTR_DESCRIPTION, bookA)));
			assertEquals("still one scene-refs row for A", Integer.valueOf(1), counts2.get(scoped(PictureBookUtil.ATTR_SCENE_REFS, bookA)));
			assertEquals("still one unscoped row", Integer.valueOf(1), counts2.get(PictureBookUtil.ATTR_DESCRIPTION));
			assertEquals(DESC_A2, PictureBookUtil.readBookScopedAttribute(r2, PictureBookUtil.ATTR_DESCRIPTION, bookA));
			assertEquals("0,2,3", PictureBookUtil.readBookScopedAttribute(r2, PictureBookUtil.ATTR_SCENE_REFS, bookA));
			assertEquals("B's row is untouched by A's re-extraction", DESC_B, PictureBookUtil.readBookScopedAttribute(r2, PictureBookUtil.ATTR_DESCRIPTION, bookB));
			assertEquals("the unscoped seed is not rewritten", DESC_A, PictureBookUtil.readBookScopedAttribute(r2, PictureBookUtil.ATTR_DESCRIPTION, null));

			/// (3) B then A - the other interleaving of the 2-wide chapter jobs. Each book still reads its own text.
			cpBA = newCharacter(u, "UAT1 BA");
			PictureBookUtil.persistCharacterSceneAttributes(u, cpBA, Arrays.asList(1), DESC_B, bookB);
			PictureBookUtil.persistCharacterSceneAttributes(u, cpBA, Arrays.asList(0, 2), DESC_A, bookA);
			BaseRecord s = reread(u, cpBA);
			assertEquals(DESC_A, PbRenderPlanUtil.actorDescription(s, bookA));
			assertEquals(DESC_B, PbRenderPlanUtil.actorDescription(s, bookB));
			assertEquals("the unscoped seed is the FIRST writer's, whichever chapter that was", DESC_B, PbRenderPlanUtil.actorDescription(s));
			assertEquals("one unscoped row", Integer.valueOf(1), rowCounts(s).get(PictureBookUtil.ATTR_DESCRIPTION));

			/// (4) a legacy / fixture row written directly (no book) still reads through the scoped reader
			BaseRecord cpLegacy = newCharacter(u, "UAT1 Legacy");
			try {
				assertTrue(IOSystem.getActiveContext().getRecordUtil().createRecord(
					AttributeUtil.addAttribute(cpLegacy, PictureBookUtil.ATTR_DESCRIPTION, DESC_A)));
				BaseRecord l = reread(u, cpLegacy);
				assertEquals(DESC_A, PictureBookUtil.readBookScopedAttribute(l, PictureBookUtil.ATTR_DESCRIPTION, bookA));
				assertEquals(DESC_A, PbRenderPlanUtil.actorDescription(l, bookB));
				assertNull("no scene refs at all reads null, not blank", PictureBookUtil.readBookScopedAttribute(l, PictureBookUtil.ATTR_SCENE_REFS, bookA));
			}
			finally {
				deleteQuietly(u, cpLegacy);
			}

			/// (5) the legacy writer shape (no book) appends unscoped rows exactly as before
			PictureBookUtil.persistCharacterSceneAttributes(u, cpBA, Arrays.asList(5), DESC_A2, null);
			TreeMap<String, Integer> counts3 = rowCounts(reread(u, cpBA));
			log("rows after a book-less write: " + counts3);
			assertEquals("book-less write appends a second unscoped row (pre-UAT-1 behaviour kept)", Integer.valueOf(2), counts3.get(PictureBookUtil.ATTR_DESCRIPTION));
			assertEquals("a blank book scopes to the unscoped name", PictureBookUtil.ATTR_DESCRIPTION, scoped(PictureBookUtil.ATTR_DESCRIPTION, ""));
			assertEquals("book-less write creates no scoped row (A and B only)", 2, scopedRowCount(counts3, PictureBookUtil.ATTR_DESCRIPTION));
			log("perBookRowsWinInEitherWriteOrderAndReExtractionUpserts done");
		}
		finally {
			deleteQuietly(u, cpAB);
			deleteQuietly(u, cpBA);
		}
	}

	// ─────────────────────────────── 2. the refHash sees the description ───────────────────────────────

	/// Before the projection fix PbWatchedFields.projection(olio.charPerson) never requested the attributes, so
	/// the declared watched pbDescription rendered as the null token forever and a description edit changed
	/// nothing. Now the hash moves when the description is written, the canonical form carries the text, the
	/// scoped rows are rendered sorted (so two persons whose chapters ran in opposite orders render the same
	/// scoped segment), and an unrelated attribute does not move the hash.
	@Test
	public void refHashSeesTheDescriptionAndRendersScopedRowsOrderIndependent() throws Exception {
		BaseRecord u = user();
		String bookA = UUID.randomUUID().toString();
		String bookB = UUID.randomUUID().toString();
		BaseRecord cpAB = null;
		BaseRecord cpBA = null;
		try {
			cpAB = newCharacter(u, "UAT1 Hash AB");
			String oidAB = cpAB.get(FieldNames.FIELD_OBJECT_ID);
			String[] projection = PbWatchedFields.projection(OlioModelNames.MODEL_CHAR_PERSON);
			log("projection -> " + Arrays.toString(projection));
			assertTrue("the charPerson projection loads the attributes", Arrays.asList(projection).contains(FieldNames.FIELD_ATTRIBUTES));

			String h0 = PbWatchedFields.computeRefHash(u, OlioModelNames.MODEL_CHAR_PERSON, oidAB);
			assertNotNull("hash with no description", h0);
			String c0 = PbWatchedFields.canonicalRef(readProjected(u, oidAB), OlioModelNames.MODEL_CHAR_PERSON);
			assertTrue("no description renders the null token", c0.contains("@" + PictureBookUtil.ATTR_DESCRIPTION + "=" + PbConfigUtil.NULL_TOKEN));

			/// an unscoped description (a hand edit, a legacy row) moves the hash and is in the canonical form
			assertTrue(IOSystem.getActiveContext().getRecordUtil().createRecord(
				AttributeUtil.addAttribute(cpAB, PictureBookUtil.ATTR_DESCRIPTION, DESC_A)));
			String h1 = PbWatchedFields.computeRefHash(u, OlioModelNames.MODEL_CHAR_PERSON, oidAB);
			String c1 = PbWatchedFields.canonicalRef(readProjected(u, oidAB), OlioModelNames.MODEL_CHAR_PERSON);
			log("canonical after unscoped description:\n" + c1);
			assertNotEquals("writing pbDescription changes the refHash", h0, h1);
			assertTrue("the canonical form carries the description", c1.contains("@" + PictureBookUtil.ATTR_DESCRIPTION + "=" + DESC_A));

			/// chapter rows: A then B
			PictureBookUtil.persistCharacterSceneAttributes(u, cpAB, Arrays.asList(0), DESC_A, bookA);
			PictureBookUtil.persistCharacterSceneAttributes(u, cpAB, Arrays.asList(1), DESC_B, bookB);
			String h2 = PbWatchedFields.computeRefHash(u, OlioModelNames.MODEL_CHAR_PERSON, oidAB);
			String c2 = PbWatchedFields.canonicalRef(readProjected(u, oidAB), OlioModelNames.MODEL_CHAR_PERSON);
			log("canonical after A,B:\n" + c2);
			assertNotEquals("a chapter's own description row changes the refHash", h1, h2);
			assertTrue(c2.contains("@" + scoped(PictureBookUtil.ATTR_DESCRIPTION, bookA) + "=" + DESC_A));
			assertTrue(c2.contains("@" + scoped(PictureBookUtil.ATTR_DESCRIPTION, bookB) + "=" + DESC_B));
			assertFalse("pbSceneRefs is not watched (an index list, not an appearance)", c2.contains("@" + PictureBookUtil.ATTR_SCENE_REFS));

			/// editing one chapter's row moves the hash; the hash is stable across identical reads
			PictureBookUtil.persistCharacterSceneAttributes(u, cpAB, Arrays.asList(0), DESC_A2, bookA);
			String h3 = PbWatchedFields.computeRefHash(u, OlioModelNames.MODEL_CHAR_PERSON, oidAB);
			assertNotEquals("re-extracting one chapter with a new description changes the refHash", h2, h3);
			assertEquals("the hash is stable", h3, PbWatchedFields.computeRefHash(u, OlioModelNames.MODEL_CHAR_PERSON, oidAB));

			/// an unrelated attribute does not move it
			assertTrue(IOSystem.getActiveContext().getRecordUtil().createRecord(
				AttributeUtil.addAttribute(cpAB, "uat1.unrelated", "x")));
			assertEquals("an unwatched attribute leaves the refHash alone", h3, PbWatchedFields.computeRefHash(u, OlioModelNames.MODEL_CHAR_PERSON, oidAB));

			/// B then A on a second person renders the same scoped segment (sorted by name, not by write order)
			cpBA = newCharacter(u, "UAT1 Hash BA");
			String oidBA = cpBA.get(FieldNames.FIELD_OBJECT_ID);
			PictureBookUtil.persistCharacterSceneAttributes(u, cpBA, Arrays.asList(1), DESC_B, bookB);
			PictureBookUtil.persistCharacterSceneAttributes(u, cpBA, Arrays.asList(0), DESC_A2, bookA);
			String cAB = PbWatchedFields.canonicalRef(readProjected(u, oidAB), OlioModelNames.MODEL_CHAR_PERSON);
			String cBA = PbWatchedFields.canonicalRef(readProjected(u, oidBA), OlioModelNames.MODEL_CHAR_PERSON);
			String segAB = scopedSegment(cAB);
			String segBA = scopedSegment(cBA);
			log("scoped segment AB:\n" + segAB + "\nscoped segment BA:\n" + segBA);
			assertTrue("scoped segment is non-empty", segAB.contains(bookA) && segAB.contains(bookB));
			assertEquals("the scoped rows render identically whichever chapter wrote first", segAB, segBA);
			log("refHashSeesTheDescriptionAndRendersScopedRowsOrderIndependent done");
		}
		finally {
			deleteQuietly(u, cpAB);
			deleteQuietly(u, cpBA);
		}
	}

	// ─────────────────────────────── helpers ───────────────────────────────

	/// The lines of the canonical form from the first scoped pbDescription pair up to (excluding) the store.apparel pair.
	private static String scopedSegment(String canonical) {
		String[] lines = canonical.split(PbConfigUtil.PAIR_SEPARATOR);
		StringBuilder sb = new StringBuilder();
		for(String line : lines) {
			if(line.startsWith("@" + PictureBookUtil.ATTR_DESCRIPTION + PictureBookUtil.ATTR_BOOK_SCOPE_SEPARATOR)) {
				sb.append(line).append('\n');
			}
		}
		return sb.toString();
	}

	private static String scoped(String attr, String bookObjectId) {
		return PictureBookUtil.bookScopedAttributeName(attr, bookObjectId);
	}

	/// How many rows carry a book scope for {@code attr} ({@code attr:<anything>}), excluding the bare row.
	private static int scopedRowCount(TreeMap<String, Integer> counts, String attr) {
		String prefix = attr + PictureBookUtil.ATTR_BOOK_SCOPE_SEPARATOR;
		int n = 0;
		for(String k : counts.keySet()) {
			if(k.startsWith(prefix) && k.length() > prefix.length()) {
				n += counts.get(k);
			}
		}
		return n;
	}

	private static TreeMap<String, Integer> rowCounts(BaseRecord rec) {
		TreeMap<String, Integer> counts = new TreeMap<>();
		List<BaseRecord> attrs = rec.get(FieldNames.FIELD_ATTRIBUTES);
		assertNotNull("attributes projected", attrs);
		for(BaseRecord a : attrs) {
			String n = a.get(FieldNames.FIELD_NAME);
			counts.merge(n, 1, Integer::sum);
		}
		return counts;
	}

	private static BaseRecord reread(BaseRecord u, BaseRecord cp) {
		Query q = QueryUtil.createQuery(OlioModelNames.MODEL_CHAR_PERSON, FieldNames.FIELD_OBJECT_ID, cp.get(FieldNames.FIELD_OBJECT_ID));
		q.field(FieldNames.FIELD_ORGANIZATION_ID, u.get(FieldNames.FIELD_ORGANIZATION_ID));
		q.setRequest(new String[] { FieldNames.FIELD_ID, FieldNames.FIELD_OBJECT_ID, FieldNames.FIELD_NAME,
			FieldNames.FIELD_ORGANIZATION_ID, FieldNames.FIELD_ATTRIBUTES });
		q.setCache(false);
		BaseRecord r = IOSystem.getActiveContext().getAccessPoint().find(u, q);
		assertNotNull("re-read " + cp.get(FieldNames.FIELD_NAME), r);
		return r;
	}

	private static BaseRecord readProjected(BaseRecord u, String oid) {
		Query q = QueryUtil.createQuery(OlioModelNames.MODEL_CHAR_PERSON, FieldNames.FIELD_OBJECT_ID, oid);
		q.setRequest(PbWatchedFields.projection(OlioModelNames.MODEL_CHAR_PERSON));
		q.setCache(false);
		BaseRecord r = IOSystem.getActiveContext().getAccessPoint().find(u, q);
		assertNotNull("projected read " + oid, r);
		return r;
	}

	/// A charPerson in the caller's home (no book, no olio world): the attribute mechanism is the same, and
	/// it keeps the test to the attribute rows it is about.
	private BaseRecord newCharacter(BaseRecord u, String label) throws Exception {
		String name = label + " " + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
		ParameterList plist = ParameterList.newParameterList(FieldNames.FIELD_PATH, "~/Characters");
		plist.parameter(FieldNames.FIELD_NAME, name);
		BaseRecord cp = IOSystem.getActiveContext().getFactory().newInstance(OlioModelNames.MODEL_CHAR_PERSON, u, null, plist);
		cp.set(FieldNames.FIELD_NAME, name);
		cp.set(FieldNames.FIELD_FIRST_NAME, label.split(" ")[0]);
		cp.set(FieldNames.FIELD_GENDER, "female");
		BaseRecord created = ioContext.getAccessPoint().create(u, cp);
		assertNotNull("create character " + name, created);
		cp.set(FieldNames.FIELD_ID, created.get(FieldNames.FIELD_ID));
		cp.set(FieldNames.FIELD_OBJECT_ID, created.get(FieldNames.FIELD_OBJECT_ID));
		log("character " + name + " " + created.get(FieldNames.FIELD_OBJECT_ID));
		return cp;
	}

	private void deleteQuietly(BaseRecord u, BaseRecord rec) {
		if(rec == null) {
			return;
		}
		try {
			if(!ioContext.getAccessPoint().delete(u, rec)) {
				logger.warn("cleanup: could not delete " + rec.get(FieldNames.FIELD_NAME));
			}
		}
		catch(Exception e) {
			logger.warn("cleanup: " + e.getMessage());
		}
	}

	private BaseRecord user() {
		OrganizationContext org = getTestOrganization(ORG_A);
		assertNotNull("Failed to resolve " + ORG_A, org);
		BaseRecord u = ioContext.getFactory().getCreateUser(org.getAdminUser(), TEST_USER, org.getOrganizationId());
		assertNotNull("Failed to resolve " + TEST_USER, u);
		return u;
	}

	private static void log(String s) {
		logger.info(s);
	}
}
