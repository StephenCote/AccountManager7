import m from 'mithril';
import { am7client } from '../core/am7client.js';
import { applicationPath } from '../core/config.js';
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

function profileImage(userName) {
    let profilePath = null;
    if (page.user) {
        profilePath = am7client.getAttributeValue(page.user, "v1-profile-path", null);
    }
    if (profilePath) {
        return m("div", { class: "flex items-center gap-1 mx-2" }, [
            m("img", {
                src: applicationPath + "/thumbnail/" + profilePath + "/48x48",
                class: "w-8 h-8 rounded-full object-cover",
                onerror: function (e) { e.target.style.display = 'none'; }
            }),
            m("span", { class: "text-sm text-gray-500 dark:text-gray-400" }, userName)
        ]);
    }
    // Fallback: initials circle or generic icon
    let initials = userName ? userName.charAt(0).toUpperCase() : '?';
    return m("div", { class: "flex items-center gap-1 mx-2" }, [
        m("div", {
            class: "w-8 h-8 rounded-full bg-gray-300 dark:bg-gray-600 flex items-center justify-center text-sm font-medium text-gray-700 dark:text-gray-200"
        }, initials),
        m("span", { class: "text-sm text-gray-500 dark:text-gray-400" }, userName)
    ]);
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
                    title: mi.label,
                    'aria-label': mi.label
                }, m("span", { class: "material-symbols-outlined", 'aria-hidden': 'true' }, mi.icon));
            });
        return m("div", { class: "flex items-center gap-2", role: "toolbar", 'aria-label': "Main toolbar" }, [
            m("button", {
                class: "menu-button",
                onclick: function () { m.route.set("/main"); },
                title: "Home",
                'aria-label': "Home"
            }, m("span", { class: "material-symbols-outlined", 'aria-hidden': 'true' }, "home")),
            m("button", {
                class: "menu-button",
                onclick: toggleDarkMode,
                title: "Toggle dark mode",
                'aria-label': "Toggle dark mode"
            }, m("span", { class: "material-symbols-outlined", 'aria-hidden': 'true' }, "dark_mode")),
            m("button", {
                class: "menu-button" + (densityIndex ? " active" : ""),
                onclick: toggleDensity,
                title: "Toggle density: " + (densityModes[densityIndex] || "normal"),
                'aria-label': "Toggle density: " + (densityModes[densityIndex] || "normal")
            }, m("span", { class: "material-symbols-outlined", 'aria-hidden': 'true' }, "density_small")),
            ...featureButtons,
            m(notificationButton),
            profileImage(userName),
            m("button", {
                class: "menu-button",
                onclick: function () { page.logout(); },
                title: "Logout",
                'aria-label': "Logout"
            }, m("span", { class: "material-symbols-outlined", 'aria-hidden': 'true' }, "logout"))
        ]);
    }
};

export { topMenu, toggleDarkMode, initTheme };
export default topMenu;
