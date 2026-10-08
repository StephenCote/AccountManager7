package org.cote.accountmanager.objects.tests;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.util.List;

import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.io.OrganizationContext;
import org.cote.accountmanager.io.ParameterList;
import org.cote.accountmanager.io.Query;
import org.cote.accountmanager.io.QueryUtil;
import org.cote.accountmanager.olio.schema.OlioModelNames;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.schema.FieldNames;
import org.junit.Before;
import org.junit.Test;

/**
 * {@code olio.cb.set} membership backend (audit 2026-10-07 item 11).
 * <p>
 * {@code ChapBookService} exposes only {@code GET /sets} and {@code POST /set}; there is deliberately no
 * add/remove-poem endpoint because {@code olio.cb.set.poems} is a participation list
 * ({@code participantModel cb.set.poem}) and the generic membership route
 * {@code GET /rest/authorization/olio.cb.set/{setObjectId}/member/poems/olio.cb.poem/{poemObjectId}/{true|false}}
 * ({@code AuthorizationService.enableMember}) is the contract. That route is transport over exactly one
 * call: {@code AccessPoint.member(user, set, "poems", poem, null, enable)}. This test exercises that call
 * against the live DB, so the REST contract is proven at the layer that owns it, without the servlet layer.
 * <p>
 * Proves: add returns true and the poem reads back on {@code set.poems} with a fresh (uncached) query;
 * a second add is a no-op (false); remove returns true and the poem is gone; a second remove is false;
 * and another user in the same organization can neither add to nor read the owner's set (PBAC).
 * <p>
 * First run (2026-10-07) failed at {@code newInstance} with {@code Field name was not found on model
 * olio.cb.set}: {@code setModel.json} listed {@code data.directory} in BOTH {@code likeInherits} and
 * {@code inherits}, and {@code RecordFactory.importSchema} adds the likeInherits entries to the import set
 * before walking inherits, so the real inherit was skipped as "already imported". The model resolved with
 * two fields, no identity, no table (am7db and the Docker am72db both lacked {@code a7_olio_cb_set_0_1}),
 * and {@code ChapBookService} {@code GET /sets} / {@code POST /set} could never have worked. Fixed by
 * dropping {@code likeInherits} so the model matches its sibling {@code olio.cb.poem}; the table is created
 * at the next {@code IOSystem.open()} (boot DDL, resource-driven). The persisted {@code system.modelSchema}
 * blob for {@code olio.cb.set} still carries the two-field shape; it is not consulted while the
 * resource-derived schema is cached, so it is reported, not patched, here.
 */
public class TestCbSetMembership extends BaseTest {

	private static final String ORG_PATH = "/Development/ChapBook Set Tests";

	private BaseRecord owner;
	private BaseRecord other;
	private long orgId;

	@Before
	public void setUpSetMembership() {
		OrganizationContext ctx = getTestOrganization(ORG_PATH);
		owner = IOSystem.getActiveContext().getFactory().getCreateUser(ctx.getAdminUser(), "cbSetOwnerUser", ctx.getOrganizationId());
		other = IOSystem.getActiveContext().getFactory().getCreateUser(ctx.getAdminUser(), "cbSetOtherUser", ctx.getOrganizationId());
		assertNotNull("owner must be created", owner);
		assertNotNull("other must be created", other);
		orgId = ((Number) owner.get(FieldNames.FIELD_ORGANIZATION_ID)).longValue();
	}

	@Test
	public void testAddRemovePoemOnSet() throws Exception {
		long ts = System.currentTimeMillis();
		/// Same shape as ChapBookService.createSet: ~/ChapBook default path, factory newInstance, AccessPoint.create.
		BaseRecord set = createSet(owner, "~/ChapBook", "Set " + ts, "membership test");
		assertNotNull("set must be created", set);
		String setOid = set.get(FieldNames.FIELD_OBJECT_ID);

		String poemPath = "~/Data/CbSetPoems-" + ts;
		BaseRecord poem1 = createPoem(owner, poemPath, "Set Poem One " + ts, "A line of verse,\nand then another.");
		BaseRecord poem2 = createPoem(owner, poemPath, "Set Poem Two " + ts, "The second poem,\nshort as the first.");
		assertNotNull(poem1);
		assertNotNull(poem2);

		/// The records the REST route hands to member(): findByObjectId on each side, as enableMember does.
		BaseRecord setRef = IOSystem.getActiveContext().getAccessPoint().findByObjectId(owner, OlioModelNames.MODEL_CB_SET, setOid);
		BaseRecord poem1Ref = IOSystem.getActiveContext().getAccessPoint().findByObjectId(owner, OlioModelNames.MODEL_CB_POEM, poem1.get(FieldNames.FIELD_OBJECT_ID));
		BaseRecord poem2Ref = IOSystem.getActiveContext().getAccessPoint().findByObjectId(owner, OlioModelNames.MODEL_CB_POEM, poem2.get(FieldNames.FIELD_OBJECT_ID));
		assertNotNull(setRef);
		assertNotNull(poem1Ref);
		assertNotNull(poem2Ref);

		assertEquals("new set has no poems", 0, readPoems(owner, setOid).size());

		assertTrue("add poem1", IOSystem.getActiveContext().getAccessPoint().member(owner, setRef, "poems", poem1Ref, null, true));
		assertTrue("add poem2", IOSystem.getActiveContext().getAccessPoint().member(owner, setRef, "poems", poem2Ref, null, true));
		assertFalse("adding poem1 again is a no-op", IOSystem.getActiveContext().getAccessPoint().member(owner, setRef, "poems", poem1Ref, null, true));

		List<BaseRecord> poems = readPoems(owner, setOid);
		assertEquals("both poems read back on set.poems", 2, poems.size());
		assertTrue(containsOid(poems, poem1.get(FieldNames.FIELD_OBJECT_ID)));
		assertTrue(containsOid(poems, poem2.get(FieldNames.FIELD_OBJECT_ID)));

		assertTrue("remove poem1", IOSystem.getActiveContext().getAccessPoint().member(owner, setRef, "poems", poem1Ref, null, false));
		assertFalse("removing poem1 again is a no-op", IOSystem.getActiveContext().getAccessPoint().member(owner, setRef, "poems", poem1Ref, null, false));
		poems = readPoems(owner, setOid);
		assertEquals("only poem2 remains", 1, poems.size());
		assertEquals((String) poem2.get(FieldNames.FIELD_OBJECT_ID), (String) poems.get(0).get(FieldNames.FIELD_OBJECT_ID));

		/// PBAC: another user in the same org cannot read the owner's set (so the route's findByObjectId
		/// yields null and enableMember answers false without reaching member()), and even handed the
		/// owner's record directly, member() denies the update.
		BaseRecord otherView = IOSystem.getActiveContext().getAccessPoint().findByObjectId(other, OlioModelNames.MODEL_CB_SET, setOid);
		assertTrue("another user cannot read the owner's set", otherView == null);
		BaseRecord otherPoem = createPoem(other, "~/Data/CbSetOther-" + ts, "Other Poem " + ts, "Mine,\nnot yours.");
		assertNotNull(otherPoem);
		assertFalse("another user cannot add to the owner's set",
			IOSystem.getActiveContext().getAccessPoint().member(other, setRef, "poems", otherPoem, null, true));
		assertEquals("owner's set unchanged by the denied add", 1, readPoems(owner, setOid).size());
	}

	private List<BaseRecord> readPoems(BaseRecord user, String setOid) {
		Query q = QueryUtil.createQuery(OlioModelNames.MODEL_CB_SET, FieldNames.FIELD_OBJECT_ID, setOid);
		q.field(FieldNames.FIELD_ORGANIZATION_ID, orgId);
		q.setRequest(new String[] {FieldNames.FIELD_ID, FieldNames.FIELD_OBJECT_ID, FieldNames.FIELD_NAME, "poems"});
		/// Fresh read every time: the participation list is cached with the parent and member() does not
		/// invalidate it (.claude/rules/model-api.md, "Cache invalidation does not follow nested references").
		q.setCache(false);
		BaseRecord rec = IOSystem.getActiveContext().getAccessPoint().find(user, q);
		assertNotNull("owner can read the set", rec);
		List<BaseRecord> poems = rec.get("poems");
		return poems;
	}

	private static boolean containsOid(List<BaseRecord> recs, String oid) {
		for (BaseRecord r : recs) {
			if (oid.equals(r.get(FieldNames.FIELD_OBJECT_ID))) return true;
		}
		return false;
	}

	private BaseRecord createSet(BaseRecord user, String groupPath, String name, String description) {
		try {
			ParameterList plist = ParameterList.newParameterList(FieldNames.FIELD_PATH, groupPath);
			plist.parameter(FieldNames.FIELD_NAME, name);
			BaseRecord set = IOSystem.getActiveContext().getFactory().newInstance(OlioModelNames.MODEL_CB_SET, user, null, plist);
			if (description != null) set.set("description", description);
			return IOSystem.getActiveContext().getAccessPoint().create(user, set);
		} catch (Exception e) {
			logger.error("createSet failed: {}", e.getMessage(), e);
			return null;
		}
	}

	private BaseRecord createPoem(BaseRecord user, String groupPath, String name, String text) {
		try {
			ParameterList plist = ParameterList.newParameterList(FieldNames.FIELD_PATH, groupPath);
			plist.parameter(FieldNames.FIELD_NAME, name);
			BaseRecord poem = IOSystem.getActiveContext().getFactory().newInstance(OlioModelNames.MODEL_CB_POEM, user, null, plist);
			poem.set("text", text);
			poem.set("title", name);
			return IOSystem.getActiveContext().getAccessPoint().create(user, poem);
		} catch (Exception e) {
			logger.error("createPoem failed: {}", e.getMessage(), e);
			return null;
		}
	}
}
