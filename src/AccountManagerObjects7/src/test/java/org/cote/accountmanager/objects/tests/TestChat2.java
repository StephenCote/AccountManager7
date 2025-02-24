package org.cote.accountmanager.objects.tests;

import org.cote.accountmanager.exceptions.FieldException;
import org.cote.accountmanager.exceptions.ModelNotFoundException;
import org.cote.accountmanager.olio.schema.OlioModelNames;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.record.RecordFactory;
import org.junit.Test;

public class TestChat2 extends BaseTest {

	@Test
	public void TestOpenAIModel() {
		logger.info("Test Open AI Model");
		BaseRecord aireq = null;
		try {
			aireq = RecordFactory.newInstance(OlioModelNames.OPENAI_MESSAGE);
		} catch (FieldException | ModelNotFoundException e) {
			logger.error(e);
		}
		
		String ser = aireq.toFullString();
		
	}
	
	@Test
	public void TestOllamaModel() {
		
	}
}
