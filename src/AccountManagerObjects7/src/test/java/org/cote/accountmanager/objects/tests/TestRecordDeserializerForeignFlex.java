package org.cote.accountmanager.objects.tests;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import org.cote.accountmanager.factory.Factory;
import org.cote.accountmanager.io.OrganizationContext;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.record.LooseRecord;
import org.cote.accountmanager.record.RecordDeserializerConfig;
import org.cote.accountmanager.record.RecordSerializerConfig;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.schema.type.ActionEnumType;
import org.cote.accountmanager.util.AuditUtil;
import org.cote.accountmanager.util.JSONUtil;
import org.junit.Test;

/// Modules with filterForeign=true (getUnfilteredModule, getForeignFilteredModule - the ones
/// BatchQueue spills and FileWriter persist with) serialize a foreign $flex field such as
/// system.audit.subject as "subject_FK": <id>. Until 2026-10-07 RecordDeserializer added an empty
/// "subject" field for that key AND a second, resolved one from its deferred foreignFlex pass, so
/// the restored record carried the field twice and DBWriter emitted
/// `INSERT ... (subject, subject, ...)` -> "column specified more than once".
public class TestRecordDeserializerForeignFlex extends BaseTest {

	@Test
	public void TestForeignFlexRoundTripYieldsOneResolvedField() throws Exception {
		OrganizationContext oc = getTestOrganization("/Development/Deserializer");
		Factory mf = ioContext.getFactory();
		BaseRecord user = mf.getCreateUser(oc.getAdminUser(), "rdesUser1", oc.getOrganizationId());
		assertNotNull(user);
		long userId = user.get(FieldNames.FIELD_ID);
		assertTrue(userId > 0L);

		BaseRecord audit = AuditUtil.startAudit(user, ActionEnumType.READ, user, null);
		assertNotNull(audit);
		String marker = "rdes-" + UUID.randomUUID().toString();
		audit.set(FieldNames.FIELD_MESSAGE, marker);

		String json = JSONUtil.exportObject(Arrays.asList(audit), RecordSerializerConfig.getUnfilteredModule());
		assertNotNull(json);
		assertTrue("Precondition: the serializer must emit the foreign-key form for the flex field, got " + json,
			json.contains("\"subject_FK\""));

		List<BaseRecord> recs = JSONUtil.getList(json, LooseRecord.class, RecordDeserializerConfig.getUnfilteredModule());
		assertNotNull(recs);
		assertEquals(1, recs.size());
		BaseRecord restored = recs.get(0);
		assertEquals(ModelNames.MODEL_AUDIT, restored.getSchema());
		assertEquals(marker, restored.get(FieldNames.FIELD_MESSAGE));

		long subjectFields = restored.getFields().stream().filter(f -> f.getName().equals(FieldNames.FIELD_SUBJECT)).count();
		assertEquals("The flex foreign field must appear exactly once on the restored record", 1L, subjectFields);

		BaseRecord subject = restored.get(FieldNames.FIELD_SUBJECT);
		assertNotNull("The deferred foreignFlex pass must resolve the subject record", subject);
		assertEquals(ModelNames.MODEL_USER, subject.getSchema());
		assertEquals(userId, (long)subject.get(FieldNames.FIELD_ID));
		assertEquals(ModelNames.MODEL_USER, restored.get(FieldNames.FIELD_SUBJECT_TYPE));
	}
}
