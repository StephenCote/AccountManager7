/**
 * explorer.js — File explorer view: tree panel (left) + list panel (right)
 * Phase 11b-3: Split layout for browsing group hierarchy
 *
 * Reuses tree.js for the left panel and list.js for the right panel.
 */
import m from 'mithril';
import { am7model } from '../core/model.js';
import { page } from '../core/pageClient.js';
import { newTreeComponent } from '../components/tree.js';
import { newListControl } from './list.js';

function newExplorerControl() {
    const explorer = {};
    let treeView;
    let listView;
    let origin;
    let fullMode = false;
    let lastSelectedId = null;

    function getSplitView() {
        return m("div", { class: "flex flex-1 overflow-hidden" }, [
            // Left: tree panel
            m("div", {
                class: "shrink-0 overflow-y-auto bg-gray-50 dark:bg-gray-900 border-r border-gray-200 dark:border-gray-700",
                style: "width:250px"
            }, [
                treeView ? m(treeView, { origin: origin, list: listView }) : null
            ]),
            // Right: list panel
            m("div", { class: "flex-1 flex flex-col overflow-hidden" }, [
                getListView()
            ])
        ]);
    }

    function getListView() {
        let mtx = treeView ? treeView.selectedNode() : null;
        if (!mtx) {
            return m("div", { class: "flex-1 flex items-center justify-center text-gray-400" },
                m("div", { class: "text-center" }, [
                    m("span", { class: "material-symbols-outlined", style: "font-size:48px" }, "folder_open"),
                    m("div", { class: "mt-2" }, "Select a folder in the tree")
                ])
            );
        }

        // When the selected node changes, reset list pagination
        if (mtx.id !== lastSelectedId) {
            lastSelectedId = mtx.id;
            if (listView && listView.pagination) listView.pagination().new();
        }

        return m(listView.view, {
            type: mtx.listEntityType,
            objectId: mtx.id,
            startRecord: 0,
            recordCount: 0,
            embeddedMode: true,
            embeddedController: explorer
        });
    }

    explorer.toggleFullMode = function () {
        fullMode = !fullMode;
        m.redraw();
    };

    explorer.cancelView = function () {
        fullMode = false;
        m.redraw();
    };

    explorer.editItem = function (object) {
        if (!object) return;
        let type = object[am7model.jsonModelKey] || '';
        m.route.set('/view/' + type + '/' + object.objectId);
    };

    explorer.view = {
        oninit: function (vnode) {
            origin = (vnode.attrs && vnode.attrs.origin) || (page.user ? page.user.homeDirectory : null);
            if (!listView) listView = newListControl();
            if (!treeView) treeView = newTreeComponent();
        },
        onremove: function () { },
        view: function () {
            return explorer.renderContent();
        }
    };

    explorer.renderContent = function () {
        if (!page.authenticated()) return m("div", { class: "p-4" }, "Loading...");

        let containerClass = fullMode
            ? "fixed inset-0 z-50 bg-white dark:bg-gray-900 flex flex-col"
            : "flex-1 flex flex-col overflow-hidden";

        return m("div", { class: containerClass }, [
            // Toolbar
            m("div", { class: "flex items-center gap-2 px-3 py-1.5 border-b border-gray-200 dark:border-gray-700 shrink-0" }, [
                page.iconButton("button" + (fullMode ? " active" : ""),
                    fullMode ? "close_fullscreen" : "open_in_new", "", explorer.toggleFullMode),
                m("span", { class: "material-symbols-outlined material-icons-24 text-gray-500" }, "folder_open"),
                m("span", { class: "text-sm font-medium text-gray-600 dark:text-gray-300" }, "Explorer")
            ]),
            getSplitView()
        ]);
    };

    return explorer;
}

export { newExplorerControl };
export default newExplorerControl;
