package org.cote.accountmanager.objects.tests;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.UUID;

import org.cote.accountmanager.cache.CacheUtil;
import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.io.OrganizationContext;
import org.cote.accountmanager.olio.OlioContextUtil;
import org.cote.accountmanager.olio.picturebook.PbArtifactUtil;
import org.cote.accountmanager.olio.picturebook.PbBookUtil;
import org.cote.accountmanager.olio.picturebook.PbGraphUtil;
import org.cote.accountmanager.olio.schema.OlioFieldNames;
import org.cote.accountmanager.olio.schema.OlioModelNames;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.record.RecordFactory;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.type.PbArtifactTypeEnumType;
import org.cote.accountmanager.schema.type.PbNodeTypeEnumType;
import org.cote.accountmanager.schema.type.PbReviewStatusEnumType;
import org.cote.accountmanager.schema.type.PbRunStatusEnumType;
import org.junit.Before;
import org.junit.Test;

/**
 * V3 Phase 1, P1-1: the review/duration/auto-approve columns and the two enum additions exist and round
 * trip through {@code AccessPoint} against the live {@code am7db}. The Path 1 boot patch adds the columns
 * on the first {@code IOSystem.open()} after the model JSON changed; a column that did not get added shows
 * up here as a failed write or a null read, not as a compile error.
 * <p>
 * Same shared fixture as {@link TestPbGraph} (one book world, random node handles), for the same reason:
 * a fresh world per run costs a corpus load.
 */
public class TestPbV3Schema extends BaseTest {

	private static final String ORG_A = "/Development/World Building";
	private static final String TEST_USER = "testUser1";
	private static final String SLUG = "pb2c-graph";

	@Before
	public void schemaSetup() {
		OlioContextUtil.clearCache();
		IOSystem.getActiveContext().getAccessPoint().setPermitBulkContainerApproval(false);
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

	private BaseRecord workflow(BaseRecord u, BaseRecord bk) {
		BaseRecord wf = PbGraphUtil.getCreateWorkflow(u, bk, PbBookUtil.workflowGroupPath(SLUG));
		assertNotNull("Failed to resolve the fixture workflow", wf);
		return wf;
	}

	private BaseRecord node(BaseRecord u, BaseRecord wf, String prefix, PbNodeTypeEnumType type, int ordinal) {
		String handle = prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
		BaseRecord n = PbGraphUtil.addNode(u, wf, handle, type, PbBookUtil.workflowGroupPath(SLUG), ordinal);
		assertNotNull("Failed to create node " + handle, n);
		return n;
	}

	// ───────── 1: artifact review fields default, then round trip through a PATCH ─────────

	/**
	 * A fresh artifact reads {@code GENERATED} ({@code persistArtifact} writes it explicitly on create - the
	 * PB models declare no schema defaults, TestPbModelSchema), null {@code approvedBy}, and {@code durationMs}
	 * 0 (the DDL default - "unrecorded").
	 * Then every new column is written through a hand-built identity+changed patch and read back fresh.
	 */
	@Test
	public void case01_artifactReviewFieldsDefaultAndRoundTrip() throws Exception {
		BaseRecord u = user();
		BaseRecord wf = workflow(u, book(u));
		long orgId = orgOf(u);
		BaseRecord n = node(u, wf, "p11-art", PbNodeTypeEnumType.PORTRAIT, 0);

		BaseRecord a = PbArtifactUtil.persistArtifact(u, n, "portrait0", PbArtifactTypeEnumType.TEXT,
			PbBookUtil.artifactGroupPath(SLUG), null, "p1-1 artifact", null, null);
		assertNotNull(a);
		String oid = a.get(FieldNames.FIELD_OBJECT_ID);

		BaseRecord fresh = PbArtifactUtil.readArtifact(u, oid, orgId);
		assertNotNull(fresh);
		assertTrue("artifactRequest() must project reviewStatus", fresh.hasField(OlioFieldNames.FIELD_PB_REVIEW_STATUS));
		assertTrue("artifactRequest() must project approvedBy", fresh.hasField(OlioFieldNames.FIELD_PB_APPROVED_BY));
		assertTrue("artifactRequest() must project approvedAt", fresh.hasField(OlioFieldNames.FIELD_PB_APPROVED_AT));
		assertTrue("artifactRequest() must project durationMs", fresh.hasField(OlioFieldNames.FIELD_PB_DURATION_MS));
		assertEquals("A fresh artifact is GENERATED", PbReviewStatusEnumType.GENERATED, PbArtifactUtil.reviewStatusOf(fresh));
		assertEquals("...and the raw column agrees", PbReviewStatusEnumType.GENERATED,
			fresh.getEnum(OlioFieldNames.FIELD_PB_REVIEW_STATUS));
		assertNull("approvedBy is null until approved", fresh.get(OlioFieldNames.FIELD_PB_APPROVED_BY));
		Long duration = fresh.get(OlioFieldNames.FIELD_PB_DURATION_MS);
		assertNotNull("durationMs reads the DDL default, never NULL (StatementUtil has no null guard to need)", duration);
		assertEquals("0 means unrecorded", 0L, duration.longValue());
		/// Documented trap: zonetime columns default now(), so approvedAt is NOT a null test for approval.
		assertNotNull("approvedAt is never NULL - readers must key on reviewStatus == APPROVED",
			fresh.get(OlioFieldNames.FIELD_PB_APPROVED_AT));

		ZonedDateTime approvedAt = ZonedDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.SECONDS);
		BaseRecord patch = RecordFactory.newInstance(OlioModelNames.MODEL_PB_ARTIFACT, new String[] {
			FieldNames.FIELD_ID, FieldNames.FIELD_OBJECT_ID, FieldNames.FIELD_NAME,
			OlioFieldNames.FIELD_PB_REVIEW_STATUS, OlioFieldNames.FIELD_PB_APPROVED_BY,
			OlioFieldNames.FIELD_PB_APPROVED_AT, OlioFieldNames.FIELD_PB_DURATION_MS
		});
		patch.set(FieldNames.FIELD_ID, fresh.get(FieldNames.FIELD_ID));
		patch.set(FieldNames.FIELD_OBJECT_ID, oid);
		patch.set(FieldNames.FIELD_NAME, fresh.get(FieldNames.FIELD_NAME));
		patch.set(OlioFieldNames.FIELD_PB_REVIEW_STATUS, PbReviewStatusEnumType.APPROVED.toString());
		patch.set(OlioFieldNames.FIELD_PB_APPROVED_BY, OlioFieldNames.PB_APPROVED_BY_AUTO);
		patch.set(OlioFieldNames.FIELD_PB_APPROVED_AT, approvedAt);
		patch.set(OlioFieldNames.FIELD_PB_DURATION_MS, Long.valueOf(1234L));
		assertNotNull("The review patch must be accepted by AccessPoint.update", ioContext.getAccessPoint().update(u, patch));

		BaseRecord after = PbArtifactUtil.readArtifact(u, oid, orgId);
		assertEquals(PbReviewStatusEnumType.APPROVED, PbArtifactUtil.reviewStatusOf(after));
		assertEquals(OlioFieldNames.PB_APPROVED_BY_AUTO, after.get(OlioFieldNames.FIELD_PB_APPROVED_BY));
		assertEquals(Long.valueOf(1234L), after.get(OlioFieldNames.FIELD_PB_DURATION_MS));
		ZonedDateTime readAt = after.get(OlioFieldNames.FIELD_PB_APPROVED_AT);
		assertNotNull(readAt);
		assertEquals("approvedAt must round trip (compared at second precision, as an instant)",
			approvedAt.toInstant(), readAt.toInstant().truncatedTo(ChronoUnit.SECONDS));

		/// Every other review value must be writable too - the column is validated against the class.
		for(PbReviewStatusEnumType rs : new PbReviewStatusEnumType[] {PbReviewStatusEnumType.IN_REVIEW,
				PbReviewStatusEnumType.REJECTED, PbReviewStatusEnumType.ARCHIVED, PbReviewStatusEnumType.GENERATED}) {
			BaseRecord p = RecordFactory.newInstance(OlioModelNames.MODEL_PB_ARTIFACT, new String[] {
				FieldNames.FIELD_ID, FieldNames.FIELD_OBJECT_ID, FieldNames.FIELD_NAME, OlioFieldNames.FIELD_PB_REVIEW_STATUS
			});
			p.set(FieldNames.FIELD_ID, fresh.get(FieldNames.FIELD_ID));
			p.set(FieldNames.FIELD_OBJECT_ID, oid);
			p.set(FieldNames.FIELD_NAME, fresh.get(FieldNames.FIELD_NAME));
			p.set(OlioFieldNames.FIELD_PB_REVIEW_STATUS, rs.toString());
			assertNotNull("reviewStatus=" + rs + " must be writable", ioContext.getAccessPoint().update(u, p));
			assertEquals(rs, PbArtifactUtil.reviewStatusOf(PbArtifactUtil.readArtifact(u, oid, orgId)));
		}
	}

	/**
	 * The null-means-GENERATED rule, for rows written before the column existed. {@code EnumValueType.setValue}
	 * turns a null into the literal {@code UNKNOWN} before validating it, which is why the enum carries that
	 * value: without it a pre-Phase-1 row throws {@code ValueException} inside {@code StatementUtil.populateRecord}
	 * and becomes unreadable. First the in-memory path, then the real thing: an artifact created this run has
	 * its column set to SQL NULL (one row, by id - the same shape every pre-Phase-1 row has) and is read back
	 * through the normal projection.
	 */
	@Test
	public void case02_reviewStatusOfTreatsAbsentAndNullAsGenerated() throws Exception {
		assertEquals(PbReviewStatusEnumType.GENERATED, PbArtifactUtil.reviewStatusOf(null));
		BaseRecord sparse = RecordFactory.newInstance(OlioModelNames.MODEL_PB_ARTIFACT, new String[] {
			FieldNames.FIELD_ID, FieldNames.FIELD_OBJECT_ID
		});
		assertFalse("Precondition: the field is not materialised", sparse.hasField(OlioFieldNames.FIELD_PB_REVIEW_STATUS));
		assertEquals("A record read without the field is GENERATED", PbReviewStatusEnumType.GENERATED,
			PbArtifactUtil.reviewStatusOf(sparse));
		BaseRecord nulled = RecordFactory.newInstance(OlioModelNames.MODEL_PB_ARTIFACT, new String[] {
			FieldNames.FIELD_ID, FieldNames.FIELD_OBJECT_ID, OlioFieldNames.FIELD_PB_REVIEW_STATUS
		});
		nulled.set(OlioFieldNames.FIELD_PB_REVIEW_STATUS, null);
		assertEquals("Precondition: the enum layer surfaces a null as UNKNOWN", PbReviewStatusEnumType.UNKNOWN,
			nulled.getEnum(OlioFieldNames.FIELD_PB_REVIEW_STATUS));
		assertEquals("A null (pre-Phase-1 row) is GENERATED", PbReviewStatusEnumType.GENERATED,
			PbArtifactUtil.reviewStatusOf(nulled));

		BaseRecord u = user();
		BaseRecord wf = workflow(u, book(u));
		long orgId = orgOf(u);
		BaseRecord n = node(u, wf, "p11-null", PbNodeTypeEnumType.PORTRAIT, 0);
		BaseRecord a = PbArtifactUtil.persistArtifact(u, n, "portrait0", PbArtifactTypeEnumType.TEXT,
			PbBookUtil.artifactGroupPath(SLUG), null, "p1-1 pre-phase-1 shaped artifact", null, null);
		assertNotNull(a);
		String oid = a.get(FieldNames.FIELD_OBJECT_ID);
		long id = (long) a.get(FieldNames.FIELD_ID);
		String table = ioContext.getDbUtil().getTableName(OlioModelNames.MODEL_PB_ARTIFACT);
		try (Connection con = ioContext.getDbUtil().getDataSource().getConnection();
			 PreparedStatement st = con.prepareStatement("UPDATE " + table + " SET reviewstatus = NULL WHERE id = ?")) {
			st.setLong(1, id);
			assertEquals("Exactly the one row created above is nulled", 1, st.executeUpdate());
		}
		CacheUtil.clearCache();
		BaseRecord fresh = PbArtifactUtil.readArtifact(u, oid, orgId);
		assertNotNull("A row with a NULL reviewStatus column must still be readable", fresh);
		assertEquals(PbReviewStatusEnumType.UNKNOWN, fresh.getEnum(OlioFieldNames.FIELD_PB_REVIEW_STATUS));
		assertEquals("...and reads as GENERATED through the helper", PbReviewStatusEnumType.GENERATED,
			PbArtifactUtil.reviewStatusOf(fresh));
	}

	// ───────── 3: book.autoApprove defaults false and round trips ─────────

	/**
	 * The shared fixture book predates the column, so its first read is the boot patch's DDL default
	 * ({@code false}), which is also the decided default. Flipped on, read, flipped back off so the fixture is
	 * left as it was found.
	 */
	@Test
	public void case03_bookAutoApproveDefaultsFalseAndRoundTrips() throws Exception {
		BaseRecord u = user();
		BaseRecord bk = book(u);
		long orgId = orgOf(u);
		String oid = bk.get(FieldNames.FIELD_OBJECT_ID);

		assertTrue("bookRequest() must project autoApprove", bk.hasField(OlioFieldNames.FIELD_PB_AUTO_APPROVE));
		Boolean initial = bk.get(OlioFieldNames.FIELD_PB_AUTO_APPROVE);
		assertNotNull("A boolean column reads its DDL default, never NULL", initial);
		/// The finally below restores the fixture, so a dirty entry state is a real failure, not noise.
		assertEquals("autoApprove defaults to false", Boolean.FALSE, initial);
		try {
			assertNotNull(ioContext.getAccessPoint().update(u, autoApprovePatch(bk, true)));
			BaseRecord on = PbBookUtil.readBook(u, oid, orgId);
			assertEquals("autoApprove=true must round trip", Boolean.TRUE, on.get(OlioFieldNames.FIELD_PB_AUTO_APPROVE));
			assertNotNull(ioContext.getAccessPoint().update(u, autoApprovePatch(bk, false)));
			BaseRecord off = PbBookUtil.readBook(u, oid, orgId);
			assertEquals("autoApprove=false must round trip", Boolean.FALSE, off.get(OlioFieldNames.FIELD_PB_AUTO_APPROVE));
		}
		finally {
			assertNotNull("Fixture restore", ioContext.getAccessPoint().update(u, autoApprovePatch(bk, initial.booleanValue())));
		}
	}

	private BaseRecord autoApprovePatch(BaseRecord bk, boolean value) throws Exception {
		BaseRecord patch = RecordFactory.newInstance(OlioModelNames.MODEL_PB_BOOK, new String[] {
			FieldNames.FIELD_ID, FieldNames.FIELD_OBJECT_ID, FieldNames.FIELD_NAME, OlioFieldNames.FIELD_PB_AUTO_APPROVE
		});
		patch.set(FieldNames.FIELD_ID, bk.get(FieldNames.FIELD_ID));
		patch.set(FieldNames.FIELD_OBJECT_ID, bk.get(FieldNames.FIELD_OBJECT_ID));
		patch.set(FieldNames.FIELD_NAME, bk.get(FieldNames.FIELD_NAME));
		patch.set(OlioFieldNames.FIELD_PB_AUTO_APPROVE, Boolean.valueOf(value));
		return patch;
	}

	// ───────── 4: run.runStatus accepts INTERRUPTED ─────────

	/**
	 * The enum column is validated against the Java class, so the new value must be writable with no
	 * model-schema change; it is written here through the same {@code completeRun} path P1-7's recovery
	 * sweep will use.
	 */
	@Test
	public void case04_runStatusAcceptsInterrupted() {
		BaseRecord u = user();
		BaseRecord wf = workflow(u, book(u));
		long orgId = orgOf(u);

		BaseRecord run = PbGraphUtil.startRun(u, wf, PbBookUtil.workflowGroupPath(SLUG), new ArrayList<>());
		assertNotNull(run);
		assertEquals("Precondition: startRun writes PENDING", PbRunStatusEnumType.PENDING,
			run.getEnum(OlioFieldNames.FIELD_PB_RUN_STATUS));

		assertTrue("INTERRUPTED must be writable", PbGraphUtil.completeRun(u, run, PbRunStatusEnumType.INTERRUPTED, 0, 0,
			"p1-1: server stopped while RUNNING"));
		BaseRecord after = PbGraphUtil.readRun(u, run.get(FieldNames.FIELD_OBJECT_ID), orgId);
		assertNotNull(after);
		assertEquals(PbRunStatusEnumType.INTERRUPTED, after.getEnum(OlioFieldNames.FIELD_PB_RUN_STATUS));
	}

	// ───────── 5: enum shape ─────────

	/**
	 * The ratified five review values plus the framework-required UNKNOWN; no DONE_UNVERIFIED anywhere it
	 * was not asked for.
	 */
	@Test
	public void case05_enumShape() {
		assertEquals(Arrays.asList("UNKNOWN", "GENERATED", "IN_REVIEW", "APPROVED", "REJECTED", "ARCHIVED"),
			names(PbReviewStatusEnumType.values()));
		assertEquals(Arrays.asList("UNKNOWN", "PENDING", "RUNNING", "COMPLETED", "FAILED", "CANCELLED", "INTERRUPTED"),
			names(PbRunStatusEnumType.values()));
		for(Enum<?> e : PbReviewStatusEnumType.values()) {
			if("DONE_UNVERIFIED".equals(e.name())) {
				fail("DONE_UNVERIFIED belongs to the Phase 7 migration, not the review enum");
			}
		}
	}

	// ───────── 6 (P6-1): attempt / variantIndex / archivedAt exist, default, and round trip ─────────

	/**
	 * The three Phase 6 columns. A single-variant artifact (the 9-arg {@code persistArtifact}) reads a null
	 * {@code attempt}, {@code variantIndex} 0 (DDL default) and a never-NULL {@code archivedAt} that the
	 * helper refuses to report while the status is not ARCHIVED. The 13-arg overload stamps attempt and
	 * index on create. Then {@code archivedAt} is written through a hand-built patch alongside ARCHIVED and
	 * read back; the epoch-0 trap ({@code patchOf} materialises a ZONETIME at 1970) is covered by the
	 * helper's guard, exercised in-memory.
	 */
	@Test
	public void case06_variantColumnsDefaultAndRoundTrip() throws Exception {
		BaseRecord u = user();
		BaseRecord wf = workflow(u, book(u));
		long orgId = orgOf(u);
		BaseRecord n = node(u, wf, "p61-var", PbNodeTypeEnumType.PORTRAIT, 0);

		BaseRecord single = PbArtifactUtil.persistArtifact(u, n, "portrait0", PbArtifactTypeEnumType.TEXT,
			PbBookUtil.artifactGroupPath(SLUG), null, "p6-1 single", null, null);
		assertNotNull(single);
		BaseRecord fresh = PbArtifactUtil.readArtifact(u, single.get(FieldNames.FIELD_OBJECT_ID), orgId);
		assertTrue("artifactRequest() must project attempt", fresh.hasField(OlioFieldNames.FIELD_PB_ATTEMPT));
		assertTrue("artifactRequest() must project variantIndex", fresh.hasField(OlioFieldNames.FIELD_PB_VARIANT_INDEX));
		assertTrue("artifactRequest() must project archivedAt", fresh.hasField(OlioFieldNames.FIELD_PB_ARCHIVED_AT));
		assertNull("A single-variant artifact has no attempt", PbArtifactUtil.attemptOf(fresh));
		assertEquals("variantIndex reads the DDL default 0", 0, PbArtifactUtil.variantIndexOf(fresh));
		assertEquals(Integer.valueOf(0), fresh.get(OlioFieldNames.FIELD_PB_VARIANT_INDEX));
		assertNotNull("archivedAt is never NULL in the column (default now())", fresh.get(OlioFieldNames.FIELD_PB_ARCHIVED_AT));
		assertNull("...but is reported only when ARCHIVED", PbArtifactUtil.archivedAtOf(fresh));

		String attempt = UUID.randomUUID().toString();
		BaseRecord v1 = PbArtifactUtil.persistArtifact(u, n, "portrait0", PbArtifactTypeEnumType.TEXT,
			PbBookUtil.artifactGroupPath(SLUG), null, "p6-1 variant 1", null, null, fresh, 1, attempt, false);
		assertNotNull(v1);
		BaseRecord v1Fresh = PbArtifactUtil.readArtifact(u, v1.get(FieldNames.FIELD_OBJECT_ID), orgId);
		assertEquals("attempt round trips on create", attempt, PbArtifactUtil.attemptOf(v1Fresh));
		assertEquals("variantIndex round trips on create", 1, PbArtifactUtil.variantIndexOf(v1Fresh));
		BaseRecord sup = v1Fresh.get(OlioFieldNames.FIELD_PB_SUPERSEDES);
		assertNotNull("The pre-batch supersedes is written as given", sup);
		assertEquals((Long) fresh.get(FieldNames.FIELD_ID), (Long) sup.get(FieldNames.FIELD_ID));

		ZonedDateTime archivedAt = ZonedDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.SECONDS);
		BaseRecord patch = RecordFactory.newInstance(OlioModelNames.MODEL_PB_ARTIFACT, new String[] {
			FieldNames.FIELD_ID, FieldNames.FIELD_OBJECT_ID, FieldNames.FIELD_NAME,
			OlioFieldNames.FIELD_PB_REVIEW_STATUS, OlioFieldNames.FIELD_PB_ARCHIVED_AT
		});
		patch.set(FieldNames.FIELD_ID, v1Fresh.get(FieldNames.FIELD_ID));
		patch.set(FieldNames.FIELD_OBJECT_ID, v1Fresh.get(FieldNames.FIELD_OBJECT_ID));
		patch.set(FieldNames.FIELD_NAME, v1Fresh.get(FieldNames.FIELD_NAME));
		patch.set(OlioFieldNames.FIELD_PB_REVIEW_STATUS, PbReviewStatusEnumType.ARCHIVED.toString());
		patch.set(OlioFieldNames.FIELD_PB_ARCHIVED_AT, archivedAt);
		assertNotNull("The archive patch must be accepted by AccessPoint.update", ioContext.getAccessPoint().update(u, patch));
		BaseRecord archived = PbArtifactUtil.readArtifact(u, v1.get(FieldNames.FIELD_OBJECT_ID), orgId);
		assertEquals(PbReviewStatusEnumType.ARCHIVED, PbArtifactUtil.reviewStatusOf(archived));
		ZonedDateTime readAt = PbArtifactUtil.archivedAtOf(archived);
		assertNotNull("archivedAt is reported once ARCHIVED", readAt);
		assertEquals("archivedAt must round trip (second precision, as an instant)",
			archivedAt.toInstant(), readAt.toInstant().truncatedTo(ChronoUnit.SECONDS));

		/// The epoch-0 guard: an ARCHIVED row whose stamp is 1970 (a patch that listed the field without setting
		/// it) reports null rather than a 56-year-old archival.
		BaseRecord epoch = RecordFactory.newInstance(OlioModelNames.MODEL_PB_ARTIFACT, new String[] {
			FieldNames.FIELD_ID, FieldNames.FIELD_OBJECT_ID, OlioFieldNames.FIELD_PB_REVIEW_STATUS, OlioFieldNames.FIELD_PB_ARCHIVED_AT
		});
		epoch.set(OlioFieldNames.FIELD_PB_REVIEW_STATUS, PbReviewStatusEnumType.ARCHIVED.toString());
		ZonedDateTime materialised = epoch.get(OlioFieldNames.FIELD_PB_ARCHIVED_AT);
		assertNotNull("Precondition: a listed ZONETIME is materialised, not null", materialised);
		assertEquals("Precondition: ...at epoch-0", 0L, materialised.toInstant().toEpochMilli());
		assertNull("An epoch-0 stamp is not an archival date", PbArtifactUtil.archivedAtOf(epoch));
	}

	private java.util.List<String> names(Enum<?>[] values) {
		java.util.List<String> out = new java.util.ArrayList<>();
		for(Enum<?> e : values) {
			out.add(e.name());
		}
		return out;
	}
}
