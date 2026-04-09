/**
 * cardGame.js — Card Game feature routes (ESM)
 * Lazy-loaded by features.js when 'cardGame' feature is enabled.
 */
import m from 'mithril';
import { am7model } from '../core/model.js';
import { navigation } from '../components/navigation.js';
import { page } from '../core/pageClient.js';

function getPage() { return am7model._page || page; }

let cardGameApp = null;
let loading = false;
let loadError = null;

async function loadCardGame() {
    if (cardGameApp) return cardGameApp;
    if (loading) return null;
    loading = true;
    try {
        await import('../styles/cardGame-v2.css');
        let mod = await import('../cardGame/CardGameApp.js');
        cardGameApp = mod.cardGameApp || mod.default;
        loading = false;
        return cardGameApp;
    } catch (e) {
        console.error("[cardGame] Failed to load:", e);
        loadError = e;
        loading = false;
        return null;
    }
}

function layout(content) {
    let pg = getPage();
    return [
        content,
        pg.loadToast(),
        pg.components.dialog ? pg.components.dialog.loadDialogs() : null
    ];
}

const routes = {
    "/cardGame": {
        oninit: function() {
            loadCardGame().then(() => m.redraw());
        },
        view: function() {
            if (loading) {
                return layout(
                    m("div", { style: "display:flex;flex-direction:column;height:100vh;overflow:hidden" }, [
                        m(navigation, {}),
                        m("div", { class: "flex-1 flex items-center justify-center" },
                            m("div", { class: "text-gray-500 text-lg" }, "Loading Card Game...")
                        )
                    ])
                );
            }
            if (loadError || !cardGameApp) {
                return layout(
                    m("div", { style: "display:flex;flex-direction:column;height:100vh;overflow:hidden" }, [
                        m(navigation, {}),
                        m("div", { class: "flex-1 flex items-center justify-center" },
                            m("div", { class: "text-red-500 text-lg" }, "Failed to load Card Game: " + (loadError ? loadError.message : "Unknown error"))
                        )
                    ])
                );
            }
            return layout(m(cardGameApp.component));
        }
    }
};

export { routes };
export default { routes };
