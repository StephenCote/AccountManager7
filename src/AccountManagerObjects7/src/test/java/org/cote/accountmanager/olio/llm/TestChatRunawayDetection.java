package org.cote.accountmanager.olio.llm;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.cote.accountmanager.objects.tests.BaseTest;
import org.cote.accountmanager.record.BaseRecord;
import org.junit.After;
import org.junit.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

/// DETERMINISTIC coverage (no live LLM, no network beyond 127.0.0.1) for Chat's runaway detector.
///
/// The defect this pins, measured 2026-10-05 on the Ourselves.doc PictureBook run
/// (extract-scenes-chunk 6/10, LiteLLM request 80b340dc-6669-44dc-be42-154cec0e9bd6): after 12
/// distinct scenes the model alternated two scene objects verbatim — 23 + 22 copies — at a steady
/// 34 tok/s until the 300s deadline. Nothing in the stream reader could tell that from a long
/// legitimate reply, so the chunk ran to the full timeout, was reported as "Request timed out ...
/// while the model was still generating; 48600 characters had been received", and was then NOT
/// retried (a timeout is never retried). Five minutes of GPU for zero scenes.
///
/// The POSITIVE case replays that very reply (the Langfuse output, byte for byte, committed under
/// test/resources/llm-fixtures/runaway/) from a raw-socket model-server stand-in that never sends
/// its terminator, so the only two things that can end the call are the requestTimeout (set far
/// away) and the detector. Observed at the socket, not via bookkeeping: EOF on the server's read
/// can only be the CLIENT closing the exchange.
///
/// The NEGATIVE case is the thing a repetition heuristic must not do: a poem with a refrain is
/// repetitive on purpose, and must stream to completion untouched.
///
/// Extends BaseTest only so the Olio model schemas are registered (OpenAIRequest's constructor goes
/// through RecordFactory). Nothing is written, no schema is reset.
public class TestChatRunawayDetection extends BaseTest {

	static final String FIXTURE = "/llm-fixtures/runaway/ourselves-chunk6-runaway.json";

	/// Well above anything the detector should need, so a return before it is the detector's doing.
	private static final int REQUEST_TIMEOUT_SECONDS = 90;
	private static final int CHUNK_CHARS = 40;

	/// A raw-socket stand-in for a model server that streams a scripted reply in Ollama NDJSON
	/// chunks (CHUNK_CHARS of content per line, a short pause between lines). With terminate=false
	/// it never sends done:true — it just stops writing after the script and blocks on read(), so the
	/// only way its loop can end is the client closing the exchange. With an endlessTail it does
	/// what a looping model does: after the script it keeps streaming that tail over and over until
	/// a write fails, so the ONLY way it ever stops generating is the client tearing the exchange
	/// down (writeFailed). sentChars is how far it had got when that happened.
	private static final class ReplayServer implements AutoCloseable {
		private final ServerSocket ss;
		private final Thread thread;
		private final String script;
		private final boolean terminate;
		private final String endlessTail;
		final CountDownLatch clientDisconnected = new CountDownLatch(1);
		volatile int sentChars = 0;
		volatile boolean writeFailed = false;
		volatile String endReason = "still-connected";

		ReplayServer(String script, boolean terminate) throws Exception {
			this(script, terminate, null);
		}

		ReplayServer(String script, boolean terminate, String endlessTail) throws Exception {
			this.script = script;
			this.terminate = terminate;
			this.endlessTail = endlessTail;
			this.ss = new ServerSocket(0, 4, InetAddress.getByName("127.0.0.1"));
			this.thread = new Thread(this::run, "replay-" + ss.getLocalPort());
			this.thread.setDaemon(true);
			this.thread.start();
		}

		String baseUrl() {
			return "http://127.0.0.1:" + ss.getLocalPort();
		}

		private static void writeChunk(OutputStream out, String json) throws Exception {
			String payload = json + "\n";
			byte[] bytes = payload.getBytes(StandardCharsets.UTF_8);
			out.write((Integer.toHexString(bytes.length) + "\r\n").getBytes(StandardCharsets.US_ASCII));
			out.write(bytes);
			out.write("\r\n".getBytes(StandardCharsets.US_ASCII));
			out.flush();
		}

		private void run() {
			ObjectMapper om = new ObjectMapper();
			try (Socket sock = ss.accept()) {
				InputStream in = sock.getInputStream();
				int[] last = new int[4];
				int n = 0;
				int b;
				while ((b = in.read()) != -1) {
					last[n % 4] = b;
					n++;
					if (n >= 4
							&& last[(n - 4) % 4] == '\r' && last[(n - 3) % 4] == '\n'
							&& last[(n - 2) % 4] == '\r' && last[(n - 1) % 4] == '\n') {
						break;
					}
				}
				OutputStream out = sock.getOutputStream();
				out.write(("HTTP/1.1 200 OK\r\n"
						+ "Content-Type: application/x-ndjson\r\n"
						+ "Transfer-Encoding: chunked\r\n"
						+ "Connection: keep-alive\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
				out.flush();
				try {
					for (int i = 0; i < script.length(); i += CHUNK_CHARS) {
						String piece = script.substring(i, Math.min(script.length(), i + CHUNK_CHARS));
						writeChunk(out, "{\"message\":{\"role\":\"assistant\",\"content\":"
								+ om.writeValueAsString(piece) + "},\"done\":false}");
						sentChars = i + piece.length();
						Thread.sleep(1);
					}
					while (endlessTail != null) {
						for (int i = 0; i < endlessTail.length(); i += CHUNK_CHARS) {
							String piece = endlessTail.substring(i, Math.min(endlessTail.length(), i + CHUNK_CHARS));
							writeChunk(out, "{\"message\":{\"role\":\"assistant\",\"content\":"
									+ om.writeValueAsString(piece) + "},\"done\":false}");
							sentChars += piece.length();
							Thread.sleep(1);
						}
					}
					if (terminate) {
						writeChunk(out, "{\"message\":{\"role\":\"assistant\",\"content\":\"\"},\"done\":true}");
						out.write("0\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
						out.flush();
					}
				} catch (Exception we) {
					/// The client closed the socket under us mid-script — the detector's abort.
					/// (An interrupt is the test's own teardown, not the client.)
					writeFailed = !(we instanceof InterruptedException);
				}
				byte[] sink = new byte[1024];
				while (in.read(sink) != -1) {
					/// never respond further; wait for the client to go away
				}
				endReason = "EOF";
			} catch (Exception e) {
				endReason = e.getClass().getSimpleName() + ": " + e.getMessage();
			} finally {
				clientDisconnected.countDown();
			}
		}

		@Override
		public void close() {
			try { ss.close(); } catch (Exception e) { /* teardown */ }
			thread.interrupt();
		}
	}

	static String loadFixtureContent() throws Exception {
		try (InputStream is = TestChatRunawayDetection.class.getResourceAsStream(FIXTURE)) {
			assertNotNull("fixture missing from test resources: " + FIXTURE, is);
			Map<?, ?> obj = new ObjectMapper().readValue(is, Map.class);
			String content = (String) obj.get("content");
			assertNotNull("fixture has no content field", content);
			return content;
		}
	}

	private static OpenAIRequest bufferModeRequest() {
		OpenAIRequest req = new OpenAIRequest();
		req.setModel("qwen3:8b");
		req.setStream(false);
		OpenAIMessage m = new OpenAIMessage();
		m.setRole("user");
		m.setContent("Extract the scenes");
		req.addMessage(m);
		return req;
	}

	private static Chat chatPointedAt(String baseUrl) {
		Chat chat = new Chat();
		chat.setServiceType(LLMServiceEnumType.OLLAMA);
		chat.setServerUrl(baseUrl);
		chat.setRequestTimeout(REQUEST_TIMEOUT_SECONDS);
		return chat;
	}

	private static String contentOf(OpenAIResponse resp) {
		BaseRecord msg = resp != null ? resp.get("message") : null;
		return msg != null ? msg.get("content") : null;
	}

	/// The recorded reply converges on its loop gradually: scenes 8 and 10 are near-duplicates of
	/// each other ("...in Lara's hands as she turns from the countertop / the island"), scene 11 is
	/// the first "Materialization of items occurs...", and from scene 12 (offset 9993) on it is the
	/// exact A/B alternation — "palms ... counter top" / "Materialization ... island countertop" —
	/// 45 times. The verbatim repetition therefore begins inside scene 10, whose BODY is already
	/// identical to every later A. That is where the detector must cut: everything before it is the
	/// model's own work (12 distinct scenes, scene 9 being the last fully distinct one), everything
	/// after it is the cycle.
	static final String LAST_DISTINCT_SCENE = "Silver web appears at the edge of a countertop indentation";
	static final String LOOP_A = "palms as she turns from the counter top";
	static final String LOOP_B = "Materialization of items occurs";
	/// One A scene (887 chars) plus one B scene (868): the block the reply repeats verbatim.
	static final int LOOP_PERIOD = 1755;

	private static void assertCutAtTheStartOfTheLoop(String kept) {
		assertTrue("the cut must keep the distinct scenes that preceded the loop (kept " + kept.length() + " chars)",
			kept.contains(LAST_DISTINCT_SCENE));
		assertFalse("the cut must land before the first verbatim A scene", kept.contains(LOOP_A));
		assertFalse("the cut must land before the first verbatim B scene", kept.contains(LOOP_B));
	}

	/// Six stanzas that each differ, sharing a four-line refrain: repetitive by design, and well
	/// over the detector's minimum length. The refrain is ~150 characters, so no 300-character
	/// window can recur verbatim — every window straddles a stanza that is unique.
	static String refrainPoem() {
		String refrain = "And still the river runs to sea,\nand still the tide comes home to me,\n"
			+ "and what was lost is lost for good,\nand what remains is understood.\n";
		String[] stanzas = {
			"The morning laid its silver down across the fields of early frost,\nand every fencepost held a crow that counted what the winter cost.\n",
			"My father's boots stood by the door, the laces stiff with last year's mud,\nhe never said the words out loud, but kept the garden as he could.\n",
			"The kitchen clock has lost an hour and nobody has set it right,\nwe eat our supper by its lie and go to bed a little late.\n",
			"There is a photograph of her in sunlight on a borrowed boat,\nthe water bright behind her hair, the summer caught inside her coat.\n",
			"The dog is buried by the wall where lilac drops its bruise-blue bloom,\nI pass it twice a day and still expect the bark to fill the room.\n",
			"So this is what the years hand back: a house, a road, a name, a hill,\nthe same four lines I cannot shake and would not shake if given will.\n"
		};
		StringBuilder sb = new StringBuilder();
		for (String s : stanzas) {
			sb.append(s).append(refrain).append("\n");
		}
		return sb.toString();
	}

	@After
	public void restoreDetector() {
		Chat.setRunawayDetectionEnabled(true);
		Chat.clearLastCallError();
	}

	/// (1) The detector on its own, over the real reply, fed in growing prefixes the way the stream
	/// reader feeds it. Pins WHERE it fires (well inside the reply, not at the end) and WHERE it
	/// cuts (before the second copy of either looped scene).
	@Test
	public void testDetectorFiresOnTheRecordedChunk6RunawayAndCutsWhereTheLoopBegan() throws Exception {
		String content = loadFixtureContent();
		assertTrue("fixture should be the ~48K-char runaway reply, got " + content.length(), content.length() > 40000);

		Chat.RunawayDetector det = new Chat.RunawayDetector();
		int firedAt = -1;
		for (int len = 0; len <= content.length(); len += CHUNK_CHARS) {
			if (det.check(content.substring(0, Math.min(len, content.length())))) {
				firedAt = Math.min(len, content.length());
				break;
			}
		}
		logger.info("[RUNAWAY-UNIT] fired at " + firedAt + " of " + content.length() + " chars; repeats="
			+ det.repeats + " period=" + det.period + " copies=" + det.periodCopies + " firstRepeatIndex=" + det.firstRepeatIndex);
		assertTrue("the detector never fired on a reply that is 45 verbatim copies of two scenes", firedAt > 0);
		assertTrue("it should fire in the first half of the reply (" + firedAt + " of " + content.length() + ")",
			firedAt < content.length() / 2);
		assertTrue("repeat count must be at least MIN_REPEATS", det.repeats >= Chat.RunawayDetector.MIN_REPEATS);
		assertEquals("the period must be one A scene plus one B scene", LOOP_PERIOD, det.period);
		assertTrue("the reply must end in at least MIN_REPEATS copies of the period", det.periodCopies >= Chat.RunawayDetector.MIN_REPEATS);
		assertTrue("firstRepeatIndex must point inside the reply", det.firstRepeatIndex > 0 && det.firstRepeatIndex < firedAt);
		/// Pins the cost of a fire: MIN_REPEATS copies past the cut, plus one period of slack for the
		/// check stride and the cut landing a little before the first clean copy. At the old 5 copies
		/// this reply took ~8.8K chars past the cut; at 3 it must take no more than ~7K.
		assertTrue("the detector must fire within MIN_REPEATS+1 periods of the cut (fired " + (firedAt - det.firstRepeatIndex)
			+ " chars past it, period " + det.period + ")",
			firedAt - det.firstRepeatIndex <= (Chat.RunawayDetector.MIN_REPEATS + 1) * LOOP_PERIOD);

		assertCutAtTheStartOfTheLoop(content.substring(0, det.firstRepeatIndex));
	}

	/// (2) The negative the heuristic must survive: a refrain is not a runaway.
	@Test
	public void testDetectorIgnoresARefrainPoem() {
		String poem = refrainPoem();
		assertTrue("poem must be long enough for the detector to look at it at all (" + poem.length() + ")",
			poem.length() >= Chat.RunawayDetector.MIN_LOOP_CHARS);
		Chat.RunawayDetector det = new Chat.RunawayDetector();
		for (int len = 0; len <= poem.length(); len += 17) {
			assertFalse("the detector fired on a poem with a refrain at " + len + " chars",
				det.check(poem.substring(0, Math.min(len, poem.length()))));
		}
		assertFalse(det.check(poem));
	}

	/// The consumer's own shape, done legitimately: a scene list whose scenes are all different but
	/// each carry the same long SD style suffix — far more than WINDOW_CHARS of verbatim text
	/// recurring far more than MIN_REPEATS times. A window-recurrence heuristic alone fires on this
	/// (measured: a 353-char suffix x8 fired at 2000 chars and cut the reply to 80 characters). The
	/// detector requires the text BETWEEN the copies to repeat too, so a list of distinct items must
	/// stream through untouched.
	static String distinctScenesWithSharedBoilerplate(int count) {
		String style = "masterpiece, best quality, ultra detailed, cinematic lighting, volumetric light, "
			+ "soft shadows, 35mm film grain, shallow depth of field, muted palette, natural skin texture, "
			+ "subtle rim light, high dynamic range, photorealistic, sharp focus on the subject, "
			+ "coherent anatomy, no text, no watermark, no logo, no border, no frame, no signature, "
			+ "editorial composition, rule of thirds, balanced negative space, award-winning photograph";
		assertTrue("the shared suffix must exceed the detector window to be a meaningful negative (" + style.length() + ")",
			style.length() > Chat.RunawayDetector.WINDOW_CHARS);
		String[] actions = {
			"Lara sets the kettle on the stove and watches the window fog", "Marcus counts the coins twice and pockets the short change",
			"The dog drags its blanket into the hallway and refuses to move", "A letter slides under the door while the house is asleep",
			"Nell climbs the fence to retrieve the kite and tears her sleeve", "The streetlight flickers as the last bus pulls away",
			"Grandfather tunes the radio to a station that no longer exists", "Rain finds the gap in the roof above the piano",
			"The twins argue over who left the gate open for the goats", "A stranger asks for directions to a road that was renamed",
			"The bakery sells its last loaf to the boy with no shoes", "Lara finds the photograph tucked inside the hymnal"
		};
		StringBuilder sb = new StringBuilder("{\n  \"scenes\": [\n");
		for (int i = 0; i < count; i++) {
			String action = actions[i % actions.length] + (i >= actions.length ? " (again, later)" : "");
			sb.append("    {\n      \"title\": \"Scene ").append(i + 1).append(": ").append(action)
				.append("\",\n      \"action\": \"").append(action).append(".\",\n      \"mood\": \"")
				.append(i % 2 == 0 ? "quiet" : "uneasy").append("\",\n      \"sdPrompt\": \"").append(action)
				.append(", ").append(style).append("\"\n    }").append(i + 1 < count ? "," : "").append("\n");
		}
		return sb.append("  ]\n}\n").toString();
	}

	/// (2b) The false positive a recurrence-only heuristic produces: distinct scenes sharing a
	/// 480-char style suffix, twelve of them — must NOT fire at any prefix.
	@Test
	public void testDetectorIgnoresDistinctScenesThatShareLongBoilerplate() {
		String json = distinctScenesWithSharedBoilerplate(12);
		assertTrue("list must be long enough for the detector to look at it (" + json.length() + ")",
			json.length() >= Chat.RunawayDetector.MIN_LOOP_CHARS);
		Chat.RunawayDetector det = new Chat.RunawayDetector();
		for (int len = 0; len <= json.length(); len += 17) {
			assertFalse("the detector fired on a list of distinct scenes with shared boilerplate at " + len + " chars",
				det.check(json.substring(0, Math.min(len, json.length()))));
		}
		assertFalse(det.check(json));
	}

	/// (2c) ...and the same shape done as a runaway — one scene object repeated verbatim — must
	/// fire, and cut back to where that scene was first emitted so the distinct scenes before it
	/// survive.
	@Test
	public void testDetectorFiresWhenOneSceneObjectIsRepeatedVerbatim() {
		String distinct = distinctScenesWithSharedBoilerplate(6);
		String prefix = distinct.substring(0, distinct.lastIndexOf("  ]"));
		String looped = "    {\n      \"title\": \"Scene 7: The kettle boils over\",\n      \"action\": \"The kettle boils over.\",\n"
			+ "      \"mood\": \"quiet\",\n      \"sdPrompt\": \"The kettle boils over, masterpiece, best quality\"\n    },\n";
		StringBuilder sb = new StringBuilder(prefix);
		for (int i = 0; i < 40; i++) sb.append(looped);
		String json = sb.toString();
		Chat.RunawayDetector det = new Chat.RunawayDetector();
		int firedAt = -1;
		for (int len = 0; len <= json.length(); len += 17) {
			if (det.check(json.substring(0, Math.min(len, json.length())))) { firedAt = Math.min(len, json.length()); break; }
		}
		logger.info("[RUNAWAY-UNIT] one-scene loop fired at " + firedAt + " of " + json.length() + " period=" + det.period
			+ " copies=" + det.periodCopies + " cut=" + det.firstRepeatIndex);
		assertTrue("a scene object repeated 40 times verbatim must fire", firedAt > 0);
		assertEquals("the period must be the repeated scene object", looped.length(), det.period);
		assertTrue("the cut must land within one probe stride of the loop's first copy (cut=" + det.firstRepeatIndex
			+ ", first copy at " + prefix.length() + ")",
			det.firstRepeatIndex >= prefix.length()
				&& det.firstRepeatIndex < prefix.length() + Chat.RunawayDetector.CUT_PROBE_STRIDE_CHARS);
		assertTrue("the cut must keep the distinct scenes whole (cut=" + det.firstRepeatIndex + ")",
			json.substring(0, det.firstRepeatIndex).startsWith(prefix));
	}

	/// A scene object whose diffusionPrompt is a long run of DISTINCT detail sentences, so the block
	/// has no internal period shorter than itself.
	private static String longSceneBlock(int targetChars) {
		StringBuilder sb = new StringBuilder("    {\n      \"title\": \"Scene 7: The kettle boils over\",\n      \"sdPrompt\": \"");
		int i = 0;
		while (sb.length() < targetChars - 40) {
			sb.append("detail ").append(i).append(": the ").append(i % 2 == 0 ? "steam" : "light")
				.append(" settles on surface number ").append(i * 7 % 101).append("; ");
			i++;
		}
		return sb.append("\"\n    },\n").toString();
	}

	/// Feeds growing prefixes the way the stream reader does, then the complete text on a fresh
	/// detector (check() skips a prefix within CHECK_STRIDE_CHARS of the last one it looked at, so
	/// the exact end is otherwise easy to miss). -1 = never fired.
	private static int firstFire(String text) {
		Chat.RunawayDetector det = new Chat.RunawayDetector();
		for (int len = 0; len < text.length(); len += 17) {
			if (det.check(text.substring(0, len))) return len;
		}
		return new Chat.RunawayDetector().check(text) ? text.length() : -1;
	}

	/// (2d) The long-period rule: a 4.5K-char scene emitted twice back to back is a runaway after
	/// the SECOND copy — waiting for a third is another minute of GPU on a period that size — and
	/// the cut still lands on the first copy so the distinct scenes before it survive.
	@Test
	public void testDetectorFiresOnTwoCopiesOfALongPeriod() {
		String distinct = distinctScenesWithSharedBoilerplate(4);
		String prefix = distinct.substring(0, distinct.lastIndexOf("  ]"));
		String block = longSceneBlock(Chat.RunawayDetector.LONG_LOOP_CHARS / 2 + 500);
		assertTrue("block must be long enough that two copies clear LONG_LOOP_CHARS (" + block.length() + ")",
			2 * block.length() >= Chat.RunawayDetector.LONG_LOOP_CHARS);

		String oneCopyAndAHalf = prefix + block + block.substring(0, block.length() / 2);
		assertEquals("one copy plus a partial second is not yet a loop", -1, firstFire(oneCopyAndAHalf));

		String twoCopies = prefix + block + block;
		int firedAt = firstFire(twoCopies);
		Chat.RunawayDetector det2 = new Chat.RunawayDetector();
		assertTrue(det2.check(twoCopies));
		logger.info("[RUNAWAY-UNIT] long-period loop fired at " + firedAt + " of " + twoCopies.length() + " period=" + det2.period
			+ " copies=" + det2.periodCopies + " cut=" + det2.firstRepeatIndex);
		assertTrue("two verbatim copies of a " + block.length() + "-char block must fire", firedAt > 0);
		assertEquals("the period must be the repeated block", block.length(), det2.period);
		assertEquals("it must have fired on the second copy, not waited for MIN_REPEATS", 2, det2.periodCopies);
		assertTrue("the cut must land within one probe stride of the first copy (cut=" + det2.firstRepeatIndex
			+ ", first copy at " + prefix.length() + ")",
			det2.firstRepeatIndex >= prefix.length()
				&& det2.firstRepeatIndex < prefix.length() + Chat.RunawayDetector.CUT_PROBE_STRIDE_CHARS);
	}

	/// (2e) The floor MIN_REPEATS now leans on: three copies of a 400-char line are 1200 chars of
	/// repetition — under MIN_LOOP_CHARS, so not a runaway — while a fourth copy clears the floor and
	/// fires. A short repeated line (a chorus, a table row) must need more than three copies.
	@Test
	public void testThreeCopiesOfAShortLineAreUnderTheFloorButAFourthIsNot() {
		String distinct = distinctScenesWithSharedBoilerplate(4);
		String prefix = distinct.substring(0, distinct.lastIndexOf("  ]"));
		StringBuilder line = new StringBuilder("    {\"title\": \"Scene 5: the clock strikes\", \"sdPrompt\": \"");
		int i = 0;
		while (line.length() < 380) line.append("tick ").append(i++).append(", ");
		line.append("\"},\n");
		assertTrue("line must be short enough that three copies stay under MIN_LOOP_CHARS (" + line.length() + ")",
			3 * line.length() < Chat.RunawayDetector.MIN_LOOP_CHARS);
		assertTrue("...and long enough that four copies clear it", 4 * line.length() >= Chat.RunawayDetector.MIN_LOOP_CHARS);

		String three = prefix + line + line + line;
		assertEquals("three copies under the floor must not fire", -1, firstFire(three));

		String four = three + line;
		Chat.RunawayDetector det = new Chat.RunawayDetector();
		assertTrue("four copies over the floor must fire", det.check(four));
		assertEquals("the period must be the repeated line", line.length(), det.period);
		assertEquals(4, det.periodCopies);
	}

	/// (3) END TO END through Chat.chat() in buffer mode: the recorded runaway reply streamed from a
	/// server that, like the real one, never stops on its own — after the recording it keeps
	/// streaming the loop period. Asserts the call returns long before requestTimeout, with the
	/// reply cut back to where the loop began, flagged stalled AND runaway with a reason — and that
	/// the exchange was torn down at the socket so the model server stops generating: the server's
	/// write fails, which is the only way its loop can end.
	@Test
	public void testBufferModeCallIsCutShortByTheDetectorAndTheExchangeIsTornDown() throws Exception {
		String content = loadFixtureContent();
		try (ReplayServer server = new ReplayServer(content, false, content.substring(content.length() - 2 * LOOP_PERIOD))) {
			Chat chat = chatPointedAt(server.baseUrl());
			Chat.clearLastCallError();

			long start = System.currentTimeMillis();
			OpenAIResponse resp = chat.chat(bufferModeRequest());
			long elapsed = System.currentTimeMillis() - start;
			String why = Chat.getLastCallError();
			String got = contentOf(resp);
			logger.info("[RUNAWAY-E2E] chat() returned after " + elapsed + "ms; content="
				+ (got == null ? "null" : got.length() + " chars") + "; server had sent " + server.sentChars
				+ " of " + content.length() + "; stalled=" + Chat.isLastCallStalled() + " runaway="
				+ Chat.isLastCallRunaway() + "; reason=" + why);

			assertTrue("the call must end well before requestTimeout (" + REQUEST_TIMEOUT_SECONDS + "s); took " + elapsed + "ms",
				elapsed < (REQUEST_TIMEOUT_SECONDS / 2) * 1000L);
			assertNotNull("a runaway reply must be returned as a truncated partial, not null", resp);
			assertNotNull(got);
			assertTrue("returned content must be a prefix of the reply", content.startsWith(got));
			assertTrue("returned content must be cut well short of the full reply (" + got.length() + " of " + content.length() + ")",
				got.length() < content.length() / 2);
			assertCutAtTheStartOfTheLoop(got);
			assertTrue("a runaway must be flagged STALLED so existing one-retry handling applies", Chat.isLastCallStalled());
			assertTrue("a runaway must be flagged RUNAWAY so a caller can word its retry for a loop", Chat.isLastCallRunaway());
			assertFalse("a looping model is not 'unreachable'", Chat.isLastCallUnreachable());
			assertNotNull("the reason must be reported through Chat.getLastCallError()", why);
			assertTrue("the reason must say the output repeated itself; got: " + why, why.contains("repeating itself"));

			boolean closed = server.clientDisconnected.await(20, TimeUnit.SECONDS);
			logger.info("[RUNAWAY-E2E] exchange torn down? " + closed + " (endReason=" + server.endReason
				+ ", writeFailed=" + server.writeFailed + ", sentChars=" + server.sentChars + ")");
			assertTrue("the detector must CLOSE the exchange so the model server stops generating; the socket was still open 20s later", closed);
			assertTrue("an endless server can only stop because its write failed — it must have been cut off while generating (endReason="
				+ server.endReason + ", sent " + server.sentChars + ")", server.writeFailed);
		}
	}

	/// (4) The same refrain poem end to end: streams to its terminator and comes back whole, with
	/// nothing flagged.
	@Test
	public void testBufferModeCallLeavesARefrainPoemAlone() throws Exception {
		String poem = refrainPoem();
		try (ReplayServer server = new ReplayServer(poem, true)) {
			Chat chat = chatPointedAt(server.baseUrl());
			Chat.clearLastCallError();
			OpenAIResponse resp = chat.chat(bufferModeRequest());
			String got = contentOf(resp);
			logger.info("[RUNAWAY-POEM] content=" + (got == null ? "null" : got.length() + " chars")
				+ " stalled=" + Chat.isLastCallStalled() + " runaway=" + Chat.isLastCallRunaway()
				+ " reason=" + Chat.getLastCallError());
			assertNotNull("the poem must come back", resp);
			assertEquals("the poem must come back whole", poem, got);
			assertFalse("a complete reply is not stalled", Chat.isLastCallStalled());
			assertFalse("a refrain is not a runaway", Chat.isLastCallRunaway());
			assertNull("no error for a clean reply", Chat.getLastCallError());
		}
	}

	/// (5) The kill switch: with detection off, the same runaway streams through untouched (here the
	/// server terminates, so the call completes; the point is that nothing was cut).
	@Test
	public void testKillSwitchDisablesTheDetector() throws Exception {
		String content = loadFixtureContent();
		Chat.setRunawayDetectionEnabled(false);
		try (ReplayServer server = new ReplayServer(content, true)) {
			Chat chat = chatPointedAt(server.baseUrl());
			Chat.clearLastCallError();
			OpenAIResponse resp = chat.chat(bufferModeRequest());
			String got = contentOf(resp);
			assertNotNull(resp);
			assertEquals("with the detector off the full reply must come back", content.length(), got.length());
			assertFalse(Chat.isLastCallRunaway());
			assertFalse(Chat.isLastCallStalled());
		}
	}
}
