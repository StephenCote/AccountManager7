package org.cote.accountmanager.objects.tests;

/**
 * JUnit category (design §7.2) for tests that require a running Service7 (live HTTP/JSON-RPC round-trips).
 * Distinct from {@code UnitTest}/{@code IntegrationTest}: these are driven against a deployed appserver,
 * started by the operator at the top of the Track-B session.
 */
public interface RestIntegrationTest {
}
