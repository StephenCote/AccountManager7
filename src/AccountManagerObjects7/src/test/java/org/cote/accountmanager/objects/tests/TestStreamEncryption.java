package org.cote.accountmanager.objects.tests;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.util.UUID;

import org.cote.accountmanager.exceptions.FactoryException;
import org.cote.accountmanager.exceptions.FieldException;
import org.cote.accountmanager.exceptions.IndexException;
import org.cote.accountmanager.exceptions.ModelException;
import org.cote.accountmanager.exceptions.ModelNotFoundException;
import org.cote.accountmanager.exceptions.ReaderException;
import org.cote.accountmanager.exceptions.ValueException;
import org.cote.accountmanager.factory.Factory;
import org.cote.accountmanager.io.OrganizationContext;
import org.cote.accountmanager.io.ParameterList;
import org.cote.accountmanager.io.Query;
import org.cote.accountmanager.io.QueryUtil;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.util.ByteModelUtil;
import org.cote.accountmanager.util.FileUtil;
import org.cote.accountmanager.util.StreamUtil;
import org.junit.Test;

public class TestStreamEncryption extends BaseTest {

	@Test
	public void TestStreamIntegrity() {
		logger.info("Test Stream Integrity");
		OrganizationContext testOrgContext = getTestOrganization("/Development/Stream");
		Factory mf = ioContext.getFactory();
		BaseRecord testUser5 =  mf.getCreateUser(testOrgContext.getAdminUser(), "testUser5", testOrgContext.getOrganizationId());
		String dataName = "Demo stream " + UUID.randomUUID().toString() + ".mp4";
		
		BaseRecord group = ioContext.getPathUtil().makePath(testUser5, ModelNames.MODEL_GROUP, "~/Data/StreamUtil", "DATA", testOrgContext.getOrganizationId());
		ParameterList plist = ParameterList.newParameterList(FieldNames.FIELD_PATH, "~/Data/StreamUtil");
		plist.parameter(FieldNames.FIELD_NAME, dataName);

		/// cut at 1k
		StreamUtil.setStreamCutoff(1024);
		String[] sampleData = new String[] {"./media/YoureFired.mp4"};
		try(FileInputStream fos = new FileInputStream(sampleData[0])){
			boolean created = StreamUtil.streamToData(testUser5, dataName, "Test stream utility", "~/Data/StreamUtil", 0L, fos);
			assertTrue("Failed to stream into data", created);
			
		} catch (IOException | FieldException | ModelNotFoundException | ValueException | FactoryException | IndexException | ReaderException | ModelException e) {
			logger.error(e);
		}

		Query q = QueryUtil.createQuery(ModelNames.MODEL_DATA, FieldNames.FIELD_NAME, dataName);
		q.field(FieldNames.FIELD_GROUP_ID, group.get(FieldNames.FIELD_ID));
		q.planMost(true);
		BaseRecord data = ioContext.getSearch().findRecord(q);
		assertNotNull("Data is null");
		
		byte[] dat = new byte[0];
		try {
			dat = ByteModelUtil.getValue(data);
		} catch (ValueException | FieldException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
		logger.info("Data length: " + dat.length);
		FileUtil.emitFile("./tmp.mp4", dat);
	}
	
	@Test
	public void TestStreamEncrypt() {
		logger.info("Test Streaming Encryption");

		OrganizationContext testOrgContext = getTestOrganization("/Development/Stream");
		Factory mf = ioContext.getFactory();
		BaseRecord testUser5 =  mf.getCreateUser(testOrgContext.getAdminUser(), "testUser5", testOrgContext.getOrganizationId());
		String dataName = "Demo stream " + UUID.randomUUID().toString() + ".jpg";
		BaseRecord group = ioContext.getPathUtil().makePath(testUser5, ModelNames.MODEL_GROUP, "~/Data/StreamUtil", "DATA", testOrgContext.getOrganizationId());
		ParameterList plist = ParameterList.newParameterList(FieldNames.FIELD_PATH, "~/Data/StreamUtil");
		plist.parameter(FieldNames.FIELD_NAME, dataName);

		/// cut at 1mb
		StreamUtil.setStreamCutoff(1048576);
		String[] sampleData = new String[] {"sunflower.jpg"};
		try(FileInputStream fos = new FileInputStream("./media/" + sampleData[0])){
			boolean created = StreamUtil.streamToData(testUser5, dataName, "Test stream utility", "~/Data/StreamUtil", 0L, fos);
			assertTrue("Failed to stream into data", created);
			
		} catch (IOException | FieldException | ModelNotFoundException | ValueException | FactoryException | IndexException | ReaderException | ModelException e) {
			logger.error(e);
		}

		Query q = QueryUtil.createQuery(ModelNames.MODEL_DATA, FieldNames.FIELD_NAME, dataName);
		q.field(FieldNames.FIELD_GROUP_ID, group.get(FieldNames.FIELD_ID));
		q.planMost(true);
		BaseRecord data = ioContext.getSearch().findRecord(q);
		assertNotNull("Data is null");
		
		Query sq = QueryUtil.createQuery(ModelNames.MODEL_STREAM, FieldNames.FIELD_ID, data.get("stream.id"));
		sq.planMost(true);
		BaseRecord stream = ioContext.getSearch().findRecord(sq);
		assertNotNull("Stream is null");
		
		boolean boxed = false;
		try {
			boxed = StreamUtil.boxStream(stream, false);
			StreamUtil.clearUnboxedStream(stream);
			StreamUtil.unboxStream(stream, false);
		} catch (ModelException e) {
			logger.error(e);
		}
		assertTrue("Expected stream to be boxed", boxed);

	}

	/// KI-23 regression test: StreamUtil.rebox() used to buffer the entire boxed file into one byte[] to
	/// encipher/decipher it (StreamUtil.fileHandleToBytes + CryptoUtil.encipher/decipher), the same
	/// buffer-everything OOM shape as KI-17/KI-22, one layer deeper - and unlike those, this one fires on
	/// EVERY stream-backed upload's first read, since StreamUtil.streamToData boxes every stream
	/// immediately. Fixed by driving the SAME already-initialized Cipher through
	/// CipherInputStream/CipherOutputStream (CryptoUtil.decipherStream/encipherStream) instead of
	/// cipher.doFinal() on a whole-file byte[]. This test proves correctness across chunk boundaries
	/// (content spans multiple 1MB StreamSegmentUtil chunks) with a real box -> unbox -> re-box -> re-unbox
	/// round trip, verified byte-for-byte and by checksum - not merely that the code compiles.
	@Test
	public void TestStreamEncryptionLargeRoundTrip() throws Exception {
		logger.info("=== TestStreamEncryptionLargeRoundTrip ===");
		OrganizationContext testOrgContext = getTestOrganization("/Development/Stream");
		Factory mf = ioContext.getFactory();
		BaseRecord testUser5 = mf.getCreateUser(testOrgContext.getAdminUser(), "testUser5", testOrgContext.getOrganizationId());
		String dataName = "Demo stream " + UUID.randomUUID().toString() + ".bin";

		/// cut at 1mb, so the fixture below (spanning several 1MB StreamSegmentUtil chunks) is genuinely
		/// stream-backed rather than staying inline.
		StreamUtil.setStreamCutoff(1048576);
		int fileSize = 3 * 1024 * 1024 + 777; // spans multiple 1MB chunks, with an odd remainder chunk
		byte[] content = deterministicBytes(fileSize, 11);

		boolean created;
		try (ByteArrayInputStream bis = new ByteArrayInputStream(content)) {
			created = StreamUtil.streamToData(testUser5, dataName, "Round trip test", "~/Data/StreamUtil", 0L, bis);
		}
		assertTrue("[STREAMCRYPT] Failed to stream into data", created);

		BaseRecord group = ioContext.getPathUtil().makePath(testUser5, ModelNames.MODEL_GROUP, "~/Data/StreamUtil", "DATA", testOrgContext.getOrganizationId());
		Query q = QueryUtil.createQuery(ModelNames.MODEL_DATA, FieldNames.FIELD_NAME, dataName);
		q.field(FieldNames.FIELD_GROUP_ID, group.get(FieldNames.FIELD_ID));
		q.planMost(true);
		BaseRecord data = ioContext.getSearch().findRecord(q);
		assertNotNull("[STREAMCRYPT] Data is null", data);

		Query sq = QueryUtil.createQuery(ModelNames.MODEL_STREAM, FieldNames.FIELD_ID, data.get("stream.id"));
		sq.planMost(true);
		BaseRecord stream = ioContext.getSearch().findRecord(sq);
		assertNotNull("[STREAMCRYPT] Stream is null", stream);

		/// streamToData boxes the stream immediately on upload (StreamUtil.java:531) - only the
		/// ciphertext should exist right now.
		String plainPath = StreamUtil.getFileStreamPath(stream);
		assertFalse("[STREAMCRYPT] plaintext should not exist right after upload (boxed immediately)", new File(plainPath).exists());
		assertTrue("[STREAMCRYPT] ciphertext (.box) should exist", new File(plainPath + ".box").exists());

		/// Force unbox via the new CipherInputStream-based streaming rebox path and verify the recovered
		/// plaintext is byte-for-byte identical to the original content.
		boolean unboxed = StreamUtil.unboxStream(stream, true);
		assertTrue("[STREAMCRYPT] unboxStream failed", unboxed);
		byte[] recovered = StreamUtil.fileToBytes(plainPath);
		assertEquals("[STREAMCRYPT] recovered length mismatch", content.length, recovered.length);
		assertArrayEquals("[STREAMCRYPT] recovered content mismatch - streaming cipher I/O corrupted data", content, recovered);
		assertEquals("[STREAMCRYPT] checksum mismatch after unbox", sha256(content), sha256(recovered));

		/// Re-box (streaming encipher) the recovered plaintext, then unbox again (streaming decipher) -
		/// a second round trip through the new streaming path should still recover the exact original
		/// bytes, proving both directions (encipherStream and decipherStream) are correct, not just one.
		boolean reboxed = StreamUtil.boxStream(stream, true);
		assertTrue("[STREAMCRYPT] re-box failed", reboxed);
		StreamUtil.clearUnboxedStream(stream);
		boolean reunboxed = StreamUtil.unboxStream(stream, true);
		assertTrue("[STREAMCRYPT] re-unbox failed", reunboxed);
		byte[] recovered2 = StreamUtil.fileToBytes(plainPath);
		assertArrayEquals("[STREAMCRYPT] second round-trip content mismatch", content, recovered2);
		assertEquals("[STREAMCRYPT] checksum mismatch on second round trip", sha256(content), sha256(recovered2));
	}

	/// Deterministic, non-degenerate (not all-zero) content so a corrupted chunk/cipher-block boundary
	/// would actually be detectable via assertArrayEquals rather than accidentally matching.
	private byte[] deterministicBytes(int size, int seed) {
		byte[] b = new byte[size];
		for (int i = 0; i < size; i++) {
			b[i] = (byte) ((i * 31 + seed * 17 + 7) & 0xFF);
		}
		return b;
	}

	private String sha256(byte[] data) throws Exception {
		return java.util.Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest(data));
	}


}
