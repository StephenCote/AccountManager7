package org.cote.accountmanager.olio.picturebook;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

import org.junit.Test;

/**
 * P9-7 (§6.C 237 l): pure-JUnit coverage of {@link PbServiceFacade#scrubHosts(String)}, the one scrub the
 * three node DTOs ({@code nodeSummary}, {@code locationNodeDto}, {@code coverEntryDto}) apply to a node's
 * stored {@code lastError} before it reaches a Reader. No database, no server, no {@code BaseTest} (the
 * {@code TestSwUtilPromptText} pattern).
 */
public class TestPbLastErrorScrub {

	private static final String MARKER = PbServiceFacade.SD_SERVER_MARKER;

	@Test
	public void urlWithPortIsReplacedAndThePathKept() {
		String in = "Render of scene 3 failed: UNREACHABLE: Connection refused to http://10.0.0.5:7801/API/GenerateText2Image (plan artifact abc)";
		String out = PbServiceFacade.scrubHosts(in);
		assertEquals("Render of scene 3 failed: UNREACHABLE: Connection refused to " + MARKER + "/API/GenerateText2Image (plan artifact abc)", out);
		assertFalse("no host survives: " + out, out.contains("10.0.0.5"));
		assertFalse("no port survives: " + out, out.contains("7801"));
	}

	@Test
	public void urlWithoutPortIsReplaced() {
		String in = "timeout waiting on http://swarm-host/API/GetModelHash";
		assertEquals("timeout waiting on " + MARKER + "/API/GetModelHash", PbServiceFacade.scrubHosts(in));

		String bare = "could not reach http://swarm-host";
		assertEquals("could not reach " + MARKER, PbServiceFacade.scrubHosts(bare));
	}

	@Test
	public void httpsAndOtherSchemesAreReplaced() {
		assertEquals("TLS failure at " + MARKER + "/ (handshake)", PbServiceFacade.scrubHosts("TLS failure at https://sd.example.internal:8443/ (handshake)"));
		assertEquals("socket closed: " + MARKER + "/API/ws", PbServiceFacade.scrubHosts("socket closed: wss://sd.example.internal:7801/API/ws"));
		/// userinfo is part of the authority and goes with it
		assertEquals("auth: " + MARKER + "/x", PbServiceFacade.scrubHosts("auth: http://user:secret@sd.example.internal:7801/x"));
	}

	@Test
	public void twoUrlsInOneMessageAreBothReplaced() {
		String in = "primary http://10.0.0.5:7801 and fallback https://10.0.0.6:7801/ both refused";
		String out = PbServiceFacade.scrubHosts(in);
		assertEquals("primary " + MARKER + " and fallback " + MARKER + "/ both refused", out);
	}

	@Test
	public void messageWithoutUrlIsUnchanged() {
		String in = "Render of scene 3 failed: GRAPH_INVALID: reference 4f1c not readable (plan artifact abc)";
		assertSame("no URL: the same instance comes back", in, PbServiceFacade.scrubHosts(in));
		String colons = "ratio 16:9 at 1024x576; code 503:UNREACHABLE";
		assertSame("colons without a scheme are not a URL", colons, PbServiceFacade.scrubHosts(colons));
		assertNull("null stays null", PbServiceFacade.scrubHosts(null));
		assertEquals("empty stays empty", "", PbServiceFacade.scrubHosts(""));
	}

	@Test
	public void quotedAndBracketedUrlsStopAtTheDelimiter() {
		assertEquals("url \"" + MARKER + "\" refused", PbServiceFacade.scrubHosts("url \"http://10.0.0.5:7801\" refused"));
		assertEquals("url [" + MARKER + "] refused", PbServiceFacade.scrubHosts("url [http://10.0.0.5:7801] refused"));
		assertEquals("url (" + MARKER + ") refused", PbServiceFacade.scrubHosts("url (http://10.0.0.5:7801) refused"));
		assertEquals("url " + MARKER + "?x=1 refused", PbServiceFacade.scrubHosts("url http://10.0.0.5:7801?x=1 refused"));
	}
}
