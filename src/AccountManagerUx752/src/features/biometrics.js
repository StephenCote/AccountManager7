/**
 * Biometrics feature — Magic 8 immersive session (ESM)
 * Lazy-loads the full Magic8App on route entry.
 * Route: /magic8
 */
import m from 'mithril';
import { am7model } from '../core/model.js';
import { navigation } from '../components/navigation.js';
import { page } from '../core/pageClient.js';
import { layout } from '../router.js';

function getPage() { return am7model._page || page; }

let magic8App = null;
let loading = false;
let loadError = null;

async function loadMagic8() {
    if (magic8App) return magic8App;
    if (loading) return null;
    loading = true;
    try {
        let mod = await import('../magic8/Magic8App.js');
        magic8App = mod.Magic8App || mod.default;
        loading = false;
        return magic8App;
    } catch (e) {
        console.error("[magic8] Failed to load:", e);
        loadError = e;
        loading = false;
        return null;
    }
}

const routes = {
    "/magic8": {
        oninit: function() {
            loadMagic8().then(() => m.redraw());
        },
        view: function(vnode) {
            if (loading) {
                return layout(
                    m("div", { style: "display:flex;flex-direction:column;height:100vh;overflow:hidden" }, [
                        m(navigation, {}),
                        m("div", { class: "flex-1 flex items-center justify-center bg-gray-900" },
                            m("div", { class: "text-gray-400 text-lg" }, "Loading Magic 8...")
                        )
                    ])
                );
            }
            if (loadError || !magic8App) {
                return layout(
                    m("div", { style: "display:flex;flex-direction:column;height:100vh;overflow:hidden" }, [
                        m(navigation, {}),
                        m("div", { class: "flex-1 flex items-center justify-center bg-gray-900" },
                            m("div", { class: "text-red-500 text-lg" }, "Failed to load Magic 8: " + (loadError ? loadError.message : "Unknown error"))
                        )
                    ])
                );
            }
            // Magic8App renders fullscreen — no nav/layout wrapper needed
            return layout(m(magic8App, { routeParams: vnode.attrs }));
        }
    }
};

export { routes };
export default { routes };
