import m from 'mithril';
import { page } from '../core/pageClient.js';
import { topMenu } from './topMenu.js';
import { asideMenu } from './asideMenu.js';
import { breadcrumb } from './breadcrumb.js';
import { FullscreenManager } from './FullscreenManager.js';

let fullscreenMgr = new FullscreenManager();

function navigable() {
    let nav = {
        drawerOpen: false,
        fullscreenManager: fullscreenMgr,
        init: function () {
            document.addEventListener("keydown", function (e) {
                if (e.key === 'Escape') {
                    if (page.components.dialog) page.components.dialog.close();
                    nav.drawer(true);
                }
                // Ctrl+F11: toggle fullscreen
                if (e.key === 'F11' && e.ctrlKey) {
                    e.preventDefault();
                    let target = document.querySelector('.flex-1.overflow-auto') || document.body;
                    fullscreenMgr.toggleFullscreen(target);
                }
            });
        },
        addDrawerClickEvent: function (eventPath) {
            let el = document.querySelector(eventPath);
            if (el) {
                el.onclick = function () { nav.drawer(); };
            }
        },
        drawer: function (forceClose) {
            nav.drawerOpen = (!forceClose && !nav.drawerOpen) || false;
            nav.flash();
        },
        flash: function () {
            let glass = document.querySelector("div.screen-glass-slide");
            if (glass) {
                glass.className = "screen-glass-slide screen-glass-" + (nav.drawerOpen ? 'open' : 'close');
            }
            let aside = document.querySelector("aside.transition");
            if (aside) {
                aside.className = "transition transition-" + (nav.drawerOpen ? 'full' : '0');
            }
        }
    };
    return nav;
}

function asideToggle() {
    return m("div", { class: "menuToggle" }, [
        m("button", { class: "mr-2", 'aria-label': "Toggle navigation menu", 'aria-expanded': "false" },
            m("span", { class: "material-symbols-outlined material-icons-48", 'aria-hidden': 'true' }, "menu")
        )
    ]);
}

function asideGlass() {
    return m("div", { class: "screen-glass-slide screen-glass-close" });
}

const navigation = {
    oninit: function () {
        if (!page.authenticated()) return;
    },
    oncreate: function () {
        if (!page.authenticated()) return;
        if (page.navigable) {
            page.navigable.addDrawerClickEvent("div.screen-glass-slide");
            page.navigable.addDrawerClickEvent("div.menuToggle button");
        }
    },
    view: function (vnode) {
        if (!page.authenticated()) return '';
        let showBreadcrumb = !(vnode.attrs && vnode.attrs.hideBreadcrumb);
        return [
            m("nav", { class: "pageNav", 'aria-label': "Main navigation" }, [
                asideToggle(),
                m(topMenu)
            ]),
            asideGlass(),
            m(asideMenu),
            showBreadcrumb ? m(breadcrumb) : ''
        ];
    }
};

export { navigation, navigable };
export default navigation;
