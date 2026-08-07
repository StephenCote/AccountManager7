/**
 * Disabled-feature catch-all route (design §3.6).
 *
 * Kept in its own module — with the layout wrapper injected — so it can be mounted against the real
 * Mithril router in a unit test without dragging in router.js's whole view graph, and so router.js
 * does not have to import a module that imports it back.
 *
 * Mechanism, spelled out because it is easy to get wrong: Mithril compiles `Object.keys(routes)` and
 * takes the FIRST template whose check() passes (mithril/api/router.js `resolveRoute` -> `loop`), and
 * the variadic `:featurePath...` template compiles to `^/(.*)$` — it matches EVERYTHING, including
 * "/main", every concrete route, and genuinely unknown paths. Correctness therefore does not rest on
 * object-key insertion order: the resolver returns `m.route.SKIP` for any path it does not own, which
 * makes Mithril resume its loop at the next matching route and, when nothing else matches, fall back
 * to the default route exactly as it did before this existed. Registering it last is only an
 * optimisation (concrete routes keep resolving without the extra promise tick), not the mechanism.
 */
import m from 'mithril';
import { disabledFeatureForPath, features } from '../features.js';

const disabledFeatureRouteKey = "/:featurePath...";

function createDisabledFeatureRoute(opts) {
    let o = opts || {};
    let wrap = (typeof o.wrap === 'function') ? o.wrap : function (content) { return content; };
    return {
        onmatch: function (args, requestedPath) {
            let path = String(requestedPath || '').split('?')[0];
            let id = disabledFeatureForPath(path);
            if (!id) return m.route.SKIP;
            let f = features[id];
            let label = (f && f.label) ? f.label : id;
            return {
                view: function () {
                    return wrap(m("div", { class: "p-8 max-w-2xl" }, [
                        m("h2", { class: "text-xl font-semibold text-gray-900 dark:text-white mb-2" },
                            "This feature is not enabled."),
                        m("p", { class: "text-sm text-gray-600 dark:text-gray-400" },
                            label + " is not enabled for this organization, so " + path + " cannot be opened."),
                        m("button", {
                            class: "mt-4 px-4 py-2 rounded bg-blue-600 hover:bg-blue-700 text-white text-sm font-medium",
                            onclick: function () { m.route.set("/main"); }
                        }, "Back to home")
                    ]));
                }
            };
        }
    };
}

export { disabledFeatureRouteKey, createDisabledFeatureRoute };
