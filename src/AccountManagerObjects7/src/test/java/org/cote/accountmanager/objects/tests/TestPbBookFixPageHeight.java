package org.cote.accountmanager.objects.tests;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.io.OrganizationContext;
import org.cote.accountmanager.io.Query;
import org.cote.accountmanager.io.QueryUtil;
import org.cote.accountmanager.olio.OlioContextUtil;
import org.cote.accountmanager.olio.picturebook.PbBookUtil;
import org.cote.accountmanager.olio.schema.OlioFieldNames;
import org.cote.accountmanager.olio.schema.OlioModelNames;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.record.RecordFactory;
import org.cote.accountmanager.schema.FieldNames;
import org.junit.Before;
import org.junit.Test;

/**
 * Workstream C round-trip: the new persisted boolean {@code olio.pb.book.fixPageHeight}.
 * <p>
 * ChapBook clamps a rendered page to a fixed height (overflow hidden) instead of growing vertically
 * when this flag is set; the frontend render work is separate, so this only proves the field persists
 * and round-trips through a PATCH.
 * <p>
 * No LLM and no SD are involved — this is a pure persistence test against the live DB. It reuses the
 * shared, corpus-loaded {@code /Development/World Building} organization and a stable book slug so the
 * Olio world is created once and re-opened on later runs (creating a world costs a corpus load).
 * <p>
 * The PATCH is deliberately built the safe way: an explicit field-name array on
 * {@link RecordFactory#newInstance(String, String[])} (NOT the bare {@code newInstance(model)}, which
 * would materialise and re-persist every field at its default), carrying {@code schema} + identity
 * ({@code id}/{@code objectId}) + the model's locally-declared, {@code \S}-validated {@code name}
 * (omitting it fails writer validation silently) + the one changed field. The update result is asserted,
 * never discarded.
 */
public class TestPbBookFixPageHeight extends BaseTest {

	/** Pre-existing, corpus-loaded organization — same shared fixture org as TestPbGraph. */
	private static final String ORG_A = "/Development/World Building";
	private static final String TEST_USER = "testUser1";

	/** Stable slug: the book world is created once and re-opened on later runs. */
	private static final String SLUG = "pb-fixpageheight";

	@Before
	public void fixPageHeightSetup() {
		OlioContextUtil.clearCache();
	}

	private String dataPath() {
		return testProperties.getProperty("test.datagen.path");
	}

	private BaseRecord user() {
		OrganizationContext org = getTestOrganization(ORG_A);
		BaseRecord u = ioContext.getFactory().getCreateUser(org.getAdminUser(), TEST_USER, org.getOrganizationId());
		assertNotNull("Failed to resolve " + TEST_USER, u);
		return u;
	}

	/**
	 * Get-or-create the shared book. {@code PbBookUtil.createBook} refuses a duplicate slug with a 409 by
	 * design (the unique index is the serialization point), so a reusable fixture has to probe first.
	 */
	private BaseRecord book(BaseRecord u) {
		long orgId = ((Number) u.get(FieldNames.FIELD_ORGANIZATION_ID)).longValue();
		BaseRecord existing = PbBookUtil.findBookBySlug(u, SLUG, orgId);
		if(existing != null) {
			return existing;
		}
		BaseRecord created = PbBookUtil.createBook(u, dataPath(), SLUG, "fixPageHeight round-trip fixture");
		assertNotNull("Failed to create the fixture book", created);
		return created;
	}

	/**
	 * Read the book by objectId with {@code fixPageHeight} explicitly projected (it is not a default
	 * query field, so without the projection it comes back null even when persisted). Uncached, with the
	 * required numeric {@code organizationId} condition.
	 */
	private BaseRecord readBookWithFlag(BaseRecord u, String objectId, long orgId) {
		Query q = QueryUtil.createQuery(OlioModelNames.MODEL_PB_BOOK, FieldNames.FIELD_OBJECT_ID, objectId);
		q.field(FieldNames.FIELD_ORGANIZATION_ID, orgId);
		q.setRequest(new String[] {
			FieldNames.FIELD_ID, FieldNames.FIELD_OBJECT_ID, FieldNames.FIELD_NAME,
			FieldNames.FIELD_GROUP_ID, FieldNames.FIELD_ORGANIZATION_ID, FieldNames.FIELD_OWNER_ID,
			OlioFieldNames.FIELD_PB_FIX_PAGE_HEIGHT
		});
		q.setCache(false);
		return IOSystem.getActiveContext().getAccessPoint().find(u, q);
	}

	@Test
	public void testFixPageHeightRoundTrips() throws Exception {
		String dataPath = dataPath();
		assertNotNull("test.datagen.path must be set", dataPath);

		BaseRecord u = user();
		long orgId = ((Number) u.get(FieldNames.FIELD_ORGANIZATION_ID)).longValue();

		BaseRecord bk = book(u);
		String objectId = bk.get(FieldNames.FIELD_OBJECT_ID);
		assertNotNull("Fixture book must have an objectId", objectId);

		// ── Baseline: the flag defaults to non-true (null/false) before any patch ──
		BaseRecord before = readBookWithFlag(u, objectId, orgId);
		assertNotNull("Book must be readable with fixPageHeight projected", before);
		Object beforeVal = before.get(OlioFieldNames.FIELD_PB_FIX_PAGE_HEIGHT);
		assertFalse("fixPageHeight must default to non-true (null or false) before the patch; got: " + beforeVal,
			Boolean.TRUE.equals(beforeVal));

		// ── PATCH: schema + identity (id/objectId) + validated name + the one changed field ──
		// Explicit field-name array so the bare newInstance overload does NOT blank every other field.
		List<String> fields = new ArrayList<>(Arrays.asList(
			FieldNames.FIELD_ID, FieldNames.FIELD_OBJECT_ID, FieldNames.FIELD_NAME,
			OlioFieldNames.FIELD_PB_FIX_PAGE_HEIGHT));
		BaseRecord patch = RecordFactory.newInstance(OlioModelNames.MODEL_PB_BOOK, fields.toArray(new String[0]));
		patch.set(FieldNames.FIELD_ID, before.get(FieldNames.FIELD_ID));
		patch.set(FieldNames.FIELD_OBJECT_ID, before.get(FieldNames.FIELD_OBJECT_ID));
		// olio.pb.book declares name locally with a \S rule — the writer validates the patch record
		// itself, so omitting name would fail validation silently.
		patch.set(FieldNames.FIELD_NAME, before.get(FieldNames.FIELD_NAME));
		assertNotNull("Patch must carry the validated name (never null)", patch.get(FieldNames.FIELD_NAME));
		patch.set(OlioFieldNames.FIELD_PB_FIX_PAGE_HEIGHT, true);

		// Never discard the update result — a null here is the only signal the write failed.
		BaseRecord updated = IOSystem.getActiveContext().getAccessPoint().update(u, patch);
		assertNotNull("AccessPoint.update must not return null (a null is a silent persistence failure)", updated);

		// ── Read back (cache:false, flag projected) and assert it round-tripped to true ──
		BaseRecord after = readBookWithFlag(u, objectId, orgId);
		assertNotNull("Book must be re-readable after the patch", after);
		Object afterVal = after.get(OlioFieldNames.FIELD_PB_FIX_PAGE_HEIGHT);
		assertNotNull("fixPageHeight must be non-null after the patch", afterVal);
		assertTrue("fixPageHeight must round-trip to true after the patch; got: " + afterVal,
			Boolean.TRUE.equals(afterVal));

		logger.info("testFixPageHeightRoundTrips: book {} fixPageHeight {} -> {}",
			objectId, beforeVal, afterVal);

		// ── Flip it back to false and confirm that also persists (the field is a real boolean column) ──
		BaseRecord patchOff = RecordFactory.newInstance(OlioModelNames.MODEL_PB_BOOK, fields.toArray(new String[0]));
		patchOff.set(FieldNames.FIELD_ID, after.get(FieldNames.FIELD_ID));
		patchOff.set(FieldNames.FIELD_OBJECT_ID, after.get(FieldNames.FIELD_OBJECT_ID));
		patchOff.set(FieldNames.FIELD_NAME, after.get(FieldNames.FIELD_NAME));
		patchOff.set(OlioFieldNames.FIELD_PB_FIX_PAGE_HEIGHT, false);
		BaseRecord updatedOff = IOSystem.getActiveContext().getAccessPoint().update(u, patchOff);
		assertNotNull("AccessPoint.update (flip to false) must not return null", updatedOff);

		BaseRecord afterOff = readBookWithFlag(u, objectId, orgId);
		assertNotNull("Book must be re-readable after the second patch", afterOff);
		Object afterOffVal = afterOff.get(OlioFieldNames.FIELD_PB_FIX_PAGE_HEIGHT);
		assertFalse("fixPageHeight must round-trip back to false; got: " + afterOffVal,
			Boolean.TRUE.equals(afterOffVal));
	}
}
