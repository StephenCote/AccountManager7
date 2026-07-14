package org.cote.accountmanager.objects.tests;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.WriteListener;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Hand-rolled (no mocking framework) {@link HttpServletResponse}, mirroring {@link HttpServletRequestMock}'s
 * pattern, for {@link TestMediaUtilStreaming}: captures everything {@code MediaUtil.writeBinaryData} actually
 * writes/sets (bytes, Content-Length, error/redirect status) so a real end-to-end assertion (byte-for-byte
 * content, checksum) can be made against the KI-22 streaming fix without a live servlet container.
 */
public class HttpServletResponseMock implements HttpServletResponse {

	private final ByteArrayOutputStream written = new ByteArrayOutputStream();
	private final Map<String, String> headers = new HashMap<>();
	private String contentType;
	private int contentLength = -1;
	private long contentLengthLong = -1L;
	private Integer errorCode;
	private String errorMessage;
	private String redirectLocation;
	private boolean committed = false;

	public byte[] getWrittenBytes() {
		return written.toByteArray();
	}

	public Integer getErrorCode() {
		return errorCode;
	}

	public String getErrorMessage() {
		return errorMessage;
	}

	public String getRedirectLocation() {
		return redirectLocation;
	}

	public int getContentLength() {
		return contentLength;
	}

	public long getContentLengthLong() {
		return contentLengthLong;
	}

	public String getContentType() {
		return contentType;
	}

	private final ServletOutputStream sos = new ServletOutputStream() {
		@Override
		public void write(int b) throws IOException {
			written.write(b);
		}

		@Override
		public void write(byte[] b, int off, int len) throws IOException {
			written.write(b, off, len);
		}

		@Override
		public boolean isReady() {
			return true;
		}

		@Override
		public void setWriteListener(WriteListener writeListener) {
		}
	};

	@Override public ServletOutputStream getOutputStream() throws IOException { return sos; }
	@Override public PrintWriter getWriter() throws IOException { return new PrintWriter(sos); }
	@Override public void setContentType(String type) { this.contentType = type; }
	@Override public void setContentLength(int len) { this.contentLength = len; }
	@Override public void setContentLengthLong(long len) { this.contentLengthLong = len; }
	@Override public void flushBuffer() throws IOException { committed = true; }
	@Override public boolean isCommitted() { return committed; }
	@Override public void resetBuffer() { }
	@Override public void reset() { }
	@Override public void setBufferSize(int size) { }
	@Override public int getBufferSize() { return 0; }
	@Override public void setCharacterEncoding(String encoding) { }
	@Override public String getCharacterEncoding() { return "UTF-8"; }
	@Override public void setLocale(Locale loc) { }
	@Override public Locale getLocale() { return Locale.getDefault(); }

	@Override public void sendError(int sc, String msg) throws IOException { this.errorCode = sc; this.errorMessage = msg; }
	@Override public void sendError(int sc) throws IOException { this.errorCode = sc; }
	@Override public void sendRedirect(String location, int sc, boolean clearBuffer) throws IOException { this.redirectLocation = location; }

	@Override public void addCookie(Cookie cookie) { }
	@Override public boolean containsHeader(String name) { return headers.containsKey(name); }
	@Override public String encodeURL(String url) { return url; }
	@Override public String encodeRedirectURL(String url) { return url; }
	@Override public void setDateHeader(String name, long date) { headers.put(name, String.valueOf(date)); }
	@Override public void addDateHeader(String name, long date) { headers.put(name, String.valueOf(date)); }
	@Override public void setHeader(String name, String value) { headers.put(name, value); }
	@Override public void addHeader(String name, String value) { headers.put(name, value); }
	@Override public void setIntHeader(String name, int value) { headers.put(name, String.valueOf(value)); }
	@Override public void addIntHeader(String name, int value) { headers.put(name, String.valueOf(value)); }
	@Override public void setStatus(int sc) { }
	@Override public int getStatus() { return committed ? 200 : 0; }
	@Override public String getHeader(String name) { return headers.get(name); }
	@Override public Collection<String> getHeaders(String name) {
		String v = headers.get(name);
		return (v == null) ? Collections.emptyList() : Collections.singletonList(v);
	}
	@Override public Collection<String> getHeaderNames() { return headers.keySet(); }
}
