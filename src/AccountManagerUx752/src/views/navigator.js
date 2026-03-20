/**
 * navigator.js — Split-pane navigator: tree (left) + list (right) (ESM)
 * Port of Ux7 client/view/navigator.js
 *
 * Split pane with tree on left and list on right. Full-mode toggle,
 * embedded pagination, inline editing support.
 */
import m from 'mithril';
import { am7model } from '../core/model.js';
import { page } from '../core/pageClient.js';
import { newTreeComponent } from '../components/tree.js';
import { newListControl } from './list.js';

function newNavigatorControl() {
    const navigator = {};
    let fullMode = false;
    let listView;
    let treeView;
    let altView;
    let origin;

    function getSplitContainerView() {
        let splitLeft = fullMode ? null : getSplitLeftContainerView();
        return m("div", { class: "flex flex-1 overflow-hidden", onselectstart: function(e) { e.preventDefault(); } }, [
            splitLeft,
            getSplitRightContainerView()
        ]);
    }

    function getSplitRightContainerView() {
        let cls = fullMode ? "flex-1 flex flex-col overflow-hidden" : "flex-1 flex flex-col overflow-hidden border-l border-gray-200 dark:border-gray-700";
        return m("div", { class: cls }, [getResultsView()]);
    }

    function getSplitLeftContainerView() {
        return m("div", {
            class: "w-64 shrink-0 overflow-y-auto bg-gray-50 dark:bg-gray-900"
        }, [
            m(treeView, { origin: origin, list: listView })
        ]);
    }

    function getResultsView() {
        let mtx = treeView.selectedNode();
        if (!mtx) return m("div", { class: "p-4 text-gray-400" }, "Select a node");

        if (altView) {
            return m(altView.view.view, {
                fullMode: fullMode,
                type: altView.type,
                objectId: altView.containerId,
                new: altView.new,
                embeddedMode: true,
                embeddedController: navigator
            });
        }

        return m(listView.view, {
            fullMode: fullMode,
            type: mtx.listEntityType,
            objectId: mtx.id,
            startRecord: 0,
            recordCount: 0,
            embeddedMode: true,
            embeddedController: navigator
        });
    }

    navigator.toggleFullMode = function() {
        fullMode = !fullMode;
        m.redraw();
    };

    navigator.cancelView = function() {
        altView = undefined;
        fullMode = false;
        m.redraw();
    };

    navigator.editItem = function(object) {
        if (!object) return;
        altView = {
            fullMode: fullMode,
            view: page.views.object(),
            type: object[am7model.jsonModelKey],
            containerId: object.objectId
        };
        m.redraw();
    };

    navigator.addNew = function(type, containerId, parentNew) {
        altView = {
            fullMode: fullMode,
            view: page.views.object(),
            type: type,
            containerId: containerId,
            parentNew: parentNew,
            new: true
        };
        m.redraw();
    };

    navigator.view = {
        oninit: function(vnode) {
            origin = (vnode.attrs && vnode.attrs.origin) || (page.user ? page.user.homeDirectory : null);
            if (!listView) listView = newListControl();
            if (!treeView) treeView = newTreeComponent();
        },
        onremove: function() {
            altView = undefined;
        },
        view: function() {
            return navigator.renderContent();
        }
    };

    navigator.renderContent = function() {
        if (!page.authenticated()) return m("div", { class: "p-4" }, "Loading...");

        let containerClass = fullMode
            ? "fixed inset-0 z-50 bg-white dark:bg-gray-900 flex flex-col"
            : "flex-1 flex flex-col overflow-hidden";

        return m("div", { class: containerClass }, [
            // Toolbar
            m("div", { class: "flex items-center gap-2 px-3 py-1.5 border-b border-gray-200 dark:border-gray-700 shrink-0" }, [
                page.iconButton("button" + (fullMode ? " active" : ""),
                    fullMode ? "close_fullscreen" : "open_in_new", "", navigator.toggleFullMode),
                m("span", { class: "text-sm font-medium text-gray-600 dark:text-gray-300" }, "Navigator")
            ]),
            getSplitContainerView()
        ]);
    };

    return navigator;
}

page.views.navigator = newNavigatorControl;

export { newNavigatorControl };
export default newNavigatorControl;
