package org.cote.accountmanager.objects.tests;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.io.OrganizationContext;
import org.cote.accountmanager.io.ParameterList;
import org.cote.accountmanager.io.Query;
import org.cote.accountmanager.io.QueryResult;
import org.cote.accountmanager.io.QueryUtil;
import org.cote.accountmanager.olio.llm.ChatUtil;
import org.cote.accountmanager.olio.schema.OlioModelNames;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.schema.FieldNames;
import org.junit.Test;

/// Regression coverage for create-time cache invalidation in DBWriter (2026-11-27).
///
/// CacheDBSearch keeps non-empty query results for up to 360s, keyed per query TYPE. Before the fix,
/// DBWriter.write only invalidated on UPDATE (CacheUtil.clearCache(rec)), so a CREATE left every cached
/// result for that model in place:
///
/// 1. PBAC: AccessPoint.find with organizationId + ownerId + name and no groupId (the exact shape of
///    ChatUtil.getConfig) is authorized by PolicyUtil.evaluateQueryToReadPolicyResponses, which runs an
///    inspection sub-query `name = X` with no owner/org/context-user, through the caching search. Once any
///    user has warmed that key (while another user's X exists), a NEW X created by a second user is
///    invisible to the authorizer and the find is DENIED for up to 6 minutes.
/// 2. Generic search: a list query cached non-empty before a create keeps returning the pre-create page.
///
/// Both run as non-admin users created through BaseTest.getCreateUser in a dedicated organization, with
/// per-run unique names so no earlier run's records can satisfy (or poison) the inspection key.
public class TestCreateCacheInvalidation extends BaseTest {

	private BaseRecord createChatConfig(BaseRecord user, String name) throws Exception {
		ParameterList plist = ParameterList.newParameterList(FieldNames.FIELD_PATH, "~/Chat");
		plist.parameter(FieldNames.FIELD_NAME, name);
		BaseRecord cfg = IOSystem.getActiveContext().getFactory()
			.newInstance(OlioModelNames.MODEL_CHAT_CONFIG, user, null, plist);
		BaseRecord created = IOSystem.getActiveContext().getAccessPoint().create(user, cfg);
		assertNotNull("chatConfig " + name + " must be created", created);
		return created;
	}

	private void cleanup(BaseRecord user, List<BaseRecord> recs) {
		for (BaseRecord r : recs) {
			boolean del = IOSystem.getActiveContext().getAccessPoint().delete(user, r);
			if (!del) {
				logger.warn("Cleanup failed to delete " + r.getSchema() + " " + r.get(FieldNames.FIELD_OBJECT_ID));
			}
		}
	}

	/// User A owns X and warms the groupless org+owner+name find (which caches the name-only inspection
	/// sub-query with A's record). User B then creates X in B's own ~/Chat. B's identical-shape find
	/// must return B's record.
	@Test
	public void groupless_owner_name_find_sees_record_created_after_warm() throws Exception {
		OrganizationContext o = getTestOrganization("/Development/CacheCreateInval");
		String stamp = Long.toString(System.currentTimeMillis());
		BaseRecord userA = getCreateUser("cciUserA-" + stamp, o);
		BaseRecord userB = getCreateUser("cciUserB-" + stamp, o);
		assertNotNull(userA);
		assertNotNull(userB);
		String name = "cciCfg-" + stamp;

		List<BaseRecord> createdA = new ArrayList<>();
		List<BaseRecord> createdB = new ArrayList<>();
		try {
			createdA.add(createChatConfig(userA, name));

			/// Warm: A's find of A's X. Its authorization runs the shared `name = X` inspection sub-query,
			/// which returns A's X (non-empty) and so enters the cache.
			BaseRecord warmA = ChatUtil.getConfig(userA, OlioModelNames.MODEL_CHAT_CONFIG, null, name);
			assertNotNull("Precondition: A must find A's own " + name, warmA);

			BaseRecord cfgB = createChatConfig(userB, name);
			createdB.add(cfgB);

			BaseRecord foundB = ChatUtil.getConfig(userB, OlioModelNames.MODEL_CHAT_CONFIG, null, name);
			assertNotNull("B's groupless org+owner+name find of " + name + " must return the record B just created "
				+ "(null = the authorizer evaluated a stale cached inspection result that predates B's create)", foundB);
			assertEquals("Must be B's record", (String) cfgB.get(FieldNames.FIELD_OBJECT_ID), (String) foundB.get(FieldNames.FIELD_OBJECT_ID));

			/// A's view is unchanged: still A's own record only.
			BaseRecord againA = ChatUtil.getConfig(userA, OlioModelNames.MODEL_CHAT_CONFIG, null, name);
			assertNotNull(againA);
			assertEquals((String) createdA.get(0).get(FieldNames.FIELD_OBJECT_ID), (String) againA.get(FieldNames.FIELD_OBJECT_ID));
		}
		finally {
			cleanup(userB, createdB);
			cleanup(userA, createdA);
		}
	}

	/// A group-scoped list cached (non-empty) before a create must include the new record afterwards.
	@Test
	public void cached_list_query_sees_record_created_after_it() throws Exception {
		OrganizationContext o = getTestOrganization("/Development/CacheCreateInval");
		String stamp = Long.toString(System.currentTimeMillis());
		BaseRecord user = getCreateUser("cciListUser-" + stamp, o);
		assertNotNull(user);
		long orgId = user.get(FieldNames.FIELD_ORGANIZATION_ID);

		List<BaseRecord> created = new ArrayList<>();
		try {
			BaseRecord first = createChatConfig(user, "cciList1-" + stamp);
			created.add(first);
			long groupId = first.get(FieldNames.FIELD_GROUP_ID);

			Set<String> before = listNames(user, groupId, orgId);
			assertTrue("Precondition: the warmed list must contain the first record (non-empty, so it is cached): " + before,
				before.contains("cciList1-" + stamp));

			created.add(createChatConfig(user, "cciList2-" + stamp));

			Set<String> after = listNames(user, groupId, orgId);
			assertTrue("A list query cached before the create must see the newly created record: " + after,
				after.contains("cciList2-" + stamp));
			assertTrue(after.contains("cciList1-" + stamp));
		}
		finally {
			cleanup(user, created);
		}
	}

	private Set<String> listNames(BaseRecord user, long groupId, long orgId) {
		/// Same query shape both times (identical key/hash) and caching left ON, as a UI list re-fetch would.
		Query q = QueryUtil.createQuery(OlioModelNames.MODEL_CHAT_CONFIG, FieldNames.FIELD_GROUP_ID, groupId);
		q.field(FieldNames.FIELD_ORGANIZATION_ID, orgId);
		q.setRequest(new String[] {FieldNames.FIELD_ID, FieldNames.FIELD_OBJECT_ID, FieldNames.FIELD_NAME, FieldNames.FIELD_GROUP_ID});
		QueryResult qr = IOSystem.getActiveContext().getAccessPoint().list(user, q);
		assertNotNull("list must return a result", qr);
		return Arrays.stream(qr.getResults()).map(r -> (String) r.get(FieldNames.FIELD_NAME)).collect(Collectors.toSet());
	}
}
