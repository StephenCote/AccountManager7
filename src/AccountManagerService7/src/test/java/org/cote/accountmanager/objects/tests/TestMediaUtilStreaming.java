package org.cote.accountmanager.objects.tests;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import java.io.ByteArrayInputStream;
import java.lang.reflect.Proxy;
import java.security.MessageDigest;
import java.util.Collections;
import java.util.UUID;

import jakarta.servlet.ServletContext;

import org.cote.accountmanager.factory.Factory;
import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.io.OrganizationContext;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.schema.type.ActionEnumType;
import org.cote.accountmanager.util.AuditUtil;
import org.cote.accountmanager.util.MediaOptions;
import org.cote.accountmanager.util.MediaUtil;
import org.cote.accountmanager.util.StreamUtil;
import org.junit.Test;

/// KI-22 real end-to-end integration test: exercises MediaUtil.writeBinaryData's stream-backed branch
/// (now StreamSegmentUtil.streamToOutput instead of streamToEnd) against a live-DB stream-backed fixture
/// spanning multiple MEDIA_STREAM_CHUNK_SIZE (1MB) chunks, proving byte-for-byte correctness via checksum
/// - mirroring the kind of test TestGroupExport#TestExportGroupLargeStreamBackedContent used for the
/// KI-17 group-export OOM fix. Also proves the small/inline byte[] branch is unaffected (regression
/// guard). Never uses the admin user as the acting user - a dedicated non-admin test user is created per
/// AM7 convention (ensureSharedTestUser()'s Java-side analogue: Factory.getCreateUser under a scoped test
/// organization, never octx.getAdminUser() itself as the acting caller).
public class TestMediaUtilStreaming extends BaseTest {

	/// HttpServletRequestMock#getServletContext() returns null, which is fine for the ISO REST-shim
	/// tests it was written for, but MediaUtil.writeBinaryData unconditionally calls
	/// request.getServletContext().getInitParameter(...) (image size limit config) before it ever looks
	/// at the stream/thumbnail options - so this request mock needs a non-null ServletContext. A JDK
	/// dynamic proxy avoids hand-stubbing ServletContext's ~50 methods for the two calls actually made
	/// (getInitParameter returning null is the correct "no override configured" behavior anyway).
	private static class MediaHttpServletRequestMock extends HttpServletRequestMock {
		private static final ServletContext BENIGN_CONTEXT = (ServletContext) Proxy.newProxyInstance(
			MediaHttpServletRequestMock.class.getClassLoader(),
			new Class<?>[] { ServletContext.class },
			(proxy, method, args) -> {
				Class<?> rt = method.getReturnType();
				if (rt == boolean.class) return false;
				if (rt == int.class) return 0;
				if (rt == long.class) return 0L;
				if (rt.equals(java.util.Enumeration.class)) return Collections.emptyEnumeration();
				return null;
			}
		);

		@Override
		public ServletContext getServletContext() {
			return BENIGN_CONTEXT;
		}
	}

	private BaseRecord createStreamBackedFile(BaseRecord user, String path, String name, byte[] content, long orgId) throws Exception {
		boolean ok = StreamUtil.streamToData(user, name, "MediaUtil streaming fixture", path, 0L, new ByteArrayInputStream(content));
		org.junit.Assert.assertTrue("[MEDIAUTIL-STREAM] streamToData failed for " + name, ok);
		return ioContext.getPathUtil().makePath(user, ModelNames.MODEL_GROUP, path, "DATA", orgId);
	}

	/// Deterministic, non-degenerate (not all-zero) content so a truncated/corrupted chunk boundary
	/// would actually be detectable rather than accidentally matching.
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

	@Test
	public void TestWriteBinaryDataStreamsLargeStreamBackedFileWithoutFullBuffer() throws Exception {
		logger.info("=== TestWriteBinaryDataStreamsLargeStreamBackedFileWithoutFullBuffer ===");
		Factory mf = ioContext.getFactory();
		OrganizationContext octx = getTestOrganization("/Development/MediaUtilStreamTest");
		BaseRecord testUser1 = mf.getCreateUser(octx.getAdminUser(), "testUser1", octx.getOrganizationId());
		assertNotNull(testUser1);

		/// Unique path per run: StreamUtil.streamToData hard-fails on a same-named record in the target
		/// group, and the live DB is never reset between runs.
		String path = "~/MediaUtilStreamTest-" + UUID.randomUUID();
		int fileSize = 3 * 1024 * 1024 + 777; // spans multiple 1MB chunks, with an odd remainder chunk
		String name = "large.bin";
		byte[] content = deterministicBytes(fileSize, 7);
		BaseRecord dir = createStreamBackedFile(testUser1, path, name, content, octx.getOrganizationId());

		MediaOptions options = new MediaOptions();

		/// First read: StreamUtil.streamToData boxes (encrypts at rest) every stream it creates
		/// (StreamUtil.java:531), so at this point only the ciphertext ".box" file exists on disk - the
		/// upfront "cheap" size lookup (StreamSegmentUtil.getFileStreamSize -> RandomAccessFile on the
		/// plaintext path) can't find it and returns 0, so no Content-Length is set and the response
		/// falls back to chunked transfer (see KI-22's own fix-direction notes; tracked as a separate,
		/// deeper buffer-everything gap in the box/unbox layer as KI-23). What still matters here: the
		/// content streamed back must be byte-for-byte correct even though unboxing had to happen first.
		HttpServletResponseMock firstResponse = new HttpServletResponseMock();
		BaseRecord firstAudit = AuditUtil.startAudit(testUser1, ActionEnumType.READ, testUser1, null);
		MediaUtil.writeBinaryData(new MediaHttpServletRequestMock(), firstResponse, options, firstAudit,
			ModelNames.MODEL_DATA, octx, testUser1, dir, name);

		assertNull("[MEDIAUTIL-STREAM] unexpected sendError: " + firstResponse.getErrorCode(), firstResponse.getErrorCode());
		byte[] firstWritten = firstResponse.getWrittenBytes();
		assertEquals("[MEDIAUTIL-STREAM] streamed content length mismatch", content.length, firstWritten.length);
		assertArrayEquals("[MEDIAUTIL-STREAM] streamed content mismatch - chunked read/write corrupted data",
			content, firstWritten);
		assertEquals("[MEDIAUTIL-STREAM] checksum mismatch", sha256(content), sha256(firstWritten));

		/// Second read: the plaintext file now exists on disk (unboxed by the first read), so the cheap
		/// size lookup succeeds and Content-Length should be set up front without a full read.
		HttpServletResponseMock secondResponse = new HttpServletResponseMock();
		BaseRecord secondAudit = AuditUtil.startAudit(testUser1, ActionEnumType.READ, testUser1, null);
		MediaUtil.writeBinaryData(new MediaHttpServletRequestMock(), secondResponse, options, secondAudit,
			ModelNames.MODEL_DATA, octx, testUser1, dir, name);

		assertNull("[MEDIAUTIL-STREAM] unexpected sendError on second read: " + secondResponse.getErrorCode(), secondResponse.getErrorCode());
		assertArrayEquals("[MEDIAUTIL-STREAM] second-read content mismatch", content, secondResponse.getWrittenBytes());
		assertEquals("[MEDIAUTIL-STREAM] Content-Length should equal the true file size on a subsequent read, "
			+ "obtained via a cheap size lookup rather than a full read", (long) content.length, secondResponse.getContentLengthLong());
	}

	/// Regression guard: options.isEncodeData()/isUseTemplate() and the inline byte[] path (content
	/// under StreamUtil's inline cutoff) must still work exactly as before - the KI-22 fix only changes
	/// the stream-backed, no-encode, no-template branch.
	@Test
	public void TestWriteBinaryDataSmallInlineContentStillBuffered() throws Exception {
		logger.info("=== TestWriteBinaryDataSmallInlineContentStillBuffered ===");
		Factory mf = ioContext.getFactory();
		OrganizationContext octx = getTestOrganization("/Development/MediaUtilStreamTest");
		BaseRecord testUser1 = mf.getCreateUser(octx.getAdminUser(), "testUser1", octx.getOrganizationId());
		assertNotNull(testUser1);

		String path = "~/MediaUtilStreamSmallTest-" + UUID.randomUUID();
		byte[] content = "hello small inline media content".getBytes("UTF-8");
		BaseRecord data = getCreateData(testUser1, "small.txt", "text/plain", content, path, octx.getOrganizationId());
		assertNotNull(data);
		BaseRecord dir = ioContext.getPathUtil().makePath(testUser1, ModelNames.MODEL_GROUP, path, "DATA", octx.getOrganizationId());

		MediaHttpServletRequestMock request = new MediaHttpServletRequestMock();
		HttpServletResponseMock response = new HttpServletResponseMock();
		MediaOptions options = new MediaOptions();
		BaseRecord audit = AuditUtil.startAudit(testUser1, ActionEnumType.READ, testUser1, null);

		MediaUtil.writeBinaryData(request, response, options, audit, ModelNames.MODEL_DATA, octx, testUser1, dir, "small.txt");

		assertNull("[MEDIAUTIL-STREAM] unexpected sendError: " + response.getErrorCode(), response.getErrorCode());
		assertArrayEquals("[MEDIAUTIL-STREAM] inline content path regressed", content, response.getWrittenBytes());
	}
}
