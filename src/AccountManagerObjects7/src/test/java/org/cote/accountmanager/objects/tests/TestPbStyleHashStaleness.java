package org.cote.accountmanager.objects.tests;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.cache.CacheUtil;
import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.io.OrganizationContext;
import org.cote.accountmanager.io.Query;
import org.cote.accountmanager.io.QueryUtil;
import org.cote.accountmanager.olio.OlioContextUtil;
import org.cote.accountmanager.olio.picturebook.PbBookUtil;
import org.cote.accountmanager.olio.picturebook.PbConfigUtil;
import org.cote.accountmanager.olio.picturebook.PbGraphUtil;
import org.cote.accountmanager.olio.picturebook.PbStaleUtil;
import org.cote.accountmanager.olio.picturebook.PbStyleResolver;
import org.cote.accountmanager.olio.picturebook.PbStyleResolver.EffectiveStyle;
import org.cote.accountmanager.olio.picturebook.PbStyleUtil;
import org.cote.accountmanager.olio.schema.OlioFieldNames;
import org.cote.accountmanager.olio.schema.OlioModelNames;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.record.RecordFactory;
import org.cote.accountmanager.schema.AccessSchema;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.schema.type.PbNodeStatusEnumType;
import org.cote.accountmanager.schema.type.PbNodeTypeEnumType;
import org.cote.accountmanager.schema.type.PbStyleScopeEnumType;
import org.cote.accountmanager.schema.type.PermissionEnumType;
import org.junit.Before;
import org.junit.Test;

/// PictureBook V3 Phase 2, P2-3: what the effective style does to {@code inputHash} and to the drift
/// scan (§6.C 156 g/h, 157 h). Live DB through {@code AccessPoint} as {@code testUser1}; no LLM, no SD.
///
/// The fixture book {@code pb2c-graph} (TestPbGraph's) and its workflow are shared; every node here is
/// freshly created with a random handle, and {@code book.style} is captured and restored by every case
/// that points it at a style of its own. The golden vector for a style-less node is pinned in
/// {@link TestPbGraph#case09_computeInputHashGoldenVector()}; this class pins that the style line is
/// absent for an empty chain, present for a non-empty one, moves with a style field, and ignores a lock.
public class TestPbStyleHashStaleness extends BaseTest {

	private static final Logger logger = LogManager.getLogger(TestPbStyleHashStaleness.class);

	private static final String ORG_A = "/Development/World Building";
	private static final String TEST_USER = "testUser1";
	private static final String STRANGER_USER = "p21StyleStranger";
	private static final String SLUG = "pb2c-graph";
	private static final String SCRATCH_PATH = "~/P23StyleHash";
	/// The stranger's grantable group (same path as TestPbStyleResolver so an orphan is easy to find).
	private static final String SHARED_PATH = "~/P23StyleShared";

	@Before
	public void hashSetup() {
		OlioContextUtil.clearCache();
		IOSystem.getActiveContext().getAccessPoint().setPermitBulkContainerApproval(false);
		PbStaleUtil.clear();
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

	private static String oid(BaseRecord r) {
		return (r != null ? r.get(FieldNames.FIELD_OBJECT_ID) : null);
	}

	private static long idOf(BaseRecord rec) {
		return ((Number) rec.get(FieldNames.FIELD_ID)).longValue();
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

	private BaseRecord freshBook(BaseRecord u, BaseRecord bk) {
		BaseRecord fresh = PbBookUtil.readBook(u, oid(bk), orgOf(u));
		assertNotNull("readBook " + oid(bk), fresh);
		return fresh;
	}

	private BaseRecord workflow(BaseRecord u, BaseRecord bk) {
		BaseRecord wf = PbGraphUtil.getCreateWorkflow(u, bk, PbBookUtil.workflowGroupPath(SLUG));
		assertNotNull("Failed to get/create the workflow", wf);
		return wf;
	}

	private BaseRecord node(BaseRecord u, BaseRecord wf, String prefix, PbNodeTypeEnumType type, int ordinal) {
		String handle = prefix + "-" + shortId();
		BaseRecord n = PbGraphUtil.addNode(u, wf, handle, type, PbBookUtil.workflowGroupPath(SLUG), ordinal);
		assertNotNull("Failed to add node " + handle, n);
		return n;
	}

	private BaseRecord readNode(BaseRecord u, BaseRecord node) {
		BaseRecord r = PbGraphUtil.readNode(u, oid(node), orgOf(u));
		assertNotNull("Node vanished: " + node.get(FieldNames.FIELD_NAME), r);
		return r;
	}

	private PbNodeStatusEnumType statusOf(BaseRecord u, BaseRecord node) {
		return readNode(u, node).getEnum(OlioFieldNames.FIELD_PB_NODE_STATUS);
	}

	/// The node's stored status and hash through a fresh, explicitly uncached, authorized query.
	private BaseRecord uncachedNode(BaseRecord u, BaseRecord node) {
		Query q = QueryUtil.createQuery(OlioModelNames.MODEL_PB_NODE, FieldNames.FIELD_OBJECT_ID, oid(node));
		q.field(FieldNames.FIELD_ORGANIZATION_ID, orgOf(u));
		q.setRequest(new String[] {FieldNames.FIELD_ID, FieldNames.FIELD_OBJECT_ID, FieldNames.FIELD_NAME,
			OlioFieldNames.FIELD_PB_NODE_STATUS, OlioFieldNames.FIELD_PB_INPUT_HASH});
		q.setCache(false);
		BaseRecord r = ioContext.getAccessPoint().find(u, q);
		assertNotNull("uncached node read " + oid(node), r);
		return r;
	}

	/// "Render": hash the node over the CURRENT book/style/bindings, persist the hash, mark DONE (TestPbStale).
	private String markRendered(BaseRecord u, BaseRecord node, BaseRecord bk) {
		BaseRecord fresh = readNode(u, node);
		String hash = PbGraphUtil.computeInputHash(u, fresh, bk);
		assertNotNull("computeInputHash returned null", hash);
		assertTrue("persistInputHash returned false", PbGraphUtil.persistInputHash(u, fresh, hash));
		assertTrue("persistStatus(DONE) returned false", PbGraphUtil.persistStatus(u, fresh, PbNodeStatusEnumType.DONE));
		assertEquals("Node should read back DONE after render", PbNodeStatusEnumType.DONE, statusOf(u, fresh));
		return hash;
	}

	private BaseRecord newStyle(BaseRecord u, String name, String groupPath, PbStyleScopeEnumType scope) throws Exception {
		BaseRecord style = RecordFactory.newInstance(OlioModelNames.MODEL_PB_STYLE);
		ioContext.getRecordUtil().applyNameGroupOwnership(u, style, name, groupPath, orgOf(u));
		style.set(FieldNames.FIELD_NAME, name);
		style.set(OlioFieldNames.FIELD_PB_SCOPE, scope.toString());
		return style;
	}

	private BaseRecord createStyle(BaseRecord u, BaseRecord style) {
		BaseRecord created = ioContext.getAccessPoint().create(u, style);
		assertNotNull("AccessPoint.create must accept olio.pb.style '" + style.get(FieldNames.FIELD_NAME) + "'", created);
		BaseRecord read = PbStyleUtil.readStyle(u, oid(created), orgOf(u));
		assertNotNull("readStyle must find " + oid(created) + " as the owning user", read);
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

	/// identity + name + one changed field (model-api PATCH idiom); returns the uncached re-read.
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

	/// Patch book.style (identity + name + style) as the book owner and verify by uncached re-read.
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

	/// An in-memory book with no style and no series: the empty chain (the org has no library style).
	private BaseRecord styleLessBook(BaseRecord u) throws Exception {
		BaseRecord b = RecordFactory.newInstance(OlioModelNames.MODEL_PB_BOOK, new String[] {
			FieldNames.FIELD_OBJECT_ID, FieldNames.FIELD_ORGANIZATION_ID, OlioFieldNames.FIELD_STYLE, OlioFieldNames.FIELD_PB_SERIES
		});
		b.set(FieldNames.FIELD_OBJECT_ID, "in-memory-" + shortId());
		b.set(FieldNames.FIELD_ORGANIZATION_ID, orgOf(u));
		return b;
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

	private BaseRecord groupOf(BaseRecord owner, BaseRecord rec) {
		long gid = ((Number) rec.get(FieldNames.FIELD_GROUP_ID)).longValue();
		BaseRecord dir = ioContext.getAccessPoint().findById(owner, ModelNames.MODEL_GROUP, gid);
		assertNotNull("The owner must read its own group " + gid, dir);
		return dir;
	}

	private void grant(BaseRecord owner, BaseRecord dir, BaseRecord grantee, boolean enable) {
		long orgId = orgOf(owner);
		BaseRecord rg = AccessSchema.getSystemPermission(AccessSchema.SYSTEM_PERMISSION_READ, PermissionEnumType.GROUP.toString(), orgId);
		BaseRecord rd = AccessSchema.getSystemPermission(AccessSchema.SYSTEM_PERMISSION_READ, PermissionEnumType.DATA.toString(), orgId);
		assertNotNull(rg);
		assertNotNull(rd);
		boolean g = ioContext.getMemberUtil().member(owner, dir, grantee, rg, enable);
		boolean d = ioContext.getMemberUtil().member(owner, dir, grantee, rd, enable);
		if(enable) {
			assertTrue("grant Read/GROUP", g);
			assertTrue("grant Read/DATA", d);
		}
		CacheUtil.clearCache();
	}

	private static String styleLine(String canonical) {
		for(String line : canonical.split(PbConfigUtil.PAIR_SEPARATOR)) {
			if(line.startsWith("style=")) {
				return line;
			}
		}
		return null;
	}

	// ───────── 1: a style-less book hashes exactly as before P2-3 (no style line) ─────────

	/**
	 * 156 g: the {@code style=} line is present only for a non-empty chain. The in-memory COMPOSITE node is
	 * TestPbGraph.case09's shape; the golden digest itself is pinned there (run that class alongside).
	 * Here: null book, a style-less in-memory book, and an explicit empty chain all produce one identical
	 * canonical form with no {@code style=} line, while the same node under the fixture book's real style
	 * produces a different one that carries it.
	 */
	@Test
	public void case01_styleLessBookHasNoStyleLine() throws Exception {
		BaseRecord u = user();
		BaseRecord bk = book(u);
		BaseRecord styleLess = styleLessBook(u);
		EffectiveStyle none = PbStyleResolver.resolve(u, styleLess);
		assertTrue("the org must have no library style for this case to be meaningful: " + none, none.isComplete() && none.isEmpty());

		BaseRecord node = RecordFactory.newInstance(OlioModelNames.MODEL_PB_NODE);
		node.set(FieldNames.FIELD_NAME, "Node hash " + shortId());
		node.set(OlioFieldNames.FIELD_PB_HANDLE, "hash");
		node.set(OlioFieldNames.FIELD_PB_NODE_TYPE, PbNodeTypeEnumType.COMPOSITE.toString());
		node.set(OlioFieldNames.FIELD_PB_PROMPT_TEXT, "a fixed prompt");

		String viaNullBook = PbGraphUtil.canonicalInput(u, node, null);
		String viaStyleLess = PbGraphUtil.canonicalInput(u, node, styleLess);
		String viaEmptyChain = PbGraphUtil.canonicalInput(u, node, styleLess, Arrays.asList(), PbStyleResolver.empty());
		logger.info("style-less canonical form:\n" + viaStyleLess);
		assertNull("no style line for a null book", styleLine(viaNullBook));
		assertNull("no style line for a style-less book", styleLine(viaStyleLess));
		assertNull("no style line for an explicit empty chain", styleLine(viaEmptyChain));
		assertEquals("a style-less book hashes as a null book", viaNullBook, viaStyleLess);
		assertEquals("resolving the empty chain and passing it are the same", viaStyleLess, viaEmptyChain);
		assertEquals(PbConfigUtil.sha256Hex(viaStyleLess), PbGraphUtil.computeInputHash(u, node, styleLess));

		/// The fixture book carries a style (TestPbStyleSchema's p21-book-style); the same node under it
		/// carries a style line and hashes differently. Guarded: if the fixture is unstyled this leg is moot.
		BaseRecord fresh = freshBook(u, bk);
		EffectiveStyle es = PbStyleResolver.resolve(u, fresh);
		if(!es.isEmpty() && es.isComplete()) {
			String viaStyled = PbGraphUtil.canonicalInput(u, node, fresh);
			String line = styleLine(viaStyled);
			assertNotNull("a styled book carries a style line", line);
			assertEquals("style=" + PbConfigUtil.sha256Hex(es.canonicalStyle()), line);
			assertNotEquals("the styled form differs from the style-less one", viaStyleLess, viaStyled);
		}
		else {
			logger.warn("MEASURED: fixture book " + SLUG + " resolves " + es + "; the styled leg of case01 was not exercised");
		}
	}

	// ───────── 2: a field change on the book's own style moves the hash and makes the node STALE ─────────

	/** 156 g, 157 h: palette edit -> inputHash moves -> recomputeStatus STALE -> scan marks and persists it. */
	@Test
	public void case02_styleFieldChangeMovesHashAndMarksStale() throws Exception {
		BaseRecord u = user();
		BaseRecord bk = book(u);
		BaseRecord priorStyle = bk.get(OlioFieldNames.FIELD_STYLE);
		String tag = shortId();
		BaseRecord s = scratchStyle(u, "p23-hash-" + tag, fields(OlioFieldNames.FIELD_PB_PALETTE, "palette before " + tag));
		boolean restored = false;
		try {
			assertTrue("set book.style -> own", setBookStyle(u, bk, s));
			BaseRecord styled = freshBook(u, bk);
			BaseRecord wf = workflow(u, styled);
			BaseRecord n = node(u, wf, "p23-hash-c02", PbNodeTypeEnumType.PORTRAIT, 0);

			String hashA = markRendered(u, n, styled);
			String canonA = PbGraphUtil.canonicalInput(u, readNode(u, n), styled);
			assertNotNull("the styled node carries a style line", styleLine(canonA));
			assertEquals(PbNodeStatusEnumType.DONE, PbGraphUtil.recomputeStatus(u, readNode(u, n), styled));

			patchStyle(u, s, OlioFieldNames.FIELD_PB_PALETTE, "palette after " + tag);
			BaseRecord styledAgain = freshBook(u, bk);
			String canonB = PbGraphUtil.canonicalInput(u, readNode(u, n), styledAgain);
			String hashB = PbConfigUtil.sha256Hex(canonB);
			assertNotEquals("a style field edit must move the inputHash\nA:\n" + canonA + "\nB:\n" + canonB, hashA, hashB);
			assertNotEquals("only the style line moved", styleLine(canonA), styleLine(canonB));
			assertEquals("everything but the style line is unchanged",
				canonA.replace(styleLine(canonA), "<style>"), canonB.replace(styleLine(canonB), "<style>"));

			assertEquals("recomputeStatus sees the moved hash", PbNodeStatusEnumType.STALE, PbGraphUtil.recomputeStatus(u, readNode(u, n), styledAgain));
			assertEquals("recomputeStatus writes nothing", PbNodeStatusEnumType.DONE, uncachedNode(u, n).getEnum(OlioFieldNames.FIELD_PB_NODE_STATUS));

			PbStaleUtil.clear();
			PbStaleUtil.Scan scan = PbStaleUtil.scan(u, styledAgain, wf, true);
			assertNull("complete chain: nothing truncated", scan.styleTruncatedAt);
			assertFalse(scan.styleCycleDetected);
			assertTrue("the edited style's node is stale: " + scan.entries.keySet(), scan.entries.containsKey(idOf(n)));
			assertEquals(PbStaleUtil.REASON_HASH_CHANGED, scan.entries.get(idOf(n)).reason);
			assertTrue("the owning user's mark persisted", scan.entries.get(idOf(n)).persisted);
			assertEquals(PbNodeStatusEnumType.STALE, uncachedNode(u, n).getEnum(OlioFieldNames.FIELD_PB_NODE_STATUS));
			assertEquals("the stored hash is never rewritten by a scan", hashA, uncachedNode(u, n).get(OlioFieldNames.FIELD_PB_INPUT_HASH));
		}
		finally {
			restored = setBookStyle(u, bk, priorStyle);
			if(restored) {
				cleanup(u, s);
			}
			else {
				logger.error("book.style was NOT restored to " + oid(priorStyle) + "; leaving " + oid(s) + " in place");
			}
		}
		assertTrue("book.style restored to its prior value " + oid(priorStyle), restored);
	}

	// ───────── 3: a lock toggle moves nothing ─────────

	/** 156 g: locked is not hashed; lockedBy is observable on the chain but the node stays DONE. */
	@Test
	public void case03_lockToggleDoesNotMoveHash() throws Exception {
		BaseRecord u = user();
		BaseRecord bk = book(u);
		BaseRecord priorStyle = bk.get(OlioFieldNames.FIELD_STYLE);
		String tag = shortId();
		BaseRecord s = scratchStyle(u, "p23-lock-" + tag, fields(OlioFieldNames.FIELD_PB_PALETTE, "locked palette " + tag));
		boolean restored = false;
		try {
			assertTrue("set book.style -> own", setBookStyle(u, bk, s));
			BaseRecord styled = freshBook(u, bk);
			BaseRecord wf = workflow(u, styled);
			BaseRecord n = node(u, wf, "p23-hash-c03", PbNodeTypeEnumType.LANDSCAPE, 0);
			String hashA = markRendered(u, n, styled);
			String canonA = PbGraphUtil.canonicalInput(u, readNode(u, n), styled);
			assertNull(PbStyleResolver.resolve(u, styled).lockedBy());

			patchStyle(u, s, OlioFieldNames.FIELD_PB_LOCKED, Boolean.TRUE);
			BaseRecord styledLocked = freshBook(u, bk);
			EffectiveStyle locked = PbStyleResolver.resolve(u, styledLocked);
			assertEquals("the lock is observable on the chain", oid(s), oid(locked.lockedBy()));
			String canonB = PbGraphUtil.canonicalInput(u, readNode(u, n), styledLocked);
			assertEquals("a lock toggle must not move the canonical form\nA:\n" + canonA + "\nB:\n" + canonB, canonA, canonB);
			assertEquals("nor the hash", hashA, PbConfigUtil.sha256Hex(canonB));
			assertEquals(PbNodeStatusEnumType.DONE, PbGraphUtil.recomputeStatus(u, readNode(u, n), styledLocked));

			PbStaleUtil.clear();
			PbStaleUtil.Scan scan = PbStaleUtil.scan(u, styledLocked, wf, true);
			assertFalse("a lock toggle must not stale the node", scan.entries.containsKey(idOf(n)));
			assertEquals(PbNodeStatusEnumType.DONE, scan.statuses.get(idOf(n)));
			assertEquals(PbNodeStatusEnumType.DONE, uncachedNode(u, n).getEnum(OlioFieldNames.FIELD_PB_NODE_STATUS));

			patchStyle(u, s, OlioFieldNames.FIELD_PB_LOCKED, Boolean.FALSE);
			assertEquals("unlock moves nothing either", hashA, PbGraphUtil.computeInputHash(u, readNode(u, n), freshBook(u, bk)));
		}
		finally {
			restored = setBookStyle(u, bk, priorStyle);
			if(restored) {
				cleanup(u, s);
			}
			else {
				logger.error("book.style was NOT restored to " + oid(priorStyle) + "; leaving " + oid(s) + " in place");
			}
		}
		assertTrue("book.style restored to its prior value " + oid(priorStyle), restored);
	}

	// ───────── 4: a truncated chain makes the scan incomplete and persists nothing ─────────

	/**
	 * 156 h: the book's own style has a parent owned by another user, readable only while granted. The node
	 * is rendered while the chain is complete (the parent's values are in the hash). After the revoke a
	 * naive recompute would see a different hash and mark the node STALE; the scan must instead report
	 * {@code complete=false}, name the truncation, keep the stored DONE, write nothing and cache nothing.
	 */
	@Test
	public void case04_truncatedChainScanIsIncompleteAndPersistsNothing() throws Exception {
		BaseRecord u = user();
		BaseRecord st = stranger();
		assertFalse("two principals", u.get(FieldNames.FIELD_ID).equals(st.get(FieldNames.FIELD_ID)));
		BaseRecord bk = book(u);
		BaseRecord priorStyle = bk.get(OlioFieldNames.FIELD_STYLE);
		String tag = shortId();
		BaseRecord parent = newStyle(st, "p23-hash-stranger-parent-" + tag, SHARED_PATH, PbStyleScopeEnumType.SERIES);
		parent.set(FieldNames.FIELD_DESCRIPTION, "stranger description " + tag);
		parent = createStyle(st, parent);
		BaseRecord sharedDir = groupOf(st, parent);
		BaseRecord child = null;
		boolean restored = false;
		try {
			grant(st, sharedDir, u, true);
			BaseRecord parentAsUser = PbStyleUtil.readStyle(u, oid(parent), orgOf(u));
			assertNotNull("Control: with the grant the test user reads the stranger's parent", parentAsUser);
			child = scratchStyle(u, "p23-hash-child-" + tag, fields(
				OlioFieldNames.FIELD_PB_PARENT, parentAsUser, OlioFieldNames.FIELD_PB_PALETTE, "child palette " + tag));
			assertTrue("set book.style -> child", setBookStyle(u, bk, child));
			BaseRecord styled = freshBook(u, bk);
			EffectiveStyle complete = PbStyleResolver.resolve(u, styled);
			assertTrue("Control: complete while readable: " + complete, complete.isComplete());
			assertEquals("stranger description " + tag, complete.value(FieldNames.FIELD_DESCRIPTION));

			BaseRecord wf = workflow(u, styled);
			BaseRecord n = node(u, wf, "p23-hash-c04", PbNodeTypeEnumType.PORTRAIT, 0);
			String hashA = markRendered(u, n, styled);
			long wfId = idOf(wf);

			PbStaleUtil.clear();
			PbStaleUtil.Scan control = PbStaleUtil.scan(u, styled, wf, true);
			assertTrue("Control: a complete chain scans complete", control.complete);
			assertNull(control.styleTruncatedAt);
			assertFalse("Control: freshly rendered node is not stale", control.entries.containsKey(idOf(n)));
			assertNotNull("Control: a complete scan is cached", PbStaleUtil.cached(wfId));

			grant(st, sharedDir, u, false);
			assertNull("Control: after the revoke the parent is unreadable", PbStyleUtil.readStyle(u, oid(parent), orgOf(u)));
			BaseRecord bookAfter = PbBookUtil.readBook(u, oid(bk), orgOf(u));
			logger.info("MEASURED: with book.style -> a child of an unreadable parent, readBook as the owner returns "
				+ (bookAfter != null ? "the book" : "NULL (PBAC denies the book read whole)"));
			BaseRecord scanBook = (bookAfter != null ? bookAfter : styled);

			/// What a naive recompute would do: the chain lost the parent's values, so the hash differs.
			EffectiveStyle truncated = PbStyleResolver.resolve(u, scanBook);
			assertFalse("incomplete after the revoke: " + truncated, truncated.isComplete());
			assertNotNull("a truncation names the hop", truncated.truncatedAt());
			assertEquals(OlioModelNames.MODEL_PB_STYLE, truncated.truncatedModel());
			String naive = PbGraphUtil.computeInputHash(u, readNode(u, n), scanBook);
			assertNotEquals("Precondition: the truncated chain hashes differently, so a naive scan WOULD mark STALE", hashA, naive);

			PbStaleUtil.clear();
			PbStaleUtil.Scan scan = PbStaleUtil.scan(u, scanBook, wf, true);
			assertFalse("an incomplete chain is an incomplete scan", scan.complete);
			assertEquals("styleTruncatedAt names the unreadable hop", truncated.truncatedAt(), scan.styleTruncatedAt);
			assertTrue("styleTruncatedAt is the child or its parent: " + scan.styleTruncatedAt,
				oid(child).equals(scan.styleTruncatedAt) || oid(parent).equals(scan.styleTruncatedAt));
			logger.info("MEASURED: scan.styleTruncatedAt = " + scan.styleTruncatedAt + " (child " + oid(child) + ", parent " + oid(parent) + ")");
			assertFalse(scan.styleCycleDetected);
			assertTrue("the truncation is an undetermined ref: " + scan.undeterminedRefs,
				scan.undeterminedRefs.contains(OlioModelNames.MODEL_PB_STYLE + ":" + scan.styleTruncatedAt));
			assertFalse("the hashed node is NOT reported stale", scan.entries.containsKey(idOf(n)));
			assertEquals("the hashed node reports its STORED status", PbNodeStatusEnumType.DONE, scan.statuses.get(idOf(n)));
			assertNull("an incomplete scan is never cached", PbStaleUtil.cached(wfId));

			BaseRecord after = uncachedNode(u, n);
			assertEquals("nothing persisted: status unchanged", PbNodeStatusEnumType.DONE, after.getEnum(OlioFieldNames.FIELD_PB_NODE_STATUS));
			assertEquals("nothing persisted: hash unchanged", hashA, after.get(OlioFieldNames.FIELD_PB_INPUT_HASH));

			/// Same answer twice: the second forced scan is still incomplete and still writes nothing.
			PbStaleUtil.Scan again = PbStaleUtil.scan(u, scanBook, wf, true);
			assertFalse(again.complete);
			assertEquals(PbNodeStatusEnumType.DONE, uncachedNode(u, n).getEnum(OlioFieldNames.FIELD_PB_NODE_STATUS));
		}
		finally {
			try {
				grant(st, sharedDir, u, true);
			}
			catch(Throwable t) {
				logger.warn("re-grant: " + t.getMessage());
			}
			restored = setBookStyle(u, bk, priorStyle);
			if(restored) {
				cleanup(u, child);
			}
			else {
				logger.error("book.style was NOT restored to " + oid(priorStyle) + "; leaving " + oid(child) + " in place");
			}
			grant(st, sharedDir, u, false);
			cleanup(st, parent);
		}
		assertTrue("book.style restored to its prior value " + oid(priorStyle), restored);
	}

	// ───────── 5: the once-per-scan chain and the per-node chain hash identically ─────────

	/** 156 h: the 5-arg overload with a pre-resolved chain equals the 3-arg overload that resolves per node. */
	@Test
	public void case05_preResolvedChainHashesLikePerNodeResolve() throws Exception {
		BaseRecord u = user();
		BaseRecord bk = book(u);
		BaseRecord priorStyle = bk.get(OlioFieldNames.FIELD_STYLE);
		String tag = shortId();
		BaseRecord s = scratchStyle(u, "p23-once-" + tag, fields(OlioFieldNames.FIELD_PB_PALETTE, "once palette " + tag));
		boolean restored = false;
		try {
			assertTrue(setBookStyle(u, bk, s));
			BaseRecord styled = freshBook(u, bk);
			BaseRecord wf = workflow(u, styled);
			BaseRecord n = node(u, wf, "p23-hash-c05", PbNodeTypeEnumType.SCENE_PROMPT, 0);
			BaseRecord fresh = readNode(u, n);
			List<BaseRecord> bindings = PbGraphUtil.listBindings(u, fresh);
			EffectiveStyle once = PbStyleResolver.resolve(u, styled);
			assertTrue(once.isComplete());
			String perNode = PbGraphUtil.computeInputHash(u, fresh, styled);
			String preResolved = PbGraphUtil.computeInputHash(u, fresh, styled, bindings, once);
			assertEquals("one chain per scan must hash exactly as one chain per node", perNode, preResolved);
			assertEquals(PbGraphUtil.canonicalInput(u, fresh, styled), PbGraphUtil.canonicalInput(u, fresh, styled, bindings, once));
		}
		finally {
			restored = setBookStyle(u, bk, priorStyle);
			if(restored) {
				cleanup(u, s);
			}
		}
		assertTrue("book.style restored", restored);
	}
}
