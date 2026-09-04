package org.cote.accountmanager.iso42001.tests;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import java.util.UUID;

import org.cote.accountmanager.io.OrganizationContext;
import org.cote.accountmanager.iso42001.schema.ISO42001ModelNames;
import org.cote.accountmanager.iso42001.service.ISO42001ServiceFacade;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.ModelNames;
import org.junit.Test;

/**
 * Phase 3 P3-3b: server-side tenancy binding on {@link ISO42001ServiceFacade#createAnalysisProfile}
 * ({@code bindOwnership}), which closes the forged-org create vector.
 *
 * <ol>
 *   <li><b>Override:</b> a payload carrying a FORGED {@code organizationId} and a FORGED
 *       {@code ownerId} must persist with the acting user's org and id — the client-supplied tenancy
 *       fields are overridden, never honored. (The write path persists these fields verbatim, so the
 *       facade must stamp them before create.)</li>
 *   <li><b>Fail-closed:</b> a payload whose {@code groupId} resolves to a DIFFERENT org must be
 *       rejected (create returns {@code null}) rather than falling through to PBAC.</li>
 * </ol>
 *
 * <p>Exercised through the real facade + AccessPoint + live DB. Deterministic; no live LLM.</p>
 */
public class TestIsoProfileForgedOrg extends ISO42001BaseTest {

	@Test
	public void testForgedOrgAndOwnerAreOverridden() {
		long forgedOrg = orgId + 100000L;
		long forgedOwner = (long) isoReader.get(FieldNames.FIELD_ID);
		long actingOwner = (long) isoTester.get(FieldNames.FIELD_ID);

		BaseRecord payload = newRec(ISO42001ModelNames.MODEL_ANALYSIS_PROFILE);
		set(payload, FieldNames.FIELD_NAME, "forged-tenancy-" + UUID.randomUUID());
		set(payload, FieldNames.FIELD_GROUP_ID, sharedGroupId);        // valid group in the acting org
		set(payload, FieldNames.FIELD_ORGANIZATION_ID, forgedOrg);     // forged
		set(payload, FieldNames.FIELD_OWNER_ID, forgedOwner);          // forged (another user's id)

		BaseRecord created = ISO42001ServiceFacade.createAnalysisProfile(isoTester, payload);
		assertNotNull("create with a VALID (acting-org) group must succeed", created);

		/// findByObjectId constrains organizationId == orgId (the acting org). If the forged org had
		/// persisted, this re-read would return null — so a non-null result already proves the record
		/// landed in the acting org, and the field checks below confirm org + owner precisely.
		BaseRecord stored = findByObjectId(isoTester, ISO42001ModelNames.MODEL_ANALYSIS_PROFILE,
			created.get(FieldNames.FIELD_OBJECT_ID));
		assertNotNull("stored profile re-read is null — did the forged organizationId persist?", stored);

		long storedOrg = (long) stored.get(FieldNames.FIELD_ORGANIZATION_ID);
		long storedOwner = (long) stored.get(FieldNames.FIELD_OWNER_ID);

		assertEquals("organizationId must be the acting user's org, not the forged value", orgId, storedOrg);
		assertNotEquals("forged organizationId must NOT have persisted", forgedOrg, storedOrg);
		assertEquals("ownerId must be the acting user's id, not the forged value", actingOwner, storedOwner);
		assertNotEquals("forged ownerId must NOT have persisted", forgedOwner, storedOwner);

		ioContext.getAccessPoint().delete(isoTester, stored);
	}

	@Test
	public void testCrossOrgGroupIsRejected() {
		/// A group that lives in a DIFFERENT organization (created additively in am7isotestdb).
		OrganizationContext forgeOrg = getTestOrganization("/ISO42001Forge");
		long forgeOrgId = forgeOrg.getOrganizationId();
		assertNotEquals("forge org must differ from the ISO org", orgId, forgeOrgId);

		BaseRecord foreignGroup = ioContext.getPathUtil().makePath(forgeOrg.getAdminUser(),
			ModelNames.MODEL_GROUP, "~/ForgeShared", "DATA", forgeOrgId);
		assertNotNull("foreign group is null", foreignGroup);
		long foreignGroupId = (long) foreignGroup.get(FieldNames.FIELD_ID);

		/// Acting as isoTester (in the ISO org) but pointing groupId at the foreign org's group.
		BaseRecord payload = newRec(ISO42001ModelNames.MODEL_ANALYSIS_PROFILE);
		set(payload, FieldNames.FIELD_NAME, "cross-org-group-" + UUID.randomUUID());
		set(payload, FieldNames.FIELD_GROUP_ID, foreignGroupId);

		BaseRecord created = ISO42001ServiceFacade.createAnalysisProfile(isoTester, payload);
		assertNull("create with a cross-org groupId MUST be rejected (fail-closed) -> null", created);
	}
}
