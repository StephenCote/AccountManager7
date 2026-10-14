package org.cote.accountmanager.objects.tests;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import org.cote.accountmanager.cache.CacheUtil;
import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.io.OrganizationContext;
import org.cote.accountmanager.io.Query;
import org.cote.accountmanager.io.QueryUtil;
import org.cote.accountmanager.olio.OlioContext;
import org.cote.accountmanager.olio.OlioContextUtil;
import org.cote.accountmanager.olio.picturebook.PbBookUtil;
import org.cote.accountmanager.olio.picturebook.PbSeriesUtil;
import org.cote.accountmanager.olio.picturebook.PbStyleUtil;
import org.cote.accountmanager.olio.schema.OlioFieldNames;
import org.cote.accountmanager.olio.schema.OlioModelNames;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.record.RecordFactory;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.schema.type.GroupEnumType;
import org.cote.accountmanager.schema.type.PbModelTaskEnumType;
import org.cote.accountmanager.schema.type.PbNodeTypeEnumType;
import org.cote.accountmanager.schema.type.PbPageFormatEnumType;
import org.cote.accountmanager.schema.type.PbQualityPresetEnumType;
import org.cote.accountmanager.schema.type.PbStrategyEnumType;
import org.cote.accountmanager.schema.type.PbStyleScopeEnumType;
import org.cote.accountmanager.util.ByteModelUtil;
import org.junit.Before;
import org.junit.Test;

/**
 * V3 Phase 2, P2-1: {@code olio.pb.style} round trips through {@code AccessPoint} against the live
 * {@code am7db} as a non-admin user - every column, the two FK links from book and series, the
 * {@code parent} self-reference, and the participation-backed {@code referenceImages}. The table and its
 * participation table are created by the first {@code IOSystem.open()} after the model was registered
 * (Path 1, objects7-reference.md); a column or index that did not get created shows up here as a failed
 * write or a null read, never as a compile error.
 * <p>
 * Same fixture as {@link TestPbV3Schema}: org {@code /Development/World Building}, user {@code testUser1},
 * book {@code pb2c-graph}. Book-scope styles are created in the book's own {@code Book} group as the test
 * user - the book {@code Writer} role granted by {@code PbOlioContextUtil.getCreateBookContext} is what
 * authorizes that write, exactly as it authorizes nodes and artifacts. A PBAC denial there is a finding
 * about the grant, not something for this test to route around.
 */
public class TestPbStyleSchema extends BaseTest {

	private static final String ORG_A = "/Development/World Building";
	private static final String TEST_USER = "testUser1";
	private static final String SLUG = "pb2c-graph";
	/// Stable: the series world is a corpus load, so one series is created once and reused across runs.
	private static final String SERIES_SLUG = "p21style";
	private static final String SCRATCH_PATH = "~/PbStyleRefs";
	/// A second non-admin user in the same organization with no role on the fixture book (case 9). Its own
	/// name, not TestPbSecurity's pbSecUserB, so no grant made over there can satisfy a read here by accident.
	private static final String STRANGER_USER = "p21StyleStranger";

	@Before
	public void schemaSetup() {
		OlioContextUtil.clearCache();
		IOSystem.getActiveContext().getAccessPoint().setPermitBulkContainerApproval(false);
	}

	private static String shortId() {
		return UUID.randomUUID().toString().replace("-", "").substring(0, 8);
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

	private long orgOf(BaseRecord u) {
		return (long) u.get(FieldNames.FIELD_ORGANIZATION_ID);
	}

	private BaseRecord book(BaseRecord u) {
		BaseRecord existing = PbBookUtil.findBookBySlug(u, SLUG, orgOf(u));
		if(existing != null) {
			return existing;
		}
		BaseRecord created = PbBookUtil.createBook(u, dataPath(), SLUG, "Phase 2c graph fixture");
		assertNotNull("Failed to create the fixture book", created);
		return created;
	}

	private String bookGroup() {
		return PbBookUtil.bookGroupPath(SLUG);
	}

	/// An unpersisted style with ownership and group applied. The model does not inherit common.name,
	/// so applyNameGroupOwnership does not set the name (TestPbModelSchema documents this) - set it here.
	private BaseRecord newStyle(BaseRecord u, String name, String groupPath) {
		BaseRecord style = null;
		try {
			style = RecordFactory.newInstance(OlioModelNames.MODEL_PB_STYLE);
			ioContext.getRecordUtil().applyNameGroupOwnership(u, style, name, groupPath, orgOf(u));
			style.set(FieldNames.FIELD_NAME, name);
		}
		catch(Exception e) {
			fail("Failed to assemble olio.pb.style '" + name + "': " + e.getMessage());
		}
		return style;
	}

	private BaseRecord createStyle(BaseRecord u, BaseRecord style) {
		BaseRecord created = ioContext.getAccessPoint().create(u, style);
		assertNotNull("AccessPoint.create must accept olio.pb.style '" + style.get(FieldNames.FIELD_NAME)
			+ "' in " + style.get(FieldNames.FIELD_GROUP_ID), created);
		assertNotNull("create returns identity", created.get(FieldNames.FIELD_OBJECT_ID));
		return created;
	}

	private BaseRecord read(BaseRecord u, String objectId) {
		BaseRecord r = PbStyleUtil.readStyle(u, objectId, orgOf(u));
		assertNotNull("readStyle must find " + objectId + " as the owning user", r);
		return r;
	}

	/// get-or-create by name in a group, for the styles that fixture rows keep pointing at across runs.
	private BaseRecord getCreateStyle(BaseRecord u, String name, String groupPath, PbStyleScopeEnumType scope) {
		long orgId = orgOf(u);
		BaseRecord dir = ioContext.getPathUtil().makePath(u, ModelNames.MODEL_GROUP, groupPath,
			GroupEnumType.DATA.toString(), orgId);
		assertNotNull("Failed to resolve " + groupPath, dir);
		long groupId = dir.get(FieldNames.FIELD_ID);
		Query q = QueryUtil.createQuery(OlioModelNames.MODEL_PB_STYLE, FieldNames.FIELD_NAME, name);
		q.field(FieldNames.FIELD_ORGANIZATION_ID, orgId);
		q.field(FieldNames.FIELD_GROUP_ID, groupId);
		q.setRequest(PbStyleUtil.styleRequest());
		q.setCache(false);
		BaseRecord existing = ioContext.getAccessPoint().find(u, q);
		if(existing != null) {
			return existing;
		}
		BaseRecord style = newStyle(u, name, groupPath);
		try {
			style.set(OlioFieldNames.FIELD_PB_SCOPE, scope.toString());
		}
		catch(Exception e) {
			fail("Failed to set scope: " + e.getMessage());
		}
		return read(u, createStyle(u, style).get(FieldNames.FIELD_OBJECT_ID));
	}

	private BaseRecord createSdConfig(BaseRecord u, String name) {
		BaseRecord cfg = null;
		try {
			cfg = RecordFactory.newInstance(OlioModelNames.MODEL_SD_CONFIG);
			ioContext.getRecordUtil().applyNameGroupOwnership(u, cfg, name, SCRATCH_PATH, orgOf(u));
			cfg.set(FieldNames.FIELD_NAME, name);
			cfg.set("style", "art");
		}
		catch(Exception e) {
			fail("Failed to assemble olio.sd.config: " + e.getMessage());
		}
		BaseRecord created = ioContext.getAccessPoint().create(u, cfg);
		assertNotNull("Failed to persist olio.sd.config " + name, created);
		return created;
	}

	// ───────── 1: every column round trips create → read → patch → list → delete ─────────

	@Test
	public void case01_everyColumnRoundTripsAsTheBookWriter() throws Exception {
		BaseRecord u = user();
		book(u);
		long orgId = orgOf(u);
		String tag = shortId();
		String name = "p21-full-" + tag;

		BaseRecord cfg = createSdConfig(u, "p21-sdcfg-" + tag);
		String cfgOid = cfg.get(FieldNames.FIELD_OBJECT_ID);

		BaseRecord style = newStyle(u, name, bookGroup());
		style.set(FieldNames.FIELD_DESCRIPTION, "watercolour storybook, soft edges");
		style.set(OlioFieldNames.FIELD_PB_EXCLUSIONS, "text, watermark, signature");
		style.set(OlioFieldNames.FIELD_PB_SD_CONFIG, cfg);
		style.set(OlioFieldNames.FIELD_PB_TASK_MODELS, "{\"portrait\":\"flux2Klein_9b\",\"sceneDirect\":\"flux2Klein_9b\"}");
		style.set(OlioFieldNames.FIELD_PB_PALETTE, "#f4e2c1,#8c5a3c,#2e4a62");
		style.set(OlioFieldNames.FIELD_PB_PAGE_FORMAT, PbPageFormatEnumType.CUSTOM.toString());
		style.set(OlioFieldNames.FIELD_PB_CUSTOM_WIDTH, Integer.valueOf(1536));
		style.set(OlioFieldNames.FIELD_PB_CUSTOM_HEIGHT, Integer.valueOf(1024));
		style.set(OlioFieldNames.FIELD_PB_QUALITY_PRESET, PbQualityPresetEnumType.STANDARD.toString());
		style.set(OlioFieldNames.FIELD_PB_PHOTOREAL, Boolean.TRUE);
		style.set(OlioFieldNames.FIELD_PB_LOCKED, Boolean.TRUE);
		style.set(OlioFieldNames.FIELD_PB_SCOPE, PbStyleScopeEnumType.BOOK.toString());

		BaseRecord created = createStyle(u, style);
		String oid = created.get(FieldNames.FIELD_OBJECT_ID);

		BaseRecord r = read(u, oid);
		assertEquals(name, r.get(FieldNames.FIELD_NAME));
		String urn = r.get(FieldNames.FIELD_URN);
		assertTrue("UrnProvider must compose a urn, got '" + urn + "'", urn != null && !urn.isBlank());
		assertEquals("watercolour storybook, soft edges", r.get(FieldNames.FIELD_DESCRIPTION));
		assertEquals("text, watermark, signature", r.get(OlioFieldNames.FIELD_PB_EXCLUSIONS));
		assertEquals("#f4e2c1,#8c5a3c,#2e4a62", r.get(OlioFieldNames.FIELD_PB_PALETTE));
		assertEquals("{\"portrait\":\"flux2Klein_9b\",\"sceneDirect\":\"flux2Klein_9b\"}", r.get(OlioFieldNames.FIELD_PB_TASK_MODELS));
		assertEquals(PbPageFormatEnumType.CUSTOM, r.getEnum(OlioFieldNames.FIELD_PB_PAGE_FORMAT));
		assertEquals(Integer.valueOf(1536), r.get(OlioFieldNames.FIELD_PB_CUSTOM_WIDTH));
		assertEquals(Integer.valueOf(1024), r.get(OlioFieldNames.FIELD_PB_CUSTOM_HEIGHT));
		assertEquals(PbQualityPresetEnumType.STANDARD, r.getEnum(OlioFieldNames.FIELD_PB_QUALITY_PRESET));
		assertEquals(Boolean.TRUE, r.get(OlioFieldNames.FIELD_PB_PHOTOREAL));
		assertEquals(Boolean.TRUE, r.get(OlioFieldNames.FIELD_PB_LOCKED));
		assertEquals(PbStyleScopeEnumType.BOOK, r.getEnum(OlioFieldNames.FIELD_PB_SCOPE));
		assertNull("No parent was set", r.get(OlioFieldNames.FIELD_PB_PARENT));
		BaseRecord cfgRef = r.get(OlioFieldNames.FIELD_PB_SD_CONFIG);
		assertNotNull("The sdConfig FK must resolve to a record carrying identity", cfgRef);
		assertEquals(cfgOid, cfgRef.get(FieldNames.FIELD_OBJECT_ID));
		assertFalse("styleRequest() never projects referenceImages", r.hasField(OlioFieldNames.FIELD_PB_REFERENCE_IMAGES));

		/// PATCH: identity + name (validated, so it must travel with a value) + the two changed fields.
		BaseRecord patch = RecordFactory.newInstance(OlioModelNames.MODEL_PB_STYLE, new String[] {
			FieldNames.FIELD_ID, FieldNames.FIELD_OBJECT_ID, FieldNames.FIELD_NAME,
			OlioFieldNames.FIELD_PB_EXCLUSIONS, OlioFieldNames.FIELD_PB_QUALITY_PRESET
		});
		patch.set(FieldNames.FIELD_ID, r.get(FieldNames.FIELD_ID));
		patch.set(FieldNames.FIELD_OBJECT_ID, oid);
		patch.set(FieldNames.FIELD_NAME, name);
		patch.set(OlioFieldNames.FIELD_PB_EXCLUSIONS, "text, watermark");
		patch.set(OlioFieldNames.FIELD_PB_QUALITY_PRESET, PbQualityPresetEnumType.FINAL.toString());
		assertNotNull("The style patch must be accepted by AccessPoint.update", ioContext.getAccessPoint().update(u, patch));

		BaseRecord after = read(u, oid);
		assertEquals("text, watermark", after.get(OlioFieldNames.FIELD_PB_EXCLUSIONS));
		assertEquals(PbQualityPresetEnumType.FINAL, after.getEnum(OlioFieldNames.FIELD_PB_QUALITY_PRESET));
		assertEquals("Unpatched fields are untouched", "watercolour storybook, soft edges", after.get(FieldNames.FIELD_DESCRIPTION));
		assertEquals("#f4e2c1,#8c5a3c,#2e4a62", after.get(OlioFieldNames.FIELD_PB_PALETTE));
		assertEquals(PbPageFormatEnumType.CUSTOM, after.getEnum(OlioFieldNames.FIELD_PB_PAGE_FORMAT));
		assertEquals(Integer.valueOf(1536), after.get(OlioFieldNames.FIELD_PB_CUSTOM_WIDTH));
		assertEquals(Boolean.TRUE, after.get(OlioFieldNames.FIELD_PB_LOCKED));
		assertEquals(PbStyleScopeEnumType.BOOK, after.getEnum(OlioFieldNames.FIELD_PB_SCOPE));
		assertEquals(cfgOid, ((BaseRecord) after.get(OlioFieldNames.FIELD_PB_SD_CONFIG)).get(FieldNames.FIELD_OBJECT_ID));

		/// LIST by the unique key (name, groupId, organizationId) - the shape a style picker uses.
		long groupId = after.get(FieldNames.FIELD_GROUP_ID);
		Query q = QueryUtil.createQuery(OlioModelNames.MODEL_PB_STYLE, FieldNames.FIELD_NAME, name);
		q.field(FieldNames.FIELD_ORGANIZATION_ID, orgId);
		q.field(FieldNames.FIELD_GROUP_ID, groupId);
		q.setRequest(PbStyleUtil.styleRequest());
		q.setCache(false);
		BaseRecord[] listed = ioContext.getAccessPoint().list(u, q).getResults();
		assertNotNull(listed);
		assertEquals("Exactly one style carries that name in that group", 1, listed.length);
		assertEquals(oid, listed[0].get(FieldNames.FIELD_OBJECT_ID));

		assertTrue("The owner can delete the style", ioContext.getAccessPoint().delete(u, after));
		assertNull("Deleted styles are gone from readStyle", PbStyleUtil.readStyle(u, oid, orgId));
		assertTrue("Cleanup: the scratch sdConfig", ioContext.getAccessPoint().delete(u,
			ioContext.getAccessPoint().findByObjectId(u, OlioModelNames.MODEL_SD_CONFIG, cfgOid)));
	}

	// ───────── 2: a sparse create reads UNKNOWN for every enum (no JSON defaults, TestPbModelSchema rule 3) ─────────

	@Test
	public void case02_sparseStyleReadsUnknownEnums() throws Exception {
		BaseRecord u = user();
		book(u);
		BaseRecord created = createStyle(u, newStyle(u, "p21-sparse-" + shortId(), bookGroup()));
		String oid = created.get(FieldNames.FIELD_OBJECT_ID);
		BaseRecord r = read(u, oid);
		assertEquals("scope is UNKNOWN until set", PbStyleScopeEnumType.UNKNOWN, r.getEnum(OlioFieldNames.FIELD_PB_SCOPE));
		assertEquals("pageFormat is UNKNOWN until set", PbPageFormatEnumType.UNKNOWN, r.getEnum(OlioFieldNames.FIELD_PB_PAGE_FORMAT));
		assertEquals("qualityPreset is UNKNOWN until set - the resolver, not the column, supplies DRAFT",
			PbQualityPresetEnumType.UNKNOWN, r.getEnum(OlioFieldNames.FIELD_PB_QUALITY_PRESET));
		assertNull(r.get(OlioFieldNames.FIELD_PB_SD_CONFIG));
		assertNull(r.get(OlioFieldNames.FIELD_PB_PARENT));
		assertTrue(ioContext.getAccessPoint().delete(u, r));
	}

	// ───────── 3: olio.pb.book.style is a nullable FK the book writer can set, and bookRequest() projects it ─────────

	@Test
	public void case03_bookStyleFkRoundTripsThroughReadBook() throws Exception {
		BaseRecord u = user();
		BaseRecord bk = book(u);
		long orgId = orgOf(u);
		BaseRecord style = getCreateStyle(u, "p21-book-style", bookGroup(), PbStyleScopeEnumType.BOOK);
		String styleOid = style.get(FieldNames.FIELD_OBJECT_ID);

		BaseRecord patch = RecordFactory.newInstance(OlioModelNames.MODEL_PB_BOOK, new String[] {
			FieldNames.FIELD_ID, FieldNames.FIELD_OBJECT_ID, FieldNames.FIELD_NAME, OlioFieldNames.FIELD_STYLE
		});
		patch.set(FieldNames.FIELD_ID, bk.get(FieldNames.FIELD_ID));
		patch.set(FieldNames.FIELD_OBJECT_ID, bk.get(FieldNames.FIELD_OBJECT_ID));
		patch.set(FieldNames.FIELD_NAME, bk.get(FieldNames.FIELD_NAME));
		patch.set(OlioFieldNames.FIELD_STYLE, style);
		assertNotNull("The book writer can set book.style", ioContext.getAccessPoint().update(u, patch));

		BaseRecord after = PbBookUtil.readBook(u, bk.get(FieldNames.FIELD_OBJECT_ID), orgId);
		assertNotNull(after);
		assertTrue("bookRequest() must project style", after.hasField(OlioFieldNames.FIELD_STYLE));
		BaseRecord ref = after.get(OlioFieldNames.FIELD_STYLE);
		assertNotNull("book.style must resolve to the style's identity", ref);
		assertEquals(styleOid, ref.get(FieldNames.FIELD_OBJECT_ID));
		assertEquals(OlioModelNames.MODEL_PB_STYLE, ref.getSchema());
	}

	// ───────── 4: olio.pb.series.style FK, linked as the olio principal, read back as the user ─────────

	/**
	 * The series row sits in the universe's read-only Book group (the acting user holds the universe
	 * Reader role only), so the link is written the way {@code getCreateSeries} writes {@code universe}:
	 * as the olio principal through {@code RecordUtil.updateRecord}. The proof is the read-back as the
	 * acting user through {@code seriesRequest()}.
	 */
	@Test
	public void case04_seriesStyleFkRoundTripsThroughReadSeries() throws Exception {
		BaseRecord u = user();
		long orgId = orgOf(u);
		BaseRecord series = PbSeriesUtil.getCreateSeries(u, dataPath(), SERIES_SLUG, "P2-1 style fixture series");
		assertNotNull("series fixture", series);
		String seriesOid = series.get(FieldNames.FIELD_OBJECT_ID);

		BaseRecord style = getCreateStyle(u, "p21-series-style", SCRATCH_PATH, PbStyleScopeEnumType.SERIES);
		String styleOid = style.get(FieldNames.FIELD_OBJECT_ID);

		BaseRecord olioUser = ioContext.getFactory().findUser(OlioContext.OLIO_USER_NAME, orgId);
		assertNotNull("olio principal", olioUser);
		BaseRecord patch = RecordFactory.newInstance(OlioModelNames.MODEL_PB_SERIES, new String[] {
			FieldNames.FIELD_ID, FieldNames.FIELD_OBJECT_ID, FieldNames.FIELD_NAME, OlioFieldNames.FIELD_STYLE
		});
		patch.set(FieldNames.FIELD_ID, series.get(FieldNames.FIELD_ID));
		patch.set(FieldNames.FIELD_OBJECT_ID, seriesOid);
		patch.set(FieldNames.FIELD_NAME, series.get(FieldNames.FIELD_NAME));
		patch.set(OlioFieldNames.FIELD_STYLE, style);
		assertTrue("series.style is written as the olio principal, like universe", ioContext.getRecordUtil().updateRecord(patch));

		BaseRecord after = PbSeriesUtil.readSeries(u, seriesOid, orgId);
		assertNotNull("readSeries as the acting user", after);
		assertTrue("seriesRequest() must project style", after.hasField(OlioFieldNames.FIELD_STYLE));
		BaseRecord ref = after.get(OlioFieldNames.FIELD_STYLE);
		assertNotNull("series.style must resolve to the style's identity", ref);
		assertEquals(styleOid, ref.get(FieldNames.FIELD_OBJECT_ID));
	}

	// ───────── 5: referenceImages is participation-backed and round trips a data.data ─────────

	@Test
	public void case05_referenceImagesRoundTripThroughParticipation() throws Exception {
		BaseRecord u = user();
		book(u);
		long orgId = orgOf(u);
		String tag = shortId();

		BaseRecord style = read(u, createStyle(u, newStyle(u, "p21-refs-" + tag, bookGroup())).get(FieldNames.FIELD_OBJECT_ID));
		assertTrue("A fresh style has no reference images", PbStyleUtil.listReferenceImages(u, style).isEmpty());

		/// The image is created through the authorized path as the same user - the enrolment needs canRead on it.
		BaseRecord img = RecordFactory.newInstance(ModelNames.MODEL_DATA);
		ioContext.getRecordUtil().applyNameGroupOwnership(u, img, "p21-ref-" + tag + ".png", SCRATCH_PATH, orgId);
		img.set(FieldNames.FIELD_CONTENT_TYPE, "image/png");
		ByteModelUtil.setValue(img, ("not-a-real-png-" + tag).getBytes(StandardCharsets.UTF_8));
		BaseRecord imgCreated = ioContext.getAccessPoint().create(u, img);
		assertNotNull("reference data.data", imgCreated);
		String imgOid = imgCreated.get(FieldNames.FIELD_OBJECT_ID);
		BaseRecord imgRead = ioContext.getAccessPoint().findByObjectId(u, ModelNames.MODEL_DATA, imgOid);
		assertNotNull(imgRead);

		assertTrue("enrol", PbStyleUtil.addReferenceImage(u, style, imgRead));
		assertTrue("enrolling again is idempotent (MemberUtil.member alone would return false)", PbStyleUtil.addReferenceImage(u, style, imgRead));

		List<BaseRecord> refs = PbStyleUtil.listReferenceImages(u, style);
		assertEquals("one reference image", 1, refs.size());
		BaseRecord ref = refs.get(0);
		assertEquals(imgOid, ref.get(FieldNames.FIELD_OBJECT_ID));
		assertEquals("p21-ref-" + tag + ".png", ref.get(FieldNames.FIELD_NAME));
		assertEquals("image/png", ref.get(FieldNames.FIELD_CONTENT_TYPE));
		assertFalse("the listing never carries the bytes", ref.hasField(FieldNames.FIELD_BYTE_STORE));

		/// A re-read of the style still does not carry the list as a column - it is read on demand.
		BaseRecord again = read(u, style.get(FieldNames.FIELD_OBJECT_ID));
		assertFalse(again.hasField(OlioFieldNames.FIELD_PB_REFERENCE_IMAGES));

		assertTrue("remove", PbStyleUtil.removeReferenceImage(u, style, imgRead));
		assertTrue("empty after removal", PbStyleUtil.listReferenceImages(u, style).isEmpty());
		assertFalse("removing an absent image reports false", PbStyleUtil.removeReferenceImage(u, style, imgRead));

		assertTrue(ioContext.getAccessPoint().delete(u, imgRead));
		assertTrue(ioContext.getAccessPoint().delete(u, again));
	}

	// ───────── 6: parent is a self-referencing FK (inheritance seam for P2-3) ─────────

	@Test
	public void case06_parentSelfReferenceRoundTrips() throws Exception {
		BaseRecord u = user();
		book(u);
		String tag = shortId();

		BaseRecord parent = newStyle(u, "p21-parent-" + tag, bookGroup());
		parent.set(OlioFieldNames.FIELD_PB_SCOPE, PbStyleScopeEnumType.SERIES.toString());
		parent.set(OlioFieldNames.FIELD_PB_LOCKED, Boolean.TRUE);
		parent.set(OlioFieldNames.FIELD_PB_PALETTE, "#000000,#ffffff");
		BaseRecord parentRead = read(u, createStyle(u, parent).get(FieldNames.FIELD_OBJECT_ID));
		String parentOid = parentRead.get(FieldNames.FIELD_OBJECT_ID);

		BaseRecord child = newStyle(u, "p21-child-" + tag, bookGroup());
		child.set(OlioFieldNames.FIELD_PB_SCOPE, PbStyleScopeEnumType.BOOK.toString());
		child.set(OlioFieldNames.FIELD_PB_PARENT, parentRead);
		BaseRecord childRead = read(u, createStyle(u, child).get(FieldNames.FIELD_OBJECT_ID));

		BaseRecord ref = childRead.get(OlioFieldNames.FIELD_PB_PARENT);
		assertNotNull("parent resolves to an identity reference", ref);
		assertEquals(parentOid, ref.get(FieldNames.FIELD_OBJECT_ID));
		assertEquals(OlioModelNames.MODEL_PB_STYLE, ref.getSchema());
		/// A plain foreign field in the projection returns the referenced record's default query fields,
		/// not the full record (model-api.md); the full parent is a second read.
		BaseRecord resolved = read(u, ref.get(FieldNames.FIELD_OBJECT_ID));
		assertEquals(PbStyleScopeEnumType.SERIES, resolved.getEnum(OlioFieldNames.FIELD_PB_SCOPE));
		assertEquals(Boolean.TRUE, resolved.get(OlioFieldNames.FIELD_PB_LOCKED));
		assertEquals("#000000,#ffffff", resolved.get(OlioFieldNames.FIELD_PB_PALETTE));
		assertNull("the parent itself has no parent", resolved.get(OlioFieldNames.FIELD_PB_PARENT));

		assertTrue(ioContext.getAccessPoint().delete(u, childRead));
		assertTrue(ioContext.getAccessPoint().delete(u, parentRead));
	}

	// ───────── 7: the DDL defaults for the typed columns ─────────

	@Test
	public void case07_booleanAndIntColumnsReadDdlDefaultsWhenUnset() throws Exception {
		BaseRecord u = user();
		book(u);
		BaseRecord r = read(u, createStyle(u, newStyle(u, "p21-defaults-" + shortId(), bookGroup())).get(FieldNames.FIELD_OBJECT_ID));
		assertEquals("locked defaults false", Boolean.FALSE, r.get(OlioFieldNames.FIELD_PB_LOCKED));
		assertEquals("photoreal defaults false", Boolean.FALSE, r.get(OlioFieldNames.FIELD_PB_PHOTOREAL));
		assertEquals("customWidth 0 means unset", Integer.valueOf(0), r.get(OlioFieldNames.FIELD_PB_CUSTOM_WIDTH));
		assertEquals("customHeight 0 means unset", Integer.valueOf(0), r.get(OlioFieldNames.FIELD_PB_CUSTOM_HEIGHT));
		assertNull(r.get(FieldNames.FIELD_DESCRIPTION));
		assertNull(r.get(OlioFieldNames.FIELD_PB_EXCLUSIONS));
		assertNull(r.get(OlioFieldNames.FIELD_PB_TASK_MODELS));
		assertNull(r.get(OlioFieldNames.FIELD_PB_PALETTE));
		assertTrue(ioContext.getAccessPoint().delete(u, r));
	}

	// ───────── 9: a stranger in the same organization is denied the by-identity read and the update ─────────

	/**
	 * {@link PbStyleUtil#readStyle} promises null "when the style does not exist or the user may not read
	 * it"; cases 1-7 only ever exercise the owner. A second non-admin user in the SAME organization, holding
	 * no role on the {@code pb2c-graph} book, must get null from the by-identity read and null from
	 * {@code AccessPoint.update}. The positive controls (the owner reads it, the organizations match) are
	 * what make the nulls meaningful - a bug that returned null for everyone would otherwise pass. The
	 * org-wide list path is deliberately not asserted here: {@code TestPbSecurity.case02} already pins
	 * that {@code AccessPoint.list} filters nothing per record.
	 */
	@Test
	public void case09_strangerInSameOrgCannotReadOrUpdateBookScopeStyle() throws Exception {
		BaseRecord owner = user();
		book(owner);
		long orgId = orgOf(owner);
		OrganizationContext org = getTestOrganization(ORG_A);
		BaseRecord stranger = ioContext.getFactory().getCreateUser(org.getAdminUser(), STRANGER_USER, org.getOrganizationId());
		assertNotNull("Failed to resolve " + STRANGER_USER, stranger);
		assertEquals("Both users must be in the same organization for this to be a PBAC test rather than a tenant test",
			orgId, orgOf(stranger));
		assertFalse("The stranger must be a different principal", owner.get(FieldNames.FIELD_ID).equals(stranger.get(FieldNames.FIELD_ID)));

		String tag = shortId();
		BaseRecord style = newStyle(owner, "p21-stranger-" + tag, bookGroup());
		style.set(OlioFieldNames.FIELD_PB_SCOPE, PbStyleScopeEnumType.BOOK.toString());
		style.set(OlioFieldNames.FIELD_PB_LOCKED, Boolean.TRUE);
		BaseRecord created = createStyle(owner, style);
		String oid = created.get(FieldNames.FIELD_OBJECT_ID);
		BaseRecord ownerRead = read(owner, oid);
		assertEquals("Positive control: the owner reads the style", PbStyleScopeEnumType.BOOK, ownerRead.getEnum(OlioFieldNames.FIELD_PB_SCOPE));

		CacheUtil.clearCache();
		assertNull("A stranger in the same org must not read a book-scope style through readStyle",
			PbStyleUtil.readStyle(stranger, oid, orgId));
		assertNull("...nor through findByObjectId",
			ioContext.getAccessPoint().findByObjectId(stranger, OlioModelNames.MODEL_PB_STYLE, oid));

		/// The stranger may not flip locked either - a hand-built identity+changed patch carrying the real name.
		BaseRecord patch = RecordFactory.newInstance(OlioModelNames.MODEL_PB_STYLE, new String[] {
			FieldNames.FIELD_ID, FieldNames.FIELD_OBJECT_ID, FieldNames.FIELD_NAME, OlioFieldNames.FIELD_PB_LOCKED
		});
		patch.set(FieldNames.FIELD_ID, ownerRead.get(FieldNames.FIELD_ID));
		patch.set(FieldNames.FIELD_OBJECT_ID, oid);
		patch.set(FieldNames.FIELD_NAME, ownerRead.get(FieldNames.FIELD_NAME));
		patch.set(OlioFieldNames.FIELD_PB_LOCKED, Boolean.FALSE);
		assertNull("A stranger must not update a book-scope style", ioContext.getAccessPoint().update(stranger, patch));

		BaseRecord after = read(owner, oid);
		assertEquals("The denied update must have changed nothing", Boolean.TRUE, after.get(OlioFieldNames.FIELD_PB_LOCKED));
		assertTrue(ioContext.getAccessPoint().delete(owner, after));
	}

	// ───────── 8: the enums are exactly what §13/§16/§4 name ─────────

	@Test
	public void case08_enumValuesAreExactlyTheDesignsLists() {
		assertEquals(Arrays.asList("UNKNOWN", "TEXT_ONLY", "DIRECT_REFERENCE", "SEQUENTIAL_INPAINT", "REGIONAL_PROMPT", "CLASSIC_COMPOSITE"),
			names(PbStrategyEnumType.values()));
		assertEquals(Arrays.asList("UNKNOWN", "PORTRAIT", "LOCATION", "SCENE_DIRECT", "INPAINT", "HARMONIZE", "UPSCALE"),
			names(PbModelTaskEnumType.values()));
		assertEquals(Arrays.asList("UNKNOWN", "PORTRAIT_PAGE", "LANDSCAPE_PAGE", "SQUARE", "CUSTOM"),
			names(PbPageFormatEnumType.values()));
		assertEquals(Arrays.asList("UNKNOWN", "DRAFT", "STANDARD", "FINAL"),
			names(PbQualityPresetEnumType.values()));
		assertEquals(Arrays.asList("UNKNOWN", "BOOK", "SERIES", "LIBRARY"),
			names(PbStyleScopeEnumType.values()));
		assertTrue("RENDER_PLAN joins the node types", names(PbNodeTypeEnumType.values()).contains("RENDER_PLAN"));
	}

	private static List<String> names(Enum<?>[] values) {
		List<String> out = new ArrayList<>();
		for(Enum<?> e : values) {
			out.add(e.name());
		}
		return out;
	}
}
