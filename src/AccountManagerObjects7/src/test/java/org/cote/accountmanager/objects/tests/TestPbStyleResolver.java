package org.cote.accountmanager.objects.tests;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.cache.CacheUtil;
import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.io.OrganizationContext;
import org.cote.accountmanager.io.Query;
import org.cote.accountmanager.io.QueryResult;
import org.cote.accountmanager.io.QueryUtil;
import org.cote.accountmanager.olio.OlioContext;
import org.cote.accountmanager.olio.OlioContextUtil;
import org.cote.accountmanager.olio.picturebook.PbBookUtil;
import org.cote.accountmanager.olio.picturebook.PbConfigUtil;
import org.cote.accountmanager.olio.picturebook.PbSeriesUtil;
import org.cote.accountmanager.olio.picturebook.PbServiceFacade;
import org.cote.accountmanager.olio.picturebook.PbStyleResolver;
import org.cote.accountmanager.olio.picturebook.PbStyleResolver.EffectiveStyle;
import org.cote.accountmanager.olio.picturebook.PbStyleUtil;
import org.cote.accountmanager.olio.picturebook.PictureBookException;
import org.cote.accountmanager.olio.schema.OlioFieldNames;
import org.cote.accountmanager.olio.schema.OlioModelNames;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.record.RecordFactory;
import org.cote.accountmanager.schema.AccessSchema;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.schema.type.PbBookStatusEnumType;
import org.cote.accountmanager.schema.type.PbBookTypeEnumType;
import org.cote.accountmanager.schema.type.PbModelTaskEnumType;
import org.cote.accountmanager.schema.type.PbPageFormatEnumType;
import org.cote.accountmanager.schema.type.PbQualityPresetEnumType;
import org.cote.accountmanager.schema.type.PbStyleScopeEnumType;
import org.cote.accountmanager.schema.type.PermissionEnumType;
import org.cote.accountmanager.util.ByteModelUtil;
import org.junit.Before;
import org.junit.Test;

/**
 * V3 Phase 2, P2-3: {@link PbStyleResolver} against the live {@code am7db} as a non-admin user
 * ({@code testUser1}), with a second non-admin user ({@code p21StyleStranger}) in the same organization
 * for every "unreadable hop" case. Same fixture as {@link TestPbStyleSchema}: org
 * {@code /Development/World Building}, book {@code pb2c-graph}, series {@code p21style}.
 * <p>
 * Every style this class mutates is its own (created under {@code ~/P23StyleResolver} or the stranger's
 * {@code ~/P23StyleShared}); the shared fixture rows ({@code p21-book-style}, {@code p21-series-style})
 * are read but never edited. The one shared row this class writes is {@code p21style.series.style}
 * (cases 3 and 13, as the olio principal the way {@code TestPbStyleSchema.case04} does), captured and
 * restored in {@code finally} with the restore verified by a re-read. The book -> series hop is driven
 * through an in-memory {@code olio.pb.book} carrying only the {@code series} reference, which is exactly
 * what the resolver reads ({@code book.get(series)}) - the fixture book's own {@code series} FK is left
 * untouched so no other class sees a different chain.
 */
public class TestPbStyleResolver extends BaseTest {
	private static final Logger logger = LogManager.getLogger(TestPbStyleResolver.class);

	private static final String ORG_A = "/Development/World Building";
	private static final String TEST_USER = "testUser1";
	private static final String STRANGER_USER = "p21StyleStranger";
	private static final String SLUG = "pb2c-graph";
	private static final String SERIES_SLUG = "p21style";
	private static final String SCRATCH_PATH = "~/P23StyleResolver";
	/// In the stranger's home: a group the stranger can grant the test user Read on.
	private static final String SHARED_PATH = "~/P23StyleShared";
	/// In the stranger's home: never granted to anyone.
	private static final String PRIVATE_PATH = "~/P23StylePrivate";

	@Before
	public void resolverSetup() {
		OlioContextUtil.clearCache();
		IOSystem.getActiveContext().getAccessPoint().setPermitBulkContainerApproval(false);
	}

	// ─────────────────────────────── fixtures ───────────────────────────────

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

	private BaseRecord stranger() {
		OrganizationContext org = getTestOrganization(ORG_A);
		BaseRecord s = ioContext.getFactory().getCreateUser(org.getAdminUser(), STRANGER_USER, org.getOrganizationId());
		assertNotNull("Failed to resolve " + STRANGER_USER, s);
		return s;
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

	private static String oid(BaseRecord r) {
		return (r != null ? r.get(FieldNames.FIELD_OBJECT_ID) : null);
	}

	private static List<String> oids(List<BaseRecord> recs) {
		List<String> out = new ArrayList<>();
		for(BaseRecord r : recs) {
			out.add(oid(r));
		}
		return out;
	}

	/// An unpersisted style with ownership and group applied (the model does not inherit common.name).
	private BaseRecord newStyle(BaseRecord u, String name, String groupPath, PbStyleScopeEnumType scope) throws Exception {
		BaseRecord style = RecordFactory.newInstance(OlioModelNames.MODEL_PB_STYLE);
		ioContext.getRecordUtil().applyNameGroupOwnership(u, style, name, groupPath, orgOf(u));
		style.set(FieldNames.FIELD_NAME, name);
		style.set(OlioFieldNames.FIELD_PB_SCOPE, scope.toString());
		return style;
	}

	/// Create through AccessPoint as the user and return the full re-read (styleRequest projection).
	private BaseRecord createStyle(BaseRecord u, BaseRecord style) {
		BaseRecord created = ioContext.getAccessPoint().create(u, style);
		assertNotNull("AccessPoint.create must accept olio.pb.style '" + style.get(FieldNames.FIELD_NAME) + "'", created);
		String id = oid(created);
		assertNotNull("create returns identity", id);
		BaseRecord read = PbStyleUtil.readStyle(u, id, orgOf(u));
		assertNotNull("readStyle must find " + id + " as the owning user", read);
		return read;
	}

	private BaseRecord scratchStyle(BaseRecord u, String name, Map<String, Object> fields) throws Exception {
		BaseRecord s = newStyle(u, name, SCRATCH_PATH, PbStyleScopeEnumType.BOOK);
		if(fields != null) {
			for(Map.Entry<String, Object> e : fields.entrySet()) {
				s.set(e.getKey(), e.getValue());
			}
		}
		return createStyle(u, s);
	}

	private static Map<String, Object> fields(Object... kv) {
		Map<String, Object> m = new LinkedHashMap<>();
		for(int i = 0; i + 1 < kv.length; i += 2) {
			m.put((String) kv[i], kv[i + 1]);
		}
		return m;
	}

	/// identity + name + one changed field, as the model-api PATCH idiom requires; returns the re-read.
	private BaseRecord patchStyle(BaseRecord u, BaseRecord style, String field, Object value) throws Exception {
		BaseRecord patch = RecordFactory.newInstance(OlioModelNames.MODEL_PB_STYLE, new String[] {
			FieldNames.FIELD_ID, FieldNames.FIELD_OBJECT_ID, FieldNames.FIELD_NAME, field
		});
		patch.set(FieldNames.FIELD_ID, style.get(FieldNames.FIELD_ID));
		patch.set(FieldNames.FIELD_OBJECT_ID, oid(style));
		patch.set(FieldNames.FIELD_NAME, style.get(FieldNames.FIELD_NAME));
		patch.set(field, value);
		assertNotNull("AccessPoint.update of " + field + " on " + style.get(FieldNames.FIELD_NAME), ioContext.getAccessPoint().update(u, patch));
		BaseRecord read = PbStyleUtil.readStyle(u, oid(style), orgOf(u));
		assertNotNull("re-read after patch", read);
		return read;
	}

	/// A bare FK reference as it comes back on a parent record: objectId only.
	private static BaseRecord styleRef(String objectId) throws Exception {
		BaseRecord ref = RecordFactory.newInstance(OlioModelNames.MODEL_PB_STYLE, new String[] {FieldNames.FIELD_OBJECT_ID});
		ref.set(FieldNames.FIELD_OBJECT_ID, objectId);
		return ref;
	}

	private static BaseRecord seriesRef(String objectId) throws Exception {
		BaseRecord ref = RecordFactory.newInstance(OlioModelNames.MODEL_PB_SERIES, new String[] {FieldNames.FIELD_OBJECT_ID});
		ref.set(FieldNames.FIELD_OBJECT_ID, objectId);
		return ref;
	}

	/**
	 * An in-memory {@code olio.pb.book} carrying exactly what the resolver reads: {@code style} and
	 * {@code series} (both may be null) plus the organization. The resolver documents that a book must be
	 * projected with those two fields; this is that projection without touching the fixture book's FKs.
	 */
	private BaseRecord bookWith(BaseRecord u, BaseRecord styleRef, BaseRecord seriesRef) throws Exception {
		BaseRecord b = RecordFactory.newInstance(OlioModelNames.MODEL_PB_BOOK, new String[] {
			FieldNames.FIELD_OBJECT_ID, FieldNames.FIELD_ORGANIZATION_ID, OlioFieldNames.FIELD_STYLE, OlioFieldNames.FIELD_PB_SERIES
		});
		b.set(FieldNames.FIELD_OBJECT_ID, "in-memory-" + shortId());
		b.set(FieldNames.FIELD_ORGANIZATION_ID, orgOf(u));
		b.set(OlioFieldNames.FIELD_STYLE, styleRef);
		b.set(OlioFieldNames.FIELD_PB_SERIES, seriesRef);
		return b;
	}

	/// How many hops the organization's LIBRARY style contributes to every chain (0 when there is none).
	private int libraryTail(BaseRecord u) throws Exception {
		EffectiveStyle es = PbStyleResolver.resolve(u, bookWith(u, null, null));
		assertTrue("A style-less, series-less book must resolve completely: " + es, es.isComplete());
		return es.chain().size();
	}

	private void cleanup(BaseRecord owner, BaseRecord... records) {
		for(BaseRecord r : records) {
			if(r == null) {
				continue;
			}
			try {
				if(!ioContext.getAccessPoint().delete(owner, r)) {
					logger.warn("Cleanup: could not delete " + r.getSchema() + " " + oid(r));
				}
			}
			catch(Exception e) {
				logger.warn("Cleanup: " + e.getMessage());
			}
		}
	}

	private BaseRecord readPerm(long orgId) {
		BaseRecord p = AccessSchema.getSystemPermission(AccessSchema.SYSTEM_PERMISSION_READ, PermissionEnumType.GROUP.toString(), orgId);
		assertNotNull("Read/GROUP permission", p);
		return p;
	}

	private BaseRecord readDataPerm(long orgId) {
		BaseRecord p = AccessSchema.getSystemPermission(AccessSchema.SYSTEM_PERMISSION_READ, PermissionEnumType.DATA.toString(), orgId);
		assertNotNull("Read/DATA permission", p);
		return p;
	}

	private BaseRecord groupOf(BaseRecord owner, BaseRecord rec) {
		long gid = ((Number) rec.get(FieldNames.FIELD_GROUP_ID)).longValue();
		BaseRecord dir = ioContext.getAccessPoint().findById(owner, ModelNames.MODEL_GROUP, gid);
		assertNotNull("The owner must read its own group " + gid, dir);
		return dir;
	}

	private void grant(BaseRecord owner, BaseRecord dir, BaseRecord grantee, boolean enable) {
		long orgId = orgOf(owner);
		boolean g = ioContext.getMemberUtil().member(owner, dir, grantee, readPerm(orgId), enable);
		boolean d = ioContext.getMemberUtil().member(owner, dir, grantee, readDataPerm(orgId), enable);
		if(enable) {
			assertTrue("grant Read/GROUP", g);
			assertTrue("grant Read/DATA", d);
		}
		CacheUtil.clearCache();
	}

	/// The series fixture plus its current style reference (may be null).
	private BaseRecord series(BaseRecord u) {
		BaseRecord series = PbSeriesUtil.getCreateSeries(u, dataPath(), SERIES_SLUG, "P2-1 style fixture series");
		assertNotNull("series fixture", series);
		BaseRecord read = PbSeriesUtil.readSeries(u, oid(series), orgOf(u));
		assertNotNull("readSeries as the acting user", read);
		assertTrue("seriesRequest() must project style", read.hasField(OlioFieldNames.FIELD_STYLE));
		return read;
	}

	/**
	 * Write series.style as the olio principal (TestPbStyleSchema.case04: the series row sits in the
	 * universe's read-only Book group) and verify with a raw, uncached, PBAC-free read - test instrumentation
	 * only, so the check is independent of which principal may read the series afterwards.
	 */
	private boolean setSeriesStyle(BaseRecord u, BaseRecord series, BaseRecord styleRefOrNull) {
		try {
			long orgId = orgOf(u);
			assertNotNull("olio principal", ioContext.getFactory().findUser(OlioContext.OLIO_USER_NAME, orgId));
			BaseRecord patch = RecordFactory.newInstance(OlioModelNames.MODEL_PB_SERIES, new String[] {
				FieldNames.FIELD_ID, FieldNames.FIELD_OBJECT_ID, FieldNames.FIELD_NAME, OlioFieldNames.FIELD_STYLE
			});
			patch.set(FieldNames.FIELD_ID, series.get(FieldNames.FIELD_ID));
			patch.set(FieldNames.FIELD_OBJECT_ID, oid(series));
			patch.set(FieldNames.FIELD_NAME, series.get(FieldNames.FIELD_NAME));
			patch.set(OlioFieldNames.FIELD_STYLE, styleRefOrNull);
			if(!ioContext.getRecordUtil().updateRecord(patch)) {
				logger.error("series.style write returned false");
				return false;
			}
			CacheUtil.clearCache();
			Query q = QueryUtil.createQuery(OlioModelNames.MODEL_PB_SERIES, FieldNames.FIELD_OBJECT_ID, oid(series));
			q.field(FieldNames.FIELD_ORGANIZATION_ID, orgId);
			q.setRequest(new String[] {FieldNames.FIELD_ID, FieldNames.FIELD_OBJECT_ID, OlioFieldNames.FIELD_STYLE});
			q.setCache(false);
			BaseRecord after = ioContext.getSearch().findRecord(q);
			BaseRecord ref = (after != null && after.hasField(OlioFieldNames.FIELD_STYLE) ? after.get(OlioFieldNames.FIELD_STYLE) : null);
			String want = oid(styleRefOrNull);
			String got = oid(ref);
			if((want == null && got != null) || (want != null && !want.equals(got))) {
				logger.error("series.style raw re-read is " + got + ", wanted " + want);
				return false;
			}
			return true;
		}
		catch(Exception e) {
			logger.error("series.style write failed: " + e.getMessage(), e);
			return false;
		}
	}

	private int countRows(String model, long orgId) {
		Query q = QueryUtil.createQuery(model, FieldNames.FIELD_ORGANIZATION_ID, orgId);
		q.setCache(false);
		return ioContext.getSearch().count(q);
	}

	// ───────── 1: a book whose own style is the only hop ─────────

	/**
	 * Through the real path: {@code PbBookUtil.readBook} projects {@code book.style}, the resolver walks it.
	 * The fixture book's {@code style} is captured and restored; the style under test is this case's own.
	 */
	@Test
	public void case01_bookOnly() throws Exception {
		BaseRecord u = user();
		long orgId = orgOf(u);
		BaseRecord bk = book(u);
		BaseRecord priorStyle = bk.get(OlioFieldNames.FIELD_STYLE);
		String tag = shortId();
		BaseRecord s = scratchStyle(u, "p23-own-" + tag, fields(
			OlioFieldNames.FIELD_PB_PALETTE, "p23 warm palette " + tag,
			OlioFieldNames.FIELD_PB_QUALITY_PRESET, PbQualityPresetEnumType.DRAFT.toString(),
			OlioFieldNames.FIELD_PB_PAGE_FORMAT, PbPageFormatEnumType.SQUARE.toString(),
			OlioFieldNames.FIELD_PB_CUSTOM_WIDTH, Integer.valueOf(0)));
		boolean restored = false;
		try {
			assertTrue("set book.style -> own", setBookStyle(u, bk, s));
			BaseRecord fresh = PbBookUtil.readBook(u, oid(bk), orgId);
			assertNotNull(fresh);
			assertEquals(oid(s), oid(fresh.get(OlioFieldNames.FIELD_STYLE)));

			EffectiveStyle es = PbStyleResolver.resolve(u, fresh);
			assertFalse(es.isEmpty());
			assertTrue("complete: " + es, es.isComplete());
			assertNull(es.truncatedAt());
			assertNull(es.truncatedModel());
			assertFalse(es.cycleDetected());
			assertEquals("hop 0 is the book's own style: " + oids(es.chain()), oid(s), oid(es.chain().get(0)));
			for(int i = 1; i < es.chain().size(); i++) {
				assertFalse("the own style appears once", oid(s).equals(oid(es.chain().get(i))));
			}
			assertEquals("p23 warm palette " + tag, es.value(OlioFieldNames.FIELD_PB_PALETTE));
			assertEquals(oid(s), oid(es.source(OlioFieldNames.FIELD_PB_PALETTE)));
			assertEquals(PbQualityPresetEnumType.DRAFT, es.qualityPreset());
			assertEquals(oid(s), oid(es.source(OlioFieldNames.FIELD_PB_QUALITY_PRESET)));
			assertEquals(PbPageFormatEnumType.SQUARE, es.pageFormat());
			assertNull("customWidth 0 is unset, never a value", es.value(OlioFieldNames.FIELD_PB_CUSTOM_WIDTH));
			assertEquals("photoreal is always observable on hop 0", Boolean.FALSE, es.value(OlioFieldNames.FIELD_PB_PHOTOREAL));
			assertNull(es.lockedBy());
			assertNull("no hop names a config", es.sdConfig());
			assertNull(es.sdConfigSource());
			assertTrue(es.taskModels().isEmpty());
			assertTrue("canonicalStyle carries the palette", es.canonicalStyle().contains("palette=") && es.canonicalStyle().contains("p23 warm palette " + tag));
		}
		finally {
			restored = setBookStyle(u, bk, priorStyle);
			if(restored) {
				cleanup(u, s);
			}
			else {
				logger.error("book.style was NOT restored to " + oid(priorStyle) + "; leaving " + oid(s) + " in place so the FK does not dangle");
			}
		}
		assertTrue("book.style restored to its prior value " + oid(priorStyle), restored);
	}

	/// Patch book.style (identity + name + style) as the book owner and verify by re-read.
	private boolean setBookStyle(BaseRecord u, BaseRecord bk, BaseRecord styleRefOrNull) {
		try {
			BaseRecord patch = RecordFactory.newInstance(OlioModelNames.MODEL_PB_BOOK, new String[] {
				FieldNames.FIELD_ID, FieldNames.FIELD_OBJECT_ID, FieldNames.FIELD_NAME, OlioFieldNames.FIELD_STYLE
			});
			patch.set(FieldNames.FIELD_ID, bk.get(FieldNames.FIELD_ID));
			patch.set(FieldNames.FIELD_OBJECT_ID, oid(bk));
			patch.set(FieldNames.FIELD_NAME, bk.get(FieldNames.FIELD_NAME));
			patch.set(OlioFieldNames.FIELD_STYLE, styleRefOrNull);
			if(ioContext.getAccessPoint().update(u, patch) == null) {
				logger.error("book.style update returned null");
				return false;
			}
			CacheUtil.clearCache();
			BaseRecord after = PbBookUtil.readBook(u, oid(bk), orgOf(u));
			String got = (after != null ? oid(after.get(OlioFieldNames.FIELD_STYLE)) : "<book unreadable>");
			String want = oid(styleRefOrNull);
			if((want == null && got != null) || (want != null && !want.equals(got))) {
				logger.error("book.style re-read is " + got + ", wanted " + want);
				return false;
			}
			return true;
		}
		catch(Exception e) {
			logger.error("book.style write failed: " + e.getMessage(), e);
			return false;
		}
	}

	// ───────── 2: parent chain, nearest-set-wins per field ─────────

	@Test
	public void case02_parentChainNearestSetWins() throws Exception {
		BaseRecord u = user();
		String tag = shortId();
		BaseRecord parent = scratchStyle(u, "p23-parent-" + tag, fields(
			OlioFieldNames.FIELD_PB_PALETTE, "parent palette " + tag,
			FieldNames.FIELD_DESCRIPTION, "parent description " + tag,
			OlioFieldNames.FIELD_PB_EXCLUSIONS, "parent exclusions " + tag,
			OlioFieldNames.FIELD_PB_QUALITY_PRESET, PbQualityPresetEnumType.FINAL.toString(),
			OlioFieldNames.FIELD_PB_PAGE_FORMAT, PbPageFormatEnumType.CUSTOM.toString(),
			OlioFieldNames.FIELD_PB_CUSTOM_WIDTH, Integer.valueOf(640),
			OlioFieldNames.FIELD_PB_CUSTOM_HEIGHT, Integer.valueOf(480)));
		/// The child: blank palette, the literal "null" description, width 0, no preset - all "unset", so
		/// each inherits; exclusions is set and must win.
		BaseRecord child = scratchStyle(u, "p23-child-" + tag, fields(
			OlioFieldNames.FIELD_PB_PARENT, parent,
			OlioFieldNames.FIELD_PB_PALETTE, "",
			FieldNames.FIELD_DESCRIPTION, "null",
			OlioFieldNames.FIELD_PB_EXCLUSIONS, "child exclusions " + tag,
			OlioFieldNames.FIELD_PB_CUSTOM_WIDTH, Integer.valueOf(0)));
		try {
			assertEquals("child.parent persisted", oid(parent), oid(child.get(OlioFieldNames.FIELD_PB_PARENT)));
			EffectiveStyle es = PbStyleResolver.resolve(u, bookWith(u, styleRef(oid(child)), null));
			assertTrue("complete: " + es, es.isComplete());
			List<String> chain = oids(es.chain());
			assertTrue("chain starts [child, parent]: " + chain, chain.size() >= 2);
			assertEquals(oid(child), chain.get(0));
			assertEquals(oid(parent), chain.get(1));

			assertEquals("blank child palette inherits", "parent palette " + tag, es.value(OlioFieldNames.FIELD_PB_PALETTE));
			assertEquals(oid(parent), oid(es.source(OlioFieldNames.FIELD_PB_PALETTE)));
			assertEquals("literal \"null\" child description inherits", "parent description " + tag, es.value(FieldNames.FIELD_DESCRIPTION));
			assertEquals(oid(parent), oid(es.source(FieldNames.FIELD_DESCRIPTION)));
			assertEquals("set child exclusions win", "child exclusions " + tag, es.value(OlioFieldNames.FIELD_PB_EXCLUSIONS));
			assertEquals(oid(child), oid(es.source(OlioFieldNames.FIELD_PB_EXCLUSIONS)));
			assertEquals("width 0 on the child inherits 640", Integer.valueOf(640), es.value(OlioFieldNames.FIELD_PB_CUSTOM_WIDTH));
			assertEquals(oid(parent), oid(es.source(OlioFieldNames.FIELD_PB_CUSTOM_WIDTH)));
			assertEquals(Integer.valueOf(480), es.value(OlioFieldNames.FIELD_PB_CUSTOM_HEIGHT));
			assertEquals("UNKNOWN child preset inherits FINAL", PbQualityPresetEnumType.FINAL, es.qualityPreset());
			assertEquals(oid(parent), oid(es.source(OlioFieldNames.FIELD_PB_QUALITY_PRESET)));
			assertEquals(PbPageFormatEnumType.CUSTOM, es.pageFormat());
			assertEquals("photoreal comes from hop 0", oid(child), oid(es.source(OlioFieldNames.FIELD_PB_PHOTOREAL)));
		}
		finally {
			cleanup(u, child, parent);
		}
	}

	// ───────── 3: series style fallback ─────────

	/**
	 * {@code book.style} null -> the resolver reads {@code book.series} as the user and walks
	 * {@code series.style}. {@code series.style} is written as the olio principal (the series row lives in
	 * the universe's read-only Book group) and restored afterwards; the restore is verified.
	 */
	@Test
	public void case03_seriesStyleFallback() throws Exception {
		BaseRecord u = user();
		BaseRecord series = series(u);
		BaseRecord priorRef = series.get(OlioFieldNames.FIELD_STYLE);
		String tag = shortId();
		BaseRecord ss = newStyle(u, "p23-series-" + tag, SCRATCH_PATH, PbStyleScopeEnumType.SERIES);
		ss.set(OlioFieldNames.FIELD_PB_PALETTE, "series palette " + tag);
		ss.set(OlioFieldNames.FIELD_PB_QUALITY_PRESET, PbQualityPresetEnumType.FINAL.toString());
		ss = createStyle(u, ss);
		BaseRecord own = scratchStyle(u, "p23-own-" + tag, fields(OlioFieldNames.FIELD_PB_EXCLUSIONS, "own exclusions " + tag));
		boolean restored = false;
		try {
			assertTrue("series.style -> " + oid(ss), setSeriesStyle(u, series, ss));

			/// No book style: the series style is hop 0.
			EffectiveStyle es = PbStyleResolver.resolve(u, bookWith(u, null, seriesRef(oid(series))));
			assertTrue("complete: " + es, es.isComplete());
			assertFalse(es.isEmpty());
			assertEquals("hop 0 is the series style: " + oids(es.chain()), oid(ss), oid(es.chain().get(0)));
			assertEquals("series palette " + tag, es.value(OlioFieldNames.FIELD_PB_PALETTE));
			assertEquals(oid(ss), oid(es.source(OlioFieldNames.FIELD_PB_PALETTE)));
			assertEquals(PbQualityPresetEnumType.FINAL, es.qualityPreset());

			/// Book style present: it is hop 0, the series style follows, nearest-set still wins per field.
			EffectiveStyle es2 = PbStyleResolver.resolve(u, bookWith(u, styleRef(oid(own)), seriesRef(oid(series))));
			assertTrue("complete: " + es2, es2.isComplete());
			List<String> chain = oids(es2.chain());
			assertTrue(chain.size() >= 2);
			assertEquals(oid(own), chain.get(0));
			assertEquals(oid(ss), chain.get(1));
			assertEquals("own exclusions " + tag, es2.value(OlioFieldNames.FIELD_PB_EXCLUSIONS));
			assertEquals(oid(own), oid(es2.source(OlioFieldNames.FIELD_PB_EXCLUSIONS)));
			assertEquals("palette falls through to the series style", "series palette " + tag, es2.value(OlioFieldNames.FIELD_PB_PALETTE));
			assertEquals(oid(ss), oid(es2.source(OlioFieldNames.FIELD_PB_PALETTE)));
		}
		finally {
			restored = setSeriesStyle(u, series, priorRef);
			cleanup(u, own);
			if(restored) {
				cleanup(u, ss);
			}
			else {
				logger.error("series.style was NOT restored to " + oid(priorRef) + "; leaving " + oid(ss) + " so the FK does not dangle");
			}
		}
		assertTrue("series.style restored to its prior value " + oid(priorRef), restored);
	}

	// ───────── 4: a cycle terminates and is reported ─────────

	@Test
	public void case04_cycleTerminatesAndReports() throws Exception {
		BaseRecord u = user();
		String tag = shortId();
		BaseRecord a = scratchStyle(u, "p23-cycle-a-" + tag, fields(OlioFieldNames.FIELD_PB_PALETTE, "a palette"));
		BaseRecord b = scratchStyle(u, "p23-cycle-b-" + tag, fields(OlioFieldNames.FIELD_PB_PARENT, a, FieldNames.FIELD_DESCRIPTION, "b description"));
		try {
			a = patchStyle(u, a, OlioFieldNames.FIELD_PB_PARENT, b);
			assertEquals("a.parent = b", oid(b), oid(a.get(OlioFieldNames.FIELD_PB_PARENT)));

			EffectiveStyle es = PbStyleResolver.resolve(u, bookWith(u, styleRef(oid(a)), null));
			assertTrue("cycleDetected", es.cycleDetected());
			assertFalse("not complete", es.isComplete());
			assertNull("a cycle is not a truncation", es.truncatedAt());
			assertEquals("finite chain [a, b]: " + oids(es.chain()), Arrays.asList(oid(a), oid(b)), oids(es.chain()));
			assertEquals("values are still resolved from the hops read", "a palette", es.value(OlioFieldNames.FIELD_PB_PALETTE));
			assertEquals("b description", es.value(FieldNames.FIELD_DESCRIPTION));

			EffectiveStyle fromStyle = PbStyleResolver.resolveFromStyle(u, b);
			assertTrue(fromStyle.cycleDetected());
			assertEquals(Arrays.asList(oid(b), oid(a)), oids(fromStyle.chain()));
		}
		finally {
			/// Break the cycle before deleting so neither row is left pointing at a deleted one.
			try {
				patchStyle(u, a, OlioFieldNames.FIELD_PB_PARENT, null);
			}
			catch(Throwable t) {
				logger.warn("could not clear a.parent: " + t.getMessage());
			}
			cleanup(u, b, a);
		}
	}

	// ───────── 5: the depth cap ─────────

	@Test
	public void case05_depthCap() throws Exception {
		BaseRecord u = user();
		int lib = libraryTail(u);
		String tag = shortId();
		int hops = PbStyleResolver.MAX_DEPTH + 1;
		/// s[hops-1] is the root; s[i].parent = s[i+1].
		BaseRecord[] s = new BaseRecord[hops];
		try {
			for(int i = hops - 1; i >= 0; i--) {
				Map<String, Object> f = fields(OlioFieldNames.FIELD_PB_PALETTE, "palette " + i);
				if(i < hops - 1) {
					f.put(OlioFieldNames.FIELD_PB_PARENT, s[i + 1]);
				}
				s[i] = scratchStyle(u, "p23-depth-" + i + "-" + tag, f);
			}
			EffectiveStyle es = PbStyleResolver.resolve(u, bookWith(u, styleRef(oid(s[0])), null));
			assertFalse("9 hops is not complete", es.isComplete());
			assertFalse(es.cycleDetected());
			assertEquals("chain holds exactly MAX_DEPTH hops: " + oids(es.chain()), PbStyleResolver.MAX_DEPTH, es.chain().size());
			for(int i = 0; i < PbStyleResolver.MAX_DEPTH; i++) {
				assertEquals("hop " + i, oid(s[i]), oid(es.chain().get(i)));
			}
			assertEquals("truncated at the 9th hop", oid(s[PbStyleResolver.MAX_DEPTH]), es.truncatedAt());
			assertEquals(OlioModelNames.MODEL_PB_STYLE, es.truncatedModel());
			assertEquals("nearest-set still resolved over the hops read", "palette 0", es.value(OlioFieldNames.FIELD_PB_PALETTE));

			/// Control: exactly MAX_DEPTH hops fits - unless a library style would be the 9th.
			EffectiveStyle ok = PbStyleResolver.resolve(u, bookWith(u, styleRef(oid(s[1])), null));
			assertEquals(PbStyleResolver.MAX_DEPTH, ok.chain().size());
			if(lib == 0) {
				assertTrue("8 hops with no library style is complete: " + ok, ok.isComplete());
			}
			else {
				assertFalse("8 hops plus a library style exceeds the cap at the library hop", ok.isComplete());
				assertEquals(OlioModelNames.MODEL_PB_STYLE, ok.truncatedModel());
			}
		}
		finally {
			cleanup(u, s);
		}
	}

	// ───────── 6: taskModels merge per key ─────────

	@Test
	public void case06_taskModelsMergePerKey() throws Exception {
		BaseRecord u = user();
		String tag = shortId();
		BaseRecord parent = scratchStyle(u, "p23-tm-parent-" + tag, fields(
			OlioFieldNames.FIELD_PB_TASK_MODELS, "{\"portrait\":\"parent-portrait.safetensors\",\"location\":\"parent-location.safetensors\"}"));
		BaseRecord child = scratchStyle(u, "p23-tm-child-" + tag, fields(
			OlioFieldNames.FIELD_PB_PARENT, parent,
			OlioFieldNames.FIELD_PB_TASK_MODELS, "{\"portrait\":\"child-portrait.safetensors\"}"));
		try {
			EffectiveStyle es = PbStyleResolver.resolve(u, bookWith(u, styleRef(oid(child)), null));
			assertTrue(es.isComplete());
			Map<PbModelTaskEnumType, String> tm = es.taskModels();
			assertEquals("child portrait wins", "child-portrait.safetensors", tm.get(PbModelTaskEnumType.PORTRAIT));
			assertEquals("parent location fills the missing key", "parent-location.safetensors", tm.get(PbModelTaskEnumType.LOCATION));
			assertEquals(2, tm.size());
			assertEquals(oid(child), oid(es.taskModelSource(PbModelTaskEnumType.PORTRAIT)));
			assertEquals(oid(parent), oid(es.taskModelSource(PbModelTaskEnumType.LOCATION)));
			assertNull(es.taskModelSource(PbModelTaskEnumType.SCENE_DIRECT));
			assertEquals("{\"location\":\"parent-location.safetensors\",\"portrait\":\"child-portrait.safetensors\"}",
				es.value(OlioFieldNames.FIELD_PB_TASK_MODELS));
			assertEquals("the nearest hop that sets any key", oid(child), oid(es.source(OlioFieldNames.FIELD_PB_TASK_MODELS)));
		}
		finally {
			cleanup(u, child, parent);
		}
	}

	// ───────── 7: an unreadable parent truncates - no throw, no write ─────────

	/**
	 * The stranger owns the parent in its own shared group and grants the test user Read long enough to
	 * create the child against it (PBAC refuses a create against an unreadable foreign ref), then revokes.
	 * Whether the walk stops at the parent or at the child itself depends on whether PBAC's foreign READ
	 * pattern ({@code TestModelResolverLive.case13}) denies the whole child read once its parent is
	 * unreadable; both are truncations at {@code olio.pb.style}, and the test records which one it measured.
	 */
	@Test
	public void case07_unreadableParentTruncatesNoThrowNoWrite() throws Exception {
		BaseRecord u = user();
		BaseRecord s = stranger();
		assertFalse("two principals", u.get(FieldNames.FIELD_ID).equals(s.get(FieldNames.FIELD_ID)));
		String tag = shortId();
		BaseRecord parent = newStyle(s, "p23-stranger-parent-" + tag, SHARED_PATH, PbStyleScopeEnumType.SERIES);
		parent.set(OlioFieldNames.FIELD_PB_PALETTE, "stranger palette " + tag);
		parent.set(FieldNames.FIELD_DESCRIPTION, "stranger description " + tag);
		parent = createStyle(s, parent);
		BaseRecord sharedDir = groupOf(s, parent);
		BaseRecord child = null;
		try {
			grant(s, sharedDir, u, true);
			BaseRecord parentAsUser = PbStyleUtil.readStyle(u, oid(parent), orgOf(u));
			assertNotNull("Control: with the grant the test user reads the stranger's parent", parentAsUser);
			child = scratchStyle(u, "p23-child-of-stranger-" + tag, fields(
				OlioFieldNames.FIELD_PB_PARENT, parentAsUser, OlioFieldNames.FIELD_PB_PALETTE, "child palette " + tag));
			assertEquals(oid(parent), oid(child.get(OlioFieldNames.FIELD_PB_PARENT)));
			/// Control: while readable the chain is complete and the parent's values flow.
			EffectiveStyle before = PbStyleResolver.resolve(u, bookWith(u, styleRef(oid(child)), null));
			assertTrue("complete while readable: " + before, before.isComplete());
			assertEquals("stranger description " + tag, before.value(FieldNames.FIELD_DESCRIPTION));

			grant(s, sharedDir, u, false);
			assertNull("Control: after the revoke the parent is unreadable", PbStyleUtil.readStyle(u, oid(parent), orgOf(u)));
			BaseRecord childAfter = PbStyleUtil.readStyle(u, oid(child), orgOf(u));

			EffectiveStyle es = PbStyleResolver.resolve(u, bookWith(u, styleRef(oid(child)), null));
			assertFalse("incomplete: " + es, es.isComplete());
			assertFalse(es.cycleDetected());
			assertEquals(OlioModelNames.MODEL_PB_STYLE, es.truncatedModel());
			if(childAfter != null) {
				logger.info("MEASURED: the child stays readable once its parent is not; truncation at the parent " + oid(parent));
				assertEquals(oid(parent), es.truncatedAt());
				assertEquals(Arrays.asList(oid(child)), oids(es.chain()));
				assertEquals("child palette " + tag, es.value(OlioFieldNames.FIELD_PB_PALETTE));
				assertNull("the unreadable parent's value never leaks", es.value(FieldNames.FIELD_DESCRIPTION));
			}
			else {
				logger.info("MEASURED: PBAC denies the whole child read once its parent is unreadable; truncation at the child " + oid(child));
				assertEquals(oid(child), es.truncatedAt());
				assertTrue(es.chain().isEmpty());
			}
			/// The same through resolveFromStyle on the child as read before the revoke: the parent hop is the truncation.
			EffectiveStyle rooted = PbStyleResolver.resolveFromStyle(u, child);
			assertFalse(rooted.isComplete());
			assertEquals(oid(parent), rooted.truncatedAt());

			/// Nothing was written: the stranger re-reads the parent uncached and every projected field is unchanged.
			BaseRecord parentAgain = PbStyleUtil.readStyle(s, oid(parent), orgOf(s));
			assertNotNull(parentAgain);
			for(String f : PbStyleUtil.styleRequest()) {
				Object was = (parent.hasField(f) ? parent.get(f) : null);
				Object is = (parentAgain.hasField(f) ? parentAgain.get(f) : null);
				if(was instanceof BaseRecord || is instanceof BaseRecord) {
					assertEquals("parent." + f, oid((BaseRecord) was), oid((BaseRecord) is));
				}
				else {
					assertEquals("parent." + f, was, is);
				}
			}
		}
		finally {
			/// Re-grant so the owner can delete the child whose foreign parent it may otherwise not read.
			try {
				grant(s, sharedDir, u, true);
			}
			catch(Throwable t) {
				logger.warn("re-grant: " + t.getMessage());
			}
			cleanup(u, child);
			grant(s, sharedDir, u, false);
			cleanup(s, parent);
		}
	}

	// ───────── 8: lockedBy is the nearest locked ancestor and never pins a value ─────────

	@Test
	public void case08_lockedByNearestAncestor() throws Exception {
		BaseRecord u = user();
		String tag = shortId();
		BaseRecord g = scratchStyle(u, "p23-lock-g-" + tag, fields(OlioFieldNames.FIELD_PB_LOCKED, Boolean.TRUE, OlioFieldNames.FIELD_PB_PALETTE, "g palette"));
		BaseRecord p = scratchStyle(u, "p23-lock-p-" + tag, fields(OlioFieldNames.FIELD_PB_PARENT, g));
		BaseRecord c = scratchStyle(u, "p23-lock-c-" + tag, fields(OlioFieldNames.FIELD_PB_PARENT, p, OlioFieldNames.FIELD_PB_PALETTE, "c palette"));
		try {
			EffectiveStyle es = PbStyleResolver.resolve(u, bookWith(u, styleRef(oid(c)), null));
			assertTrue(es.isComplete());
			assertEquals("the grandparent is the only locked hop", oid(g), oid(es.lockedBy()));
			assertEquals("a lock never pins a value: the child's palette still wins", "c palette", es.value(OlioFieldNames.FIELD_PB_PALETTE));

			p = patchStyle(u, p, OlioFieldNames.FIELD_PB_LOCKED, Boolean.TRUE);
			es = PbStyleResolver.resolve(u, bookWith(u, styleRef(oid(c)), null));
			assertEquals("nearest locked hop wins", oid(p), oid(es.lockedBy()));

			c = patchStyle(u, c, OlioFieldNames.FIELD_PB_LOCKED, Boolean.TRUE);
			es = PbStyleResolver.resolve(u, bookWith(u, styleRef(oid(c)), null));
			assertEquals("the book's own style included", oid(c), oid(es.lockedBy()));

			EffectiveStyle none = PbStyleResolver.resolve(u, bookWith(u, styleRef(oid(p)), null));
			assertEquals(oid(p), oid(none.lockedBy()));
		}
		finally {
			cleanup(u, c, p, g);
		}
	}

	// ───────── 9: the library lookup is find-only and never creates (157 i) ─────────

	@Test
	public void case09_libraryFindOnlyNeverCreates() throws Exception {
		BaseRecord u = user();
		long orgId = orgOf(u);
		int stylesBefore = countRows(OlioModelNames.MODEL_PB_STYLE, orgId);
		int groupsBefore = countRows(ModelNames.MODEL_GROUP, orgId);

		EffectiveStyle withPath = PbStyleResolver.resolve(u, bookWith(u, null, null));
		assertTrue("complete: " + withPath, withPath.isComplete());

		/// The 157 i fallback: a user record that carries no organizationPath must resolve through the
		/// organization context by id, still find-only.
		BaseRecord bare = u.copyRecord(new String[] {FieldNames.FIELD_ID, FieldNames.FIELD_OBJECT_ID, FieldNames.FIELD_NAME, FieldNames.FIELD_ORGANIZATION_ID});
		assertTrue(!bare.hasField(FieldNames.FIELD_ORGANIZATION_PATH) || bare.get(FieldNames.FIELD_ORGANIZATION_PATH) == null);
		EffectiveStyle noPath = PbStyleResolver.resolve(bare, bookWith(u, null, null));
		assertTrue("complete without organizationPath: " + noPath, noPath.isComplete());
		assertEquals("both lookups see the same library tail", withPath.chain().size(), noPath.chain().size());
		assertEquals(oids(withPath.chain()), oids(noPath.chain()));
		logger.info("Library tail for " + ORG_A + ": " + withPath.chain().size() + " hop(s)");

		assertEquals("no olio.pb.style row was created by resolving", stylesBefore, countRows(OlioModelNames.MODEL_PB_STYLE, orgId));
		assertEquals("no auth.group row (/Library/PictureBookStyles) was created by resolving", groupsBefore, countRows(ModelNames.MODEL_GROUP, orgId));
	}

	// ───────── 10: reference images are readable by a non-owner who can read the style (150 d) ─────────

	@Test
	public void case10_referenceImagesReadByNonOwner() throws Exception {
		BaseRecord u = user();
		BaseRecord s = stranger();
		long orgId = orgOf(u);
		String tag = shortId();
		BaseRecord style = scratchStyle(u, "p23-refs-" + tag, null);
		BaseRecord img = RecordFactory.newInstance(ModelNames.MODEL_DATA);
		ioContext.getRecordUtil().applyNameGroupOwnership(u, img, "p23-ref-" + tag + ".png", SCRATCH_PATH, orgId);
		img.set(FieldNames.FIELD_CONTENT_TYPE, "image/png");
		ByteModelUtil.setValue(img, ("not-a-real-png-" + tag).getBytes(StandardCharsets.UTF_8));
		BaseRecord imgCreated = ioContext.getAccessPoint().create(u, img);
		assertNotNull(imgCreated);
		BaseRecord imgRead = ioContext.getAccessPoint().findByObjectId(u, ModelNames.MODEL_DATA, oid(imgCreated));
		assertNotNull(imgRead);
		BaseRecord scratchDir = groupOf(u, style);
		try {
			assertTrue(PbStyleUtil.addReferenceImage(u, style, imgRead));
			assertEquals(1, PbStyleUtil.listReferenceImages(u, style).size());

			CacheUtil.clearCache();
			assertNull("Control: without a grant the stranger cannot read the style", PbStyleUtil.readStyle(s, oid(style), orgId));

			grant(u, scratchDir, s, true);
			BaseRecord asStranger = PbStyleUtil.readStyle(s, oid(style), orgId);
			assertNotNull("With Read on the group the stranger reads the style", asStranger);
			List<BaseRecord> refs = PbStyleUtil.listReferenceImages(s, asStranger);
			assertEquals("...and lists its reference image", 1, refs.size());
			assertEquals(oid(imgRead), oid(refs.get(0)));
			assertEquals("image/png", refs.get(0).get(FieldNames.FIELD_CONTENT_TYPE));
			assertFalse("never the bytes", refs.get(0).hasField(FieldNames.FIELD_BYTE_STORE));

			grant(u, scratchDir, s, false);
			assertNull("after the revoke the style is unreadable again", PbStyleUtil.readStyle(s, oid(style), orgId));
			List<BaseRecord> stale = PbStyleUtil.listReferenceImages(s, asStranger);
			assertNotNull("never null, never throws", stale);
			logger.info("MEASURED: listReferenceImages with a stale style handle after revoke returned " + stale.size() + " record(s)");
		}
		finally {
			grant(u, scratchDir, s, false);
			try {
				PbStyleUtil.removeReferenceImage(u, style, imgRead);
			}
			catch(Throwable t) {
				logger.warn("remove ref: " + t.getMessage());
			}
			cleanup(u, imgRead, style);
		}
	}

	// ───────── 11: null book / null user is the empty chain ─────────

	@Test
	public void case11_nullBookIsEmptyChain() throws Exception {
		BaseRecord u = user();
		EffectiveStyle es = PbStyleResolver.resolve(u, null);
		assertSame("resolve(user, null) is the shared EMPTY", PbStyleResolver.empty(), es);
		assertSame(PbStyleResolver.empty(), PbStyleResolver.resolve(null, book(u)));
		assertSame(PbStyleResolver.empty(), PbStyleResolver.resolveFromStyle(u, null));
		assertTrue(es.isEmpty());
		assertTrue(es.isComplete());
		assertTrue(es.chain().isEmpty());
		assertEquals(PbStyleResolver.DEFAULT_QUALITY_PRESET, es.qualityPreset());
		assertEquals(PbQualityPresetEnumType.STANDARD, es.qualityPreset());
		assertEquals(PbStyleResolver.DEFAULT_PAGE_FORMAT, es.pageFormat());
		assertEquals(PbPageFormatEnumType.SQUARE, es.pageFormat());
		assertEquals("", es.canonicalStyle());
		assertTrue(es.taskModels().isEmpty());
		assertNull(es.sdConfig());
		assertNull(es.sdConfigSource());
		assertNull(es.lockedBy());
		assertNull(es.truncatedAt());
		assertNull(es.truncatedModel());
		assertFalse(es.cycleDetected());
		assertNull(es.value(OlioFieldNames.FIELD_PB_PALETTE));
		assertNull(es.source(OlioFieldNames.FIELD_PB_PALETTE));
		/// A book whose style field is present-but-null and that names no series reads nothing but the library.
		EffectiveStyle blank = PbStyleResolver.resolve(u, bookWith(u, null, null));
		assertTrue(blank.isComplete());
		if(blank.isEmpty()) {
			assertSame(PbStyleResolver.empty(), blank);
		}
	}

	// ───────── 12: resolveFromStyle is rooted at the style handed in (157 l) ─────────

	@Test
	public void case12_resolveFromStyleRootedAtStyle() throws Exception {
		BaseRecord u = user();
		String tag = shortId();
		BaseRecord parent = scratchStyle(u, "p23-root-parent-" + tag, fields(OlioFieldNames.FIELD_PB_PALETTE, "parent palette"));
		BaseRecord child = scratchStyle(u, "p23-root-child-" + tag, fields(OlioFieldNames.FIELD_PB_PARENT, parent, FieldNames.FIELD_DESCRIPTION, "child description"));
		try {
			EffectiveStyle fromChild = PbStyleResolver.resolveFromStyle(u, child);
			assertTrue(fromChild.isComplete());
			assertSame("a fully projected style is used as hop 0 without a re-read", child, fromChild.chain().get(0));
			assertEquals(oid(parent), oid(fromChild.chain().get(1)));
			assertEquals("parent palette", fromChild.value(OlioFieldNames.FIELD_PB_PALETTE));
			assertEquals("child description", fromChild.value(FieldNames.FIELD_DESCRIPTION));

			EffectiveStyle fromParent = PbStyleResolver.resolveFromStyle(u, parent);
			assertTrue(fromParent.isComplete());
			assertEquals("rooted at the parent: the child is not in the chain", oid(parent), oid(fromParent.chain().get(0)));
			assertFalse(oids(fromParent.chain()).contains(oid(child)));
			assertNull(fromParent.value(FieldNames.FIELD_DESCRIPTION));

			/// A bare reference (objectId only) is read as the user before it becomes hop 0.
			EffectiveStyle fromRef = PbStyleResolver.resolveFromStyle(u, styleRef(oid(child)));
			assertTrue(fromRef.isComplete());
			assertEquals(oid(child), oid(fromRef.chain().get(0)));
			assertTrue("the re-read hop is a full style", fromRef.chain().get(0).hasField(OlioFieldNames.FIELD_PB_SCOPE));
			assertEquals(oids(fromChild.chain()), oids(fromRef.chain()));
		}
		finally {
			cleanup(u, child, parent);
		}
	}

	// ───────── 13: truncatedModel distinguishes a series hop from a style hop ─────────

	/**
	 * (a) A {@code book.series} reference that cannot be read at all (here: a series that does not exist)
	 * truncates at {@code olio.pb.series}. (b) A readable series whose {@code style} the user may not read:
	 * the stranger's private style is linked as {@code series.style} (olio principal) and the test records
	 * whether {@code readSeries} is denied whole (truncation at the series, the mechanism the brief expects
	 * from the foreign READ pattern) or returns the series and the style hop is the truncation.
	 */
	@Test
	public void case13_truncatedModelDistinguishesStyleFromSeries() throws Exception {
		BaseRecord u = user();
		BaseRecord s = stranger();
		long orgId = orgOf(u);

		/// (a) absent series
		String ghost = UUID.randomUUID().toString();
		assertNull(PbSeriesUtil.readSeries(u, ghost, orgId));
		EffectiveStyle absent = PbStyleResolver.resolve(u, bookWith(u, null, seriesRef(ghost)));
		assertFalse(absent.isComplete());
		assertEquals(OlioModelNames.MODEL_PB_SERIES, absent.truncatedModel());
		assertEquals(ghost, absent.truncatedAt());
		assertTrue(absent.chain().isEmpty());

		/// (b) readable series, unreadable series.style
		BaseRecord series = series(u);
		BaseRecord priorRef = series.get(OlioFieldNames.FIELD_STYLE);
		String tag = shortId();
		BaseRecord priv = newStyle(s, "p23-stranger-private-" + tag, PRIVATE_PATH, PbStyleScopeEnumType.SERIES);
		priv.set(OlioFieldNames.FIELD_PB_PALETTE, "private palette " + tag);
		priv = createStyle(s, priv);
		CacheUtil.clearCache();
		assertNull("Control: the private style is unreadable to the test user", PbStyleUtil.readStyle(u, oid(priv), orgId));
		boolean restored = false;
		try {
			assertTrue("series.style -> stranger-private", setSeriesStyle(s, series, priv));
			CacheUtil.clearCache();
			BaseRecord seriesAsUser = PbSeriesUtil.readSeries(u, oid(series), orgId);
			EffectiveStyle es = PbStyleResolver.resolve(u, bookWith(u, null, seriesRef(oid(series))));
			assertFalse("incomplete: " + es, es.isComplete());
			assertFalse(es.cycleDetected());
			assertTrue(es.chain().isEmpty());
			if(seriesAsUser == null) {
				logger.info("MEASURED: readSeries is denied whole once series.style is unreadable; truncation at olio.pb.series " + oid(series));
				assertEquals(OlioModelNames.MODEL_PB_SERIES, es.truncatedModel());
				assertEquals(oid(series), es.truncatedAt());
			}
			else {
				logger.info("MEASURED: readSeries still returns the series; truncation at olio.pb.style " + oid(priv));
				assertEquals(OlioModelNames.MODEL_PB_STYLE, es.truncatedModel());
				assertEquals(oid(priv), es.truncatedAt());
			}
			assertNull("the private style's value never leaks", es.value(OlioFieldNames.FIELD_PB_PALETTE));
		}
		finally {
			restored = setSeriesStyle(u, series, priorRef);
			if(restored) {
				cleanup(s, priv);
			}
			else {
				logger.error("series.style was NOT restored to " + oid(priorRef) + "; leaving " + oid(priv) + " so the FK does not dangle");
			}
		}
		assertTrue("series.style restored to its prior value " + oid(priorRef), restored);
	}

	// ───────── 14: a deleted sdConfig is "no config" (hash moves); an unreadable one truncates (158/160) ─────────

	/**
	 * A deleted config (§6.C 160). The owner deletes the config its style names. Through the record API the
	 * reference is then indistinguishable from "none": the style's {@code sdConfig} projection is a scalar
	 * subselect over the config table ({@code StatementUtil.getInnerSelectTemplate}), so once the row is gone
	 * it comes back as id 0 even though the FK column still holds the old id ({@code DBWriter.deleteForeignReferences}
	 * is off). The contract is therefore the dangling-reference contract every other foreign field has: the chain
	 * resolves COMPLETE with no config ({@code sdConfig()} and {@code sdConfigSource()} null), and the visible
	 * signal is the hash - the declared config loses the style tier, so {@code configHash} (inside every node's
	 * {@code inputHash}) moves and the book's nodes go STALE. The first revision of this case asserted a truncation
	 * at {@code olio.sd.config} for this variant on a wrong premise (that a deleted reference still projects its id);
	 * that assertion is now {@link #case14b_strangerOwnedSdConfigTruncates}, where the config is unreadable rather
	 * than gone.
	 */
	@Test
	public void case14_deletedSdConfigResolvesAsNoConfigAndMovesTheHash() throws Exception {
		BaseRecord u = user();
		long orgId = orgOf(u);
		String tag = shortId();
		BaseRecord cfg = RecordFactory.newInstance(OlioModelNames.MODEL_SD_CONFIG);
		ioContext.getRecordUtil().applyNameGroupOwnership(u, cfg, "p23-sdcfg-" + tag, SCRATCH_PATH, orgId);
		cfg.set(FieldNames.FIELD_NAME, "p23-sdcfg-" + tag);
		cfg.set("style", "art");
		BaseRecord cfgCreated = ioContext.getAccessPoint().create(u, cfg);
		assertNotNull(cfgCreated);
		long cfgId = ((Number) cfgCreated.get(FieldNames.FIELD_ID)).longValue();
		BaseRecord cfgRead = ioContext.getAccessPoint().findByObjectId(u, OlioModelNames.MODEL_SD_CONFIG, oid(cfgCreated));
		assertNotNull(cfgRead);
		BaseRecord sx = scratchStyle(u, "p23-dangling-" + tag, fields(OlioFieldNames.FIELD_PB_SD_CONFIG, cfgRead, OlioFieldNames.FIELD_PB_PALETTE, "dangling palette"));
		try {
			assertEquals(cfgId, PbStyleUtil.sdConfigId(sx));
			BaseRecord inMemoryBook = bookWith(u, styleRef(oid(sx)), null);
			EffectiveStyle live = PbStyleResolver.resolve(u, inMemoryBook);
			assertTrue("Control: with the config present the chain is complete: " + live, live.isComplete());
			assertNotNull(live.sdConfig());
			assertEquals(oid(cfgRead), oid(live.sdConfig()));
			assertEquals(oid(sx), oid(live.sdConfigSource()));
			assertEquals(Long.valueOf(cfgId), live.value(OlioFieldNames.FIELD_PB_SD_CONFIG));
			BaseRecord declaredBefore = PbConfigUtil.resolveDeclaredConfig(inMemoryBook, null, false, live);
			assertEquals("Control: the style tier's config is what gets declared", "art", declaredBefore.get("style"));
			String hashBefore = PbConfigUtil.configHash(declaredBefore);

			assertTrue("the owner deletes its config", ioContext.getAccessPoint().delete(u, cfgRead));
			CacheUtil.clearCache();
			BaseRecord sxAfter = PbStyleUtil.readStyle(u, oid(sx), orgId);
			assertNotNull("the owner still reads the style with the dangling reference", sxAfter);
			long projected = PbStyleUtil.sdConfigId(sxAfter);
			logger.info("MEASURED: after deleting olio.sd.config #" + cfgId + " the owner's re-read of the style projects sdConfig id " + projected);
			assertEquals("160: a deleted config projects as id 0 through the record API (scalar subselect over a missing row)", 0L, projected);
			assertNull("readSdConfig is null for the deleted config", PbStyleUtil.readSdConfig(u, sxAfter));

			EffectiveStyle es = PbStyleResolver.resolve(u, inMemoryBook);
			assertTrue("160: a style whose config was deleted resolves COMPLETE with no config - the dangling-reference"
				+ " contract of every foreign field (resolved: " + es + ")", es.isComplete());
			assertFalse(es.cycleDetected());
			assertNull(es.truncatedAt());
			assertNull(es.truncatedModel());
			assertNull("no config in effect", es.sdConfig());
			assertNull("no hop names a config any more", es.sdConfigSource());
			assertNull(es.value(OlioFieldNames.FIELD_PB_SD_CONFIG));
			assertEquals("the style's own fields still resolve", "dangling palette", es.value(OlioFieldNames.FIELD_PB_PALETTE));
			assertEquals("the style is still the chain's first hop", oid(sx), oid(es.chain().get(0)));
			/// resolveFromStyle agrees with resolve-through-a-book.
			EffectiveStyle fromStyle = PbStyleResolver.resolveFromStyle(u, sxAfter);
			assertTrue(fromStyle.isComplete());
			assertNull(fromStyle.sdConfig());

			/// The visible signal: the declared config lost its style tier, so configHash moved and nodes go STALE.
			BaseRecord declaredAfter = PbConfigUtil.resolveDeclaredConfig(inMemoryBook, null, false, es);
			assertNull("the deleted config's values are no longer declared", declaredAfter.hasField("style") ? declaredAfter.get("style") : null);
			String hashAfter = PbConfigUtil.configHash(declaredAfter);
			logger.info("MEASURED: configHash before deleting the style's config " + hashBefore + ", after " + hashAfter);
			assertNotEquals("160: deleting the style's config must move configHash (and so every node's inputHash)", hashBefore, hashAfter);
		}
		finally {
			cleanup(u, sx);
		}
	}

	/**
	 * An unreadable config (158): a stranger-owned config the test user cannot read, named by a style the test
	 * user can read (Read granted on the stranger's shared group). The chain must be incomplete; the test records
	 * whether the truncation lands on the style (PBAC denies the whole style read through the foreign READ
	 * pattern, {@code TestModelResolverLive.case13}) or on the config. Either way the facade refuses the style as
	 * a {@code parent} before writing anything: 404 when the style itself is unreadable ({@code styleValue} cannot
	 * read it), 400 from {@code requireValidParent} when the style reads but its chain is truncated at the config.
	 */
	@Test
	public void case14b_strangerOwnedSdConfigTruncates() throws Exception {
		BaseRecord u = user();
		long orgId = orgOf(u);
		String tag = shortId();
		BaseRecord s = stranger();
		BaseRecord scfg = RecordFactory.newInstance(OlioModelNames.MODEL_SD_CONFIG);
		ioContext.getRecordUtil().applyNameGroupOwnership(s, scfg, "p23-stranger-sdcfg-" + tag, PRIVATE_PATH, orgOf(s));
		scfg.set(FieldNames.FIELD_NAME, "p23-stranger-sdcfg-" + tag);
		scfg.set("style", "art");
		BaseRecord scfgCreated = ioContext.getAccessPoint().create(s, scfg);
		assertNotNull(scfgCreated);
		BaseRecord scfgRead = ioContext.getAccessPoint().findByObjectId(s, OlioModelNames.MODEL_SD_CONFIG, oid(scfgCreated));
		assertNotNull(scfgRead);
		BaseRecord sy = newStyle(s, "p23-stranger-cfg-style-" + tag, SHARED_PATH, PbStyleScopeEnumType.SERIES);
		sy.set(OlioFieldNames.FIELD_PB_SD_CONFIG, scfgRead);
		sy = createStyle(s, sy);
		BaseRecord sharedDir = groupOf(s, sy);
		try {
			grant(s, sharedDir, u, true);
			assertNull("Control: the private config is unreadable to the test user",
				ioContext.getAccessPoint().findByObjectId(u, OlioModelNames.MODEL_SD_CONFIG, oid(scfgRead)));
			BaseRecord syAsUser = PbStyleUtil.readStyle(u, oid(sy), orgId);
			EffectiveStyle es = PbStyleResolver.resolve(u, bookWith(u, styleRef(oid(sy)), null));
			assertFalse("incomplete: " + es, es.isComplete());
			assertNull(es.sdConfig());
			List<String> allowed = Arrays.asList(OlioModelNames.MODEL_PB_STYLE, OlioModelNames.MODEL_SD_CONFIG);
			assertTrue("truncated at the style or at the config: " + es.truncatedModel(), allowed.contains(es.truncatedModel()));
			if(syAsUser == null) {
				logger.info("MEASURED (secondary): PBAC denies the whole style read when its sdConfig is unreadable; truncation at olio.pb.style " + oid(sy));
				assertEquals(OlioModelNames.MODEL_PB_STYLE, es.truncatedModel());
				assertEquals(oid(sy), es.truncatedAt());
			}
			else {
				logger.info("MEASURED (secondary): the style is readable and the config is not; truncation at olio.sd.config #" + oid(scfgRead));
				assertEquals(OlioModelNames.MODEL_SD_CONFIG, es.truncatedModel());
				assertEquals(String.valueOf(((Number) scfgRead.get(FieldNames.FIELD_ID)).longValue()), es.truncatedAt());
			}

			/// The facade refuses the incomplete style as a parent before any write.
			BaseRecord bk = book(u);
			BaseRecord bookStyleBefore = bk.get(OlioFieldNames.FIELD_STYLE);
			int stylesBefore = countRows(OlioModelNames.MODEL_PB_STYLE, orgId);
			Map<String, Object> body = new LinkedHashMap<>();
			body.put(OlioFieldNames.FIELD_PB_PARENT, oid(sy));
			try {
				PbServiceFacade.putStyle(u, oid(bk), body);
				fail("putStyle with a parent whose chain is incomplete must be refused");
			}
			catch(PictureBookException e) {
				if(syAsUser == null) {
					assertEquals("an unreadable parent is a 404: " + e.getMessage(), 404, e.getStatus());
				}
				else {
					assertEquals("a readable parent with a truncated chain is a 400: " + e.getMessage(), 400, e.getStatus());
					assertTrue("message names the truncation: " + e.getMessage(), e.getMessage().contains("truncated at " + es.truncatedAt()));
				}
				assertTrue("message names the parent: " + e.getMessage(), e.getMessage().contains(oid(sy)));
			}
			assertEquals("the refused PUT created no style", stylesBefore, countRows(OlioModelNames.MODEL_PB_STYLE, orgId));
			BaseRecord bkAfter = PbBookUtil.readBook(u, oid(bk), orgId);
			assertEquals("book.style unchanged", oid(bookStyleBefore), oid(bkAfter.get(OlioFieldNames.FIELD_STYLE)));
			if(bookStyleBefore != null) {
				BaseRecord own = PbStyleUtil.readStyle(u, oid(bookStyleBefore), orgId);
				assertNotNull(own);
				BaseRecord ownParent = own.get(OlioFieldNames.FIELD_PB_PARENT);
				assertFalse("the book's own style was not re-parented onto the stranger's style", ownParent != null && oid(sy).equals(oid(ownParent)));
			}
		}
		finally {
			grant(s, sharedDir, u, false);
			cleanup(s, sy, scfgRead);
		}
	}

	// ───────── 15: two concurrent first PUTs on one style-less book ─────────

	/// A bare olio.pb.book row in the scratch group, as the user - the style write path needs no Olio world.
	private BaseRecord bareBook(BaseRecord u) throws Exception {
		String slug = "p23-conc-" + shortId();
		assertNotNull("scratch group", ioContext.getPathUtil().makePath(u, ModelNames.MODEL_GROUP, SCRATCH_PATH, "DATA", orgOf(u)));
		BaseRecord book = RecordFactory.newInstance(OlioModelNames.MODEL_PB_BOOK);
		ioContext.getRecordUtil().applyNameGroupOwnership(u, book, slug, SCRATCH_PATH, orgOf(u));
		book.set(FieldNames.FIELD_NAME, slug);
		book.set(OlioFieldNames.FIELD_PB_SLUG, slug);
		book.set(OlioFieldNames.FIELD_PB_BOOK_STATUS, PbBookStatusEnumType.DRAFT.toString());
		book.set(OlioFieldNames.FIELD_PB_BOOK_TYPE, PbBookTypeEnumType.STORY.toString());
		BaseRecord created = ioContext.getAccessPoint().create(u, book);
		assertNotNull("bare book create", created);
		BaseRecord read = PbBookUtil.readBook(u, oid(created), orgOf(u));
		assertNotNull("bare book re-read", read);
		assertNull("a bare book names no style", oid(read.hasField(OlioFieldNames.FIELD_STYLE) ? read.get(OlioFieldNames.FIELD_STYLE) : null));
		return read;
	}

	/// Every olio.pb.style row in the book's group named for the book's slug (`<slug>-style` and any
	/// `<slug>-style-<hex>` the race would leave), uncached, as the user. Earlier rounds share the
	/// scratch group, so the count must be scoped to the slug.
	private List<BaseRecord> stylesNamedFor(BaseRecord u, BaseRecord book) {
		String prefix = book.get(OlioFieldNames.FIELD_PB_SLUG) + "-style";
		Query q = QueryUtil.createQuery(OlioModelNames.MODEL_PB_STYLE, FieldNames.FIELD_GROUP_ID, ((Number) book.get(FieldNames.FIELD_GROUP_ID)).longValue());
		q.field(FieldNames.FIELD_ORGANIZATION_ID, orgOf(u));
		q.setRequest(new String[] {FieldNames.FIELD_ID, FieldNames.FIELD_OBJECT_ID, FieldNames.FIELD_NAME, OlioFieldNames.FIELD_PB_PALETTE});
		q.setCache(false);
		QueryResult qr = ioContext.getAccessPoint().list(u, q);
		assertNotNull("style list in the book's group", qr);
		List<BaseRecord> named = new ArrayList<>();
		for(BaseRecord r : qr.getResults()) {
			String name = r.get(FieldNames.FIELD_NAME);
			if(name != null && name.startsWith(prefix)) {
				named.add(r);
			}
		}
		return named;
	}

	/**
	 * Verifier finding on P2-3 (§6.C 161): {@code putStyle} reads the book, finds {@code <slug>-style},
	 * creates, then patches the FK - unserialised, two concurrent first PUTs either both create (the second
	 * hits the unique index, 500) or the second creates {@code <slug>-style-<hex>} over a stale book and
	 * orphans the first under the canonical name. Under the per-book write lock both PUTs succeed, exactly
	 * one style named for the book exists ({@code <slug>-style}, no {@code <slug>-style-<hex>}), the book
	 * points at it, and its palette is whichever PUT ran second. Repeated on fresh books so a lost lock shows up as a steady red,
	 * not a one-off; every book and style is deleted in finally.
	 */
	@Test
	public void case15_concurrentFirstPutsSerialiseOnTheBook() throws Exception {
		BaseRecord u = user();
		long orgId = orgOf(u);
		final int rounds = 4;
		List<BaseRecord> books = new ArrayList<>();
		List<BaseRecord> styles = new ArrayList<>();
		ExecutorService pool = Executors.newFixedThreadPool(2);
		try {
			for(int round = 0; round < rounds; round++) {
				BaseRecord bk = bareBook(u);
				books.add(bk);
				String slug = bk.get(OlioFieldNames.FIELD_PB_SLUG);
				String bookOid = oid(bk);
				CyclicBarrier gate = new CyclicBarrier(2);
				List<Callable<Map<String, Object>>> puts = new ArrayList<>();
				for(String palette : new String[] {"conc-A-" + round, "conc-B-" + round}) {
					puts.add(() -> {
						gate.await(30, TimeUnit.SECONDS);
						Map<String, Object> body = new LinkedHashMap<>();
						body.put(OlioFieldNames.FIELD_PB_PALETTE, palette);
						return PbServiceFacade.putStyle(u, bookOid, body);
					});
				}
				List<Future<Map<String, Object>>> results = pool.invokeAll(puts);
				List<Map<String, Object>> dtos = new ArrayList<>();
				for(Future<Map<String, Object>> f : results) {
					try {
						dtos.add(f.get(120, TimeUnit.SECONDS));
					}
					catch(ExecutionException e) {
						Throwable c = e.getCause();
						fail("round " + round + ": a concurrent first PUT failed: " + (c instanceof PictureBookException ? ((PictureBookException) c).getStatus() + " " : "") + c);
					}
				}
				assertEquals(2, dtos.size());
				@SuppressWarnings("unchecked")
				Map<String, Object> own0 = (Map<String, Object>) dtos.get(0).get("own");
				@SuppressWarnings("unchecked")
				Map<String, Object> own1 = (Map<String, Object>) dtos.get(1).get("own");
				assertNotNull("round " + round + ": first DTO carries own", own0);
				assertNotNull("round " + round + ": second DTO carries own", own1);
				assertEquals("round " + round + ": both PUTs end on the same own style", own0.get("objectId"), own1.get("objectId"));

				List<BaseRecord> rows = stylesNamedFor(u, bk);
				styles.addAll(rows);
				assertEquals("round " + round + ": exactly one style row named for the book, got " + oids(rows), 1, rows.size());
				assertEquals("round " + round + ": the one style is the canonical name", slug + "-style", rows.get(0).get(FieldNames.FIELD_NAME));
				BaseRecord after = PbBookUtil.readBook(u, bookOid, orgId);
				assertEquals("round " + round + ": book.style points at the one style", oid(rows.get(0)), oid(after.get(OlioFieldNames.FIELD_STYLE)));
				String palette = rows.get(0).get(OlioFieldNames.FIELD_PB_PALETTE);
				assertTrue("round " + round + ": the stored palette is one of the two PUTs, got " + palette,
					("conc-A-" + round).equals(palette) || ("conc-B-" + round).equals(palette));
				logger.info("MEASURED: round " + round + " two concurrent first PUTs on " + slug + " -> one style " + oid(rows.get(0)) + " palette " + palette);
			}
		}
		finally {
			pool.shutdownNow();
			cleanup(u, styles.toArray(new BaseRecord[0]));
			cleanup(u, books.toArray(new BaseRecord[0]));
		}
	}
}
