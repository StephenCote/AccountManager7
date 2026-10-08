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

	/** A person owned by {@code owner} with a contactInformation record attached the way {@code AddressUtil} does it. */
	private BaseRecord personWithContactInformation(BaseRecord owner, String groupPath) throws Exception {
		long orgId = owner.get(FieldNames.FIELD_ORGANIZATION_ID);
		String name = "Bound Person " + UUID.randomUUID().toString().substring(0, 8);
		BaseRecord person = ioContext.getFactory().getCreateDirectoryModel(owner, ModelNames.MODEL_PERSON, name, groupPath, orgId);
		assertNotNull("Person was not created", person);

		BaseRecord cit = RecordFactory.newInstance(ModelNames.MODEL_CONTACT_INFORMATION);
		cit.set("contactInformationType", ContactInformationEnumType.PERSON);
		cit.set(FieldNames.FIELD_REFERENCE_ID, person.get(FieldNames.FIELD_ID));
		cit.set(FieldNames.FIELD_REFERENCE_TYPE, person.getSchema());
		ioContext.getRecordUtil().applyOwnership(owner, cit, orgId);
		assertTrue("contactInformation was not created", ioContext.getRecordUtil().createRecord(cit));

		BaseRecord patch = person.copyRecord(new String[] {FieldNames.FIELD_ID, FieldNames.FIELD_OBJECT_ID, FieldNames.FIELD_NAME, FieldNames.FIELD_GROUP_ID, FieldNames.FIELD_ORGANIZATION_ID});
		patch.set(FieldNames.FIELD_CONTACT_INFORMATION, cit);
		assertTrue("Person was not linked to its contactInformation", ioContext.getRecordUtil().updateRecord(patch));
		CacheUtil.clearCache();
		return person;
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

		BaseRecord person = personWithContactInformation(owner, groupPath);
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
