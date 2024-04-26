package org.cote.accountmanager.objects.tests;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

import org.cote.accountmanager.exceptions.FactoryException;
import org.cote.accountmanager.exceptions.FieldException;
import org.cote.accountmanager.exceptions.ModelException;
import org.cote.accountmanager.exceptions.ModelNotFoundException;
import org.cote.accountmanager.exceptions.ValueException;
import org.cote.accountmanager.factory.Factory;
import org.cote.accountmanager.factory.ParticipationFactory;
import org.cote.accountmanager.io.OrganizationContext;
import org.cote.accountmanager.io.ParameterList;
import org.cote.accountmanager.io.Query;
import org.cote.accountmanager.io.QueryUtil;
import org.cote.accountmanager.io.db.DBStatementMeta;
import org.cote.accountmanager.io.db.StatementUtil;
import org.cote.accountmanager.model.field.FieldType;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.schema.type.GroupEnumType;
import org.cote.accountmanager.util.AttributeUtil;
import org.cote.accountmanager.util.RecordUtil;
import org.junit.Test;

public class TestNestedStructures extends BaseTest {

	@Test
	public void TestPersonConstruct() {
		OrganizationContext testOrgContext = getTestOrganization("/Development/Nested Structures");
		Factory mf = ioContext.getFactory();
		BaseRecord testUser1 = mf.getCreateUser(testOrgContext.getAdminUser(), "testUser1", testOrgContext.getOrganizationId());

		String path = "~/Dooter Peeps - " + UUID.randomUUID().toString();
		BaseRecord dir = ioContext.getPathUtil().makePath(testUser1, ModelNames.MODEL_GROUP, path, GroupEnumType.DATA.toString(), testOrgContext.getOrganizationId());
		ParameterList plist = ParameterList.newParameterList("path", path);
		String name = "Person 1";
		plist.parameter("name", name);
		try {
			BaseRecord a1 = ioContext.getFactory().newInstance(ModelNames.MODEL_CHAR_PERSON, testUser1, null, plist);
			a1.set("gender", "male");
			AttributeUtil.addAttribute(a1, "test", true);
			a1.set(FieldNames.FIELD_CONTACT_INFORMATION, ioContext.getFactory().newInstance(ModelNames.MODEL_CONTACT_INFORMATION, testUser1, null, null));
			BaseRecord a2 = ioContext.getFactory().newInstance(ModelNames.MODEL_CHAR_PERSON, testUser1, null, plist);
			a2.set(FieldNames.FIELD_NAME, "Person 2");
			a2.set("gender", "female");
			a2.set(FieldNames.FIELD_CONTACT_INFORMATION, ioContext.getFactory().newInstance(ModelNames.MODEL_CONTACT_INFORMATION, testUser1, null, null));
			AttributeUtil.addAttribute(a2, "test", false);

			/// BUG: When adding cross-relationships such as partnerships, the auto-created participation for one half will wind up missing the other half's identifier (in io.db) because of the auto-participation adds are currently coded within the scope of a single record.
			/// To fix this, participations for all records would need to be pulled out separately, have the record identifiers assigned first, and then bulk add the participations
			/// In the previous version, most model level participations were handled like this.
			/// In the current version, the preference is to keep the participation disconnected from the model factory to avoid having to perform bulk read, update, and deletes to determine what changed on every update
			/// In other words, don't auto-create cross-participations except to be able to make an in-scope reference:

			ioContext.getRecordUtil().createRecords(new BaseRecord[] {a1, a2});
			BaseRecord p1 = ParticipationFactory.newParticipation(testUser1, a1, "partners", a2);
			BaseRecord p2 = ParticipationFactory.newParticipation(testUser1, a2, "partners", a1);
			ioContext.getRecordUtil().createRecords(new BaseRecord[] {p1, p2});

			Query q = QueryUtil.createQuery(ModelNames.MODEL_CHAR_PERSON, FieldNames.FIELD_GROUP_ID, dir.get(FieldNames.FIELD_ID));
			q.field(FieldNames.FIELD_NAME, "Person 1");
			q.set(FieldNames.FIELD_LIMIT_FIELDS, false);
			BaseRecord rec = ioContext.getSearch().findRecord(q);
			assertNotNull("Record is null", rec);
			logger.info(rec.toFullString());
			
			Query q2 = QueryUtil.createQuery(ModelNames.MODEL_CONTACT_INFORMATION, FieldNames.FIELD_ID, rec.get("contactInformation.id"));
			logger.info(Arrays.asList(RecordUtil.getCommonFields(ModelNames.MODEL_CONTACT_INFORMATION)).stream().collect(Collectors.joining(", ")));
			ioContext.getPolicyUtil().setTrace(true);
			BaseRecord crec = ioContext.getAccessPoint().find(testUser1, q2);
			ioContext.getPolicyUtil().setTrace(false);
			assertNotNull("Contact record is null", crec);
			

		}
		catch(ClassCastException | StackOverflowError | FieldException | ValueException | ModelNotFoundException | FactoryException | ModelException e) {
			logger.error(e);
			e.printStackTrace();
		}
	}
}
