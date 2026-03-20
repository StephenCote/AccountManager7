/**
 * games.js — Mini-games feature routes (ESM)
 * Lazy-loaded by features.js when 'games' feature is enabled.
 * Routes to tetris, wordGame via /game/:gameId
 */
import m from 'mithril';
import { am7model } from '../core/model.js';
import { navigation } from '../components/navigation.js';
import { page } from '../core/pageClient.js';

function getPage() { return am7model._page || page; }

// Lazy-load game modules
let tetrisModule = null;
let wordGameModule = null;

async function loadTetris() {
    if (!tetrisModule) {
        let mod = await import('../games/tetris.js');
        tetrisModule = mod.tetris || mod.default;
    }
    return tetrisModule;
}

async function loadWordGame() {
    if (!wordGameModule) {
        let mod = await import('../games/wordGame.js');
        wordGameModule = mod.wordGame || mod.default;
    }
    return wordGameModule;
}

const gameRegistry = {
    tetris: { label: 'Tetris', icon: 'grid_view', load: loadTetris },
    wordGame: { label: 'Word Battle', icon: 'dictionary', load: loadWordGame }
};

let currentGameId = null;
let currentGame = null;
let loading = false;

function layout(content) {
    let pg = getPage();
    return [
        content,
        pg.loadToast(),
        pg.components.dialog ? pg.components.dialog.loadDialogs() : null
    ];
}

function pageLayout(innerContent) {
    return m("div", { style: "display:flex;flex-direction:column;height:100vh;overflow:hidden" }, [
        m(navigation, {}),
        m("div", { class: "flex-1 overflow-auto flex bg-white dark:bg-gray-900" }, [
            innerContent
        ])
    ]);
}

function gameMenu() {
    let pg = getPage();
    return m("div", { class: "flex-grow flex items-center justify-center bg-gray-50 dark:bg-gray-900" }, [
        m("div", { class: "text-center" }, [
            m("h1", { class: "text-3xl font-bold mb-6 dark:text-white" }, "Mini Games"),
            m("div", { class: "flex gap-4 justify-center flex-wrap" },
                Object.entries(gameRegistry).map(([id, info]) =>
                    m("button", {
                        class: "flex flex-col items-center gap-2 px-6 py-4 bg-white dark:bg-gray-800 rounded-lg shadow hover:shadow-lg transition-shadow border border-gray-200 dark:border-gray-700",
                        onclick: () => { m.route.set("/game/" + id); }
                    }, [
                        m("span", { class: "material-symbols-outlined text-4xl text-blue-500" }, info.icon),
                        m("span", { class: "text-lg font-medium dark:text-white" }, info.label)
                    ])
                )
            )
        ])
    ]);
}

const routes = {
    "/game": {
        view: function() {
            return layout(pageLayout(gameMenu()));
        }
    },
    "/game/:gameId": {
        oninit: function(vnode) {
            let gameId = vnode.attrs.gameId;
            if (gameId !== currentGameId) {
                currentGameId = gameId;
                currentGame = null;
                let entry = gameRegistry[gameId];
                if (entry) {
                    loading = true;
                    entry.load().then(mod => {
                        currentGame = mod;
                        loading = false;
                        m.redraw();
                    }).catch(e => {
                        console.error("[games] Failed to load game:", gameId, e);
                        loading = false;
                        m.redraw();
                    });
                }
            }
        },
        onremove: function() {
            if (currentGame) {
                if (currentGame.onremove) currentGame.onremove();
                else if (currentGame.component && currentGame.component.onremove) currentGame.component.onremove();
            }
            currentGameId = null;
            currentGame = null;
        },
        view: function(vnode) {
            if (loading) {
                return layout(pageLayout(
                    m("div", { class: "flex-grow flex items-center justify-center" },
                        m("div", { class: "text-gray-500 dark:text-gray-400 text-lg" }, "Loading game...")
                    )
                ));
            }
            if (!currentGame) {
                return layout(pageLayout(
                    m("div", { class: "flex-grow flex items-center justify-center" },
                        m("div", { class: "text-red-500 text-lg" }, "Game not found: " + vnode.attrs.gameId)
                    )
                ));
            }
            // Render game component
            let content;
            let scoreCard = null;
            if (currentGame.view) {
                content = currentGame.view(vnode);
                if (currentGame.scoreCard) scoreCard = currentGame.scoreCard();
            } else if (currentGame.component && currentGame.component.view) {
                content = m(currentGame.component, vnode.attrs);
                if (currentGame.component.scoreCard) scoreCard = currentGame.component.scoreCard();
            } else {
                content = m("div", "Game loaded but has no view");
            }
            return layout(pageLayout(
                m("div", { class: "flex-grow flex flex-col overflow-hidden" }, [scoreCard, content])
            ));
        }
    }
};

export { routes };
export default { routes };
