package org.cote.accountmanager.objects.tests;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import org.cote.accountmanager.util.ZipUtil;
import org.junit.Test;

/// KI-17 (Gallery/Group export) groundwork: pure-function unit tests for ZipUtil's new multi-entry
/// archive support (createArchive). No DB / no LLM — verifies the archive round-trips via the standard
/// java.util.zip reader, independent of anything this codebase writes.
public class TestZipUtil {

	private Map<String, ZipEntry> readEntries(byte[] zipBytes, Map<String, byte[]> outContent) throws IOException {
		Map<String, ZipEntry> entries = new LinkedHashMap<>();
		try (ZipInputStream zin = new ZipInputStream(new ByteArrayInputStream(zipBytes))) {
			ZipEntry ze;
			while ((ze = zin.getNextEntry()) != null) {
				ByteArrayOutputStream baos = new ByteArrayOutputStream();
				byte[] buf = new byte[1024];
				int r;
				while ((r = zin.read(buf)) != -1) {
					baos.write(buf, 0, r);
				}
				entries.put(ze.getName(), ze);
				outContent.put(ze.getName(), baos.toByteArray());
				zin.closeEntry();
			}
		}
		return entries;
	}

	@Test
	public void createArchiveRoundTripsMultipleEntries() throws IOException {
		Map<String, byte[]> in = ZipUtil.newOrderedEntries();
		in.put("photo1.jpg", new byte[] {1, 2, 3, 4, 5});
		in.put("notes.json", "{\"schema\":\"data.note\",\"name\":\"test\"}".getBytes("UTF-8"));
		in.put("clip.mp4", new byte[] {9, 8, 7});

		byte[] zipBytes = ZipUtil.createArchive(in);
		assertTrue("Archive should not be empty", zipBytes.length > 0);

		Map<String, byte[]> outContent = new LinkedHashMap<>();
		Map<String, ZipEntry> entries = readEntries(zipBytes, outContent);

		assertEquals("Expected 3 entries", 3, entries.size());
		assertTrue(entries.containsKey("photo1.jpg"));
		assertTrue(entries.containsKey("notes.json"));
		assertTrue(entries.containsKey("clip.mp4"));

		assertArrayEquals(in.get("photo1.jpg"), outContent.get("photo1.jpg"));
		assertArrayEquals(in.get("notes.json"), outContent.get("notes.json"));
		assertArrayEquals(in.get("clip.mp4"), outContent.get("clip.mp4"));
	}

	@Test
	public void createArchivePreservesInsertionOrder() throws IOException {
		Map<String, byte[]> in = ZipUtil.newOrderedEntries();
		in.put("a.txt", "a".getBytes());
		in.put("b.txt", "b".getBytes());
		in.put("c.txt", "c".getBytes());

		byte[] zipBytes = ZipUtil.createArchive(in);
		Map<String, byte[]> outContent = new LinkedHashMap<>();
		Map<String, ZipEntry> entries = readEntries(zipBytes, outContent);

		assertEquals("[a.txt, b.txt, c.txt]", entries.keySet().toString());
	}

	@Test
	public void createArchiveHandlesEmptyMap() {
		byte[] zipBytes = ZipUtil.createArchive(ZipUtil.newOrderedEntries());
		assertTrue("Empty archive should still produce valid (non-null) zip bytes", zipBytes != null && zipBytes.length > 0);
	}

	@Test
	public void createArchiveHandlesNullEntryBytes() throws IOException {
		Map<String, byte[]> in = ZipUtil.newOrderedEntries();
		in.put("empty.txt", null);

		byte[] zipBytes = ZipUtil.createArchive(in);
		Map<String, byte[]> outContent = new LinkedHashMap<>();
		readEntries(zipBytes, outContent);

		assertEquals(0, outContent.get("empty.txt").length);
	}
}
