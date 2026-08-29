package org.cote.accountmanager.objects.tests.olio;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeNotNull;

import org.cote.accountmanager.factory.Factory;
import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.io.OrganizationContext;
import org.cote.accountmanager.objects.tests.BaseTest;
import org.cote.accountmanager.olio.OlioContext;
import org.cote.accountmanager.olio.OlioContextUtil;
import org.cote.accountmanager.olio.picturebook.PbSubRecordUtil;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.schema.FieldNames;
import org.junit.Test;

/**
 * Regression test for Issue 13 fix: PbSubRecordUtil.createSubRecord must use the olio
 * principal (not the request user) when creating sub-records in world groups.
 *
 * Without the fix, AccessPoint.create(requestUser, inst) is denied because world groups
 * are owned by the olio principal, and character creation silently fails with
 * createCharPerson returning null for every character.
 */
public class TestPbSubRecordUtil extends BaseTest {

    private static final String ORG_SUBPATH = "/Development/PbSubRecordUtil";
    private static final String DATAGEN_PATH = "C:/Projects/data";

    @Test
    public void testCreateSubRecordInWorldGroupUsesOlioPrincipal() throws Exception {
        OrganizationContext org = getTestOrganization(ORG_SUBPATH);
        long orgId = org.getOrganizationId();
        Factory mf = IOSystem.getActiveContext().getFactory();
        BaseRecord user = mf.getCreateUser(org.getAdminUser(), "pbSubRecordTestUser", orgId);
        assertNotNull("Test user must be created", user);

        String datagenPath = testProperties.getProperty("test.datagen.path", DATAGEN_PATH);
        OlioContext octx = null;
        try {
            octx = OlioContextUtil.getOlioContext(user, datagenPath);
        } catch (Exception e) {
            logger.warn("OlioContext init failed (datagenPath=" + datagenPath + "): " + e.getMessage()
                + " — test requires a seeded Olio world; skipping");
        }
        assumeNotNull("OlioContext must be available for this test (seeded Olio world required)", octx);
        assumeNotNull("OlioContext must have a world", octx.getWorld());
        assumeNotNull("OlioContext must have an olio principal", octx.getOlioUser());

        // Each of the seven sub-record models must be createable in world group paths via the fix.
        for (String modelName : PbSubRecordUtil.WORLD_GROUP_FIELD.keySet()) {
            String path = PbSubRecordUtil.groupPathFor(user, octx, modelName);
            assertNotNull("groupPathFor must resolve for " + modelName, path);
            assertTrue("world group path must not start with ~/ for " + modelName
                + " (path=" + path + ")", !path.startsWith("~/"));

            BaseRecord created = PbSubRecordUtil.createSubRecord(user, octx, modelName, null);
            assertNotNull("createSubRecord must return non-null for " + modelName
                + " (path=" + path + ") — null means the olio-principal fix is not working", created);
            Long id = created.get(FieldNames.FIELD_ID);
            assertTrue("created " + modelName + " must have a positive id, got: " + id,
                id != null && id > 0L);

            logger.info("createSubRecord OK for " + modelName + " in " + path + " (id=" + id + ")");
        }
    }
}
