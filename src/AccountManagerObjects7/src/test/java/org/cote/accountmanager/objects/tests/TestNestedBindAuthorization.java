package org.cote.accountmanager.objects.tests;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.UUID;

import org.cote.accountmanager.factory.Factory;
import org.cote.accountmanager.io.OrganizationContext;
import org.cote.accountmanager.io.Query;
import org.cote.accountmanager.io.QueryUtil;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.record.RecordFactory;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.schema.type.ContactInformationEnumType;
import org.cote.accountmanager.schema.type.GroupEnumType;
import org.cote.accountmanager.schema.type.PermissionEnumType;
import org.cote.accountmanager.cache.CacheUtil;
import org.junit.Test;

/**
 * The {@code AccessPoint.vectorize} / {@code pageIndex} TODO ("older style reference models like
 * contactInformation are not correctly using the model level access binding ... access failure in the
 * dynamic policy for nested queries"), pinned down and closed.
 *
 * <p>{@code identity.contactInformation} declares {@code access.policies.bind} = defer to the record named
 * by {@code referenceModel}/{@code referenceId}. {@code AuthorizationUtil.canDo} honours that for a
 * top-level evaluation. The nested path did not: when a parent (here an {@code identity.person}) is read
 * with its {@code contactInformation} populated, {@code PolicyUtil.getSchemaRules} adds a rule for the
 * field whose foreign patterns came from the contactInformation's OWN read policy (owner / model roles),
 * ignoring the bind. A user with legitimate access to the person but no ownership of the contact record
 * and no {@code AccountUsersReaders} membership was therefore denied the person purely because the
 * projection included the field - which is why the six {@code planMost(false, [contactInformation])}
 * filters exist in {@code AccessPoint}.</p>
 *
 * <p>Live DB; all operations under test run as non-admin users. The org admin is used only to create
 * the two users.</p>
 */
public class TestNestedBindAuthorization extends BaseTest {

	private static final String ORG_PATH = "/Development/Nested Bind";

	private Query personQuery(BaseRecord user, String objectId, boolean includeContactInformation) {
		Query q = QueryUtil.createQuery(ModelNames.MODEL_PERSON, FieldNames.FIELD_OBJECT_ID, objectId);
		q.field(FieldNames.FIELD_ORGANIZATION_ID, user.get(FieldNames.FIELD_ORGANIZATION_ID));
		if(includeContactInformation) {
			q.planMost(false);
		}
		else {
			q.planMost(false, Arrays.asList(new String[] {FieldNames.FIELD_CONTACT_INFORMATION}));
		}
		q.setCache(false);
		return q;
	}

	private BaseRecord person(BaseRecord owner, String groupPath) throws Exception {
		long orgId = owner.get(FieldNames.FIELD_ORGANIZATION_ID);
		String name = "Bound Person " + UUID.randomUUID().toString().substring(0, 8);
		BaseRecord person = ioContext.getFactory().getCreateDirectoryModel(owner, ModelNames.MODEL_PERSON, name, groupPath, orgId);
		assertNotNull("Person was not created", person);
		return person;
	}

	/** A contactInformation record owned by {@code owner} and bound (referenceId/referenceModel) to {@code person}, not yet linked from it. */
	private BaseRecord contactInformationBoundTo(BaseRecord owner, BaseRecord person) throws Exception {
		long orgId = owner.get(FieldNames.FIELD_ORGANIZATION_ID);
		BaseRecord cit = RecordFactory.newInstance(ModelNames.MODEL_CONTACT_INFORMATION);
		cit.set("contactInformationType", ContactInformationEnumType.PERSON);
		cit.set(FieldNames.FIELD_REFERENCE_ID, person.get(FieldNames.FIELD_ID));
		cit.set(FieldNames.FIELD_REFERENCE_TYPE, person.getSchema());
		ioContext.getRecordUtil().applyOwnership(owner, cit, orgId);
		assertTrue("contactInformation was not created", ioContext.getRecordUtil().createRecord(cit));
		return cit;
	}

	/** A person owned by {@code owner} with a contactInformation record attached the way {@code AddressUtil} does it. Returns {person, contactInformation}. */
	private BaseRecord[] personWithContactInformation(BaseRecord owner, String groupPath) throws Exception {
		BaseRecord person = person(owner, groupPath);
		BaseRecord cit = contactInformationBoundTo(owner, person);
		BaseRecord patch = person.copyRecord(new String[] {FieldNames.FIELD_ID, FieldNames.FIELD_OBJECT_ID, FieldNames.FIELD_NAME, FieldNames.FIELD_GROUP_ID, FieldNames.FIELD_ORGANIZATION_ID});
		patch.set(FieldNames.FIELD_CONTACT_INFORMATION, cit);
		assertTrue("Person was not linked to its contactInformation", ioContext.getRecordUtil().updateRecord(patch));
		CacheUtil.clearCache();
		return new BaseRecord[] {person, cit};
	}

	/** The bind fields as stored right now - raw search, uncached, so the assertion is about the row and not a cache. */
	private BaseRecord storedBind(BaseRecord cit) {
		Query q = QueryUtil.createQuery(ModelNames.MODEL_CONTACT_INFORMATION, FieldNames.FIELD_ID, (long) cit.get(FieldNames.FIELD_ID));
		q.setRequest(new String[] {FieldNames.FIELD_ID, FieldNames.FIELD_REFERENCE_ID, FieldNames.FIELD_REFERENCE_TYPE});
		q.setCache(false);
		BaseRecord stored = ioContext.getSearch().findRecord(q);
		assertNotNull("The contactInformation row must still exist", stored);
		return stored;
	}

	private BaseRecord storedContactInformationLink(BaseRecord person) {
		Query q = QueryUtil.createQuery(ModelNames.MODEL_PERSON, FieldNames.FIELD_ID, (long) person.get(FieldNames.FIELD_ID));
		q.setRequest(new String[] {FieldNames.FIELD_ID, FieldNames.FIELD_CONTACT_INFORMATION});
		q.setCache(false);
		BaseRecord stored = ioContext.getSearch().findRecord(q);
		assertNotNull("The person row must still exist", stored);
		return stored.get(FieldNames.FIELD_CONTACT_INFORMATION);
	}

	/** A patch of {@code cit} that claims it is bound to {@code person} - what a caller sends to re-point the bind. */
	private BaseRecord rebindPatch(BaseRecord cit, BaseRecord person) throws Exception {
		BaseRecord patch = RecordFactory.newInstance(ModelNames.MODEL_CONTACT_INFORMATION, new String[] {FieldNames.FIELD_ID, FieldNames.FIELD_OBJECT_ID, FieldNames.FIELD_REFERENCE_ID, FieldNames.FIELD_REFERENCE_TYPE});
		patch.set(FieldNames.FIELD_ID, cit.get(FieldNames.FIELD_ID));
		patch.set(FieldNames.FIELD_OBJECT_ID, cit.get(FieldNames.FIELD_OBJECT_ID));
		patch.set(FieldNames.FIELD_REFERENCE_ID, person.get(FieldNames.FIELD_ID));
		patch.set(FieldNames.FIELD_REFERENCE_TYPE, person.getSchema());
		return patch;
	}

	/// The bind is a trust boundary: a caller who can re-point referenceId at a record they own, and have
	/// the authorization decision follow that re-pointed value, owns the record. Both evaluation paths
	/// (top-level AuthorizationUtil.canDo and the nested PolicyUtil.getForeignPatterns) must take the bind
	/// from storage, and a re-bind must be permitted by the current target as well as the requested one.
	@Test
	public void TestAnotherUserCannotRebindAContactInformationToTheirOwnPerson() throws Exception {
		OrganizationContext oc = getTestOrganization(ORG_PATH);
		Factory mf = ioContext.getFactory();
		long orgId = oc.getOrganizationId();
		BaseRecord owner = mf.getCreateUser(oc.getAdminUser(), "nbOwner", orgId);
		BaseRecord attacker = mf.getCreateUser(oc.getAdminUser(), "nbAttacker", orgId);
		assertNotNull(owner);
		assertNotNull(attacker);
		assertFalse(((long) owner.get(FieldNames.FIELD_ID)) == ((long) attacker.get(FieldNames.FIELD_ID)));

		String ownerGroup = "~/NestedBind/" + UUID.randomUUID().toString().substring(0, 8);
		String attackerGroup = "~/NestedBind/" + UUID.randomUUID().toString().substring(0, 8);
		assertNotNull(ioContext.getPathUtil().makePath(owner, ModelNames.MODEL_GROUP, ownerGroup, GroupEnumType.DATA.toString(), orgId));
		assertNotNull(ioContext.getPathUtil().makePath(attacker, ModelNames.MODEL_GROUP, attackerGroup, GroupEnumType.DATA.toString(), orgId));

		BaseRecord[] victim = personWithContactInformation(owner, ownerGroup);
		BaseRecord victimPerson = victim[0];
		BaseRecord victimCit = victim[1];
		BaseRecord attackerPerson = person(attacker, attackerGroup);
		long victimPersonId = victimPerson.get(FieldNames.FIELD_ID);
		long victimCitId = victimCit.get(FieldNames.FIELD_ID);

		/// Preconditions: the attacker has no route to the victim's person or its contact record.
		assertNull("Precondition: attacker must not read the victim's person",
			ioContext.getAccessPoint().findById(attacker, ModelNames.MODEL_PERSON, victimPersonId));
		assertNull("Precondition: attacker must not read the victim's contactInformation",
			ioContext.getAccessPoint().findById(attacker, ModelNames.MODEL_CONTACT_INFORMATION, victimCitId));
		assertEquals("Precondition: the contact record is bound to the victim's person",
			victimPersonId, (long) storedBind(victimCit).get(FieldNames.FIELD_REFERENCE_ID));

		/// Attack 1 - top level: patch the victim's contactInformation so that it claims to be bound to the
		/// attacker's person. Authorizing against the claimed target would permit this (the attacker owns it).
		BaseRecord takeover = rebindPatch(victimCit, attackerPerson);
		assertNull("A caller who cannot act on the current bind target must not be able to re-bind the record",
			ioContext.getAccessPoint().update(attacker, takeover));
		BaseRecord afterTopLevel = storedBind(victimCit);
		assertEquals("The stored bind must be unchanged after the refused top-level re-bind",
			victimPersonId, (long) afterTopLevel.get(FieldNames.FIELD_REFERENCE_ID));
		assertEquals(ModelNames.MODEL_PERSON, afterTopLevel.get(FieldNames.FIELD_REFERENCE_TYPE));
		assertNull("Attacker must still not read the victim's contactInformation",
			ioContext.getAccessPoint().findById(attacker, ModelNames.MODEL_CONTACT_INFORMATION, victimCitId));

		/// Positive control for attack 2: the attacker CAN link a contact record of their own, bound to
		/// their own person, into that person - so a refusal below is about the bind, not the field.
		BaseRecord attackerCit = contactInformationBoundTo(attacker, attackerPerson);
		CacheUtil.clearCache();
		BaseRecord ownLink = attackerPerson.copyRecord(new String[] {FieldNames.FIELD_ID, FieldNames.FIELD_OBJECT_ID, FieldNames.FIELD_NAME, FieldNames.FIELD_GROUP_ID, FieldNames.FIELD_ORGANIZATION_ID});
		ownLink.set(FieldNames.FIELD_CONTACT_INFORMATION, rebindPatch(attackerCit, attackerPerson));
		assertNotNull("Control: linking one's own bound contactInformation into one's own person must be permitted",
			ioContext.getAccessPoint().update(attacker, ownLink));
		BaseRecord linked = storedContactInformationLink(attackerPerson);
		assertNotNull("Control: the link must have been written", linked);
		assertEquals((long) attackerCit.get(FieldNames.FIELD_ID), (long) linked.get(FieldNames.FIELD_ID));

		/// Attack 2 - nested: update the attacker's own person with the VICTIM's contactInformation nested in
		/// it, the nested copy claiming to be bound to the attacker's person. The nested foreign rule would then
		/// see "bound to the enclosing record" and impose no read check on the victim's record. No cache is
		/// cleared between the control and this request on purpose: the control resolved a policy for the same
		/// actor and person with no contactInformation rule, and that resolved policy must not answer this one.
		BaseRecord nestedTakeover = attackerPerson.copyRecord(new String[] {FieldNames.FIELD_ID, FieldNames.FIELD_OBJECT_ID, FieldNames.FIELD_NAME, FieldNames.FIELD_GROUP_ID, FieldNames.FIELD_ORGANIZATION_ID});
		nestedTakeover.set(FieldNames.FIELD_CONTACT_INFORMATION, rebindPatch(victimCit, attackerPerson));
		BaseRecord nestedResult = ioContext.getAccessPoint().update(attacker, nestedTakeover);
		assertNull("A caller must not be able to link another user's bound contactInformation into their own person by claiming the bind",
			nestedResult);
		CacheUtil.clearCache();
		BaseRecord stillLinked = storedContactInformationLink(attackerPerson);
		assertNotNull("The attacker's person must still point at the attacker's own contact record", stillLinked);
		assertEquals("The attacker's person must not have been re-pointed at the victim's contact record",
			(long) attackerCit.get(FieldNames.FIELD_ID), (long) stillLinked.get(FieldNames.FIELD_ID));
		assertEquals("The victim's stored bind must be unchanged after the refused nested re-bind",
			victimPersonId, (long) storedBind(victimCit).get(FieldNames.FIELD_REFERENCE_ID));
		assertNull("Attacker must still not read the victim's contactInformation",
			ioContext.getAccessPoint().findById(attacker, ModelNames.MODEL_CONTACT_INFORMATION, victimCitId));

		/// Attack 3 - contradictory identity: an id that matches no row alongside the victim's real objectId.
		/// The stored-bind lookup must not be satisfied by one identity while the write keys on the other;
		/// with no trustworthy stored row the caller's bind values must be ignored outright.
		BaseRecord splitIdentity = rebindPatch(victimCit, attackerPerson);
		splitIdentity.set(FieldNames.FIELD_ID, 9_000_000_000_000L);
		assertNull("A record whose identity fields disagree must not be authorized through the caller's bind values",
			ioContext.getAccessPoint().update(attacker, splitIdentity));
		BaseRecord afterSplit = storedBind(victimCit);
		assertEquals("The stored bind must be unchanged after the contradictory-identity attempt",
			victimPersonId, (long) afterSplit.get(FieldNames.FIELD_REFERENCE_ID));
		assertEquals(ModelNames.MODEL_PERSON, afterSplit.get(FieldNames.FIELD_REFERENCE_TYPE));
	}

	/// The stored-bind check must not break the legitimate case: the owner moving a contact record between
	/// two persons they own - the current target and the requested target both permit the update.
	@Test
	public void TestOwnerCanRebindTheirContactInformationBetweenTheirOwnPersons() throws Exception {
		OrganizationContext oc = getTestOrganization(ORG_PATH);
		Factory mf = ioContext.getFactory();
		long orgId = oc.getOrganizationId();
		BaseRecord owner = mf.getCreateUser(oc.getAdminUser(), "nbOwner", orgId);
		assertNotNull(owner);
		String groupPath = "~/NestedBind/" + UUID.randomUUID().toString().substring(0, 8);
		assertNotNull(ioContext.getPathUtil().makePath(owner, ModelNames.MODEL_GROUP, groupPath, GroupEnumType.DATA.toString(), orgId));

		BaseRecord[] bound = personWithContactInformation(owner, groupPath);
		BaseRecord first = bound[0];
		BaseRecord cit = bound[1];
		BaseRecord second = person(owner, groupPath);
		assertEquals((long) first.get(FieldNames.FIELD_ID), (long) storedBind(cit).get(FieldNames.FIELD_REFERENCE_ID));

		assertNotNull("The owner of both persons must be able to re-bind their contact record from one to the other",
			ioContext.getAccessPoint().update(owner, rebindPatch(cit, second)));
		BaseRecord moved = storedBind(cit);
		assertEquals("The re-bind must have been written", (long) second.get(FieldNames.FIELD_ID), (long) moved.get(FieldNames.FIELD_REFERENCE_ID));
		assertEquals(ModelNames.MODEL_PERSON, moved.get(FieldNames.FIELD_REFERENCE_TYPE));

		/// And the record now answers to its new binding: a plain field update authorized through 'second'.
		BaseRecord touch = RecordFactory.newInstance(ModelNames.MODEL_CONTACT_INFORMATION, new String[] {FieldNames.FIELD_ID, FieldNames.FIELD_OBJECT_ID, "contactInformationType"});
		touch.set(FieldNames.FIELD_ID, cit.get(FieldNames.FIELD_ID));
		touch.set(FieldNames.FIELD_OBJECT_ID, cit.get(FieldNames.FIELD_OBJECT_ID));
		touch.set("contactInformationType", ContactInformationEnumType.PERSON);
		assertNotNull("The owner must still be able to update the record under its new binding", ioContext.getAccessPoint().update(owner, touch));
	}

	@Test
	public void TestReaderWithGroupAccessCanReadPersonWhoseContactInformationIsPopulated() throws Exception {
		OrganizationContext oc = getTestOrganization(ORG_PATH);
		Factory mf = ioContext.getFactory();
		long orgId = oc.getOrganizationId();
		BaseRecord owner = mf.getCreateUser(oc.getAdminUser(), "nbOwner", orgId);
		BaseRecord reader = mf.getCreateUser(oc.getAdminUser(), "nbReader", orgId);
		assertNotNull(owner);
		assertNotNull(reader);
		assertFalse("The reader must not be the owner, or nothing here measures the bind",
			((long) owner.get(FieldNames.FIELD_ID)) == ((long) reader.get(FieldNames.FIELD_ID)));

		String groupPath = "~/NestedBind/" + UUID.randomUUID().toString().substring(0, 8);
		BaseRecord group = ioContext.getPathUtil().makePath(owner, ModelNames.MODEL_GROUP, groupPath, GroupEnumType.DATA.toString(), orgId);
		assertNotNull("Group was not created", group);

		BaseRecord person = personWithContactInformation(owner, groupPath)[0];
		String objectId = person.get(FieldNames.FIELD_OBJECT_ID);

		/// Owner: both projections.
		BaseRecord ownerFull = ioContext.getAccessPoint().find(owner, personQuery(owner, objectId, true));
		assertNotNull("Owner must read the person with contactInformation populated", ownerFull);
		BaseRecord ownerCit = ownerFull.get(FieldNames.FIELD_CONTACT_INFORMATION);
		assertNotNull("Precondition: contactInformation must actually be populated in the full projection", ownerCit);
		assertEquals("Precondition: the contact record must be bound to this person",
			(Long) person.get(FieldNames.FIELD_ID), (Long) ownerCit.get(FieldNames.FIELD_REFERENCE_ID));
		assertEquals(ModelNames.MODEL_PERSON, ownerCit.get(FieldNames.FIELD_REFERENCE_TYPE));

		/// Reader, before any grant: denied either way (negative control for the grant below).
		assertNull("Reader without a grant must not read the person", ioContext.getAccessPoint().find(reader, personQuery(reader, objectId, false)));

		/// Grant the reader Read on the group - the owner can do that for a group they own; no admin involved.
		ioContext.getAuthorizationUtil().setEntitlement(owner, reader, new BaseRecord[] {group}, new String[] {"Read"},
			new String[] {PermissionEnumType.DATA.toString(), PermissionEnumType.GROUP.toString()});
		CacheUtil.clearCache();
		assertTrue("Control: the Read grant must exist on the group",
			ioContext.getAuthorizationUtil().checkEntitlement(reader,
				ioContext.getPathUtil().findPath(owner, ModelNames.MODEL_PERMISSION, "/Read", PermissionEnumType.DATA.toString(), orgId), group));

		/// Reader with the grant and the contactInformation field EXCLUDED from the plan: the positive control.
		BaseRecord readerFiltered = ioContext.getAccessPoint().find(reader, personQuery(reader, objectId, false));
		assertNotNull("Reader with a group Read grant must read the person when contactInformation is not projected", readerFiltered);

		/// Reader with the grant and contactInformation INCLUDED: the former gap. The contact record is bound
		/// to the person, so being able to read the person must be sufficient.
		BaseRecord readerFull = ioContext.getAccessPoint().find(reader, personQuery(reader, objectId, true));
		assertNotNull("Reader with a group Read grant must read the person when its bound contactInformation is projected (nested bind gap)", readerFull);
		BaseRecord readerCit = readerFull.get(FieldNames.FIELD_CONTACT_INFORMATION);
		assertNotNull("The populated contactInformation must come back to the reader too", readerCit);
		assertEquals((Long) person.get(FieldNames.FIELD_ID), (Long) readerCit.get(FieldNames.FIELD_REFERENCE_ID));

		/// Someone with no route to the person is still denied with the field projected: the bind must not
		/// become a bypass.
		BaseRecord stranger = mf.getCreateUser(oc.getAdminUser(), "nbStranger", orgId);
		assertNotNull(stranger);
		assertNull("A user with no access to the person must still be denied the full projection",
			ioContext.getAccessPoint().find(stranger, personQuery(stranger, objectId, true)));
		assertNull("...and the filtered one",
			ioContext.getAccessPoint().find(stranger, personQuery(stranger, objectId, false)));
	}
}
