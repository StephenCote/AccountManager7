package org.cote.accountmanager.objects.tests;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.UUID;

import org.cote.accountmanager.factory.Factory;
import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.io.OrganizationContext;
import org.cote.accountmanager.io.ParameterList;
import org.cote.accountmanager.io.Query;
import org.cote.accountmanager.io.QueryUtil;
import org.cote.accountmanager.olio.schema.OlioModelNames;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.record.RecordFactory;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.schema.type.GroupEnumType;
import org.junit.Test;

/**
 * Integration test for the PictureBookService.generateSceneImage portrait
 * persist + link + reuse behavior.
 *
 * The full scene-image render/composite path hits external SwarmUI (sdu.createImage),
 * which is neither available nor deterministic in unit tests, so this test does NOT
 * exercise image rendering. Instead it proves the RISKIEST newly-added logic against
 * the LIVE DB without SwarmUI: the PBAC-safe partial identity.profile update that
 * attaches a persisted portrait, and the reuse-lookup that later finds it.
 *
 * TWO methods:
 *   - {@link #TestPortraitPersistLinkReuse_asShipped()} mirrors the CURRENT shipped
 *     PictureBookService patch (id + objectId + portrait) and proves the full
 *     persist + link + reuse-lookup round-trip against the live DB.
 *   - {@link #TestPortraitPersistLinkReuse_idOnlyPatch()} uses an id-ONLY partial patch
 *     (id + portrait, no objectId). This independently guards the root-cause fix in
 *     RecordUtil.matchIdentityRecords (null-safe identity comparison during cache
 *     invalidation) and the documented id-only PATCH contract. Before that null-guard
 *     this path threw a NullPointerException from CacheDBSearch.clearCache.
 *
 * All operations use a NON-admin test user as the actor (admin is used only to
 * provision that user), mirroring the shared-test-user pattern of the other Java
 * integration tests.
 */
public class TestPortraitReuse extends BaseTest {

    /** Shared setup: returns [charPerson (full), profile (populated), image (persisted), charsGroupId] context. */
    private static final class Ctx {
        BaseRecord user;
        long orgId;
        long charsGroupId;
        Query refetch;
        BaseRecord profile;
        long profId;
        BaseRecord image;
        String imageOid;
        byte[] payload;
        int imageCount;
    }

    private Ctx buildContext(String suffix) throws Exception {
        Ctx c = new Ctx();
        assertNotNull("IOSystem should be active", IOSystem.getActiveContext());

        OrganizationContext testOrgContext = getTestOrganization("/Development/PortraitReuse");
        Factory mf = ioContext.getFactory();
        c.orgId = testOrgContext.getOrganizationId();
        c.user = mf.getCreateUser(testOrgContext.getAdminUser(), "portraitReuseUser", c.orgId);
        assertNotNull("Non-admin actor user should not be null", c.user);
        assertTrue("Actor must not be the admin user",
                !"admin".equals(c.user.get(FieldNames.FIELD_NAME)));

        BaseRecord charsGroup = ioContext.getPathUtil().makePath(c.user, ModelNames.MODEL_GROUP,
                "~/PortraitReuseTest/Characters", GroupEnumType.DATA.toString(), c.orgId);
        assertNotNull("Characters group should not be null", charsGroup);
        c.charsGroupId = ((Number) charsGroup.get(FieldNames.FIELD_ID)).longValue();

        // Persist a charPerson so its profile has a real id
        String cpName = "ReuseChar-" + suffix + "-" + UUID.randomUUID().toString();
        ParameterList plist = ParameterList.newParameterList(FieldNames.FIELD_PATH,
                charsGroup.get(FieldNames.FIELD_PATH));
        plist.parameter(FieldNames.FIELD_NAME, cpName);
        BaseRecord charPerson = ioContext.getFactory().newInstance(
                OlioModelNames.MODEL_CHAR_PERSON, c.user, null, plist);
        charPerson.set(FieldNames.FIELD_NAME, cpName);
        charPerson.set("firstName", "Reuse");
        charPerson.set("lastName", "Char");
        charPerson.set("gender", "FEMALE");
        charPerson = IOSystem.getActiveContext().getAccessPoint().create(c.user, charPerson);
        assertNotNull("charPerson create should succeed", charPerson);

        String cpOid = charPerson.get(FieldNames.FIELD_OBJECT_ID);
        c.refetch = QueryUtil.createQuery(OlioModelNames.MODEL_CHAR_PERSON, FieldNames.FIELD_OBJECT_ID, cpOid);
        c.refetch.field(FieldNames.FIELD_ORGANIZATION_ID, c.orgId);
        c.refetch.planMost(false);
        charPerson = IOSystem.getActiveContext().getAccessPoint().find(c.user, c.refetch);
        assertNotNull("charPerson re-fetch should succeed", charPerson);

        c.profile = charPerson.get(FieldNames.FIELD_PROFILE);
        if (c.profile == null) {
            IOSystem.getActiveContext().getReader().populate(charPerson, new String[] { FieldNames.FIELD_PROFILE });
            c.profile = charPerson.get(FieldNames.FIELD_PROFILE);
        }
        assertNotNull("Persisted charPerson must have a profile", c.profile);
        Long profIdObj = c.profile.get(FieldNames.FIELD_ID);
        assertNotNull("Profile id must be populated", profIdObj);
        c.profId = profIdObj.longValue();
        logger.info("charPerson '" + cpName + "' profile id = " + c.profId);
        assertTrue("PRECONDITION: profile.id must be > 0 (the fix depends on this)", c.profId > 0L);

        // Persist a data.data image (stand-in for the rendered portrait)
        c.payload = ("PNG-PORTRAIT-" + suffix + "-" + UUID.randomUUID()).getBytes();
        BaseRecord image = RecordFactory.newInstance(ModelNames.MODEL_DATA);
        ioContext.getRecordUtil().applyNameGroupOwnership(c.user, image,
                "portrait_" + cpName, charsGroup.get(FieldNames.FIELD_PATH), c.orgId);
        image.set(FieldNames.FIELD_CONTENT_TYPE, "image/png");
        image.set(FieldNames.FIELD_BYTE_STORE, c.payload);
        image = IOSystem.getActiveContext().getAccessPoint().create(c.user, image);
        assertNotNull("Portrait image create should succeed", image);
        c.image = image;
        c.imageOid = image.get(FieldNames.FIELD_OBJECT_ID);
        assertNotNull("Portrait image objectId should not be null", c.imageOid);
        c.imageCount = countData(c.user, c.charsGroupId, c.orgId);
        logger.info("Persisted portrait image objectId = " + c.imageOid
                + " (data.data count in group = " + c.imageCount + ")");
        return c;
    }

    /**
     * Mirrors the CURRENT shipped PictureBookService patch: partial identity.profile
     * patch = id + objectId + portrait. Proves the full persist + link + reuse-lookup
     * round-trip against the live DB.
     */
    @Test
    public void TestPortraitPersistLinkReuse_asShipped() throws Exception {
        Ctx c = buildContext("shipped");

        // Attach EXACTLY as the shipped fix does (PictureBookService): id + objectId + portrait.
        String profileOid = c.profile.get(FieldNames.FIELD_OBJECT_ID);
        assertNotNull("Profile objectId should be populated by planMost", profileOid);

        BaseRecord profilePatch = RecordFactory.newInstance(ModelNames.MODEL_PROFILE);
        profilePatch.set(FieldNames.FIELD_ID, c.profId);
        profilePatch.set(FieldNames.FIELD_OBJECT_ID, profileOid);
        profilePatch.set("portrait", c.image);
        BaseRecord updated = IOSystem.getActiveContext().getAccessPoint().update(c.user, profilePatch);
        assertNotNull("PBAC-safe partial identity.profile update (id + objectId + portrait) must SUCCEED", updated);
        logger.info("Shipped partial identity.profile update succeeded for profile id " + c.profId);

        assertReuseRoundTrip(c);
        cleanup(c);
    }

    /**
     * Root-cause guard: id-ONLY partial patch (id + portrait, no objectId). Exercises the
     * documented id-only PATCH contract and the null-safe RecordUtil.matchIdentityRecords
     * during cache invalidation. Before the null-guard this threw a NullPointerException
     * from CacheDBSearch.clearCache -> DBWriter.write (nothing persisted). MUST now succeed.
     */
    @Test
    public void TestPortraitPersistLinkReuse_idOnlyPatch() throws Exception {
        Ctx c = buildContext("idonly");

        BaseRecord profilePatch = RecordFactory.newInstance(ModelNames.MODEL_PROFILE);
        profilePatch.set(FieldNames.FIELD_ID, c.profId);
        profilePatch.set("portrait", c.image);
        BaseRecord updated = IOSystem.getActiveContext().getAccessPoint().update(c.user, profilePatch);
        assertNotNull("id-only partial identity.profile update (id + portrait) must SUCCEED (no NPE)", updated);
        logger.info("id-only partial identity.profile update succeeded for profile id " + c.profId);

        assertReuseRoundTrip(c);
        cleanup(c);
    }

    /** Re-fetch charPerson, populate profile->portrait->byteStore (mirror GameUtil), assert reuse-lookup works. */
    private void assertReuseRoundTrip(Ctx c) throws Exception {
        BaseRecord refetched = IOSystem.getActiveContext().getAccessPoint().find(c.user, c.refetch);
        assertNotNull("charPerson re-fetch should succeed", refetched);
        BaseRecord prof2 = refetched.get(FieldNames.FIELD_PROFILE);
        if (prof2 == null) {
            IOSystem.getActiveContext().getReader().populate(refetched, new String[] { FieldNames.FIELD_PROFILE });
            prof2 = refetched.get(FieldNames.FIELD_PROFILE);
        }
        assertNotNull("Re-fetched profile should not be null", prof2);

        IOSystem.getActiveContext().getReader().populate(prof2, new String[] { "portrait" });
        BaseRecord portrait = prof2.get("portrait");
        assertNotNull("profile.portrait must be non-null after attach (reuse-lookup finds it)", portrait);

        String portOid = portrait.get(FieldNames.FIELD_OBJECT_ID);
        assertEquals("Linked portrait objectId must match the persisted image", c.imageOid, portOid);

        IOSystem.getActiveContext().getReader().populate(portrait, new String[] { FieldNames.FIELD_BYTE_STORE });
        byte[] portBytes = portrait.get(FieldNames.FIELD_BYTE_STORE);
        assertNotNull("Reused portrait byteStore must be populated", portBytes);
        assertTrue("Reused portrait bytes must equal the original payload (bytes are reusable)",
                Arrays.equals(c.payload, portBytes));
        logger.info("Reuse-lookup found linked portrait with " + portBytes.length + " reusable bytes");

        // Reuse decision: "already has a linked portrait" => skip re-render
        boolean wouldReuse = (portBytes.length > 0);
        assertTrue("Fix's reuse condition (non-null portrait bytes => skip re-render) must be TRUE", wouldReuse);

        // No second portrait record created by the attach/reuse path
        int finalCount = countData(c.user, c.charsGroupId, c.orgId);
        assertEquals("Attach + reuse-lookup must NOT create a second portrait record",
                c.imageCount, finalCount);
        logger.info("data.data count in Characters group unchanged after attach+reuse: " + finalCount);
    }

    private void cleanup(Ctx c) {
        try {
            BaseRecord refetched = IOSystem.getActiveContext().getAccessPoint().find(c.user, c.refetch);
            IOSystem.getActiveContext().getAccessPoint().delete(c.user, c.image);
            if (refetched != null) IOSystem.getActiveContext().getAccessPoint().delete(c.user, refetched);
        } catch (Exception e) {
            logger.warn("Cleanup issue (non-fatal): " + e.getMessage());
        }
    }

    private int countData(BaseRecord user, long groupId, long orgId) {
        Query q = QueryUtil.createQuery(ModelNames.MODEL_DATA, FieldNames.FIELD_GROUP_ID, groupId);
        q.field(FieldNames.FIELD_ORGANIZATION_ID, orgId);
        return IOSystem.getActiveContext().getAccessPoint().count(user, q);
    }
}
