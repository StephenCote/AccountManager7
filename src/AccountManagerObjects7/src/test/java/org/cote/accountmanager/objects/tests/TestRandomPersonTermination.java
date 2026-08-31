package org.cote.accountmanager.objects.tests;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.util.UUID;

import org.cote.accountmanager.io.OrganizationContext;
import org.cote.accountmanager.objects.tests.olio.OlioTestUtil;
import org.cote.accountmanager.olio.CharacterUtil;
import org.cote.accountmanager.olio.OlioContext;
import org.cote.accountmanager.olio.schema.OlioFieldNames;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.schema.type.GroupEnumType;
import org.junit.Test;

/**
 * End-to-end proof that {@link CharacterUtil#randomPerson} terminates and yields a usable character
 * even when the world's NAME (and SURNAME) word directories are empty -- the exact condition that hung
 * the PictureBook /extract endpoint (unbounded "Name null null &lt;token&gt; exists .... trying again"
 * loop).
 *
 * <p>The test builds a normal seeded Olio context and then swaps EMPTY word groups into the basis
 * world's NAME/SURNAME slots (in-memory), so every name draw returns null and the primary uniqueness
 * loop can never succeed. Before the fix this call never returned; after it, the bounded retry cap and
 * the {@code synthesizeUniquePersonName} backstop guarantee a valid, unique, non-"null" name. The
 * {@code @Test(timeout=...)} is the hard non-hang guard.</p>
 *
 * <p><b>Requires a live database and a seeded Olio universe</b> (external name/geo/word data staged at
 * {@code test.datagen.path}), so it extends {@link BaseTest} and opens IOSystem against
 * {@code test.db.url}. It is NOT part of the DB-free unit set; run it against the live/Docker stack.</p>
 */
public class TestRandomPersonTermination extends BaseTest {

	private BaseRecord emptyWordGroup(BaseRecord user, long orgId, String label) {
		/// makePath creates an empty DATA group; no words are ever added, so every MODEL_WORD /
		/// MODEL_CENSUS_WORD query against its id returns zero rows (randomSelectionName -> null).
		return ioContext.getPathUtil().makePath(user, ModelNames.MODEL_GROUP,
				"/EmptyNames/" + label + "-" + UUID.randomUUID().toString(),
				GroupEnumType.DATA.toString(), orgId);
	}

	@Test(timeout = 120000)
	public void testRandomPersonTerminatesWithEmptyNameDirectory() {
		OrganizationContext testOrgContext = getTestOrganization("/Development/World Building");
		String dataPath = testProperties.getProperty("test.datagen.path");

		OlioContext octx = OlioTestUtil.getContext(testOrgContext, dataPath);
		assertNotNull("Olio context is null", octx);

		BaseRecord user = octx.getOlioUser();
		long orgId = user.get(FieldNames.FIELD_ORGANIZATION_ID);

		BaseRecord world = octx.getWorld();
		assertNotNull("World is null", world);
		BaseRecord basis = world.get(OlioFieldNames.FIELD_BASIS);
		assertNotNull("Basis (universe) world is null", basis);

		/// Force the degenerate condition: repoint the basis NAME and SURNAME dirs at empty groups so
		/// no first/middle/surname can ever be drawn. randomPerson reads these fields fresh on each call.
		BaseRecord emptyNames = emptyWordGroup(user, orgId, "names");
		BaseRecord emptySurnames = emptyWordGroup(user, orgId, "surnames");
		assertNotNull("Empty NAME group not created", emptyNames);
		assertNotNull("Empty SURNAME group not created", emptySurnames);
		try {
			basis.set(OlioFieldNames.FIELD_NAMES, emptyNames);
			basis.set(OlioFieldNames.FIELD_SURNAMES, emptySurnames);
		}
		catch(Exception e) {
			logger.error(e);
			throw new AssertionError("Failed to repoint basis name directories: " + e.getMessage());
		}

		/// The call that used to spin forever. The @Test timeout is the hard non-hang guard.
		BaseRecord person = CharacterUtil.randomPerson(octx, null);

		assertNotNull("randomPerson returned null with an empty name directory", person);
		String name = person.get(FieldNames.FIELD_NAME);
		assertNotNull("Character name is null", name);
		assertFalse("Character name is blank", name.isBlank());
		for(String tok : name.split(" ")) {
			assertFalse("Character name must not contain a literal 'null' token: '" + name + "'", "null".equals(tok));
		}
		logger.info("randomPerson terminated with a usable fallback name against an empty NAME directory: '" + name + "'");
	}
}
