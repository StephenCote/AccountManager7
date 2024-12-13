package org.cote.accountmanager.objects.tests;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.stream.Collectors;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.cote.accountmanager.exceptions.FactoryException;
import org.cote.accountmanager.exceptions.FieldException;
import org.cote.accountmanager.exceptions.ValueException;
import org.cote.accountmanager.exceptions.WriterException;
import org.cote.accountmanager.factory.Factory;
import org.cote.accountmanager.io.IOFactory;
import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.io.OrganizationContext;
import org.cote.accountmanager.objects.tests.olio.OlioTranslator;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.record.RecordFactory;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.schema.ModelSchema;
import org.cote.accountmanager.schema.type.GroupEnumType;
import org.cote.accountmanager.util.DocumentUtil;
import org.cote.accountmanager.util.VectorUtil;
import org.cote.accountmanager.util.VectorUtil.ChunkEnumType;
import org.junit.Before;
import org.junit.Test;
import com.pgvector.PGvector;

import ai.djl.Device;
import ai.djl.MalformedModelException;
import ai.djl.ModelException;
import ai.djl.huggingface.translator.TextEmbeddingTranslatorFactory;
import ai.djl.inference.Predictor;
import ai.djl.modality.nlp.DefaultVocabulary;
import ai.djl.modality.nlp.Vocabulary;
import ai.djl.modality.nlp.bert.BertToken;
import ai.djl.modality.nlp.bert.BertTokenizer;
import ai.djl.modality.nlp.qa.QAInput;
import ai.djl.repository.zoo.Criteria;
import ai.djl.repository.zoo.ModelNotFoundException;
import ai.djl.repository.zoo.ZooModel;
import ai.djl.translate.DeferredTranslatorFactory;
import ai.djl.translate.TranslateException;
public class TestVectorStore extends BaseTest {
	
	
	@Test
	public void TestPDF() {
		logger.info("Test VectorStore with PDF");
		Factory mf = ioContext.getFactory();
		OrganizationContext testOrgContext = getTestOrganization("/Development/Vector");
		BaseRecord testUser1 = mf.getCreateUser(testOrgContext.getAdminUser(), "testUser1", testOrgContext.getOrganizationId());

		BaseRecord pdf1 = getCreateDocument(testUser1, "./media/The Big Way Out.doc");
		assertNotNull("Document is null", pdf1);
		IOSystem.getActiveContext().getReader().populate(pdf1, new String[] {FieldNames.FIELD_BYTE_STORE, FieldNames.FIELD_CONTENT_TYPE});
		
		int count = VectorUtil.countVectorStore(pdf1);
		if(count == 0) {
		List<BaseRecord> chunks = new ArrayList<>();
		try {
			 chunks = VectorUtil.createVectorStore(pdf1, ChunkEnumType.WORD, 250);
			logger.info("Retrieved " + chunks.size());
			assertTrue("Expected chunks", chunks.size() > 0);
			IOSystem.getActiveContext().getWriter().write(chunks.toArray(new BaseRecord[0]));

		} catch (FieldException | WriterException e) {
			logger.error(e);
		}
		}
		List<BaseRecord> store = VectorUtil.getVectorStore(pdf1);
		assertTrue("Expected the store", store.size() > 0);
		
		List<BaseRecord> findStore = VectorUtil.find(pdf1, "Where is the casino?");
		logger.info("Found: " + findStore.size());
	}

	private BaseRecord getCreateDocument(BaseRecord user, String path) {
		File f = new File(path);
		String content = null;
		if(path.endsWith(".pdf")) {
			content = DocumentUtil.readPDF(path);
		}
		else {
			content = DocumentUtil.readDocument(path);
		}
		assertTrue("Content was null", content != null && content.trim().length() > 0);
		return getCreateData(user, f.getName(), "~/Data", content);
	}
	
	
	
}
