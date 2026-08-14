package org.cote.accountmanager.objects.tests;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Configuration;
import org.apache.logging.log4j.core.config.LoggerConfig;
import org.cote.accountmanager.factory.Factory;
import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.io.Query;
import org.cote.accountmanager.io.QueryUtil;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.record.RecordFactory;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.schema.type.ComparatorEnumType;
import org.cote.accountmanager.schema.type.GroupEnumType;
import org.cote.accountmanager.util.PathUtil;
import org.junit.Test;

/**
 * Verifies the KI-60 watch instrumentation on {@code PathUtil}'s collision-recovery path.
 *
 * <p>KI-60 (a type-filtered per-segment lookup failing to see a row that is present) does not
 * reproduce on demand — {@code TestPathUtilBehavior} established that and eliminated the
 * search-cache query-key theory — so the live condition can only be diagnosed from production
 * logs. That makes the instrumentation itself load-bearing: an unverified diagnostic is a
 * diagnostic that gets silently broken by the next edit. This suite drives the recovery branch
 * with a type-mismatched sibling and asserts the marker line, its payload, the uncached re-probe
 * verdict, and — importantly — that the watch changed nothing.
 *
 * <p>Kept out of {@code TestPathUtilBehavior} deliberately: that suite is the characterization
 * record for the four defects and its 15 cases are quoted as-is.
 */
public class TestPathUtilKi60Watch extends BaseTest {

	private static String uuid8() {
		return UUID.randomUUID().toString().replace("-", "").substring(0, 8);
	}

	/** A user nobody has used before, so its home tree is empty. Never the admin user. */
	private BaseRecord newUser(String prefix) {
		Factory mf = IOSystem.getActiveContext().getFactory();
		String name = prefix + uuid8();
		BaseRecord u = mf.getCreateUser(orgContext.getAdminUser(), name, orgContext.getOrganizationId());
		assertNotNull("Factory.getCreateUser returned null for '" + name + "'", u);
		IOSystem.getActiveContext().getRecordUtil().populate(u);
		return u;
	}

	private long orgId(BaseRecord user) {
		return ((Number) user.get(FieldNames.FIELD_ORGANIZATION_ID)).longValue();
	}

	private String homePath(BaseRecord user) {
		String hp = user.get(FieldNames.FIELD_HOME_DIRECTORY_FIELD_PATH);
		assertNotNull("The test user must have a resolvable home directory path", hp);
		return hp;
	}

	private static long idOf(BaseRecord rec) {
		return ((Number) rec.get(FieldNames.FIELD_ID)).longValue();
	}

	private BaseRecord makePath(BaseRecord user, String path, String type) {
		return IOSystem.getActiveContext().getPathUtil().makePath(user, ModelNames.MODEL_GROUP, path,
			type, orgId(user));
	}

	private int countRows(long parentId, String name, long organizationId) {
		Query q = QueryUtil.createQuery(ModelNames.MODEL_GROUP, FieldNames.FIELD_PARENT_ID, parentId);
		q.field(FieldNames.FIELD_NAME, ComparatorEnumType.EQUALS, name);
		q.field(FieldNames.FIELD_ORGANIZATION_ID, ComparatorEnumType.EQUALS, organizationId);
		q.setCache(false);
		return IOSystem.getActiveContext().getSearch().count(q);
	}

	private BaseRecord rawGroup(BaseRecord user, long parentId, String name, GroupEnumType type) throws Exception {
		BaseRecord g = RecordFactory.model(ModelNames.MODEL_GROUP).newInstance();
		g.set(FieldNames.FIELD_NAME, name);
		g.set(FieldNames.FIELD_PARENT_ID, parentId);
		g.set(FieldNames.FIELD_ORGANIZATION_ID, orgId(user));
		g.set(FieldNames.FIELD_TYPE, type.toString());
		g.set(FieldNames.FIELD_OWNER_ID, user.get(FieldNames.FIELD_ID));
		assertTrue("Precondition: raw group '" + name + "' must be created",
			IOSystem.getActiveContext().getRecordUtil().createRecord(g));
		return g;
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
						messages.add(event.getLevel() + " | " + event.getMessage().getFormattedMessage());
					}
				}
			};
			appender.start();
			root.addAppender(appender, Level.WARN, null);
		}

		List<String> matching(String needle) {
			List<String> out = new ArrayList<>();
			for (String m : messages) {
				if (m.contains(needle)) out.add(m);
			}
			return out;
		}

		@Override
		public void close() {
			root.removeAppender(tag);
			appender.stop();
		}
	}

	/**
	 * The watch must fire on the collision-recovery branch, carry the full requested/adopted
	 * payload, record the uncached re-probe verdict in words, escalate the type conflict, and leave
	 * the outcome and the database exactly as they were.
	 */
	@Test
	public void TestKi60WatchEmitsMarkerAndRecordsTheUncachedReprobe() throws Exception {
		BaseRecord user = newUser("ki60w");
		long org = orgId(user);
		String scratch = homePath(user) + "/PU-" + uuid8();
		long parent = idOf(makePath(user, scratch, GroupEnumType.DATA.toString()));

		/// A sibling the DATA-filtered per-segment lookup cannot see, but which owns the
		/// (name, parentId, organizationId) unique key the create would need.
		BaseRecord pre = rawGroup(user, parent, "Narratives", GroupEnumType.BUCKET);

		BaseRecord result;
		List<String> watch;
		List<String> reprobe;
		List<String> conflict;
		try (LogCapture cap = new LogCapture("ki60watch")) {
			result = makePath(user, scratch + "/Narratives", GroupEnumType.DATA.toString());
			watch = cap.matching(PathUtil.KI60_WATCH_MARKER + " trigger=");
			reprobe = cap.matching("reprobe=");
			conflict = cap.matching(PathUtil.KI60_WATCH_MARKER + " CONFLICT");
		}

		for (String m : watch) logger.info("captured: " + m);
		for (String m : reprobe) logger.info("captured: " + m);
		for (String m : conflict) logger.info("captured: " + m);

		/// 1. The marker fired, exactly once, on the adoption branch.
		assertEquals("The KI-60 watch must emit exactly one marker line per adoption: " + watch, 1, watch.size());
		String line = watch.get(0);
		assertTrue("The marker line must name the branch that adopted: " + line,
			line.contains("trigger=pre-create-conflict") || line.contains("trigger=write-lost-recovery"));

		/// 2. It must carry the requested identity, BOTH types, and the adopted row's identity+urn.
		assertTrue("must carry the requested model: " + line, line.contains("model=" + ModelNames.MODEL_GROUP));
		assertTrue("must carry the requested segment name: " + line, line.contains("name=Narratives"));
		assertTrue("must carry the requested parentId: " + line, line.contains("parentId=" + parent));
		assertTrue("must carry the requested organizationId: " + line, line.contains("organizationId=" + org));
		assertTrue("must carry the requested type: " + line, line.contains("type=DATA"));
		assertTrue("must carry the effective lookup type: " + line, line.contains("lookupType=DATA"));
		assertTrue("must report whether the utype override fired: " + line, line.contains("typeOverride="));
		assertTrue("must carry the adopted id: " + line, line.contains("id=" + idOf(pre)));
		assertTrue("must carry the adopted type: " + line, line.contains("type=BUCKET"));
		assertTrue("must carry the adopted urn (the original KI-60 report included it): " + line,
			line.contains("urn=") && !line.contains("urn=null"));
		assertTrue("must carry the full path being resolved: " + line,
			line.contains("path=[" + scratch + "/Narratives]"));
		assertTrue("must carry the segment index that triggered it: " + line, line.contains("segment="));

		/// 3. The name matched here, so the ANOMALY escalation must NOT fire (it is reserved for
		/// KI-60's actual signature and must stay meaningful).
		assertTrue("The ANOMALY escalation must not fire when the adopted name matches the request",
			line.contains("nameMismatch=false"));
		assertFalse("...and the line must not be tagged ANOMALY: " + line, line.contains("ANOMALY"));

		/// 4. The uncached re-probe ran and its verdict is recorded in words, not just ids.
		assertEquals("The watch must record exactly one uncached re-probe result: " + reprobe, 1, reprobe.size());
		String probe = reprobe.get(0);
		assertTrue("The re-probe must report FOUND or MISSED: " + probe,
			probe.contains("reprobe=FOUND") || probe.contains("reprobe=MISSED"));
		assertTrue("The re-probe must state which hypothesis it supports, in words: " + probe,
			probe.contains("verdict:"));
		/// This scenario is a genuine type mismatch, so the uncached DATA-filtered read must also
		/// miss. If this ever flips to FOUND, the cache is implicated and KI-60 has moved.
		assertTrue("A DATA-filtered read cannot see a BUCKET row whether cached or not, so the "
			+ "uncached re-probe must also miss here: " + probe, probe.contains("reprobe=MISSED"));

		/// 5. The type conflict is surfaced as an ERROR naming both types.
		assertEquals("The type conflict must be surfaced exactly once: " + conflict, 1, conflict.size());
		assertTrue("The conflict line must name the requested and the found type: " + conflict.get(0),
			conflict.get(0).contains("requested as type (DATA)") && conflict.get(0).contains("with type (BUCKET)"));

		/// 6. Read-only: the watch must not have written, created or repaired anything, and must not
		/// have altered the outcome of the operation it observed.
		assertNotNull("The watch must not change the outcome - the existing node is still returned", result);
		assertEquals("The adopted node must be the row that was already there", idOf(pre), idOf(result));
		assertEquals("The watch must not create anything", 1, countRows(parent, "Narratives", org));
	}
}
