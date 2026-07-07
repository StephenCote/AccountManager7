package org.cote.accountmanager.objects.tests;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

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
 * Reproduces the EXACT charPerson fetch that PictureBookService.generateSceneImage
 * uses (an AccessPoint find with
 *   setRequest({"id","objectId","name","narrative","gender","profile"}))
 * — instead of planMost(false) which TestPortraitReuse used — to prove whether the
 * profile sub-record carries a usable id/objectId via THIS projection path, and
 * whether attach + reuse-read work through it.
 *
 * This isolates the one divergence between the passing TestPortraitReuse and the
 * real runtime path.
 */
public class TestPortraitReuseSceneFetch extends BaseTest {

    private static final String[] SCENE_REQUEST = new String[] {
        "id", FieldNames.FIELD_OBJECT_ID, FieldNames.FIELD_NAME, "narrative", "gender", "profile"
    };

    /**
     * Verifies the isBook gate: a data.note created under a ".../Scenes" group, then fetched
     * EXACTLY as generateSceneImage fetches the scene (planMost(false)), must return a
     * non-null groupPath that contains "/Scenes". If groupPath came back null the runtime
     * falls back to "~/Chat" -> isBook=false -> legacy render+delete every time (the symptom).
     */
    @Test
    public void TestSceneNoteGroupPathResolvesForIsBookGate() throws Exception {
        assertNotNull("IOSystem should be active", IOSystem.getActiveContext());
        OrganizationContext testOrgContext = getTestOrganization("/Development/PortraitReuseFetch");
        Factory mf = ioContext.getFactory();
        long orgId = testOrgContext.getOrganizationId();
        BaseRecord user = mf.getCreateUser(testOrgContext.getAdminUser(), "portraitReuseFetchUser", orgId);
        assertNotNull(user);

        BaseRecord scenesGroup = ioContext.getPathUtil().makePath(user, ModelNames.MODEL_GROUP,
                "~/PictureBooks/ReuseGateBook/Scenes", GroupEnumType.DATA.toString(), orgId);
        assertNotNull("Scenes group should not be null", scenesGroup);

        ParameterList plist = ParameterList.newParameterList(FieldNames.FIELD_PATH,
                scenesGroup.get(FieldNames.FIELD_PATH));
        plist.parameter(FieldNames.FIELD_NAME, "scene-" + UUID.randomUUID());
        BaseRecord note = ioContext.getFactory().newInstance(ModelNames.MODEL_NOTE, user, null, plist);
        note.set("text", "{\"characters\":[]}");
        note = IOSystem.getActiveContext().getAccessPoint().create(user, note);
        assertNotNull("scene note create should succeed", note);
        String noteOid = note.get(FieldNames.FIELD_OBJECT_ID);

        // Fetch EXACTLY as generateSceneImage does
        Query sq = QueryUtil.createQuery(ModelNames.MODEL_NOTE, FieldNames.FIELD_OBJECT_ID, noteOid);
        sq.field(FieldNames.FIELD_ORGANIZATION_ID, orgId);
        sq.planMost(false);
        BaseRecord scene = IOSystem.getActiveContext().getAccessPoint().find(user, sq);
        assertNotNull("scene fetch should succeed", scene);

        String sceneGroupPath = scene.get(FieldNames.FIELD_GROUP_PATH);
        logger.info("SCENE groupPath via planMost(false) = " + sceneGroupPath);
        assertNotNull("[DEFECT if null] scene.groupPath must be resolved (else isBook falls back to ~/Chat)",
                sceneGroupPath);
        assertTrue("[DEFECT if false] scene.groupPath must contain '/Scenes' for isBook gate (was: " + sceneGroupPath + ")",
                sceneGroupPath.contains("/Scenes"));

        try { IOSystem.getActiveContext().getAccessPoint().delete(user, scene); } catch (Exception e) { logger.warn("cleanup: " + e.getMessage()); }
    }

    @Test
    public void TestSceneFetchProfileHasIdAndReuse() throws Exception {
        assertNotNull("IOSystem should be active", IOSystem.getActiveContext());

        OrganizationContext testOrgContext = getTestOrganization("/Development/PortraitReuseFetch");
        Factory mf = ioContext.getFactory();
        long orgId = testOrgContext.getOrganizationId();
        BaseRecord user = mf.getCreateUser(testOrgContext.getAdminUser(), "portraitReuseFetchUser", orgId);
        assertNotNull("Non-admin actor user should not be null", user);
        assertTrue("Actor must not be the admin user", !"admin".equals(user.get(FieldNames.FIELD_NAME)));

        BaseRecord charsGroup = ioContext.getPathUtil().makePath(user, ModelNames.MODEL_GROUP,
                "~/PortraitReuseFetchTest/Characters", GroupEnumType.DATA.toString(), orgId);
        assertNotNull("Characters group should not be null", charsGroup);

        // Create charPerson EXACTLY as PictureBookService.createCharPerson does (factory.newInstance).
        String cpName = "SceneChar-" + UUID.randomUUID().toString();
        ParameterList plist = ParameterList.newParameterList(FieldNames.FIELD_PATH,
                charsGroup.get(FieldNames.FIELD_PATH));
        plist.parameter(FieldNames.FIELD_NAME, cpName);
        BaseRecord charPerson = ioContext.getFactory().newInstance(
                OlioModelNames.MODEL_CHAR_PERSON, user, null, plist);
        charPerson.set(FieldNames.FIELD_NAME, cpName);
        charPerson.set("gender", "FEMALE");
        charPerson = IOSystem.getActiveContext().getAccessPoint().create(user, charPerson);
        assertNotNull("charPerson create should succeed", charPerson);
        String cpOid = charPerson.get(FieldNames.FIELD_OBJECT_ID);

        // ---- FETCH #1: exactly the generateSceneImage query (setRequest, NOT planMost) ----
        Query cq = QueryUtil.createQuery(OlioModelNames.MODEL_CHAR_PERSON, FieldNames.FIELD_OBJECT_ID, cpOid);
        cq.field(FieldNames.FIELD_ORGANIZATION_ID, orgId);
        cq.setRequest(SCENE_REQUEST);
        BaseRecord cp = IOSystem.getActiveContext().getAccessPoint().find(user, cq);
        assertNotNull("charPerson scene-fetch should succeed", cp);

        // Mirror generateSceneImage B1 profile resolution
        BaseRecord profile = cp.get("profile");
        if (profile == null) {
            IOSystem.getActiveContext().getReader().populate(cp, new String[] { "profile" });
            profile = cp.get("profile");
        }
        assertNotNull("[DEFECT if null] profile from scene-fetch must not be null", profile);
        Long profIdObj = profile.get(FieldNames.FIELD_ID);
        long profId = (profIdObj != null) ? profIdObj.longValue() : 0L;
        logger.info("SCENE-FETCH profile id = " + profId + ", objectId = " + profile.get(FieldNames.FIELD_OBJECT_ID));
        assertTrue("[DEFECT if <=0] profile.id via setRequest scene-fetch must be > 0 (attach branch depends on it)",
                profId > 0L);

        // Persist a stand-in portrait image
        byte[] payload = ("PNG-" + UUID.randomUUID()).getBytes();
        BaseRecord image = RecordFactory.newInstance(ModelNames.MODEL_DATA);
        ioContext.getRecordUtil().applyNameGroupOwnership(user, image,
                "portrait_" + cpName, charsGroup.get(FieldNames.FIELD_PATH), orgId);
        image.set(FieldNames.FIELD_CONTENT_TYPE, "image/png");
        image.set(FieldNames.FIELD_BYTE_STORE, payload);
        image = IOSystem.getActiveContext().getAccessPoint().create(user, image);
        assertNotNull("portrait image create should succeed", image);

        // ---- ATTACH exactly as generateSceneImage does (profile patch: id + objectId + portrait) ----
        BaseRecord profilePatch = RecordFactory.newInstance(ModelNames.MODEL_PROFILE);
        profilePatch.set(FieldNames.FIELD_ID, profId);
        String profOid = profile.get(FieldNames.FIELD_OBJECT_ID);
        if (profOid != null) profilePatch.set(FieldNames.FIELD_OBJECT_ID, profOid);
        profilePatch.set("portrait", image);
        BaseRecord updated = IOSystem.getActiveContext().getAccessPoint().update(user, profilePatch);
        assertNotNull("profile patch attach must succeed", updated);

        // ---- FETCH #2: a SEPARATE scene-fetch (simulates a later scene generation) ----
        Query cq2 = QueryUtil.createQuery(OlioModelNames.MODEL_CHAR_PERSON, FieldNames.FIELD_OBJECT_ID, cpOid);
        cq2.field(FieldNames.FIELD_ORGANIZATION_ID, orgId);
        cq2.setRequest(SCENE_REQUEST);
        BaseRecord cp2 = IOSystem.getActiveContext().getAccessPoint().find(user, cq2);
        assertNotNull("second scene-fetch should succeed", cp2);

        // ---- REUSE-READ exactly as generateSceneImage does ----
        BaseRecord profile2 = cp2.get("profile");
        if (profile2 == null) {
            IOSystem.getActiveContext().getReader().populate(cp2, new String[] { "profile" });
            profile2 = cp2.get("profile");
        }
        assertNotNull("second-fetch profile must not be null", profile2);
        byte[] existingPortraitBytes = null;
        IOSystem.getActiveContext().getReader().populate(profile2, new String[] { "portrait" });
        BaseRecord existingPortrait = profile2.get("portrait");
        assertNotNull("[DEFECT if null] reuse-read: profile.portrait must be linked after attach", existingPortrait);
        IOSystem.getActiveContext().getReader().populate(existingPortrait, new String[] { FieldNames.FIELD_BYTE_STORE });
        existingPortraitBytes = existingPortrait.get(FieldNames.FIELD_BYTE_STORE);
        assertNotNull("[DEFECT if null] reuse-read: portrait byteStore must be populated", existingPortraitBytes);
        assertTrue("[DEFECT if 0] reuse condition (bytes.length>0) must hold -> skip re-render",
                existingPortraitBytes.length > 0);
        logger.info("REUSE-READ found " + existingPortraitBytes.length + " reusable bytes via scene-fetch path");

        // cleanup
        try {
            IOSystem.getActiveContext().getAccessPoint().delete(user, image);
            BaseRecord full = IOSystem.getActiveContext().getAccessPoint().find(user, cq2);
            if (full != null) IOSystem.getActiveContext().getAccessPoint().delete(user, full);
        } catch (Exception e) { logger.warn("cleanup: " + e.getMessage()); }
    }
}
