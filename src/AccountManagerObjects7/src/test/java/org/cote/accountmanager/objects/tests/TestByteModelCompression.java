package org.cote.accountmanager.objects.tests;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.io.OrganizationContext;
import org.cote.accountmanager.io.Query;
import org.cote.accountmanager.io.QueryUtil;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.schema.type.CompressionEnumType;
import org.cote.accountmanager.util.ByteModelUtil;
import org.junit.Test;

/// Regression coverage for the zip-upload corruption bug: a .zip registers as content type
/// "multipart/x-zip" (ContentTypeUtil), which matched none of ByteModelUtil.tryCompress()'s exemption
/// prefixes and so was gzip-wrapped on persist even though a zip is already compressed. The follow-on
/// symptom was that a read projection omitting FIELD_COMPRESSION_TYPE returned the still-gzipped bytes.
/// These tests exercise both: that already-compressed content is NOT gzipped, and that legitimately
/// compressible content (text) still is AND round-trips. Never uses the admin user as the actor.
public class TestByteModelCompression extends BaseTest {

	private byte[] readBackBytes(BaseRecord user, BaseRecord data, boolean projectCompression) throws Exception {
		Query q = QueryUtil.createQuery(ModelNames.MODEL_DATA, FieldNames.FIELD_OBJECT_ID, data.get(FieldNames.FIELD_OBJECT_ID));
		q.field(FieldNames.FIELD_ORGANIZATION_ID, (long) user.get(FieldNames.FIELD_ORGANIZATION_ID));
		if(projectCompression) {
			q.setRequest(new String[] { FieldNames.FIELD_ID, FieldNames.FIELD_OBJECT_ID, FieldNames.FIELD_CONTENT_TYPE,
				FieldNames.FIELD_BYTE_STORE, FieldNames.FIELD_COMPRESSION_TYPE, FieldNames.FIELD_VAULTED,
				FieldNames.FIELD_ENCIPHERED, FieldNames.FIELD_KEYS });
		}
		else {
			q.setRequest(new String[] { FieldNames.FIELD_ID, FieldNames.FIELD_OBJECT_ID, FieldNames.FIELD_CONTENT_TYPE,
				FieldNames.FIELD_BYTE_STORE });
		}
		q.setCache(false);
		BaseRecord read = IOSystem.getActiveContext().getAccessPoint().find(user, q);
		assertNotNull("[BYTECOMPRESS] data.data not readable", read);
		return ByteModelUtil.getValue(read);
	}

	/// A zip is already compressed, so tryCompress() must NOT gzip it: compressionType stays non-GZIP,
	/// the persisted size equals the original length, and the bytes round-trip exactly.
	@Test
	public void TestZipContentNotCompressed() throws Exception {
		OrganizationContext octx = getTestOrganization("/Development/ByteCompress");
		BaseRecord user = getCreateUser("byteCompressUser", octx);
		assertNotNull("Test user is null", user);

		byte[] zipLike = new byte[4096];
		for(int i = 0; i < zipLike.length; i++) {
			zipLike[i] = (byte)((i * 31 + 7) & 0xFF);
		}
		String name = "archive-" + System.nanoTime() + ".zip";
		BaseRecord data = getCreateData(user, name, "multipart/x-zip", zipLike, "~/ByteCompressTest", octx.getOrganizationId());
		assertNotNull("[BYTECOMPRESS] zip data.data not created", data);

		CompressionEnumType ct = CompressionEnumType.valueOf(data.get(FieldNames.FIELD_COMPRESSION_TYPE, "NONE"));
		assertTrue("[BYTECOMPRESS] zip content must NOT be gzip-compressed (was " + ct + ")",
			ct != CompressionEnumType.GZIP);
		long size = data.get(FieldNames.FIELD_SIZE);
		assertEquals("[BYTECOMPRESS] stored size must equal original (uncompressed) length", zipLike.length, size);

		byte[] roundTrip = readBackBytes(user, data, true);
		assertArrayEquals("[BYTECOMPRESS] zip bytes must round-trip exactly", zipLike, roundTrip);
	}

	/// Guard the other side of the fix: compressible content (text/plain over the 512-byte threshold) is
	/// still gzipped on persist AND still decompresses correctly when the compression field is projected.
	@Test
	public void TestTextContentStillCompresses() throws Exception {
		OrganizationContext octx = getTestOrganization("/Development/ByteCompress");
		BaseRecord user = getCreateUser("byteCompressUser", octx);
		assertNotNull("Test user is null", user);

		StringBuilder sb = new StringBuilder();
		while(sb.length() < 4096) {
			sb.append("The quick brown fox jumps over the lazy dog. ");
		}
		byte[] text = sb.toString().getBytes("UTF-8");
		String name = "notes-" + System.nanoTime() + ".txt";
		BaseRecord data = getCreateData(user, name, "text/plain", text, "~/ByteCompressTest", octx.getOrganizationId());
		assertNotNull("[BYTECOMPRESS] text data.data not created", data);

		CompressionEnumType ct = CompressionEnumType.valueOf(data.get(FieldNames.FIELD_COMPRESSION_TYPE, "NONE"));
		assertEquals("[BYTECOMPRESS] compressible text should be gzipped on persist", CompressionEnumType.GZIP, ct);

		byte[] roundTrip = readBackBytes(user, data, true);
		assertArrayEquals("[BYTECOMPRESS] gzipped text must decompress back to the original", text, roundTrip);
	}
}
