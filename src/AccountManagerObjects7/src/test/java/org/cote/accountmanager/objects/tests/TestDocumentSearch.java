package org.cote.accountmanager.objects.tests;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.xpath.XPath;
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathExpressionException;
import javax.xml.xpath.XPathFactory;

import org.cote.accountmanager.exceptions.FieldException;
import org.cote.accountmanager.exceptions.ModelNotFoundException;
import org.cote.accountmanager.exceptions.ValueException;
import org.cote.accountmanager.exceptions.WriterException;
import org.cote.accountmanager.factory.Factory;
import org.cote.accountmanager.io.OrganizationContext;
import org.cote.accountmanager.objects.tests.olio.OlioTestUtil;
import org.cote.accountmanager.olio.assist.DocumentWatcher;
import org.cote.accountmanager.olio.assist.ScrivenerAssistant;
import org.cote.accountmanager.olio.llm.Chat;
import org.cote.accountmanager.olio.llm.LLMServiceEnumType;
import org.cote.accountmanager.olio.llm.OpenAIRequest;
import org.cote.accountmanager.olio.llm.OpenAIResponse;
import org.cote.accountmanager.olio.schema.OlioModelNames;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.record.RecordFactory;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.util.ByteModelUtil;
import org.cote.accountmanager.util.DocumentUtil;
import org.cote.accountmanager.util.VectorUtil;
import org.cote.accountmanager.util.VectorUtil.ChunkEnumType;
import org.junit.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

public class TestDocumentSearch extends BaseTest {

	
	private String scrivenerProjectPath = "C:\\Users\\swcot\\OneDrive\\Documents\\Scrivner\\The Verse.scriv";
	/*
	@Test
	public void TestScrivener() {
		logger.info("Test scan scrivener...");

		OrganizationContext testOrgContext = getTestOrganization("/Development/Scrivener");
		assertNotNull("Test org is null", testOrgContext);
		Factory mf = ioContext.getFactory();
		BaseRecord testUser1 = mf.getCreateUser(testOrgContext.getAdminUser(), "testUser1", testOrgContext.getOrganizationId());
		assertNotNull("Test user is null", testUser1);
		ScrivenerAssistant sa = new ScrivenerAssistant(testUser1);
		try {
			sa.parseProject(scrivenerProjectPath);
		} catch (NullPointerException | ParserConfigurationException | SAXException | IOException | XPathExpressionException e) {
			logger.error(e);
			e.printStackTrace();
		}
		DocumentWatcher dw = new DocumentWatcher(sa, scrivenerProjectPath);

	}
	*/
	
	@Test
	public void TestVectorAccessPoint() {
		logger.info("Test vectorize access point method...");

		OrganizationContext testOrgContext = getTestOrganization("/Development/Scrivener");
		assertNotNull("Test org is null", testOrgContext);
		Factory mf = ioContext.getFactory();
		BaseRecord testUser1 = mf.getCreateUser(testOrgContext.getAdminUser(), "testUser1", testOrgContext.getOrganizationId());
		assertNotNull("Test user is null", testUser1);
		
		String path = "./media/The Verse.docx";
		BaseRecord doc = getCreateDocument(testUser1, path);
		assertNotNull("Test data was null", doc);
		
		ioContext.getAccessPoint().vectorize(testUser1, doc.getSchema(), doc.get(FieldNames.FIELD_OBJECT_ID), ChunkEnumType.CHAPTER, 0);

		List<BaseRecord> vecs = VectorUtil.find(doc, "What are the names of Mark's kids?", 10, 0.6);
		assertTrue("Expected at least one result", vecs.size() > 0);
		logger.info("Content: " + vecs.get(0).get("content"));
		logger.info(vecs.get(0).toFullString());
		
	}


}


