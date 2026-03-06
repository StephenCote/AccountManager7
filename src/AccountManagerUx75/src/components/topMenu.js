import m from 'mithril';
import { page } from '../core/pageClient.js';
import { getMenuItems } from '../features.js';
import { notificationButton } from './notifications.js';

function toggleDarkMode() {
    let html = document.documentElement;
    if (html.classList.contains('dark')) {
        html.classList.remove('dark');
        html.classList.add('light');
        localStorage.setItem('am7.theme', 'light');
    } else {
        html.classList.remove('light');
        html.classList.add('dark');
        localStorage.setItem('am7.theme', 'dark');
    }
}

let densityModes = ['', 'density-compact', 'density-comfortable'];
let densityIndex = 0;

function toggleDensity() {
    let html = document.documentElement;
    densityModes.forEach(function (c) { if (c) html.classList.remove(c); });
    densityIndex = (densityIndex + 1) % densityModes.length;
    if (densityModes[densityIndex]) html.classList.add(densityModes[densityIndex]);
    localStorage.setItem('am7.density', densityIndex);
}

function initTheme() {
    let saved = localStorage.getItem('am7.theme');
    if (saved === 'dark') {
        document.documentElement.classList.remove('light');
        document.documentElement.classList.add('dark');
    }
    let savedDensity = parseInt(localStorage.getItem('am7.density') || '0');
    if (savedDensity > 0 && savedDensity < densityModes.length) {
        densityIndex = savedDensity;
        document.documentElement.classList.add(densityModes[densityIndex]);
    }
}

const topMenu = {
    oninit: function () {
        initTheme();
    },
    view: function () {
        let userName = page.user ? page.user.name : '';
        let featureButtons = getMenuItems('top')
            .filter(function (mi) { return !(mi.devOnly && page.productionMode); })
            .map(function (mi) {
                return m("button", {
                    class: "menu-button",
                    onclick: function () { m.route.set(mi.route); },
                    title: mi.label
                }, m("span", { class: "material-symbols-outlined" }, mi.icon));
            });
        return m("div", { class: "flex items-center gap-2" }, [
            m("button", {
                class: "menu-button",
                onclick: function () { m.route.set("/main"); },
                title: "Home"
            }, m("span", { class: "material-symbols-outlined" }, "home")),
            m("button", {
                class: "menu-button",
                onclick: toggleDarkMode,
                title: "Toggle dark mode"
            }, m("span", { class: "material-symbols-outlined" }, "dark_mode")),
            m("button", {
                class: "menu-button" + (densityIndex ? " active" : ""),
                onclick: toggleDensity,
                title: "Toggle density: " + (densityModes[densityIndex] || "normal")
            }, m("span", { class: "material-symbols-outlined" }, "density_small")),
            ...featureButtons,
            m(notificationButton),
            m("span", { class: "text-sm text-gray-500 dark:text-gray-400 mx-2" }, userName),
            m("button", {
                class: "menu-button",
                onclick: function () { page.logout(); },
                title: "Logout"
            }, m("span", { class: "material-symbols-outlined" }, "logout"))
        ]);
    }
};

export { topMenu, toggleDarkMode, initTheme };
export default topMenu;
