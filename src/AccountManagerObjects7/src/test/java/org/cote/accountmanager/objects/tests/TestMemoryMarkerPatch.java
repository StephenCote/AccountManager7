package org.cote.accountmanager.objects.tests;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.io.Query;
import org.cote.accountmanager.io.QueryUtil;
import org.cote.accountmanager.olio.llm.ChatUtil;
import org.cote.accountmanager.olio.schema.OlioModelNames;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.schema.FieldNames;
import org.junit.Test;

/// MemoryKeyframeDecouplingPlan §2.3 #4 / #5 bookkeeping — the standalone memory
/// trigger eagerly persists chatConfig.lastMemoryExtractionAt (and the keyframe /
/// interaction triggers do the same for their markers) with EXACTLY this patch shape:
///
///   accessPoint.update(user, chatConfig.copyRecord(new String[] {
///       id, ownerId, groupId, "<marker>" }))
///
/// i.e. identity + the one changed int, no `name`. model-api.md warns that a patch
/// on a common.nameId model that omits `name` can fail validation silently, and the
/// callers in Chat.java discard the update result (they only log on exception). So
/// if that shape does not persist, every extraction cadence silently degrades to
/// "fire on every message past the threshold" and nothing in the logs says so.
///
/// This test pins the real behaviour against the live DB with a non-admin user:
/// apply the production patch shape, re-read with a fresh uncached Query, assert the
/// marker landed. DB only — no LLM.
public class TestMemoryMarkerPatch extends BaseTest {

	private static final String TEST_USER = "memMarkerPatchUser";
	private static final String CFG_NAME = "memMarkerPatch-cfg";

	@Test
	public void markerPatchWithoutNamePersists() throws Exception {
		BaseRecord user = getCreateUser(TEST_USER);
		assertNotNull("non-admin test user", user);

		@SuppressWarnings("deprecation")
		BaseRecord cfg = ChatUtil.getCreateChatConfig(user, CFG_NAME);
		assertNotNull("chatConfig", cfg);
		long cfgId = cfg.get(FieldNames.FIELD_ID);

		/// Start from a known state so a stale row from a previous run cannot mask a no-op.
		applyMarker(user, cfg, "lastMemoryExtractionAt", 0);
		applyMarker(user, cfg, "lastKeyframeAt", 0);
		applyMarker(user, cfg, "lastInteractionAt", 0);
		assertEquals(0, readMarker(user, cfgId, "lastMemoryExtractionAt"));

		/// The production shape (Chat.checkMemoryExtractionTrigger).
		BaseRecord memResult = applyMarker(user, cfg, "lastMemoryExtractionAt", 7);
		assertNotNull("update(lastMemoryExtractionAt) returned null — the eager marker patch did not persist", memResult);
		assertEquals("lastMemoryExtractionAt must round-trip through the identity+marker patch",
			7, readMarker(user, cfgId, "lastMemoryExtractionAt"));

		/// Same shape used by checkKeyframeTrigger / checkInteractionTrigger.
		assertNotNull(applyMarker(user, cfg, "lastKeyframeAt", 11));
		assertEquals(11, readMarker(user, cfgId, "lastKeyframeAt"));
		assertNotNull(applyMarker(user, cfg, "lastInteractionAt", 13));
		assertEquals(13, readMarker(user, cfgId, "lastInteractionAt"));

		/// The markers are independent columns: writing one must not disturb the others.
		assertEquals(7, readMarker(user, cfgId, "lastMemoryExtractionAt"));
		assertEquals(11, readMarker(user, cfgId, "lastKeyframeAt"));

		/// Stale-marker reset path (checkMemoryExtractionTrigger resets to 0 when lastAt > msgSize).
		assertNotNull(applyMarker(user, cfg, "lastMemoryExtractionAt", 0));
		assertEquals(0, readMarker(user, cfgId, "lastMemoryExtractionAt"));
	}

	private BaseRecord applyMarker(BaseRecord user, BaseRecord cfg, String marker, int value) throws Exception {
		cfg.setValue(marker, value);
		return IOSystem.getActiveContext().getAccessPoint().update(user, cfg.copyRecord(new String[] {
			FieldNames.FIELD_ID, FieldNames.FIELD_OWNER_ID, FieldNames.FIELD_GROUP_ID, marker }));
	}

	private int readMarker(BaseRecord user, long cfgId, String marker) {
		Query q = QueryUtil.createQuery(OlioModelNames.MODEL_CHAT_CONFIG, FieldNames.FIELD_ID, cfgId);
		q.setRequest(new String[] { FieldNames.FIELD_ID, FieldNames.FIELD_NAME, marker });
		q.setCache(false);
		BaseRecord rec = IOSystem.getActiveContext().getAccessPoint().find(user, q);
		assertNotNull("re-read of chatConfig " + cfgId, rec);
		Integer v = rec.get(marker);
		return v == null ? -1 : v.intValue();
	}
}
