(function () {

    function gameContextButton() {
        let ico = m("span", { class: "material-symbols-outlined" }, "sports_esports");
        return m("button", { id: "gameContextMenuButton", class: "context-menu-button" }, [ico]);
    }

    function gameMenuButton(label, icon, route) {
        return m("button", {
            class: "context-menu-item text-left",
            onclick: function () {
                page.navigable.menu("#gameContextMenu");
                m.route.set(route);
            }
        }, [
            m("span", { class: "material-symbols-outlined mr-2" }, icon),
            m("span", label)
        ]);
    }

    function renderGameButton() {
        return m("div", { class: "context-menu-container" }, [
            gameContextButton(),
            m("div", { id: "gameContextMenu", class: "transition transition-0 context-menu-48" }, [
                gameMenuButton("Card Game", "playing_cards", "/cardGame"),
                gameMenuButton("Word Game", "match_word", "/game/wordGame"),
                gameMenuButton("Tetris", "grid_on", "/game/tetris"),
                gameMenuButton("Magic 8", "counter_8", "/magic8")
            ])
        ]);
    }

    let games = {
        menuButton: renderGameButton,
        component: {
            oncreate: function (x) {
                page.navigable.addContextMenu("#gameContextMenu", "#gameContextMenuButton");
            },
            oninit: function () {
                page.navigable.setupPendingContextMenus();
            },
            onupdate: function () {
                page.navigable.setupPendingContextMenus();
            },
            view: function (x) {
                return renderGameButton();
            }
        }
    };

    page.components.games = games;

}());
