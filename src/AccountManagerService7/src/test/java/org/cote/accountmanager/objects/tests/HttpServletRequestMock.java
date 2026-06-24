package org.cote.accountmanager.objects.tests;

import java.io.BufferedReader;
import java.io.IOException;
import java.security.Principal;
import java.util.Collection;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

import jakarta.servlet.AsyncContext;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.ServletConnection;
import jakarta.servlet.ServletContext;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpUpgradeHandler;
import jakarta.servlet.http.Part;

/**
 * Hand-rolled (no mocking framework) {@link HttpServletRequest} for Phase-7 component tests of the ISO 42001
 * REST shim. Only what the service path actually consults is implemented — the {@link Principal} (so
 * {@code ServiceUtil.getPrincipalUser} resolves the acting user) and headers; everything else returns a benign
 * default. Mirrors the AM6.5 {@code HttpServletRequestMock} pattern Stephen pointed to, ported to jakarta.
 */
public class HttpServletRequestMock implements HttpServletRequest {

	private Principal principal;
	private final Map<String, String> headers = new HashMap<>();

	public HttpServletRequestMock() {
	}

	public HttpServletRequestMock(Principal principal) {
		this.principal = principal;
	}

	public void setUserPrincipal(Principal principal) {
		this.principal = principal;
	}

	public void setHeader(String name, String value) {
		headers.put(name, value);
	}

	@Override
	public Principal getUserPrincipal() {
		return principal;
	}

	@Override
	public String getHeader(String name) {
		return headers.get(name);
	}

	@Override
	public Enumeration<String> getHeaders(String name) {
		String v = headers.get(name);
		return (v == null) ? Collections.emptyEnumeration() : Collections.enumeration(java.util.List.of(v));
	}

	@Override
	public Enumeration<String> getHeaderNames() {
		return Collections.enumeration(headers.keySet());
	}

	// ── Everything below is a benign default ─────────────────────────────────

	@Override public String getAuthType() { return null; }
	@Override public Cookie[] getCookies() { return new Cookie[0]; }
	@Override public long getDateHeader(String name) { return -1; }
	@Override public int getIntHeader(String name) { return -1; }
	@Override public String getMethod() { return null; }
	@Override public String getPathInfo() { return null; }
	@Override public String getPathTranslated() { return null; }
	@Override public String getContextPath() { return ""; }
	@Override public String getQueryString() { return null; }
	@Override public String getRemoteUser() { return (principal == null) ? null : principal.getName(); }
	@Override public boolean isUserInRole(String role) { return false; }
	@Override public String getRequestedSessionId() { return null; }
	@Override public String getRequestURI() { return null; }
	@Override public StringBuffer getRequestURL() { return new StringBuffer(); }
	@Override public String getServletPath() { return ""; }
	@Override public jakarta.servlet.http.HttpSession getSession(boolean create) { return null; }
	@Override public jakarta.servlet.http.HttpSession getSession() { return null; }
	@Override public String changeSessionId() { return null; }
	@Override public boolean isRequestedSessionIdValid() { return false; }
	@Override public boolean isRequestedSessionIdFromCookie() { return false; }
	@Override public boolean isRequestedSessionIdFromURL() { return false; }
	@Override public boolean authenticate(HttpServletResponse response) throws IOException, ServletException { return false; }
	@Override public void login(String username, String password) throws ServletException { }
	@Override public void logout() throws ServletException { }
	@Override public Collection<Part> getParts() throws IOException, ServletException { return Collections.emptyList(); }
	@Override public Part getPart(String name) throws IOException, ServletException { return null; }
	@Override public <T extends HttpUpgradeHandler> T upgrade(Class<T> handlerClass) throws IOException, ServletException { return null; }
	@Override public jakarta.servlet.http.HttpServletMapping getHttpServletMapping() { return HttpServletRequest.super.getHttpServletMapping(); }
	@Override public jakarta.servlet.http.PushBuilder newPushBuilder() { return null; }
	@Override public Map<String, String> getTrailerFields() { return Collections.emptyMap(); }
	@Override public boolean isTrailerFieldsReady() { return true; }

	@Override public Object getAttribute(String name) { return null; }
	@Override public Enumeration<String> getAttributeNames() { return Collections.emptyEnumeration(); }
	@Override public String getCharacterEncoding() { return "UTF-8"; }
	@Override public void setCharacterEncoding(String env) { }
	@Override public int getContentLength() { return -1; }
	@Override public long getContentLengthLong() { return -1; }
	@Override public String getContentType() { return null; }
	@Override public ServletInputStream getInputStream() throws IOException { return null; }
	@Override public String getParameter(String name) { return null; }
	@Override public Enumeration<String> getParameterNames() { return Collections.emptyEnumeration(); }
	@Override public String[] getParameterValues(String name) { return new String[0]; }
	@Override public Map<String, String[]> getParameterMap() { return Collections.emptyMap(); }
	@Override public String getProtocol() { return "HTTP/1.1"; }
	@Override public String getScheme() { return "http"; }
	@Override public String getServerName() { return "localhost"; }
	@Override public int getServerPort() { return 0; }
	@Override public BufferedReader getReader() throws IOException { return null; }
	@Override public String getRemoteAddr() { return "127.0.0.1"; }
	@Override public String getRemoteHost() { return "localhost"; }
	@Override public void setAttribute(String name, Object o) { }
	@Override public void removeAttribute(String name) { }
	@Override public Locale getLocale() { return Locale.getDefault(); }
	@Override public Enumeration<Locale> getLocales() { return Collections.enumeration(java.util.List.of(Locale.getDefault())); }
	@Override public boolean isSecure() { return false; }
	@Override public RequestDispatcher getRequestDispatcher(String path) { return null; }
	@Override public int getRemotePort() { return 0; }
	@Override public String getLocalName() { return "localhost"; }
	@Override public String getLocalAddr() { return "127.0.0.1"; }
	@Override public int getLocalPort() { return 0; }
	@Override public ServletContext getServletContext() { return null; }
	@Override public AsyncContext startAsync() throws IllegalStateException { return null; }
	@Override public AsyncContext startAsync(ServletRequest req, ServletResponse res) throws IllegalStateException { return null; }
	@Override public boolean isAsyncStarted() { return false; }
	@Override public boolean isAsyncSupported() { return false; }
	@Override public AsyncContext getAsyncContext() { return null; }
	@Override public DispatcherType getDispatcherType() { return DispatcherType.REQUEST; }
	@Override public String getRequestId() { return ""; }
	@Override public String getProtocolRequestId() { return ""; }
	@Override public ServletConnection getServletConnection() { return null; }
}
