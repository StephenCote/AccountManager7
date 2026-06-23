package org.cote.accountmanager.iso42001.tests;

/**
 * JUnit category marker for live-LLM integration tests (design §7.2): these open a live IO
 * context AND call a configured LLM endpoint. They are gated behind endpoint availability —
 * when the endpoint is unreachable they still exercise the seeded-plan, persistence, logging,
 * and RBAC paths, and mark the statistical/verdict assertions not-run rather than fabricating
 * a result. Separated from {@link UnitTest} so the live suite can be selected/excluded.
 */
public interface LiveTest {
}
