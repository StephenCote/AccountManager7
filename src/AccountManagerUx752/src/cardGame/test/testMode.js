/**
 * testMode.js — Card Game test mode stub (ESM)
 * Full port of Ux7 testMode.js (3,211 lines) deferred.
 * Provides minimal interface expected by CardGameApp.
 */

const TEST_CARDS = [];

let testState = {
    running: false,
    results: [],
    passed: 0,
    failed: 0,
    total: 0
};

async function runTestSuite() {
    console.warn("[cardGame/test] Full test mode not yet ported");
    return testState;
}

const TestModeUI = {
    view() {
        return null;
    }
};

const testMode = {
    TEST_CARDS,
    testState,
    runTestSuite,
    TestModeUI
};

export { testMode, TEST_CARDS, testState, runTestSuite, TestModeUI };
export default testMode;
