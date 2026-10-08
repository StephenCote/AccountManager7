package org.cote.accountmanager.objects.tests;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Configuration;
import org.apache.logging.log4j.core.config.LoggerConfig;
import org.cote.accountmanager.factory.Factory;
import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.io.OrganizationContext;
import org.cote.accountmanager.io.Query;
import org.cote.accountmanager.io.QueryUtil;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.record.RecordFactory;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.schema.type.ComparatorEnumType;
import org.cote.accountmanager.schema.type.GroupEnumType;
import org.cote.accountmanager.schema.type.RoleEnumType;
import org.junit.Test;

/**
 * {@code PathUtil} under concurrency, plus the two path-walk defects fixed on 2026-10-07:
 * <ul>
 *   <li>N threads calling {@code makePath} for the SAME brand-new path get the same tree, one row
 *       per segment, with no duplicate-key insert attempted;</li>
 *   <li>N threads creating sibling leaves under one brand-new prefix share exactly one prefix row;</li>
 *   <li>the unsynchronized {@code findPath} racing a {@code makePath} returns either {@code null}
 *       ("not there yet") or the real final node - never a phantom id and never a different path's
 *       node;</li>
 *   <li>an AMBIGUOUS segment (more than one row answers the lookup) fails closed - {@code null} plus an
 *       ERROR - instead of falling through and handing back the previous segment's node (and, for
 *       makePath, creating the remaining segments under it);</li>
 *   <li>{@code makePath} on a groupId-keyed model ({@code data.data}) creates the missing
 *       {@code auth.group} containers and the leaf itself, idempotently, and {@code findPath} then
 *       resolves it. Before, every such call died on {@code set(parentId)} and returned null.</li>
 * </ul>
 * Live Postgres, a dedicated test organization, never the admin user for the operations under test.
 */
public class TestPathUtilConcurrency extends BaseTest {

	private static final String ORG_PATH = "/Development/Path Concurrency";
	private static final int THREADS = 8;

	private static String uuid8() {
		return UUID.randomUUID().toString().replace("-", "").substring(0, 8);
	}

	private OrganizationContext org() {
		OrganizationContext oc = getTestOrganization(ORG_PATH);
		assertNotNull("Test organization " + ORG_PATH, oc);
		return oc;
	}

	private BaseRecord newUser(OrganizationContext oc, String prefix) {
		Factory mf = IOSystem.getActiveContext().getFactory();
		String name = prefix + uuid8();
		BaseRecord u = mf.getCreateUser(oc.getAdminUser(), name, oc.getOrganizationId());
		assertNotNull("Factory.getCreateUser returned null for '" + name + "'", u);
		IOSystem.getActiveContext().getRecordUtil().populate(u);
		return u;
	}

	private static long orgId(BaseRecord user) {
		return ((Number) user.get(FieldNames.FIELD_ORGANIZATION_ID)).longValue();
	}

	private static String homePath(BaseRecord user) {
		String hp = user.get(FieldNames.FIELD_HOME_DIRECTORY_FIELD_PATH);
		assertNotNull("The test user must have a resolvable home directory path", hp);
		return hp;
	}

	private static long idOf(BaseRecord rec) {
		return ((Number) rec.get(FieldNames.FIELD_ID)).longValue();
	}

	private static BaseRecord makeGroup(BaseRecord user, String path) {
		return IOSystem.getActiveContext().getPathUtil().makePath(user, ModelNames.MODEL_GROUP, path,
			GroupEnumType.DATA.toString(), orgId(user));
	}

	private static BaseRecord findGroup(BaseRecord user, String path) {
		return IOSystem.getActiveContext().getPathUtil().findPath(user, ModelNames.MODEL_GROUP, path,
			GroupEnumType.DATA.toString(), orgId(user));
	}

	/** Rows literally present for (model, parentId, name, organizationId) - no type filter, cache off. */
	private static int countInParent(String model, long parentId, String name, long organizationId) {
		Query q = QueryUtil.createQuery(model, FieldNames.FIELD_PARENT_ID, parentId);
		q.field(FieldNames.FIELD_NAME, ComparatorEnumType.EQUALS, name);
		q.field(FieldNames.FIELD_ORGANIZATION_ID, ComparatorEnumType.EQUALS, organizationId);
		q.setCache(false);
		return IOSystem.getActiveContext().getSearch().count(q);
	}

	private static int countInGroup(String model, long groupId, String name, long organizationId) {
		Query q = QueryUtil.createQuery(model, FieldNames.FIELD_GROUP_ID, groupId);
		q.field(FieldNames.FIELD_NAME, ComparatorEnumType.EQUALS, name);
		q.field(FieldNames.FIELD_ORGANIZATION_ID, ComparatorEnumType.EQUALS, organizationId);
		q.setCache(false);
		return IOSystem.getActiveContext().getSearch().count(q);
	}

	/** One row straight from the DB by id, cache off. Null means the id has no row (a phantom). */
	private static BaseRecord row(String model, long id, long organizationId) {
		Query q = QueryUtil.createQuery(model, FieldNames.FIELD_ID, id);
		q.field(FieldNames.FIELD_ORGANIZATION_ID, ComparatorEnumType.EQUALS, organizationId);
		q.setRequest(new String[] { FieldNames.FIELD_ID, FieldNames.FIELD_NAME, FieldNames.FIELD_ORGANIZATION_ID });
		q.setCache(false);
		return IOSystem.getActiveContext().getSearch().findRecord(q);
	}

	private static BaseRecord rawRole(BaseRecord user, long parentId, String name, RoleEnumType type) throws Exception {
		BaseRecord r = RecordFactory.model(ModelNames.MODEL_ROLE).newInstance();
		r.set(FieldNames.FIELD_NAME, name);
		r.set(FieldNames.FIELD_PARENT_ID, parentId);
		r.set(FieldNames.FIELD_ORGANIZATION_ID, orgId(user));
		r.set(FieldNames.FIELD_TYPE, type.toString());
		r.set(FieldNames.FIELD_OWNER_ID, user.get(FieldNames.FIELD_ID));
		assertTrue("Precondition: raw role '" + name + "' (" + type + ") must be created",
			IOSystem.getActiveContext().getRecordUtil().createRecord(r));
		return r;
	}

	/** Runs every task after a common start gate and returns their results, rethrowing any failure. */
	private static <T> List<T> runAllAtOnce(List<Callable<T>> tasks) throws Exception {
		ExecutorService pool = Executors.newFixedThreadPool(tasks.size());
		CountDownLatch gate = new CountDownLatch(1);
		List<Future<T>> futures = new ArrayList<>();
		try {
			for (Callable<T> t : tasks) {
				futures.add(pool.submit(() -> {
					gate.await(10, TimeUnit.SECONDS);
					return t.call();
				}));
			}
			gate.countDown();
			List<T> out = new ArrayList<>();
			for (Future<T> f : futures) {
				out.add(f.get(120, TimeUnit.SECONDS));
			}
			return out;
		} finally {
			pool.shutdownNow();
		}
	}

	/** Captures WARN/ERROR emitted by production code while the body runs. */
	private static final class LogCapture implements AutoCloseable {
		private final List<String> messages = new CopyOnWriteArrayList<>();
		private final LoggerConfig root;
		private final AbstractAppender appender;
		private final String tag;

		LogCapture(String tag) {
			this.tag = tag;
			LoggerContext ctx = (LoggerContext) LogManager.getContext(false);
			Configuration cfg = ctx.getConfiguration();
			root = cfg.getRootLogger();
			appender = new AbstractAppender(tag, null, null, true, null) {
				@Override
				public void append(LogEvent event) {
					if (event.getLevel().isMoreSpecificThan(Level.WARN)) {
						messages.add(event.getLoggerName() + " | " + event.getMessage().getFormattedMessage());
					}
				}
			};
			appender.start();
			root.addAppender(appender, Level.WARN, null);
		}

		List<String> matching(String needle) {
			List<String> out = new ArrayList<>();
			for (String m : messages) {
				if (m.toLowerCase().contains(needle.toLowerCase())) out.add(m);
			}
			return out;
		}

		@Override
		public void close() {
			root.removeAppender(tag);
			appender.stop();
		}
	}

	// ─────────────────────────────────────────────────────────────────────────
	// concurrency
	// ─────────────────────────────────────────────────────────────────────────

	@Test
	public void TestConcurrentMakePathOfOneNewPathYieldsOneTree() throws Exception {
		OrganizationContext oc = org();
		BaseRecord user = newUser(oc, "pathcc");
		long org = orgId(user);
		String scratch = "PU-" + uuid8();
		String target = homePath(user) + "/" + scratch + "/Alpha/Beta/Gamma";

		List<Callable<BaseRecord>> tasks = new ArrayList<>();
		for (int i = 0; i < THREADS; i++) {
			tasks.add(() -> makeGroup(user, target));
		}
		List<BaseRecord> results;
		List<String> dupes;
		try (LogCapture cap = new LogCapture("cc1")) {
			results = runAllAtOnce(tasks);
			dupes = cap.matching("duplicate key");
		}

		Set<Long> ids = new HashSet<>();
		for (BaseRecord r : results) {
			assertNotNull("Every concurrent makePath must return the node", r);
			ids.add(idOf(r));
		}
		assertEquals("All threads must resolve to ONE node: " + ids, 1, ids.size());
		long leafId = ids.iterator().next();
		assertNotNull("The shared id must be a real row, not a phantom", row(ModelNames.MODEL_GROUP, leafId, org));

		/// One row per segment, walking from the home directory down.
		BaseRecord home = user.get(FieldNames.FIELD_HOME_DIRECTORY);
		IOSystem.getActiveContext().getRecordUtil().populate(home);
		long parent = idOf(home);
		String walked = homePath(user);
		for (String seg : new String[] { scratch, "Alpha", "Beta", "Gamma" }) {
			assertEquals("Exactly one '" + seg + "' may exist under #" + parent, 1,
				countInParent(ModelNames.MODEL_GROUP, parent, seg, org));
			walked = walked + "/" + seg;
			BaseRecord g = findGroup(user, walked);
			assertNotNull(walked, g);
			parent = idOf(g);
		}
		assertEquals(leafId, parent);
		assertTrue("Concurrent makePath must not attempt a duplicate insert: " + dupes, dupes.isEmpty());
	}

	@Test
	public void TestConcurrentSiblingMakePathsShareOnePrefix() throws Exception {
		OrganizationContext oc = org();
		BaseRecord user = newUser(oc, "pathcs");
		long org = orgId(user);
		String prefix = homePath(user) + "/PU-" + uuid8() + "/Shared";

		List<Callable<BaseRecord>> tasks = new ArrayList<>();
		for (int i = 0; i < THREADS; i++) {
			final int n = i;
			tasks.add(() -> makeGroup(user, prefix + "/Leaf-" + n));
		}
		List<BaseRecord> leaves;
		List<String> dupes;
		try (LogCapture cap = new LogCapture("cc2")) {
			leaves = runAllAtOnce(tasks);
			dupes = cap.matching("duplicate key");
		}

		BaseRecord shared = findGroup(user, prefix);
		assertNotNull("The shared prefix must exist", shared);
		long sharedId = idOf(shared);
		BaseRecord sharedParent = row(ModelNames.MODEL_GROUP, sharedId, org);
		assertNotNull(sharedParent);
		Set<Long> leafIds = new HashSet<>();
		for (int i = 0; i < THREADS; i++) {
			BaseRecord leaf = leaves.get(i);
			assertNotNull("Leaf-" + i + " must be created", leaf);
			assertEquals("Leaf-" + i + " must hang off the ONE shared prefix", sharedId,
				((Number) leaf.get(FieldNames.FIELD_PARENT_ID)).longValue());
			assertEquals(1, countInParent(ModelNames.MODEL_GROUP, sharedId, "Leaf-" + i, org));
			leafIds.add(idOf(leaf));
		}
		assertEquals("Eight distinct leaves", THREADS, leafIds.size());
		assertTrue("Sibling creation must not attempt a duplicate insert of the prefix: " + dupes, dupes.isEmpty());
	}

	@Test
	public void TestFindPathDuringMakePathNeverReturnsAPhantomOrASibling() throws Exception {
		OrganizationContext oc = org();
		BaseRecord user = newUser(oc, "pathcf");
		long org = orgId(user);
		String scratch = homePath(user) + "/PU-" + uuid8();
		String target = scratch + "/Build/Ing/Now";
		String sibling = scratch + "/Build/Ing/Never";

		AtomicBoolean writerDone = new AtomicBoolean(false);
		Set<Long> seenIds = ConcurrentHashMap.newKeySet();
		List<BaseRecord> siblingHits = new CopyOnWriteArrayList<>();

		List<Callable<BaseRecord>> tasks = new ArrayList<>();
		/// Writer: a short head start for the readers, then create the path once.
		tasks.add(() -> {
			Thread.sleep(50);
			try {
				return makeGroup(user, target);
			} finally {
				writerDone.set(true);
			}
		});
		/// Readers: hammer findPath on the target (and on a sibling that is never created) until
		/// the writer is done, then one final read each.
		for (int i = 0; i < THREADS - 1; i++) {
			tasks.add(() -> {
				BaseRecord last = null;
				do {
					BaseRecord f = findGroup(user, target);
					if (f != null) {
						seenIds.add(idOf(f));
						last = f;
					}
					BaseRecord s = findGroup(user, sibling);
					if (s != null) {
						siblingHits.add(s);
					}
				} while (!writerDone.get());
				BaseRecord f = findGroup(user, target);
				if (f != null) {
					seenIds.add(idOf(f));
					last = f;
				}
				return last;
			});
		}
		List<BaseRecord> results = runAllAtOnce(tasks);

		BaseRecord created = results.get(0);
		assertNotNull("The writer must have created the path", created);
		long finalId = idOf(created);
		assertNotNull("The created id must be a real row", row(ModelNames.MODEL_GROUP, finalId, org));

		for (Long seen : seenIds) {
			assertEquals("A concurrent findPath returned #" + seen + " for [" + target + "] but the real node is #"
				+ finalId + " - a phantom or another path's node", finalId, seen.longValue());
		}
		assertTrue("findPath must never resolve a path that was never created: " + siblingHits, siblingHits.isEmpty());
		assertEquals(1, countInParent(ModelNames.MODEL_GROUP,
			idOf(findGroup(user, scratch + "/Build/Ing")), "Now", org));
	}

	// ─────────────────────────────────────────────────────────────────────────
	// ambiguity fails closed
	// ─────────────────────────────────────────────────────────────────────────

	/**
	 * auth.role constrains (parentId, name, type, organizationId), so two root roles with the same
	 * name and different types are LEGAL, and a type-less lookup for that name returns both. Before
	 * the fix the {@code nodes.length > 1} branch logged "Invalid search" and fell through with
	 * {@code node}/{@code parentId} still at the previous segment, so findPath returned (and makePath
	 * built the rest of the path under) the PARENT of the ambiguous segment.
	 */
	@Test
	public void TestAmbiguousSegmentFailsClosedInsteadOfReturningThePreviousNode() throws Exception {
		OrganizationContext oc = org();
		BaseRecord user = newUser(oc, "pathab");
		long org = orgId(user);
		String dup = "PU-Dup-" + uuid8();
		String leaf = "Leaf-" + uuid8();
		rawRole(user, 0L, dup, RoleEnumType.USER);
		rawRole(user, 0L, dup, RoleEnumType.ACCOUNT);
		assertEquals("Precondition: two same-named root roles of different types", 2,
			countInParent(ModelNames.MODEL_ROLE, 0L, dup, org));

		List<String> ambiguous;
		List<String> invalid;
		BaseRecord found;
		BaseRecord made;
		try (LogCapture cap = new LogCapture("amb")) {
			found = IOSystem.getActiveContext().getPathUtil().findPath(user, ModelNames.MODEL_ROLE,
				"/" + dup + "/" + leaf, null, org);
			made = IOSystem.getActiveContext().getPathUtil().makePath(user, ModelNames.MODEL_ROLE,
				"/" + dup + "/" + leaf, null, org);
			ambiguous = cap.matching("Ambiguous path segment");
			invalid = cap.matching("Invalid search for");
		}
		assertNull("findPath over an ambiguous segment must return null, not the previous segment's node", found);
		assertNull("makePath over an ambiguous segment must return null, not the previous segment's node", made);
		assertEquals("Both calls must say WHY at ERROR: " + ambiguous, 2, ambiguous.size());
		assertTrue("The old fall-through message must be gone: " + invalid, invalid.isEmpty());
		assertEquals("makePath must NOT have created '" + leaf + "' at the root (the previous segment's node)", 0,
			countInParent(ModelNames.MODEL_ROLE, 0L, leaf, org));
		/// A typed request disambiguates and resolves normally.
		BaseRecord typed = IOSystem.getActiveContext().getPathUtil().findPath(user, ModelNames.MODEL_ROLE,
			"/" + dup, RoleEnumType.ACCOUNT.toString(), org);
		assertNotNull("A type-qualified lookup of the same name must resolve", typed);
		/// auth.role declares no "query" array, so the node findPath hands back carries only the inherited
		/// default projection (id, urn, objectId, ownerId, name, parentId) - its "type" reads the enum
		/// default UNKNOWN. Re-read the resolved row with an explicit type projection to prove WHICH of the
		/// two same-named roles the typed lookup picked.
		Query tq = QueryUtil.createQuery(ModelNames.MODEL_ROLE, FieldNames.FIELD_ID, idOf(typed));
		tq.field(FieldNames.FIELD_ORGANIZATION_ID, ComparatorEnumType.EQUALS, org);
		tq.setRequest(new String[] { FieldNames.FIELD_ID, FieldNames.FIELD_NAME, FieldNames.FIELD_TYPE });
		tq.setCache(false);
		BaseRecord typedRow = IOSystem.getActiveContext().getSearch().findRecord(tq);
		assertNotNull("The typed lookup must resolve to a real row", typedRow);
		assertEquals(RoleEnumType.ACCOUNT.toString(), String.valueOf((Object) typedRow.get(FieldNames.FIELD_TYPE)));
	}

	// ─────────────────────────────────────────────────────────────────────────
	// groupId-keyed models
	// ─────────────────────────────────────────────────────────────────────────

	@Test
	public void TestMakePathCreatesGroupIdModelLeafAndItsContainers() throws Exception {
		OrganizationContext oc = org();
		BaseRecord user = newUser(oc, "pathgl");
		long org = orgId(user);
		String scratch = "PU-" + uuid8();
		String leafName = "Note-" + uuid8();
		String rel = "~/" + scratch + "/Docs/" + leafName;
		String abs = homePath(user) + "/" + scratch + "/Docs/" + leafName;

		BaseRecord leaf;
		BaseRecord again;
		List<String> dupes;
		try (LogCapture cap = new LogCapture("gl")) {
			leaf = IOSystem.getActiveContext().getPathUtil().makePath(user, ModelNames.MODEL_DATA, rel, null, org);
			again = IOSystem.getActiveContext().getPathUtil().makePath(user, ModelNames.MODEL_DATA, abs, null, org);
			dupes = cap.matching("duplicate key");
		}
		assertNotNull("makePath on a groupId-keyed model must create the leaf (it used to die on set(parentId) and return null)", leaf);
		assertEquals(ModelNames.MODEL_DATA, leaf.getSchema());
		assertEquals(leafName, leaf.get(FieldNames.FIELD_NAME));

		/// The containers were created as DATA groups, once each, along the home tree.
		BaseRecord docs = findGroup(user, homePath(user) + "/" + scratch + "/Docs");
		assertNotNull("The intermediate 'Docs' container must exist as an auth.group", docs);
		BaseRecord scratchGrp = findGroup(user, homePath(user) + "/" + scratch);
		assertNotNull(scratchGrp);
		assertEquals(1, countInParent(ModelNames.MODEL_GROUP, idOf(scratchGrp), "Docs", org));
		assertEquals("The leaf must live IN the resolved container", idOf(docs),
			((Number) leaf.get(FieldNames.FIELD_GROUP_ID)).longValue());
		assertEquals("Exactly one leaf row", 1, countInGroup(ModelNames.MODEL_DATA, idOf(docs), leafName, org));
		assertNotNull("The leaf id must be a real row", row(ModelNames.MODEL_DATA, idOf(leaf), org));

		/// Idempotent, and findPath sees it.
		assertNotNull(again);
		assertEquals("A second makePath (absolute form) must return the same leaf", idOf(leaf), idOf(again));
		BaseRecord found = IOSystem.getActiveContext().getPathUtil().findPath(user, ModelNames.MODEL_DATA, rel, null, org);
		assertNotNull("findPath must resolve the leaf makePath created", found);
		assertEquals(idOf(leaf), idOf(found));
		assertTrue("No duplicate insert may be attempted on the second resolution: " + dupes, dupes.isEmpty());

		/// A groupId path with a missing container is still a plain miss for findPath, with nothing created.
		assertNull(IOSystem.getActiveContext().getPathUtil().findPath(user, ModelNames.MODEL_DATA,
			"~/" + scratch + "/Nope/" + leafName, null, org));
		assertEquals(0, countInParent(ModelNames.MODEL_GROUP, idOf(scratchGrp), "Nope", org));
		assertFalse(leaf.hasField(FieldNames.FIELD_PARENT_ID));
	}
}
