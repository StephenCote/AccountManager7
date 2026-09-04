package org.cote.accountmanager.iso42001.tests;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.util.List;
import java.util.UUID;

import org.cote.accountmanager.iso42001.schema.ISO42001ModelNames;
import org.cote.accountmanager.iso42001.service.ISO42001ServiceFacade;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.schema.FieldNames;
import org.junit.Test;

/**
 * Phase 3 P3-3a: the READ boundary that {@link ISO42001ServiceFacade#listAnalysisProfiles} enforces.
 *
 * <p>The facade builds an org-wide {@code iso42001.analysisProfile} query and hands it to
 * {@code AccessPoint.list}, which authorizes the QUERY SHAPE against the model's {@code access.roles}
 * (read = Readers/Auditors/Reporters/Certifiers). A user carrying NONE of those roles has the query
 * denied — {@code AccessPoint} returns a FAILED {@code QueryResult} whose default-empty results list
 * yields an EMPTY facade list. An {@code ISO42001Readers}-role user has the same query PERMITted and
 * sees the seeded profile.</p>
 *
 * <p>This asserts the ACTUAL boundary the facade enforces (role-based query-shape authorization),
 * exercised through the real facade + AccessPoint + live DB. Deterministic; no live LLM.</p>
 */
public class TestIsoProfileReadGate extends ISO42001BaseTest {

	@Test
	public void testListAnalysisProfilesRoleGate() {
		/// Seed one profile through the facade create path (bindOwnership stamps org=orgId,
		/// owner=isoTester); isoTester holds the Testers create role.
		BaseRecord seed = newRec(ISO42001ModelNames.MODEL_ANALYSIS_PROFILE);
		set(seed, FieldNames.FIELD_NAME, "readgate-profile-" + UUID.randomUUID());
		set(seed, FieldNames.FIELD_GROUP_ID, sharedGroupId);
		BaseRecord created = ISO42001ServiceFacade.createAnalysisProfile(isoTester, seed);
		assertNotNull("facade createAnalysisProfile as isoTester (Testers role) returned null", created);

		try {
			/// A user with NO ISO role. A freshly created user carries only the auto-enrolled
			/// AccountUsers role — none of the analysisProfile read roles.
			BaseRecord isoNobody = getCreateUser("isoNobodyReadGate", isoOrg);
			assertNotNull("isoNobody is null", isoNobody);

			/// Role-less user: the facade read gate yields an EMPTY list (query shape denied).
			List<BaseRecord> nobodyList = ISO42001ServiceFacade.listAnalysisProfiles(isoNobody);
			assertNotNull("listAnalysisProfiles must never return null", nobodyList);
			assertTrue("a user with no ISO read role MUST see zero profiles, got " + nobodyList.size(),
				nobodyList.isEmpty());

			/// ISO42001Readers-role user: same query PERMITted; the seeded profile is visible.
			List<BaseRecord> readerList = ISO42001ServiceFacade.listAnalysisProfiles(isoReader);
			assertNotNull("listAnalysisProfiles must never return null", readerList);
			assertTrue("an ISO42001Readers-role user MUST see the seeded profile(s), got " + readerList.size(),
				readerList.size() >= 1);
		}
		finally {
			/// Cleanup the seed (owner-based delete as its creator).
			BaseRecord toDelete = findByObjectId(isoTester, ISO42001ModelNames.MODEL_ANALYSIS_PROFILE,
				created.get(FieldNames.FIELD_OBJECT_ID));
			if (toDelete != null) {
				ioContext.getAccessPoint().delete(isoTester, toDelete);
			}
		}
	}
}
