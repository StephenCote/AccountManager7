package org.cote.accountmanager.objects.tests;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import java.util.UUID;

import org.cote.accountmanager.factory.Factory;
import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.io.OrganizationContext;
import org.cote.accountmanager.io.ParameterList;
import org.cote.accountmanager.io.Query;
import org.cote.accountmanager.io.QueryUtil;
import org.cote.accountmanager.olio.InteractionEnumType;
import org.cote.accountmanager.olio.OutcomeEnumType;
import org.cote.accountmanager.olio.schema.OlioFieldNames;
import org.cote.accountmanager.olio.schema.OlioModelNames;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.record.RecordFactory;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.type.ActionResultEnumType;
import org.junit.Before;
import org.junit.Test;

/**
 * Measures how the dynamic policy treats a {@code $flex} foreign field ({@code olio.interaction.actor})
 * on {@code AccessPoint.update}, against the raw {@code RecordUtil.updateRecord} path the olio/llm
 * extractors currently use for the same write.
 */
public class TestFlexWritePolicy extends BaseTest {

	private static final String PERSON_MODEL = "olio.charPerson";
	private static final String[] PATCH_FIELDS = new String[] {
		FieldNames.FIELD_ID, FieldNames.FIELD_OBJECT_ID,
		OlioFieldNames.FIELD_ACTOR_TYPE, OlioFieldNames.FIELD_ACTOR
	};

	private OrganizationContext orgContext;
	private BaseRecord userA;
	private BaseRecord userB;

	@Before
	public void setupUsers() {
		orgContext = getTestOrganization("/Development/FlexWritePolicy");
		Factory mf = ioContext.getFactory();
		userA = mf.getCreateUser(orgContext.getAdminUser(), "flexPolicyUserA", orgContext.getOrganizationId());
		userB = mf.getCreateUser(orgContext.getAdminUser(), "flexPolicyUserB", orgContext.getOrganizationId());
		assertNotNull(userA);
		assertNotNull(userB);
	}

	private BaseRecord createPerson(BaseRecord owner, String label) throws Exception {
		ParameterList plist = ParameterList.newParameterList(FieldNames.FIELD_PATH, "~/People");
		plist.parameter(FieldNames.FIELD_NAME, label + "-" + UUID.randomUUID().toString().substring(0, 8));
		BaseRecord p = ioContext.getFactory().newInstance(PERSON_MODEL, owner, null, plist);
		p = ioContext.getAccessPoint().create(owner, p);
		assertNotNull("charPerson " + label + " should be created", p);
		return p;
	}

	private BaseRecord createInteraction(BaseRecord owner) throws Exception {
		ParameterList plist = ParameterList.newParameterList(FieldNames.FIELD_PATH, "~/Interactions");
		plist.parameter(FieldNames.FIELD_NAME, "SOCIALIZE " + UUID.randomUUID().toString().substring(0, 8));
		BaseRecord inter = ioContext.getFactory().newInstance(OlioModelNames.MODEL_INTERACTION, owner, null, plist);
		inter.set(FieldNames.FIELD_TYPE, InteractionEnumType.SOCIALIZE);
		inter.set("state", ActionResultEnumType.COMPLETE);
		inter.set("actorOutcome", OutcomeEnumType.EQUILIBRIUM);
		inter.set("interactorOutcome", OutcomeEnumType.EQUILIBRIUM);
		inter = ioContext.getAccessPoint().create(owner, inter);
		assertNotNull("interaction should be created", inter);
		return inter;
	}

	private BaseRecord actorPatch(BaseRecord inter, BaseRecord actor) throws Exception {
		BaseRecord patch = RecordFactory.newInstance(OlioModelNames.MODEL_INTERACTION, PATCH_FIELDS);
		patch.set(FieldNames.FIELD_ID, inter.get(FieldNames.FIELD_ID));
		patch.set(FieldNames.FIELD_OBJECT_ID, inter.get(FieldNames.FIELD_OBJECT_ID));
		patch.set(OlioFieldNames.FIELD_ACTOR_TYPE, actor.getSchema());
		patch.set(OlioFieldNames.FIELD_ACTOR, actor);
		return patch;
	}

	/** Direct, uncached read of the persisted actor FK (verification probe, not an authorization path). */
	private long persistedActorId(BaseRecord inter) {
		Query q = QueryUtil.createQuery(OlioModelNames.MODEL_INTERACTION, FieldNames.FIELD_OBJECT_ID, inter.get(FieldNames.FIELD_OBJECT_ID));
		q.setRequest(new String[] {FieldNames.FIELD_ID, FieldNames.FIELD_OBJECT_ID, OlioFieldNames.FIELD_ACTOR_TYPE, OlioFieldNames.FIELD_ACTOR});
		q.setCache(false);
		BaseRecord found = ioContext.getSearch().findRecord(q);
		assertNotNull("interaction should be readable by direct search", found);
		BaseRecord actor = found.get(OlioFieldNames.FIELD_ACTOR);
		if (actor == null || !actor.hasField(FieldNames.FIELD_ID) || actor.get(FieldNames.FIELD_ID) == null) {
			return 0L;
		}
		return actor.get(FieldNames.FIELD_ID);
	}

	@Test
	public void testAccessPointUpdatePersistsFlexRefToReadableRecord() throws Exception {
		BaseRecord inter = createInteraction(userA);
		BaseRecord ownPerson = createPerson(userA, "own");
		long ownId = ownPerson.get(FieldNames.FIELD_ID);

		BaseRecord result = ioContext.getAccessPoint().update(userA, actorPatch(inter, ownPerson));

		assertNotNull("AccessPoint.update of a $flex ref to a record the caller owns should be permitted", result);
		assertEquals("actor FK should be persisted through AccessPoint.update", ownId, persistedActorId(inter));
	}

	/** The olio/llm two-phase writers justify the PBAC bypass with "stubs without group/org fail policy checks". */
	@Test
	public void testAccessPointUpdatePersistsFlexRefGivenAsIdOnlyStub() throws Exception {
		BaseRecord inter = createInteraction(userA);
		BaseRecord ownPerson = createPerson(userA, "own-stub");
		long ownId = ownPerson.get(FieldNames.FIELD_ID);

		BaseRecord stub = RecordFactory.newInstance(PERSON_MODEL);
		stub.set(FieldNames.FIELD_ID, ownId);

		BaseRecord result = ioContext.getAccessPoint().update(userA, actorPatch(inter, stub));
		logger.warn("[FLEX-SPIKE] AccessPoint.update(A, actor=id-only stub of A's person) permitted=" + (result != null)
			+ " persistedActorId=" + persistedActorId(inter) + " ownPersonId=" + ownId);

		assertNotNull("AccessPoint.update with an id-only $flex stub of a readable record should be permitted", result);
		assertEquals("actor FK should be persisted from the id-only stub", ownId, persistedActorId(inter));
	}

	@Test
	public void testAccessPointUpdateDeniesFlexRefToUnreadableRecord() throws Exception {
		BaseRecord inter = createInteraction(userA);
		BaseRecord otherPerson = createPerson(userB, "other");
		long otherId = otherPerson.get(FieldNames.FIELD_ID);
		String otherOid = otherPerson.get(FieldNames.FIELD_OBJECT_ID);

		assertNull("precondition: user A must not be able to read user B's charPerson",
			ioContext.getAccessPoint().findByObjectId(userA, PERSON_MODEL, otherOid));

		BaseRecord result = null;
		ioContext.getPolicyUtil().setTrace(true);
		try {
			result = ioContext.getAccessPoint().update(userA, actorPatch(inter, otherPerson));
		}
		finally {
			ioContext.getPolicyUtil().setTrace(false);
		}
		long persisted = persistedActorId(inter);
		logger.warn("[FLEX-SPIKE] AccessPoint.update(A, actor=B's person) permitted=" + (result != null)
			+ " persistedActorId=" + persisted + " unreadablePersonId=" + otherId);

		assertNull("AccessPoint.update must deny linking a $flex ref to a record the caller cannot read", result);
		assertEquals("actor FK must remain unset after the denied update", 0L, persisted);
	}

	@Test
	public void testRawRecordUtilUpdateLinksUnreadableRecord() throws Exception {
		BaseRecord inter = createInteraction(userA);
		BaseRecord otherPerson = createPerson(userB, "other-raw");
		long otherId = otherPerson.get(FieldNames.FIELD_ID);

		inter.set(OlioFieldNames.FIELD_ACTOR_TYPE, otherPerson.getSchema());
		inter.set(OlioFieldNames.FIELD_ACTOR, otherPerson);
		boolean updated = ioContext.getRecordUtil().updateRecord(inter);
		long persisted = persistedActorId(inter);
		logger.warn("[FLEX-SPIKE] RecordUtil.updateRecord(actor=B's person) updated=" + updated
			+ " persistedActorId=" + persisted + " unreadablePersonId=" + otherId);

		assertEquals("raw RecordUtil.updateRecord persists the $flex ref with no policy check", otherId, persisted);
	}
}
