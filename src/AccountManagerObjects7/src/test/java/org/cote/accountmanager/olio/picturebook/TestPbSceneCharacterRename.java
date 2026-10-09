package org.cote.accountmanager.olio.picturebook;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import java.util.UUID;

import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.io.ParameterList;
import org.cote.accountmanager.objects.tests.BaseTest;
import org.cote.accountmanager.olio.schema.OlioModelNames;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.schema.type.GroupEnumType;
import org.junit.Test;

/// A scene lists the name the extractor saw ("Jideon"); the persisted character can since have been
/// given a surname ("Jideon de Rosa" - the manage-characters form, or TestPictureBookCustom's custom
/// imprint). Measured 2026-10-09 (am7-sec2-pbcustom.log): both renamed characters of the rendered
/// scene logged "Could not resolve scene character" and the composite bound ZERO characters, because
/// the 2026-09-18 whole-string match (the Darby / "Darby's dad" substring fix) has no first-name pass.
///
/// Real DB through AccessPoint as a non-admin user; no LLM, no SD. Pins both halves of the rule: a
/// unique first name resolves, and the substring collision the whole-string rule exists for stays
/// closed - a shared first name is ambiguous and resolves to nothing.
public class TestPbSceneCharacterRename extends BaseTest {

	private static String createCharPerson(BaseRecord user, String groupPath, String name, String firstName, String lastName) throws Exception {
		ParameterList plist = ParameterList.newParameterList(FieldNames.FIELD_PATH, groupPath);
		plist.parameter(FieldNames.FIELD_NAME, name);
		BaseRecord cp = IOSystem.getActiveContext().getFactory().newInstance(OlioModelNames.MODEL_CHAR_PERSON, user, null, plist);
		cp.set(FieldNames.FIELD_NAME, name);
		cp.set("firstName", firstName);
		if(lastName != null) {
			cp.set("lastName", lastName);
		}
		cp.set("gender", "female");
		BaseRecord created = IOSystem.getActiveContext().getAccessPoint().create(user, cp);
		assertNotNull("create charPerson " + name, created);
		return created.get(FieldNames.FIELD_OBJECT_ID);
	}

	private static String resolvedOid(BaseRecord user, String sceneName, BaseRecord group) {
		BaseRecord cp = PictureBookUtil.findCharPersonByNameInGroup(user, sceneName, group);
		return cp != null ? cp.get(FieldNames.FIELD_OBJECT_ID) : null;
	}

	@Test
	public void renamedCharacterResolvesByUniqueFirstName() throws Exception {
		OlioModelNames.use();
		BaseRecord user = getCreateUser("pbRenameUser");
		assertNotNull(user);
		long orgId = ((Number) user.get(FieldNames.FIELD_ORGANIZATION_ID)).longValue();
		String tag = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
		BaseRecord group = ioContext.getPathUtil().makePath(user, ModelNames.MODEL_GROUP,
			"~/Data/PbRename/" + tag, GroupEnumType.DATA.toString(), orgId);
		assertNotNull("population group", group);
		String groupPath = group.get(FieldNames.FIELD_PATH);

		/// The TestPictureBookCustom shape: extracted as "Jideon"/"Duña", imprinted with a surname.
		String jideon = createCharPerson(user, groupPath, "Jideon de Rosa", "Jideon", "de Rosa");
		String duna = createCharPerson(user, groupPath, "Duña de Rosa", "Duña", "de Rosa");
		/// The BWO 3 shape the whole-string rule was written for.
		String darby = createCharPerson(user, groupPath, "Darby", "Darby", null);
		String dad = createCharPerson(user, groupPath, "Darby's dad", "Darby's", "dad");
		/// Two records sharing a first name: ambiguous by construction.
		createCharPerson(user, groupPath, "Ann Smith", "Ann", "Smith");
		createCharPerson(user, groupPath, "Ann Jones", "Ann", "Jones");

		assertEquals("A surname added after extraction must still resolve by first name",
			jideon, resolvedOid(user, "Jideon", group));
		assertEquals("...with a diacritic the database does not fold",
			duna, resolvedOid(user, "Duna", group));
		assertEquals("...and an exact first name with the diacritic",
			duna, resolvedOid(user, "Duña", group));
		assertEquals("An exact whole-name match still wins outright",
			jideon, resolvedOid(user, "jideon de rosa", group));

		assertEquals("'Darby' resolves to Darby, never her father", darby, resolvedOid(user, "Darby", group));
		assertEquals(dad, resolvedOid(user, "Darby's dad", group));

		assertNull("A first name shared by two records is ambiguous and must resolve to nothing",
			resolvedOid(user, "Ann", group));
		assertNull("A name nobody has resolves to nothing", resolvedOid(user, "Nobody", group));
		assertNull("A substring of a first name is not a first name", resolvedOid(user, "Jid", group));
	}
}
