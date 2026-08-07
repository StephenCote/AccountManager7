/**
 * @vitest-environment jsdom
 *
 * §3.6 — proves the disabled-feature catch-all against the REAL Mithril router, rather than
 * asserting that object-key insertion order happens to work out. Three claims are under test:
 *   1. a concrete route still wins even though the variadic template also matches it,
 *   2. a path owned by a known-but-disabled feature renders "This feature is not enabled.",
 *   3. a genuinely unknown path still falls back to the default route (the pre-existing behaviour).
 * Claim 1 is checked with the catch-all registered FIRST as well as last, which is what makes the
 * m.route.SKIP mechanism (not the key order) the thing doing the work.
 */
import { describe, it, expect, beforeEach, afterEach } from 'vitest';
import m from 'mithril';
import { createDisabledFeatureRoute, disabledFeatureRouteKey } from '../core/featureRoute.js';
import { initFeatures } from '../features.js';

const resolver = createDisabledFeatureRoute({});

function concrete(label) {
    return { view: function () { return m("div", { class: "marker" }, label); } };
}

/** Let Mithril's async route resolution + redraw settle (fireAsync -> setTimeout -> promise). */
async function settle() {
    for (let i = 0; i < 8; i++) {
        await new Promise(function (r) { setTimeout(r, 0); });
    }
}

function mount(routes, defaultRoute) {
    let root = document.createElement('div');
    document.body.appendChild(root);
    m.route(root, defaultRoute, routes);
    return root;
}

describe('§3.6 disabled-feature catch-all (real Mithril router)', () => {

    beforeEach(() => {
        window.location.hash = '';
        document.body.innerHTML = '';
    });

    afterEach(() => {
        document.body.innerHTML = '';
    });

    it('a concrete route still wins with the catch-all registered LAST', async () => {
        initFeatures(['core', 'chat']);
        let routes = { "/main": concrete('MAIN'), "/chat": concrete('CHAT') };
        routes[disabledFeatureRouteKey] = resolver;
        let root = mount(routes, "/main");
        await settle();
        m.route.set("/chat");
        await settle();
        expect(root.textContent).toContain('CHAT');
        expect(root.textContent).not.toContain('not enabled');
    });

    it('a concrete route still wins with the catch-all registered FIRST (order-independent)', async () => {
        initFeatures(['core', 'chat']);
        let routes = {};
        routes[disabledFeatureRouteKey] = resolver;
        routes["/main"] = concrete('MAIN');
        routes["/chat"] = concrete('CHAT');
        let root = mount(routes, "/main");
        await settle();
        m.route.set("/chat");
        await settle();
        expect(root.textContent).toContain('CHAT');
        expect(root.textContent).not.toContain('not enabled');
    });

    it('a path owned by a disabled feature renders the "not enabled" message', async () => {
        initFeatures(['core']); // chat + cardGame disabled, so neither route is registered
        let routes = { "/main": concrete('MAIN') };
        routes[disabledFeatureRouteKey] = resolver;
        let root = mount(routes, "/main");
        await settle();
        m.route.set("/cardGame");
        await settle();
        expect(root.textContent).toContain('This feature is not enabled.');
        expect(root.textContent).toContain('Card Game');
        expect(root.textContent).not.toContain('MAIN');
        expect(m.route.get()).toBe('/cardGame');
    });

    it('a nested disabled path is recognised too', async () => {
        initFeatures(['core']);
        let routes = { "/main": concrete('MAIN') };
        routes[disabledFeatureRouteKey] = resolver;
        let root = mount(routes, "/main");
        await settle();
        m.route.set("/iso42001/cert/view/abc-123");
        await settle();
        expect(root.textContent).toContain('This feature is not enabled.');
        expect(root.textContent).toContain('Compliance');
    });

    it('an unknown path still falls back to the default route', async () => {
        initFeatures(['core']);
        let routes = { "/main": concrete('MAIN') };
        routes[disabledFeatureRouteKey] = resolver;
        let root = mount(routes, "/main");
        await settle();
        m.route.set("/no/such/place");
        await settle();
        expect(root.textContent).toContain('MAIN');
        expect(root.textContent).not.toContain('not enabled');
        expect(m.route.get()).toBe('/main');
    });

    it('an enabled feature route is NOT hijacked when its own route is registered', async () => {
        initFeatures(['core', 'chat', 'cardGame']);
        let routes = { "/main": concrete('MAIN'), "/cardGame": concrete('CARDGAME') };
        routes[disabledFeatureRouteKey] = resolver;
        let root = mount(routes, "/main");
        await settle();
        m.route.set("/cardGame");
        await settle();
        expect(root.textContent).toContain('CARDGAME');
    });

    it('the default route itself is not swallowed by the variadic template', async () => {
        initFeatures(['core']);
        let routes = { "/main": concrete('MAIN') };
        routes[disabledFeatureRouteKey] = resolver;
        let root = mount(routes, "/main");
        await settle();
        expect(root.textContent).toContain('MAIN');
    });
});
