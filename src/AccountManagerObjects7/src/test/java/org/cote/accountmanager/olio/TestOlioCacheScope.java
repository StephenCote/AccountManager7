package org.cote.accountmanager.olio;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.util.UUID;

import org.cote.accountmanager.io.OrganizationContext;
import org.cote.accountmanager.objects.tests.BaseTest;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.schema.type.GroupEnumType;
import org.junit.Test;

/**
 * Which Olio memoizations a {@code user}-role caller may drop.
 * <p>
 * {@code CacheService.clearCaches()} - reachable from {@code GET /cache/clearAll}, which is
 * {@code @RolesAllowed({"admin","user"})} - calls {@link OlioUtil#clearCache()}. Everything hung off
 * that method is therefore droppable by ANY authenticated caller at any moment, so only genuinely
 * self-refilling memoizations may live there.
 * <p>
 * {@link OlioUtil#dirNameCache} is not one. {@code nameInDirExists} APPENDS the name it just handed
 * out, so the map also holds names that are queued but not yet persisted; dropping it mid-generation
 * hands the same name out twice. That is the same argument that keeps {@code Decks.clearAll()} off
 * the general clear path, and it is why the drop moved to the admin-only
 * {@link OlioUtil#clearDirNameCache()}.
 * <p>
 * <b>Package placement.</b> {@code org.cote.accountmanager.olio} (the production package), because
 * {@code nameInDirExists} is {@code protected static} and reaching it from
 * {@code ...objects.tests.olio} would mean widening a production modifier for a test. Precedent:
 * {@code TestBookWorld}.
 * <p>
 * Live PostgreSQL {@code am7db} (BaseTest's {@code test.db.url}); no schema reset. Nothing here
 * touches an LLM, an embedding server or Stable Diffusion.
 */
public class TestOlioCacheScope extends BaseTest {

	private static final String ORG = "/Development/World Building";
	private static final String TEST_USER = "testUser1";
	/** A group that exists only to be a distinct {@code model + "-" + groupId} cache key. */
	private static final String PROBE_GROUP = "~/Data/PB2 Cache Scope";

	/**
	 * A queued-but-unpersisted directory name must survive {@link OlioUtil#clearCache()} and must be
	 * dropped by {@link OlioUtil#clearDirNameCache()}.
	 * <p>
	 * The final leg is the defect stated as behaviour rather than as commentary: once the cache is
	 * genuinely dropped, the name is handed out a SECOND time, because nothing was ever written to the
	 * database for the query to find. Before the fix, any {@code user}-role caller could produce that
	 * state through {@code /cache/clearAll} in the middle of somebody else's generation run.
	 */
	@Test
	public void testUserReachableClearCacheMustNotDropQueuedDirectoryNames() throws Exception {
		OrganizationContext org = getTestOrganization(ORG);
		BaseRecord user = getCreateUser(TEST_USER, org);
		assertNotNull("Failed to resolve " + TEST_USER, user);
		long orgId = org.getOrganizationId();

		BaseRecord dir = ioContext.getPathUtil().makePath(user, ModelNames.MODEL_GROUP, PROBE_GROUP,
			GroupEnumType.DATA.toString(), orgId);
		assertNotNull("Failed to resolve the probe group " + PROBE_GROUP, dir);
		long groupId = dir.get(FieldNames.FIELD_ID);

		/// Known start state, and a name no previous run can have persisted.
		OlioUtil.clearDirNameCache();
		String name = "pb2-cachescope-" + UUID.randomUUID().toString().substring(0, 8);

		/// 1. The name is free - and this call QUEUES it in dirNameCache.
		assertFalse("A fresh random name must not already exist in " + PROBE_GROUP,
			OlioUtil.nameInDirExists(user, ModelNames.MODEL_DATA, groupId, name));

		/// 2. Asked again, it is taken. That answer comes ONLY from the queued entry: no record was
		///    ever written, so a re-read of the database would say "free".
		assertTrue("The queued name must be reported as taken on the next ask",
			OlioUtil.nameInDirExists(user, ModelNames.MODEL_DATA, groupId, name));

		/// 3. THE FIX. OlioUtil.clearCache() is what GET /cache/clearAll reaches; it must not lose the
		///    queued name.
		OlioUtil.clearCache();
		assertTrue("OlioUtil.clearCache() is reachable from the @RolesAllowed({\"admin\",\"user\"})"
			+ " /cache/clearAll endpoint and must NOT drop dirNameCache: dropping it loses queued,"
			+ " not-yet-persisted names and allows a duplicate name within a generation run",
			OlioUtil.nameInDirExists(user, ModelNames.MODEL_DATA, groupId, name));

		/// 4. The admin-only drop really does drop it - and the consequence is visible: the same name
		///    is handed out a second time. This is what step 3 protects against.
		OlioUtil.clearDirNameCache();
		assertFalse("clearDirNameCache() must drop the map - after which the unpersisted name is"
			+ " handed out again, which is exactly why it is not on the user-reachable path",
			OlioUtil.nameInDirExists(user, ModelNames.MODEL_DATA, groupId, name));

		/// Leave nothing queued for whatever runs next in this JVM.
		OlioUtil.clearDirNameCache();
	}
}
