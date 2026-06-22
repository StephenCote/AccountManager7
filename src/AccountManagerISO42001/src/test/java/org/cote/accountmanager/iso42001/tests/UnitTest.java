package org.cote.accountmanager.iso42001.tests;

/**
 * JUnit category marker for pure-logic unit tests — no DB, no LLM, no AccessPoint
 * (design §7.2). Phase-2 statistical/scoring components are all in this category
 * and are verified against hand-computed fixtures.
 */
public interface UnitTest {
}
