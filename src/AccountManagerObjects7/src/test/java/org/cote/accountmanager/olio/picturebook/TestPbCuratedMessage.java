package org.cote.accountmanager.olio.picturebook;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import org.cote.accountmanager.objects.tests.BaseTest;
import org.cote.accountmanager.olio.schema.OlioModelNames;
import org.junit.Before;
import org.junit.Test;

/**
 * V3 Phase 4, §6.C 189 (b): {@link PbServiceFacade#curatedMessage} is the only path from a compose-job exception to
 * owner-visible text. Curated exceptions pass their own message; anything else is replaced by
 * {@link PbServiceFacade#INTERNAL_ERROR_MESSAGE}, so a URL, host or credential fragment in a driver or client-library
 * message never reaches the job result. No LLM, no SD, no records.
 */
public class TestPbCuratedMessage extends BaseTest {

	@Before
	public void setupEach() {
		OlioModelNames.use();
	}

	@Test
	public void case01_pictureBookExceptionTextPassesThrough() {
		String text = "Renderer inventory unavailable; cannot verify a model for scene 1 before rendering";
		assertEquals(text, PbServiceFacade.curatedMessage(new PictureBookException(503, text)));
	}

	@Test
	public void case02_cancelMarkerTextPassesThrough() {
		String text = "Cancelled render of scene 2; nothing recorded";
		assertEquals(text, PbServiceFacade.curatedMessage(new PbNodeExecutor.PbStageCancelledException(text)));
	}

	@Test
	public void case03_anyOtherRuntimeExceptionIsReplaced() {
		String leak = "Connection to http://192.168.1.39:7801/API/GenerateText2Image refused (user=svc token=abc123)";
		String out = PbServiceFacade.curatedMessage(new IllegalStateException(leak));
		assertEquals(PbServiceFacade.INTERNAL_ERROR_MESSAGE, out);
		assertFalse(out.contains("192.168.1.39"));
		assertFalse(out.contains("abc123"));
		assertEquals(PbServiceFacade.INTERNAL_ERROR_MESSAGE, PbServiceFacade.curatedMessage(new NullPointerException()));
		assertEquals(PbServiceFacade.INTERNAL_ERROR_MESSAGE, PbServiceFacade.curatedMessage(new RuntimeException("jdbc:postgresql://db:5432/am7db")));
	}

	@Test
	public void case04_curatedExceptionWithoutTextFallsBackToItsClassName() {
		assertEquals("PictureBookException", PbServiceFacade.curatedMessage(new PictureBookException(500, "")));
		assertEquals("PictureBookException", PbServiceFacade.curatedMessage(new PictureBookException(500, null)));
	}
}
