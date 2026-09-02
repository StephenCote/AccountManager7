package org.cote.accountmanager.objects.tests;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.io.OrganizationContext;
import org.cote.accountmanager.io.ParameterList;
import org.cote.accountmanager.io.Query;
import org.cote.accountmanager.io.QueryUtil;
import org.cote.accountmanager.olio.OlioContext;
import org.cote.accountmanager.olio.OlioContextUtil;
import org.cote.accountmanager.olio.picturebook.PbBookUtil;
import org.cote.accountmanager.olio.picturebook.PbOlioContextUtil;
import org.cote.accountmanager.olio.picturebook.PictureBookUtil;
import org.cote.accountmanager.olio.schema.OlioModelNames;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.schema.type.GroupEnumType;
import org.cote.accountmanager.util.JSONUtil;
import org.junit.Before;
import org.junit.Test;

/**
 * SECURITY reproduction — the horizontal (same-org) IDOR that the "Issue 6" {@code listCharacters}
 * change introduced, and the proof its fix closes it. LLM-free: it exercises only group resolution and
 * authorization, so no Ollama and no SD host are touched. Gated behind {@code PICTUREBOOK_E2E} because
 * building two real PB2 book worlds runs the (non-LLM) Olio world seed.
 *
 * <p><b>The vulnerability.</b> The "Issue 6" fix changed {@code listCharacters} to read the characters
 * group from a {@code charsGroupPath} string persisted in the book's {@code .pictureBookMeta} note. That
 * note lives in a {@code data.note} <b>owned by the requesting user</b>, so its {@code text} is rewritable
 * by that user through the generic {@code PATCH /rest/model} route. {@code listCharacters} then fed that
 * user-controlled path to {@code PathUtil.findPath} (which does NO read-authorization on the
 * {@code doCreate=false} branch) and to the PBAC-BYPASSING {@code Search.findRecords} (bounded only by
 * {@code groupId}+{@code organizationId}, and {@code AccessPoint.list} is not a per-record boundary
 * either). A user could therefore rewrite their own book's meta to point at another same-org user's world
 * population group and read that victim's characters (name/gender/portrait/apparel). Cross-tenant is
 * blocked by the org scope; same-org horizontal was not.
 *
 * <p><b>The fix (this test proves it).</b> {@code listCharacters} now derives the characters group
 * SERVER-SIDE from a trusted anchor — the book record's own world ({@code world.population.path}) —
 * resolving the pb2 book only through {@code PbBookUtil.readBook} → {@code AccessPoint.find}, which runs
 * per-record {@code canRead}. The user-writable {@code charsGroupPath} is never fed to the query (only
 * compared, for a tamper-warning log). The final charPerson read additionally runs through
	 * {@code AccessPoint.list} (the PBAC-gated path) instead of the raw, PBAC-bypassing
	 * {@code Search.findRecords}: a defense-in-depth group-level read check on whatever group the trusted
	 * derivation selected. In this test that derived group is always the caller's OWN group, so the check
	 * PERMITs; the victim's data is excluded upstream at the derivation layer, and the {@code AccessPoint.list}
	 * check would DENY only if a caller ever queried a group it lacks read on (which the trusted derivation
	 * prevents from ever being the victim's group). So:
 * <ul>
 *   <li><b>Positive:</b> an untampered book still lists its real characters (read path == write path).</li>
 *   <li><b>Pre-fix sensitivity:</b> the exact removed read path (read {@code charsGroupPath} → findPath →
 *       bypass search), reproduced inline against the tampered note, DISCLOSES the victim's characters —
 *       demonstrating the assertions below are bug-sensitive and would fail without the fix.</li>
 *   <li><b>Negative A:</b> with {@code charsGroupPath} tampered to the victim's group, the fixed
 *       {@code listCharacters} ignores it and returns the caller's OWN characters — never the victim's.</li>
 *   <li><b>Negative B:</b> with the {@code pb2BookObjectId} hint ALSO tampered to the victim's real book,
 *       {@code readBook} denies the cross-user read, so the fix falls back to the caller's own (empty)
 *       legacy characters group — again disclosing none of the victim's characters.</li>
 * </ul>
 *
 * <p>Non-admin actors throughout; no schema reset.
 */
public class TestPictureBookListCharactersIdor extends BaseTest {

	/** Reuse the already-seeded Books universe org (avoids the multi-minute universe seed). */
	private static final String ORG_A = "/Development/World Building";

	@Before
	public void resetSetup() {
		OlioContextUtil.clearCache();
		IOSystem.getActiveContext().getAccessPoint().setPermitBulkContainerApproval(false);
	}

	private OrganizationContext org() {
		return getTestOrganization(ORG_A);
	}

	private long orgId(BaseRecord user) {
		return ((Number) user.get(FieldNames.FIELD_ORGANIZATION_ID)).longValue();
	}

	/** A non-admin, ordinary user. */
	private BaseRecord user(String name) {
		OrganizationContext o = org();
		BaseRecord u = ioContext.getFactory().getCreateUser(o.getAdminUser(), name, o.getOrganizationId());
		assertNotNull("Failed to resolve non-admin test user " + name, u);
		return u;
	}

	/**
	 * Resolve a book world's population group path via the book context's world → {@code population.path};
	 * re-queries (PBAC-bypass, uncached) if the in-memory world was loaded shallow. This is the group the
	 * production write path routes charPersons into.
	 */
	private String populationPath(BaseRecord user, OlioContext ctx, long orgId) {
		BaseRecord world = ctx.getWorld();
		assertNotNull("PB2 book world must exist", world);
		String popPath = world.get("population.path");
		if (popPath == null || popPath.isBlank()) {
			String worldObjId = world.get(FieldNames.FIELD_OBJECT_ID);
			Query wq = QueryUtil.createQuery(OlioModelNames.MODEL_WORLD, FieldNames.FIELD_OBJECT_ID, worldObjId);
			wq.field(FieldNames.FIELD_ORGANIZATION_ID, orgId);
			wq.setRequest(new String[] { FieldNames.FIELD_ID, FieldNames.FIELD_OBJECT_ID, "population.path" });
			wq.setCache(false);
			BaseRecord full = IOSystem.getActiveContext().getSearch().findRecord(wq);
			assertNotNull("World must re-resolve for population.path projection", full);
			popPath = full.get("population.path");
		}
		assertNotNull("World must expose a population.path", popPath);
		assertFalse("population.path must not be blank", popPath.isBlank());
		return popPath;
	}

	/** Create a minimal charPerson in {@code groupPath}, exactly the production write shape minus LLM fields. */
	private String createCharPersonIn(BaseRecord user, String groupPath, String name, String gender) throws Exception {
		ParameterList plist = ParameterList.newParameterList(FieldNames.FIELD_PATH, groupPath);
		plist.parameter(FieldNames.FIELD_NAME, name);
		BaseRecord cp = IOSystem.getActiveContext().getFactory().newInstance(
			OlioModelNames.MODEL_CHAR_PERSON, user, null, plist);
		cp.set(FieldNames.FIELD_NAME, name);
		cp.set("gender", gender);
		BaseRecord created = IOSystem.getActiveContext().getAccessPoint().create(user, cp);
		assertNotNull("charPerson '" + name + "' must be created in " + groupPath, created);
		return created.get(FieldNames.FIELD_OBJECT_ID);
	}

	/** Count charPerson rows physically present in the group at {@code groupPath} (PBAC-bypass, uncached). */
	private int countCharPersonsInGroup(BaseRecord user, String groupPath, long orgId) {
		BaseRecord grp = IOSystem.getActiveContext().getPathUtil().findPath(user,
			ModelNames.MODEL_GROUP, groupPath, GroupEnumType.DATA.toString(), orgId);
		if (grp == null) return 0;
		Query q = QueryUtil.createQuery(OlioModelNames.MODEL_CHAR_PERSON, FieldNames.FIELD_GROUP_ID, grp.get(FieldNames.FIELD_ID));
		q.field(FieldNames.FIELD_ORGANIZATION_ID, orgId);
		q.setCache(false);
		return IOSystem.getActiveContext().getSearch().count(q);
	}

	/** Create the legacy book group {@code ~/Data/PictureBooks/{bookName}} and return it. */
	private BaseRecord ensureLegacyBookGroup(BaseRecord user, String bookName, long orgId) {
		String bookPath = "~/Data/PictureBooks/" + bookName;
		BaseRecord grp = IOSystem.getActiveContext().getPathUtil().makePath(user,
			ModelNames.MODEL_GROUP, bookPath, GroupEnumType.DATA.toString(), orgId);
		assertNotNull("Legacy book group must be created at " + bookPath, grp);
		return grp;
	}

	/** Write (or rewrite) the user-owned {@code .pictureBookMeta} note's text — the exact surface the attack PATCHes. */
	private BaseRecord writeMetaNote(BaseRecord user, String bookGroupPath, Map<String, Object> metaMap) throws Exception {
		String json = JSONUtil.exportObject(metaMap);
		BaseRecord existing = readMetaNote(user, bookGroupPath);
		if (existing != null) {
			existing.set("text", json);
			assertNotNull("meta note update must succeed",
				IOSystem.getActiveContext().getAccessPoint().update(user, existing));
			return existing;
		}
		ParameterList plist = ParameterList.newParameterList(FieldNames.FIELD_PATH, bookGroupPath);
		plist.parameter(FieldNames.FIELD_NAME, ".pictureBookMeta");
		BaseRecord note = IOSystem.getActiveContext().getFactory().newInstance(ModelNames.MODEL_NOTE, user, null, plist);
		note.set("text", json);
		BaseRecord created = IOSystem.getActiveContext().getAccessPoint().create(user, note);
		assertNotNull("meta note create must succeed", created);
		return created;
	}

	/** Read the user-owned {@code .pictureBookMeta} note (uncached), mirroring PictureBookUtil.loadMeta. */
	private BaseRecord readMetaNote(BaseRecord user, String bookGroupPath) {
		long orgId = orgId(user);
		BaseRecord grp = IOSystem.getActiveContext().getPathUtil().findPath(user,
			ModelNames.MODEL_GROUP, bookGroupPath, GroupEnumType.DATA.toString(), orgId);
		if (grp == null) return null;
		Query q = QueryUtil.createQuery(ModelNames.MODEL_NOTE, FieldNames.FIELD_GROUP_ID, grp.get(FieldNames.FIELD_ID));
		q.field(FieldNames.FIELD_NAME, ".pictureBookMeta");
		q.field(FieldNames.FIELD_ORGANIZATION_ID, orgId);
		q.planMost(true);
		q.setCache(false);
		return IOSystem.getActiveContext().getAccessPoint().find(user, q);
	}

	/**
	 * Reproduce the meta-trusting read pattern that an intermediate (uncommitted) iteration of this
	 * change introduced and the security hardening removed before commit — it was never in HEAD: read
	 * {@code charsGroupPath} straight out of the user-owned meta note, resolve it with an unauthorized
	 * {@code findPath}, and query charPersons via the PBAC-bypassing {@code Search.findRecords}. Returns
	 * the names it would have disclosed — used only to prove the vulnerability was real and the fixed-path
	 * assertions below are bug-sensitive.
	 */
	@SuppressWarnings("unchecked")
	private Set<String> preFixReadPath_charNames(BaseRecord user, String bookGroupPath, long orgId) {
		Set<String> names = new HashSet<>();
		BaseRecord note = readMetaNote(user, bookGroupPath);
		assertNotNull("meta note must exist for the pre-fix reproduction", note);
		String metaJson = note.get("text");
		Map<String, Object> meta = JSONUtil.getMap(metaJson.getBytes(), String.class, Object.class);
		String charsGroupPath = (String) meta.get("charsGroupPath");
		assertNotNull("pre-fix path reads charsGroupPath from the note", charsGroupPath);
		BaseRecord charsGroup = IOSystem.getActiveContext().getPathUtil().findPath(user,
			ModelNames.MODEL_GROUP, charsGroupPath, GroupEnumType.DATA.toString(), orgId);
		if (charsGroup == null) return names;
		Query q = QueryUtil.createQuery(OlioModelNames.MODEL_CHAR_PERSON, FieldNames.FIELD_GROUP_ID, charsGroup.get(FieldNames.FIELD_ID));
		q.field(FieldNames.FIELD_ORGANIZATION_ID, orgId);
		q.planMost(true);
		q.setCache(false);
		BaseRecord[] chars = IOSystem.getActiveContext().getSearch().findRecords(q);
		if (chars != null) for (BaseRecord cp : chars) names.add((String) cp.get(FieldNames.FIELD_NAME));
		return names;
	}

	private Set<String> namesOf(List<Map<String, Object>> listed) {
		Set<String> s = new HashSet<>();
		for (Map<String, Object> e : listed) s.add((String) e.get("name"));
		return s;
	}

	@Test
	public void listCharacters_isNotAHorizontalIdor() throws Exception {
		assumeTrue("Set PICTUREBOOK_E2E to run this LLM-free PB2 IDOR reproduction (builds two book worlds)",
			System.getenv("PICTUREBOOK_E2E") != null);

		String dataPath = testProperties.getProperty("test.datagen.path");
		assertNotNull("test.datagen.path must be set", dataPath);
		long ts = System.currentTimeMillis();

		// ── Two ordinary, distinct same-org users. ───────────────────────────────────────────────────────
		BaseRecord attacker = user("pbIdorAtk" + ts);
		BaseRecord victim = user("pbIdorVic" + ts);
		long orgId = orgId(attacker);
		assertEquals("both users must be in the same organization (this is the SAME-ORG horizontal case)",
			orgId, orgId(victim));
		assertNotEquals("attacker and victim must be different users",
			attacker.get(FieldNames.FIELD_OBJECT_ID), victim.get(FieldNames.FIELD_OBJECT_ID));

		// ── VICTIM's real PB2 book + characters, in the victim's world population group. ───────────────────
		String vSlug = "pb-idor-victim-" + ts;
		BaseRecord victimBook = PbBookUtil.createBook(victim, dataPath, vSlug, "Victim Book " + ts);
		assertNotNull("victim PB2 book must be created", victimBook);
		String victimBookObjectId = victimBook.get(FieldNames.FIELD_OBJECT_ID);
		OlioContext vCtx = PbOlioContextUtil.getCreateBookContext(victim, dataPath, vSlug);
		String vPopPath = populationPath(victim, vCtx, orgId);
		String vName1 = "Victim Bramble " + ts;
		String vName2 = "Secret Thorne " + ts;
		createCharPersonIn(victim, vPopPath, vName1, "FEMALE");
		createCharPersonIn(victim, vPopPath, vName2, "MALE");
		int vCount = countCharPersonsInGroup(victim, vPopPath, orgId);
		assertEquals("victim population group must hold exactly the 2 victim characters", 2, vCount);
		logger.info("Victim book={} pop={} chars={}", victimBookObjectId, vPopPath, vCount);

		// ── ATTACKER's own real PB2 book + characters, in the attacker's own world population group. ───────
		String aSlug = "pb-idor-attacker-" + ts;
		BaseRecord attackerBook = PbBookUtil.createBook(attacker, dataPath, aSlug, "Attacker Book " + ts);
		assertNotNull("attacker PB2 book must be created", attackerBook);
		String attackerBookObjectId = attackerBook.get(FieldNames.FIELD_OBJECT_ID);
		OlioContext aCtx = PbOlioContextUtil.getCreateBookContext(attacker, dataPath, aSlug);
		String aPopPath = populationPath(attacker, aCtx, orgId);
		assertNotEquals("attacker and victim must have DIFFERENT population groups", aPopPath, vPopPath);
		String aName1 = "Mira Vance " + ts;
		String aName2 = "Aldous Vance " + ts;
		createCharPersonIn(attacker, aPopPath, aName1, "FEMALE");
		createCharPersonIn(attacker, aPopPath, aName2, "MALE");
		assertEquals("attacker population group must hold exactly the 2 attacker characters",
			2, countCharPersonsInGroup(attacker, aPopPath, orgId));

		// ── ATTACKER's legacy book group + its user-owned .pictureBookMeta note (the PATCH-able surface). ──
		String bookName = "Attacker Legacy " + ts;
		BaseRecord legacyGroup = ensureLegacyBookGroup(attacker, bookName, orgId);
		String legacyGroupPath = legacyGroup.get(FieldNames.FIELD_PATH);
		String legacyGroupObjectId = legacyGroup.get(FieldNames.FIELD_OBJECT_ID);

		// Honest, untampered meta first: points at the attacker's OWN pb2 book and OWN population group.
		Map<String, Object> honest = new LinkedHashMap<>();
		honest.put("pb2BookObjectId", attackerBookObjectId);
		honest.put("charsGroupPath", aPopPath);
		writeMetaNote(attacker, legacyGroupPath, honest);

		// ══ POSITIVE: an untampered book still lists its OWN characters (read path == write path). ═════════
		List<Map<String, Object>> listedHonest = PictureBookUtil.listCharacters(attacker, legacyGroupObjectId);
		assertNotNull("listCharacters must not return null", listedHonest);
		Set<String> honestNames = namesOf(listedHonest);
		logger.info("POSITIVE untampered listCharacters -> {}", honestNames);
		assertEquals("untampered listCharacters must return the attacker's own 2 characters", 2, listedHonest.size());
		assertTrue("attacker char '" + aName1 + "' must be listed, got " + honestNames, honestNames.contains(aName1));
		assertTrue("attacker char '" + aName2 + "' must be listed, got " + honestNames, honestNames.contains(aName2));
		assertFalse("victim char must NEVER appear even untampered", honestNames.contains(vName1));
		assertFalse("victim char must NEVER appear even untampered", honestNames.contains(vName2));

		// ══ THE ATTACK: rewrite the attacker-owned meta note to point charsGroupPath at the VICTIM's group.
		//    This is exactly what a PATCH /rest/model on the note's text achieves. ═════════════════════════
		Map<String, Object> tamperedPathOnly = new LinkedHashMap<>();
		tamperedPathOnly.put("pb2BookObjectId", attackerBookObjectId); // hint left legitimate
		tamperedPathOnly.put("charsGroupPath", vPopPath);              // ← injected victim group path
		writeMetaNote(attacker, legacyGroupPath, tamperedPathOnly);

		// PRE-FIX SENSITIVITY: the meta-trusting read pattern (introduced then removed in this change,
		// never in HEAD), run against the tampered note, DISCLOSES the victim's characters. If this does
		// not disclose, the reproduction is invalid — fail loudly.
		Set<String> preFixNames = preFixReadPath_charNames(attacker, legacyGroupPath, orgId);
		logger.info("PRE-FIX read path (removed code) -> {}", preFixNames);
		assertTrue("PRE-FIX read path MUST disclose the victim's characters (this is the introduced IDOR) — "
			+ "if it does not, the reproduction failed to set up the vulnerable condition. Got " + preFixNames,
			preFixNames.contains(vName1) && preFixNames.contains(vName2));

		// NEGATIVE A: the FIXED listCharacters, same tampered note, must ignore charsGroupPath. The trusted
		// derivation still resolves the attacker's OWN population group (from the legitimate pb2 hint), so the
		// now-PBAC-gated read (AccessPoint.list -> authorizeQuery) PERMITs it and returns
		// the attacker's OWN characters — never the victim's.
		List<Map<String, Object>> listedTamperedPath = PictureBookUtil.listCharacters(attacker, legacyGroupObjectId);
		Set<String> negANames = namesOf(listedTamperedPath);
		logger.info("NEGATIVE A fixed listCharacters (charsGroupPath tampered) -> {}", negANames);
		assertFalse("FIXED listCharacters must NOT disclose victim char '" + vName1 + "' — got " + negANames,
			negANames.contains(vName1));
		assertFalse("FIXED listCharacters must NOT disclose victim char '" + vName2 + "' — got " + negANames,
			negANames.contains(vName2));
		assertTrue("FIXED listCharacters must still return the attacker's own characters, got " + negANames,
			negANames.contains(aName1) && negANames.contains(aName2));

		// ══ THE HARDER ATTACK: tamper the pb2BookObjectId hint too, to the VICTIM's REAL book id — trying to
		//    steer the server-side derivation itself at the victim's world. readBook must deny it. ══════════
		Map<String, Object> tamperedBoth = new LinkedHashMap<>();
		tamperedBoth.put("pb2BookObjectId", victimBookObjectId); // ← injected victim book id
		tamperedBoth.put("charsGroupPath", vPopPath);            // ← injected victim group path
		writeMetaNote(attacker, legacyGroupPath, tamperedBoth);

		// NEGATIVE B: readBook(attacker, victimBook) denies (canRead) inside the trusted derivation, so the
		// fix falls back to the attacker's own (empty) legacy Characters group. The PBAC-gated read
		// (AccessPoint.list) then runs against that OWN group and PERMITs, but it is empty — disclosing none
		// of the victim's characters. (The victim's group is never reached, so authorizeQuery is never even
		// asked to deny it here; the exclusion is entirely at the derivation layer.)
		List<Map<String, Object>> listedTamperedBoth = PictureBookUtil.listCharacters(attacker, legacyGroupObjectId);
		Set<String> negBNames = namesOf(listedTamperedBoth);
		logger.info("NEGATIVE B fixed listCharacters (pb2BookObjectId+charsGroupPath tampered to victim) -> {}", negBNames);
		assertFalse("FIXED listCharacters must NOT disclose victim char '" + vName1 + "' via the book-id hint — got " + negBNames,
			negBNames.contains(vName1));
		assertFalse("FIXED listCharacters must NOT disclose victim char '" + vName2 + "' via the book-id hint — got " + negBNames,
			negBNames.contains(vName2));

		logger.info("IDOR reproduction PASS: pre-fix disclosed {}, fixed disclosed none (negA={}, negB={})",
			preFixNames, negANames, negBNames);
	}
}
