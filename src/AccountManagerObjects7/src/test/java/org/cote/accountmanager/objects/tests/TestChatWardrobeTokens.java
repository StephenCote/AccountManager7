package org.cote.accountmanager.objects.tests;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.cote.accountmanager.io.OrganizationContext;
import org.cote.accountmanager.io.ParameterList;
import org.cote.accountmanager.io.Query;
import org.cote.accountmanager.io.QueryUtil;
import org.cote.accountmanager.olio.ApparelUtil;
import org.cote.accountmanager.olio.OlioUtil;
import org.cote.accountmanager.olio.WearLevelEnumType;
import org.cote.accountmanager.olio.llm.Chat;
import org.cote.accountmanager.olio.llm.ChatWardrobeTokenUtil;
import org.cote.accountmanager.olio.llm.OpenAIMessage;
import org.cote.accountmanager.olio.llm.OpenAIRequest;
import org.cote.accountmanager.olio.llm.OpenAIResponse;
import org.cote.accountmanager.olio.schema.OlioFieldNames;
import org.cote.accountmanager.olio.schema.OlioModelNames;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.record.RecordFactory;
import org.cote.accountmanager.schema.FieldNames;
import org.junit.Test;

/// Chat wardrobe tokens, Bob (system) and Sally (user), "Wedding Day" — aiDocs/ChatWardrobeTokensDesign.md §3.6.
/// Runs against the live DB as a test user (never admin), through the real Chat.applyWardrobeTokens hook that the
/// user-message and assistant-reply paths call, and verifies every effect by re-reading the characters from the DB
/// with the cache off.
public class TestChatWardrobeTokens extends BaseTest {

	private static final String UUID_RE = "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}";

	private BaseRecord newPerson(BaseRecord user, String first, String gender) throws Exception {
		ParameterList plist = ParameterList.newParameterList(FieldNames.FIELD_PATH, "~/Characters");
		plist.parameter(FieldNames.FIELD_NAME, first + " Wardrobe " + System.currentTimeMillis());
		BaseRecord p = ioContext.getFactory().newInstance(OlioModelNames.MODEL_CHAR_PERSON, user, null, plist);
		p.set(FieldNames.FIELD_FIRST_NAME, first);
		p.set(FieldNames.FIELD_GENDER, gender);
		p.set(FieldNames.FIELD_AGE, 30);
		BaseRecord created = ioContext.getAccessPoint().create(user, p);
		assertNotNull("create " + first, created);
		return reload(user, created);
	}

	private BaseRecord reload(BaseRecord user, BaseRecord person) {
		Query q = QueryUtil.createQuery(OlioModelNames.MODEL_CHAR_PERSON, FieldNames.FIELD_OBJECT_ID, person.get(FieldNames.FIELD_OBJECT_ID));
		OlioUtil.planMost(q);
		q.setCache(false);
		BaseRecord r = ioContext.getAccessPoint().find(user, q);
		assertNotNull("reload person", r);
		return r;
	}

	/// name -> inuse for the person's ACTIVE outfit
	private Map<String, Boolean> active(BaseRecord person) {
		BaseRecord app = ApparelUtil.getWearingApparel(person);
		assertNotNull("active apparel for " + person.get(FieldNames.FIELD_FIRST_NAME), app);
		List<BaseRecord> wears = app.get(OlioFieldNames.FIELD_WEARABLES);
		return wears.stream().collect(Collectors.toMap(w -> (String) w.get(FieldNames.FIELD_NAME), w -> {
			Boolean b = w.get(OlioFieldNames.FIELD_IN_USE);
			return b != null && b;
		}, (a, b) -> a || b));
	}

	private List<String> worn(BaseRecord person) {
		return active(person).entrySet().stream().filter(Map.Entry::getValue).map(Map.Entry::getKey).sorted().collect(Collectors.toList());
	}

	private long activeCount(BaseRecord person) {
		List<BaseRecord> appl = person.get(OlioFieldNames.FIELD_STORE_APPAREL);
		return appl.stream().filter(a -> { Boolean b = a.get(OlioFieldNames.FIELD_IN_USE); return b != null && b; }).count();
	}

	@Test
	public void TestWeddingDay() throws Exception {
		OrganizationContext org = getTestOrganization("/Development/ChatWardrobe");
		BaseRecord user = ioContext.getFactory().getCreateUser(org.getAdminUser(), "chatWardrobeUser", org.getOrganizationId());
		assertNotNull(user);

		BaseRecord bob = newPerson(user, "Bob", "male");
		BaseRecord sally = newPerson(user, "Sally", "female");

		BaseRecord cfg = RecordFactory.newInstance(OlioModelNames.MODEL_CHAT_CONFIG);
		cfg.set("systemCharacter", bob);
		cfg.set("userCharacter", sally);
		Chat chat = new Chat(user, cfg, null);

		/// 1. Sally picks her own starting wardrobe (user token, embedded format with enum levels)
		String s1 = chat.applyWardrobeTokens("Getting ready! ${outfit:clothing:bra:BASE:f:breast,clothing:panties:BASE:f:hip,clothing:wedding dress:SUIT:f:torso+hip+leg,clothing:high heels:SUIT:f:foot}", true);
		logger.info("[1] " + s1);
		assertTrue("Sally's outfit token resolved: " + s1, s1.matches("(?s).*\\$\\{outfit\\." + UUID_RE + "\\.me:clothing:bra:BASE.*"));
		assertTrue("note follows: " + s1, s1.contains("<private>[wardrobe] Sally got dressed: bra, panties, wedding dress and high heels.</private>"));
		sally = reload(user, sally);
		assertEquals("Sally wears all four", List.of("bra", "high heels", "panties", "wedding dress"), worn(sally));

		/// 2. Bob's reply dresses him with BARE names, as in the design's use case
		String b1 = chat.applyWardrobeTokens("Ready when you are. ${outfit:boxers,tuxedo pants,tuxedo jacket,tuxedo shirt,shoes}", false);
		logger.info("[2] " + b1);
		assertTrue("Bob's outfit resolved: " + b1, b1.matches("(?s).*\\$\\{outfit\\." + UUID_RE + "\\.me:.*"));
		bob = reload(user, bob);
		Map<String, Boolean> bobWear = active(bob);
		logger.info("[2] Bob wears " + bobWear);
		assertTrue("'boxers' maps to the catalog's boxer shorts: " + bobWear, bobWear.containsKey("boxer shorts"));
		assertTrue("tuxedo jacket present", bobWear.containsKey("tuxedo jacket"));
		assertFalse("nothing off", bobWear.containsValue(false));
		assertEquals("exactly one active outfit", 1, activeCount(bob));

		/// 3. Sally pins a corsage on Bob (user token targeting the other character)
		String s2 = chat.applyWardrobeTokens("${outfit.you:corsage} There, perfect.", true);
		logger.info("[3] " + s2);
		assertTrue(s2, s2.contains("<private>[wardrobe] Sally puts a corsage on Bob.</private>"
			+ "<private>[wardrobe-pending:assistant] Sally put a corsage on Bob; Bob is now wearing it.</private>"));
		assertTrue("resolved with the corsage's objectId: " + s2, s2.matches("(?s)^\\$\\{outfit\\." + UUID_RE + "\\.you:jewelry:corsage:ACCESSORY:u:chest\\}.*"));
		bob = reload(user, bob);
		sally = reload(user, sally);
		assertEquals("corsage worn by Bob", Boolean.TRUE, active(bob).get("corsage"));
		assertFalse("not on Sally", active(sally).containsKey("corsage"));

		/// 4. Bob takes one layer off, then puts it back: absolute levels are recorded
		String b2 = chat.applyWardrobeTokens("${dress.me.down}", false);
		logger.info("[4a] " + b2);
		bob = reload(user, bob);
		int topNow = topLevel(bob);
		assertTrue("down resolves to an absolute level: " + b2, b2.matches("(?s)^\\$\\{dress\\." + UUID_RE + "\\.me\\.[A-Z_]+\\}.*"));
		assertTrue("note says what came off: " + b2, b2.contains("Bob took off his tuxedo"));
		assertTrue("level dropped below OVER", topNow < WearLevelEnumType.valueOf(WearLevelEnumType.OVER));
		String b3 = chat.applyWardrobeTokens("${dress.me.up}", false);
		logger.info("[4b] " + b3);
		bob = reload(user, bob);
		assertFalse("everything back on", active(bob).containsValue(false));

		/// 5. Sally sets Bob to BASE: only BASE-or-below is worn
		String s3 = chat.applyWardrobeTokens("${dress.you.BASE}", true);
		logger.info("[5] " + s3);
		bob = reload(user, bob);
		assertEquals("Bob down to his boxer shorts", List.of("boxer shorts"), worn(bob));
		assertTrue(s3, s3.contains("<private>[wardrobe] Sally dresses Bob to BASE.</private>"));
		assertTrue("result names both, floats to Bob: " + s3, s3.matches("(?s).*<private>\\[wardrobe-pending:assistant\\] Sally took off Bob's [^<]*; Bob is now wearing boxer shorts\\.</private>.*"));

		/// 6. A token that cannot be applied is left unresolved with a "Not applied" note, and changes nothing
		List<String> bobBefore = worn(bob);
		String b4 = chat.applyWardrobeTokens("${dress.me.SPARKLY}", false);
		logger.info("[6] " + b4);
		assertTrue("unresolved: " + b4, b4.startsWith("${dress.me.SPARKLY}<private>[wardrobe] Not applied: unknown layer 'SPARKLY'"));
		assertEquals("nothing changed", bobBefore, worn(reload(user, bob)));

		/// 7. Bob gives Sally NEW shoes: they need not exist on Bob, and Bob keeps his own (chat-level give)
		chat.applyWardrobeTokens("${dress.me.SUIT}", false);
		String b5 = chat.applyWardrobeTokens("Here, these are for you. ${give.you:shoes}", false);
		logger.info("[7] " + b5);
		assertTrue(b5, b5.contains("<private>[wardrobe] Bob gives Sally shoes.</private>"
			+ "<private>[wardrobe-pending:user] Bob gave Sally shoes; Sally is now wearing them.</private>"));
		assertTrue(b5, b5.matches("(?s).*\\$\\{give\\." + UUID_RE + "\\.you:clothing:shoes:[^}]+\\}.*"));
		bob = reload(user, bob);
		sally = reload(user, sally);
		assertEquals("Bob keeps his own shoes", Boolean.TRUE, active(bob).get("shoes"));
		assertEquals("Sally wears her new shoes", Boolean.TRUE, active(sally).get("shoes"));

		/// 7b. User-side give with the default target (${give:...} = the other character): Sally gives Bob a ring
		String s4 = chat.applyWardrobeTokens("${give:ring}", true);
		logger.info("[7b] " + s4);
		assertTrue(s4, s4.contains("<private>[wardrobe] Sally gives Bob a ring.</private>"
			+ "<private>[wardrobe-pending:assistant] Sally gave Bob a ring; Bob is now wearing it.</private>"));
		bob = reload(user, bob);
		assertEquals("Bob wears the ring", Boolean.TRUE, active(bob).get("ring"));
		assertFalse("Sally got no ring", active(reload(user, sally)).containsKey("ring"));

		/// 8. Idempotent: re-processing resolved content changes nothing
		List<String> before = worn(sally);
		String again = chat.applyWardrobeTokens(b5, false);
		assertEquals("resolved tokens are never re-applied", b5, again);
		assertEquals(before, worn(reload(user, sally)));

		/// 9. The in-memory chatConfig characters were refreshed for the next prompt rebuild
		BaseRecord cfgSally = cfg.get("userCharacter");
		assertEquals("chatConfig.userCharacter is the fresh record", Boolean.TRUE, active(cfgSally).get("shoes"));
	}

	private int topLevel(BaseRecord person) {
		BaseRecord app = ApparelUtil.getWearingApparel(person);
		List<BaseRecord> wears = app.get(OlioFieldNames.FIELD_WEARABLES);
		int max = 0;
		for (BaseRecord w : wears) {
			Boolean b = w.get(OlioFieldNames.FIELD_IN_USE);
			WearLevelEnumType l = w.getEnum(OlioFieldNames.FIELD_LEVEL);
			if (b != null && b && l != null) max = Math.max(max, WearLevelEnumType.valueOf(l));
		}
		return max;
	}

	/// The floating result, end to end through Chat: a user token that targets the system character keeps only
	/// the action in the user's message; the LLM is shown the result as "happening to you now" on the wire copy;
	/// handleResponse moves the result into the system character's own reply and out of the user's message.
	/// And the reverse: an assistant token on the user character floats to the user's next message.
	@Test
	public void TestFloatingResults() throws Exception {
		OrganizationContext org = getTestOrganization("/Development/ChatWardrobe");
		BaseRecord user = ioContext.getFactory().getCreateUser(org.getAdminUser(), "chatWardrobeUser", org.getOrganizationId());
		BaseRecord bob = newPerson(user, "Bob", "male");
		BaseRecord sally = newPerson(user, "Sally", "female");
		BaseRecord cfg = RecordFactory.newInstance(OlioModelNames.MODEL_CHAT_CONFIG);
		cfg.set("systemCharacter", bob);
		cfg.set("userCharacter", sally);
		Chat chat = new Chat(user, cfg, null);
		chat.applyWardrobeTokens("${outfit:clothing:boxer shorts:BASE:m:waist,clothing:shirt:SUIT:m:torso+arm}", false);

		/// User (Sally) pins a corsage on Bob: her message keeps the action, the result is pending for Bob
		String userMsg = chat.applyWardrobeTokens("${outfit.you:corsage} There, perfect.", true);
		logger.info("[float user] " + userMsg);
		assertTrue(userMsg, userMsg.contains("<private>[wardrobe] Sally puts a corsage on Bob.</private>"));
		assertTrue(userMsg, ChatWardrobeTokenUtil.hasPending(userMsg));

		OpenAIRequest req = new OpenAIRequest();
		OpenAIMessage um = new OpenAIMessage();
		um.setRole("user");
		um.setContent(userMsg);
		req.addMessage(um);

		/// Wire copy for the assistant's generation: result shown to Bob as happening now, no pending marker
		String wire = ChatWardrobeTokenUtil.filterPendingForWire(userMsg, "assistant");
		logger.info("[float wire] " + wire);
		assertTrue(wire, wire.contains("<private>[wardrobe] This is happening to you now: Sally put a corsage on Bob; Bob is now wearing it.</private>"));
		assertFalse(wire, ChatWardrobeTokenUtil.hasPending(wire));
		/// ...and the same block is NOT shown when generating for the other role
		assertFalse(ChatWardrobeTokenUtil.filterPendingForWire(userMsg, "user").contains("corsage on Bob;"));

		/// Bob's reply: handleResponse moves the result into the reply and out of Sally's message
		OpenAIResponse rep = new OpenAIResponse();
		OpenAIMessage am = new OpenAIMessage();
		am.setRole("assistant");
		am.setContent("\"Why thank you, Sally.\" ${give.you:jewelry:necklace:ACCESSORY:f:neck}");
		rep.setValue("message", am);
		chat.handleResponse(req, rep, false);
		List<OpenAIMessage> msgs = req.getMessages();
		assertEquals(2, msgs.size());
		String storedUser = msgs.get(0).getContent();
		String reply = msgs.get(1).getContent();
		logger.info("[float stored user] " + storedUser);
		logger.info("[float reply] " + reply);
		assertFalse("pending removed from Sally's message", ChatWardrobeTokenUtil.hasPending(storedUser));
		assertTrue("action stays in Sally's message", storedUser.contains("<private>[wardrobe] Sally puts a corsage on Bob.</private>"));
		assertTrue("result is in Bob's own reply, first: " + reply, reply.startsWith("<private>[wardrobe] Sally put a corsage on Bob; Bob is now wearing it.</private>"));
		/// Bob's give in the same reply: action kept in the reply, result pending for Sally
		assertTrue(reply, reply.contains("<private>[wardrobe] Bob gives Sally a necklace.</private>"
			+ "<private>[wardrobe-pending:user] Bob gave Sally a necklace; Sally is now wearing it.</private>"));

		/// Sally's next message: the necklace result floats into it and leaves Bob's reply
		String next = chat.takeWardrobePending(req, "user");
		assertEquals("<private>[wardrobe] Bob gave Sally a necklace; Sally is now wearing it.</private>", next);
		assertFalse("pending removed from Bob's reply", ChatWardrobeTokenUtil.hasPending(req.getMessages().get(1).getContent()));
		assertEquals("taken once", "", chat.takeWardrobePending(req, "user"));
		assertEquals("Sally really wears it", Boolean.TRUE, active(reload(user, sally)).get("necklace"));
		assertEquals("Bob really wears the corsage", Boolean.TRUE, active(reload(user, bob)).get("corsage"));
	}

	@Test
	public void TestParseAndHelpers() {
		assertTrue(ChatWardrobeTokenUtil.hasTokens("x ${dress.me.up} y"));
		assertFalse(ChatWardrobeTokenUtil.hasTokens("x ${image.selfie} y"));
		assertEquals("boxer shorts", ChatWardrobeTokenUtil.nearestCatalogName("boxers", "male"));
		assertEquals(null, ChatWardrobeTokenUtil.nearestCatalogName("zzzz", "male"));
	}
}
