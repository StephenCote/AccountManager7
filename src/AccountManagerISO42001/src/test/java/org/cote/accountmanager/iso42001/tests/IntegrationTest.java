package org.cote.accountmanager.iso42001.tests;

/**
 * JUnit category marker for DB-backed integration tests (design §7.2): these open a live IO context
 * and exercise real CRUD/flows through {@code AccessPoint} against the {@code am7isotestdb} database,
 * but — unlike {@link LiveTest} — do NOT call a live LLM endpoint. Their inputs are constructed
 * fixtures, so they are deterministic and always runnable wherever the test DB is reachable.
 *
 * <p>Separated from {@link UnitTest} (pure logic, no DB) and {@link LiveTest} (DB + live LLM) so each
 * suite can be selected/excluded independently.</p>
 */
public interface IntegrationTest {
}
