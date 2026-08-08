package org.cote.accountmanager.objects.tests;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.util.ClientUtil;
import org.junit.After;
import org.junit.Test;

/// Pure unit tests (no HTTP) for the configurable shared HTTP read timeout.
///
/// Why this exists: the read timeout was hardcoded at 360s with the comment "360s read matches the
/// worst-case batch image generation upper bound". That premise was false on current hardware. A
/// FLUX.2 Klein 9B multi-reference composite takes ~638s of GPU time on the Beelink GTR9's Strix Halo
/// iGPU, so EVERY composite died with
///   PictureBookException: java.net.SocketTimeoutException: Read timed out
/// The image was still generated correctly on the SD server - only the client stopped listening,
/// which is what made it look like a generation failure rather than a client bug. Observed twice on
/// 2026-08-07: a standalone FLUX.2 run aborting at 361.9s, and TestPictureBookCustomPipeline at 667s.
///
/// The default must therefore stay comfortably above the slowest real generation, and the value must
/// be deployment-configurable, because it is a property of the GPU rather than of the code.
public class TestClientReadTimeout {
	public static final Logger logger = LogManager.getLogger(TestClientReadTimeout.class);

	/// Process-global static; restore so ordering against other tests can't matter.
	private final int original = ClientUtil.getReadTimeoutSeconds();

	@After
	public void restore() {
		ClientUtil.setReadTimeoutSeconds(original);
	}

	/// The assertion that actually matters: read the timeout back off the CONSTRUCTED Jersey client,
	/// not off our own static field.
	///
	/// Asserting `getReadTimeoutSeconds() >= 900` only proves a number I set is the number I set — it
	/// would still pass if setReadTimeoutSeconds never reached ClientBuilder at all, which is the one
	/// way this fix could silently not work. Jersey's ClientBuilder.readTimeout() stores into
	/// ClientProperties.READ_TIMEOUT (millis) on the client configuration, so that is observable.
	///
	/// Deliberately asserts "> 360000ms" rather than an exact value: the client is a lazily-built
	/// process-wide singleton, so whichever setter ran before the first HTTP call wins, and this test
	/// must not depend on class ordering. What must hold is that the built client is never back at the
	/// 360s that broke FLUX.2.
	@Test
	public void configuredTimeoutReachesTheBuiltJerseyClient() {
		Object prop = ClientUtil.getClient().getConfiguration()
			.getProperty(org.glassfish.jersey.client.ClientProperties.READ_TIMEOUT);
		assertTrue("Jersey should expose the configured read timeout; got " + prop,
			prop instanceof Integer);
		int millis = (Integer) prop;
		logger.info("Built Jersey client READ_TIMEOUT = " + millis + "ms");
		assertTrue("the built client must carry a read timeout above the 360s that aborted every "
			+ "FLUX.2 composite mid-generation; got " + millis + "ms", millis > 360000);
	}

	/// The regression guard. 360s is the value that broke FLUX.2; anything at or below the slowest
	/// measured generation reintroduces the bug.
	@Test
	public void defaultExceedsTheSlowestMeasuredGeneration() {
		int def = ClientUtil.getReadTimeoutSeconds();
		assertTrue("read timeout must exceed the ~638s FLUX.2 composite measured on the local iGPU, "
			+ "with headroom for a slower model or a hires pass; got " + def + "s", def >= 900);
		assertTrue("the old hardcoded 360s must never come back as the default", def > 360);
	}

	/// Still finite. The timeout exists to stop a wedged backend holding a pooled connection forever
	/// (its original purpose), so "just make it huge" is not the fix.
	@Test
	public void defaultStaysFinite() {
		assertTrue("an unbounded read timeout would reintroduce the hang this setting prevents",
			ClientUtil.getReadTimeoutSeconds() <= 3600);
	}

	@Test
	public void setterRoundTrips() {
		ClientUtil.setReadTimeoutSeconds(1500);
		assertEquals(1500, ClientUtil.getReadTimeoutSeconds());
	}

	/// A bad config value must not silently disable the timeout.
	@Test
	public void nonPositiveValuesAreRejected() {
		ClientUtil.setReadTimeoutSeconds(1500);
		ClientUtil.setReadTimeoutSeconds(0);
		assertEquals("zero must be ignored, not applied as 'no timeout'", 1500, ClientUtil.getReadTimeoutSeconds());
		ClientUtil.setReadTimeoutSeconds(-5);
		assertEquals("negative must be ignored", 1500, ClientUtil.getReadTimeoutSeconds());
	}
}
