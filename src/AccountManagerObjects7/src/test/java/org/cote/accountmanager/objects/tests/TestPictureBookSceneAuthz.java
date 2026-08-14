package org.cote.accountmanager.objects.tests;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.Arrays;
import java.util.UUID;

import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.io.OrganizationContext;
import org.cote.accountmanager.io.ParameterList;
import org.cote.accountmanager.io.Query;
import org.cote.accountmanager.io.QueryUtil;
import org.cote.accountmanager.olio.llm.SummarizeProgress;
import org.cote.accountmanager.olio.picturebook.PictureBookCancelRegistry;
import org.cote.accountmanager.olio.picturebook.PictureBookException;
import org.cote.accountmanager.olio.picturebook.PictureBookUtil;
import org.cote.accountmanager.olio.picturebook.PictureBookUtil.SceneAccessType;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.record.RecordFactory;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.schema.type.GroupEnumType;
import org.cote.accountmanager.schema.type.PermissionEnumType;
import org.cote.accountmanager.cache.CacheUtil;
import org.cote.accountmanager.util.JSONUtil;
import org.junit.Test;

/**
 * Coverage for the two live PictureBook authorization defects hoisted out of PictureBook2Plan.md
 * phase 4 (plan §5.6, Appendix A "Hoist out of phase 4"):
 *
 * <ol>
 *   <li><b>Defect 1</b> — {@code POST /{key}/cancel} discarded its principal and looked the key up
 *       in a static, process-wide, client-key-addressed map, so any authenticated user could cancel
 *       any other user's in-flight extraction. Now {@link PictureBookCancelRegistry}, keyed by
 *       (principal, key). Exercised here directly at the registry level — no HTTP needed.</li>
 *   <li><b>Defect 2</b> — the scene-addressed entry points resolved a scene note by objectId and
 *       never resolved, let alone authorized, its owning book. Now
 *       {@link PictureBookUtil#authorizeSceneAccess}.</li>
 * </ol>
 *
 * <p><b>No LLM and no Stable Diffusion are required.</b> Every assertion here is on the DENIAL
 * path, which returns before any generation is attempted. The permit path of
 * {@code generateSceneImage}/{@code regenerateBlurb} (i.e. an actual image/blurb) is NOT covered by
 * this class — that needs live SD/Ollama and lives in TestPictureBookCustom / TestPictureBookUtilE2E.
 * What IS covered on the permit side is that the owner passes the new authorization gate itself
 * ({@code authorizeSceneAccess} returns the scene, and {@code setSceneStatus} still writes).
 *
 * <p>Runs against the live Postgres backend like every other Objects7 test. The acting subjects are
 * two ordinary test users; the org admin is used only to provision them and to write grants (which
 * is an admin operation by definition), never as the subject under test.
 */
public class TestPictureBookSceneAuthz extends BaseTest {

	private static final String ORG_PATH = "/Development/PictureBook AuthZ Tests";

	/** Book owner — the entitled user. */
	private static final String USER_A = "pbauthzowner";
	/** A second ordinary user of the same organization, entitled to nothing of A's. */
	private static final String USER_B = "pbauthzother";

	private OrganizationContext org;
	private BaseRecord userA;
	private BaseRecord userB;

	private void users() {
		org = getTestOrganization(ORG_PATH);
		userA = getCreateUser(USER_A, org);
		userB = getCreateUser(USER_B, org);
		assertNotNull("Failed to provision " + USER_A, userA);
		assertNotNull("Failed to provision " + USER_B, userB);
	}

	private long orgId(BaseRecord user) {
		return ((Number) user.get(FieldNames.FIELD_ORGANIZATION_ID)).longValue();
	}

	/** A book group holder: the book group itself plus its Scenes sub-group and one scene note. */
	private static final class Book {
		BaseRecord bookGroup;
		BaseRecord scenesGroup;
		BaseRecord scene;
		String sceneObjectId;
	}

	/**
	 * Build the exact storage shape {@code createFromScenes} produces —
	 * {@code ~/Data/PictureBooks/{book}/Scenes/{scene note}} — owned by {@code owner}. No LLM
	 * involved: the scene note's text is the same JSON blob shape the extraction would have written.
	 */
	private Book makeBook(BaseRecord owner, String bookName) throws Exception {
		Book b = new Book();
		long oid = orgId(owner);
		String bookPath = "~/Data/PictureBooks/" + bookName;
		b.bookGroup = IOSystem.getActiveContext().getPathUtil().makePath(owner, ModelNames.MODEL_GROUP,
			bookPath, GroupEnumType.DATA.toString(), oid);
		assertNotNull("Failed to create book group " + bookPath, b.bookGroup);
		b.scenesGroup = IOSystem.getActiveContext().getPathUtil().makePath(owner, ModelNames.MODEL_GROUP,
			bookPath + "/Scenes", GroupEnumType.DATA.toString(), oid);
		assertNotNull("Failed to create Scenes group", b.scenesGroup);

		ParameterList plist = ParameterList.newParameterList(FieldNames.FIELD_PATH, bookPath + "/Scenes");
		plist.parameter(FieldNames.FIELD_NAME, "Scene 1");
		BaseRecord note = IOSystem.getActiveContext().getFactory().newInstance(ModelNames.MODEL_NOTE, owner, null, plist);
		note.set("text", "{\"title\":\"Scene 1\",\"setting\":\"a quiet room\",\"action\":\"nothing happens\",\"mood\":\"calm\"}");
		b.scene = IOSystem.getActiveContext().getAccessPoint().create(owner, note);
		assertNotNull("Failed to create the scene note", b.scene);
		b.sceneObjectId = b.scene.get(FieldNames.FIELD_OBJECT_ID);
		assertNotNull("Scene note has no objectId", b.sceneObjectId);
		return b;
	}

	/**
	 * Reads back the persisted "status" value out of the scene note's text JSON, or null.
	 * {@code text} is not a query field on data.note, so it must be requested explicitly
	 * (findByObjectId's default projection would return it as null); cache is off so a write made
	 * moments earlier in the same process is actually observed.
	 */
	private String readSceneStatus(BaseRecord reader, String sceneObjectId) {
		Query q = QueryUtil.createQuery(ModelNames.MODEL_NOTE, FieldNames.FIELD_OBJECT_ID, sceneObjectId);
		q.field(FieldNames.FIELD_ORGANIZATION_ID, reader.get(FieldNames.FIELD_ORGANIZATION_ID));
		q.setRequest(new String[] { FieldNames.FIELD_ID, FieldNames.FIELD_OBJECT_ID, FieldNames.FIELD_NAME,
			FieldNames.FIELD_ORGANIZATION_ID, FieldNames.FIELD_GROUP_ID, "text" });
		q.setCache(false);
		BaseRecord scene = IOSystem.getActiveContext().getAccessPoint().find(reader, q);
		if (scene == null) return null;
		String text = scene.get("text");
		if (text == null || text.isEmpty()) return null;
		java.util.Map<String, Object> m = JSONUtil.getMap(text.getBytes(), String.class, Object.class);
		Object s = (m != null ? m.get("status") : null);
		return (s != null ? s.toString() : null);
	}

	/** Runs body, requiring a PictureBookException, and returns its status. */
	private int expectDenied(String what, Runnable body) {
		try {
			body.run();
		} catch (PictureBookException pbe) {
			logger.info(what + " denied with status " + pbe.getStatus() + " (" + pbe.getMessage() + ")");
			return pbe.getStatus();
		}
		fail(what + " was NOT denied — it completed without throwing PictureBookException");
		return -1;
	}

	// ─────────────────────────────────────────────────────────────────────────
	// DEFECT 2 — scene-addressed endpoints must authorize the owning book
	// ─────────────────────────────────────────────────────────────────────────

	/**
	 * The core case: user B, who is entitled to nothing of user A's book, must be denied on a scene
	 * belonging to A; user A must be permitted on the same scene.
	 *
	 * <p>Denial is asserted three ways so it can't be satisfied by an incidental failure: the
	 * authorization utility throws, the two write entry points reachable without any LLM/SD call
	 * ({@code setSceneStatus}) throw, and the scene's persisted status is unchanged afterwards.
	 */
	@Test
	public void TestSceneAuthzDeniesUnentitledUserAndPermitsOwner() throws Exception {
		users();
		Book book = makeBook(userA, "authz-" + UUID.randomUUID().toString().substring(0, 8));

		/// Owner side: the new gate must let the book's owner through, for both access modes.
		BaseRecord asOwnerRead = PictureBookUtil.authorizeSceneAccess(userA, book.sceneObjectId, SceneAccessType.READ);
		assertNotNull("The book owner must be permitted READ on their own scene", asOwnerRead);
		BaseRecord asOwnerWrite = PictureBookUtil.authorizeSceneAccess(userA, book.sceneObjectId, SceneAccessType.WRITE);
		assertNotNull("The book owner must be permitted WRITE on their own scene", asOwnerWrite);
		assertEquals("authorizeSceneAccess must return the addressed scene", book.sceneObjectId,
			asOwnerWrite.get(FieldNames.FIELD_OBJECT_ID));

		/// Owner side, end to end: the guarded write still works.
		PictureBookUtil.setSceneStatus(userA, book.sceneObjectId, "accepted");
		assertEquals("The owner's setSceneStatus must still persist", "accepted",
			readSceneStatus(userA, book.sceneObjectId));

		/// Foreign side: every scene-addressed path must refuse user B.
		expectDenied("authorizeSceneAccess(B, READ)",
			() -> PictureBookUtil.authorizeSceneAccess(userB, book.sceneObjectId, SceneAccessType.READ));
		expectDenied("authorizeSceneAccess(B, WRITE)",
			() -> PictureBookUtil.authorizeSceneAccess(userB, book.sceneObjectId, SceneAccessType.WRITE));
		expectDenied("setSceneStatus(B)",
			() -> PictureBookUtil.setSceneStatus(userB, book.sceneObjectId, "skipped"));

		/// ...and B's attempt must have changed nothing.
		assertEquals("B's denied setSceneStatus must not have modified the scene", "accepted",
			readSceneStatus(userA, book.sceneObjectId));
	}

	/**
	 * The discriminating case, and the one that proves the check is a <b>book-level</b> check rather
	 * than a restatement of the note-level PBAC that {@code AccessPoint.find} already performs.
	 *
	 * <p>User B is granted Read+Update on the book's {@code Scenes} group — so B genuinely CAN read
	 * and write the scene note through {@code AccessPoint} — but is granted nothing on the book
	 * group above it. Before this fix that was enough to drive {@code setSceneStatus},
	 * {@code regenerateBlurb}, {@code generateSceneImage} and {@code prepare-images} on someone
	 * else's book. It must now be a 403.
	 */
	@Test
	public void TestSceneAuthzDeniesSceneReaderWithNoRightsOnTheBook() throws Exception {
		users();
		Book book = makeBook(userA, "authz-scoped-" + UUID.randomUUID().toString().substring(0, 8));

		/// Grant B on the Scenes group ONLY — deliberately not on book.bookGroup.
		IOSystem.getActiveContext().getAuthorizationUtil().setEntitlement(org.getAdminUser(), userB,
			new BaseRecord[] { book.scenesGroup }, new String[] { "Read", "Update" },
			new String[] { PermissionEnumType.DATA.toString(), PermissionEnumType.GROUP.toString() });
		CacheUtil.clearCache();

		/// Precondition: the grant really did make the scene note reachable for B. If this fails the
		/// test below would pass for the wrong reason (B denied at the note, never reaching the book).
		BaseRecord bSeesScene = IOSystem.getActiveContext().getAccessPoint()
			.findByObjectId(userB, ModelNames.MODEL_NOTE, book.sceneObjectId);
		assertNotNull("Precondition: the Scenes-group grant must let B read A's scene note; without it "
			+ "this test cannot exhibit the book-level gap it exists to prove", bSeesScene);

		int status = expectDenied("authorizeSceneAccess(B-with-scene-grant, WRITE)",
			() -> PictureBookUtil.authorizeSceneAccess(userB, book.sceneObjectId, SceneAccessType.WRITE));
		assertEquals("A caller who can read the scene but has no rights on its book must get 403 "
			+ "(404 would mean the note-level check denied, not the new book-level one)", 403, status);

		assertEquals("setSceneStatus must be denied for the same reason", 403,
			expectDenied("setSceneStatus(B-with-scene-grant)",
				() -> PictureBookUtil.setSceneStatus(userB, book.sceneObjectId, "skipped")));

		assertNull("B's denied setSceneStatus must not have written a status", readSceneStatus(userA, book.sceneObjectId));

		/// And the exploit proof: nothing OTHER than the new book-level check stops B. The pre-fix
		/// code path was literally "AccessPoint.find the note, then AccessPoint.update it", and both
		/// halves still succeed for B here — so before this change B genuinely could drive A's book.
		/// data.note carries no description field, so the probe writes the same `text` field the
		/// pre-fix updateSceneTextField wrote — with a marker key, not a status, so the assertions
		/// above are unaffected.
		BaseRecord patch = RecordFactory.newInstance(ModelNames.MODEL_NOTE);
		patch.set(FieldNames.FIELD_ID, bSeesScene.get(FieldNames.FIELD_ID));
		patch.set(FieldNames.FIELD_OBJECT_ID, book.sceneObjectId);
		patch.set(FieldNames.FIELD_NAME, bSeesScene.get(FieldNames.FIELD_NAME));
		patch.set("text", "{\"probe\":\"" + USER_B + " could write this note directly\"}");
		assertNotNull("Exploit precondition: with only the Scenes-group grant, B can still WRITE A's "
			+ "scene note directly — which is exactly what the pre-fix find+update path did on B's behalf",
			IOSystem.getActiveContext().getAccessPoint().update(userB, patch));
	}

	/**
	 * {@code generateSceneImage} and {@code prepareSceneImagePrompts} must refuse BEFORE they reach
	 * their SD / Ollama calls. Proven by handing them a deliberately unreachable SD endpoint and a
	 * null chat config: if authorization ran late, the failure would be a connection/config error,
	 * not a 403.
	 *
	 * <p>The permit path of these two is not exercised here — it needs a live SD backend.
	 */
	@Test
	public void TestGenerationEntryPointsDenyBeforeAnyGeneration() throws Exception {
		users();
		Book book = makeBook(userA, "authz-gen-" + UUID.randomUUID().toString().substring(0, 8));

		IOSystem.getActiveContext().getAuthorizationUtil().setEntitlement(org.getAdminUser(), userB,
			new BaseRecord[] { book.scenesGroup }, new String[] { "Read", "Update" },
			new String[] { PermissionEnumType.DATA.toString(), PermissionEnumType.GROUP.toString() });
		CacheUtil.clearCache();

		/// A syntactically valid SDAPIEnumType and a host nothing is listening on: reaching the SD
		/// stage at all would surface as a connection error rather than the 403 asserted below.
		final String deadSdServer = "http://127.0.0.1:1";

		assertEquals("generateSceneImage must deny on the book before any SD work", 403,
			expectDenied("generateSceneImage(B)", () -> PictureBookUtil.generateSceneImage(userB,
				book.sceneObjectId, new PictureBookUtil.SceneGenerationParams(), "SWARM", deadSdServer)));

		assertEquals("regenerateBlurb must deny on the book before any LLM work", 403,
			expectDenied("regenerateBlurb(B)",
				() -> PictureBookUtil.regenerateBlurb(userB, book.sceneObjectId, null)));

		assertEquals("prepare-images must deny on the book, not silently skip the scene", 403,
			expectDenied("prepareSceneImagePrompts(B)", () -> PictureBookUtil.prepareSceneImagePrompts(
				userB, Arrays.asList(book.sceneObjectId), null, null, null)));

		assertNull("None of the denied calls may have written a status", readSceneStatus(userA, book.sceneObjectId));
	}

	/** A scene objectId that does not exist must be 404, not 403 — absence is not an authz answer. */
	@Test
	public void TestUnknownSceneIsNotFound() throws Exception {
		users();
		assertEquals("An unknown scene objectId must be 404", 404,
			expectDenied("authorizeSceneAccess(unknown)", () -> PictureBookUtil.authorizeSceneAccess(
				userA, UUID.randomUUID().toString(), SceneAccessType.WRITE)));
	}

	// ─────────────────────────────────────────────────────────────────────────
	// DEFECT 1 — the cancel registry must be principal-scoped
	// ─────────────────────────────────────────────────────────────────────────

	/**
	 * User A registers a cancel key; user B attempts to cancel it. B must fail, A's entry must
	 * survive intact, and A must still be able to cancel it afterwards.
	 *
	 * <p>Also pins the non-disclosure property: B's attempt on A's live key and anyone's attempt on
	 * a key that was never registered return the same {@code false}, so the response cannot be used
	 * to probe whether some other user has that id in flight.
	 */
	@Test
	public void TestCancelRegistryIsScopedToItsPrincipal() throws Exception {
		users();
		String key = "pbcancel-" + UUID.randomUUID().toString().substring(0, 8);

		SummarizeProgress aToken = PictureBookCancelRegistry.register(userA, key);
		assertNotNull("register must return a usable token", aToken);
		assertFalse("A fresh token must not be cancelled", aToken.isCancelled());
		assertSame("A must see its own registration", aToken, PictureBookCancelRegistry.peek(userA, key));

		/// The defect: B cancelling A's in-flight call by supplying A's key.
		assertFalse("User B must not be able to cancel user A's in-flight call",
			PictureBookCancelRegistry.cancel(userB, key));
		assertFalse("User A's cancellation token must be untouched by B's attempt", aToken.isCancelled());
		assertSame("User A's registry entry must survive B's attempt", aToken,
			PictureBookCancelRegistry.peek(userA, key));

		/// Non-disclosure: B's attempt on a live foreign key is indistinguishable from an unknown key.
		assertFalse("An unregistered key must answer exactly as a foreign key does",
			PictureBookCancelRegistry.cancel(userB, "never-registered-" + UUID.randomUUID()));

		/// A can still cancel its own call.
		assertTrue("The owner must still be able to cancel", PictureBookCancelRegistry.cancel(userA, key));
		assertTrue("The owner's token must now be cancelled", aToken.isCancelled());
		assertFalse("A second cancel of an already-cancelled token reports false",
			PictureBookCancelRegistry.cancel(userA, key));

		PictureBookCancelRegistry.unregister(userA, key, aToken);
		assertNull("unregister must clear the owner's entry", PictureBookCancelRegistry.peek(userA, key));
	}

	/**
	 * Two users may legitimately run a call under the same book/work id at the same time — the old
	 * flat map silently clobbered one registration with the other. Each must now cancel only its own.
	 */
	@Test
	public void TestCancelRegistryKeepsTwoPrincipalsOnTheSameKeyApart() throws Exception {
		users();
		String key = "pbshared-" + UUID.randomUUID().toString().substring(0, 8);

		SummarizeProgress aToken = PictureBookCancelRegistry.register(userA, key);
		SummarizeProgress bToken = PictureBookCancelRegistry.register(userB, key);
		assertSame("A's registration must not have been clobbered by B's", aToken,
			PictureBookCancelRegistry.peek(userA, key));
		assertSame("B's registration must be its own", bToken, PictureBookCancelRegistry.peek(userB, key));

		assertTrue(PictureBookCancelRegistry.cancel(userB, key));
		assertTrue("B cancelled its own call", bToken.isCancelled());
		assertFalse("...and only its own", aToken.isCancelled());

		PictureBookCancelRegistry.unregister(userA, key, aToken);
		PictureBookCancelRegistry.unregister(userB, key, bToken);
		assertNull(PictureBookCancelRegistry.peek(userA, key));
		assertNull(PictureBookCancelRegistry.peek(userB, key));
	}

	/**
	 * The map must not leak in a long-running Tomcat: {@code unregister} both removes the entry and
	 * refuses to evict a newer registration made under the same composite key by a slower caller
	 * finishing late.
	 */
	@Test
	public void TestCancelRegistryCleansUpWithoutEvictingANewerRegistration() throws Exception {
		users();
		String key = "pbleak-" + UUID.randomUUID().toString().substring(0, 8);
		int baseline = PictureBookCancelRegistry.size();

		SummarizeProgress first = PictureBookCancelRegistry.register(userA, key);
		assertEquals("One registration must add exactly one entry", baseline + 1, PictureBookCancelRegistry.size());

		/// A second call by the same user under the same key replaces the entry.
		SummarizeProgress second = PictureBookCancelRegistry.register(userA, key);
		assertEquals("A re-registration must not add a second entry", baseline + 1, PictureBookCancelRegistry.size());
		assertSame(second, PictureBookCancelRegistry.peek(userA, key));

		/// The first (stale) caller's finally-block must not evict the live second registration.
		PictureBookCancelRegistry.unregister(userA, key, first);
		assertSame("A stale unregister must not evict the newer registration", second,
			PictureBookCancelRegistry.peek(userA, key));

		PictureBookCancelRegistry.unregister(userA, key, second);
		assertNull(PictureBookCancelRegistry.peek(userA, key));
		assertEquals("The registry must return to its baseline size", baseline, PictureBookCancelRegistry.size());
	}
}
