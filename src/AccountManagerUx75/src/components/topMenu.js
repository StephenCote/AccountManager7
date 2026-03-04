import m from 'mithril';
import { page } from '../core/pageClient.js';

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

function initTheme() {
    let saved = localStorage.getItem('am7.theme');
    if (saved === 'dark') {
        document.documentElement.classList.remove('light');
        document.documentElement.classList.add('dark');
    }
}

const topMenu = {
    oninit: function () {
        initTheme();
    },
    view: function () {
        let userName = page.user ? page.user.name : '';
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
