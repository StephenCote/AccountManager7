import m from 'mithril';
import { am7model } from '../core/model.js';
import { am7view } from '../core/view.js';
import { am7client, uwm } from '../core/am7client.js';
import { page } from '../core/pageClient.js';
// These components will be wired up in later phases
// import { newPaginationControl } from '../components/pagination.js';
// import { decorator } from '../components/decorator.js';
import { navigation } from '../components/navigation.js';

// ---------------------------------------------------------------------------
//  newListControl  --  factory that returns a list-page controller + view
// ---------------------------------------------------------------------------

function newListControl() {

    const listPage = {};

    // --- State variables ---

    let listType;
    let baseListType;
    let listContainerId;
    let containerMode = false;
    let gridMode = 0;           // 0 = list only (grid/carousel in later phases)
    let embeddedMode = false;
    let pickerMode = false;
    let modType;
    let navigateByParent = false;
    let systemList = false;
    let navContainerId = null;
    let navFilter = null;
    let defaultRecordCount = 10;

    // Pagination stub -- when the real pagination component is available it
    // will be provided via `page.pagination()` or imported directly.  For now
    // we create a minimal shim so the rest of the code can reference it.
    let pagination = (page.pagination ? page.pagination() : newPaginationShim());

    // ------------------------------------------------------------------
    //  Pagination shim -- keeps the view functional before the real
    //  pagination component lands.  Replace with the import once it
    //  exists.
    // ------------------------------------------------------------------

    function newPaginationShim() {
        let pages = {
            navigateByParent: false,
            listSystem: false,
            startRecord: 0,
            recordCount: 10,
            displayCount: 0,
            totalCount: 0,
            counted: false,
            currentPage: 1,
            currentItem: 0,
            pageCount: 0,
            pageResults: [],
            pageState: {},
            resultType: null,
            filter: null,
            containerType: 'auth.group',
            containerSubType: null,
            containerId: null,
            container: null,
            sort: 'name',
            order: 'ascending'
        };

        async function doSearch() {
            if (!pages.resultType) return;
            let q = am7client.newQuery(pages.resultType);
            if (pages.filter) {
                let qf = q.field('name', pages.filter);
                qf.comparator = 'like';
            }
            q.range(pages.startRecord, pages.recordCount);
            q.sort(pages.sort);
            q.order(pages.order);
            if (page.user) {
                q.field('organizationId', page.user.organizationId);
            }
            if (pages.containerId) {
                let gq = am7view.viewQuery(am7model.newInstance(pages.containerType));
                gq.field('objectId', pages.containerId);
                let g = await am7client.search(gq);
                if (g && g.results && g.results.length) {
                    let id = g.results[0].id;
                    if (am7model.isGroup(pages.resultType) && am7model.hasField(pages.resultType, 'groupId')) {
                        q.field('groupId', id);
                    } else if (am7model.isParent(pages.resultType)) {
                        q.field('parentId', id);
                    }
                }
            }
            let qr = await am7client.search(q);
            let results = qr;
            if (qr && qr[am7model.jsonModelKey] === 'io.queryResult') {
                results = qr.results;
                pages.totalCount = qr.count || 0;
            }
            if (results && results.length) {
                am7model.updateListModel(results);
            }
            pages.pageResults[pages.currentPage] = results || [];
            pages.counted = true;
            pages.pageCount = Math.max(1, Math.ceil(pages.totalCount / pages.recordCount));
            m.redraw();
        }

        return {
            pages: () => pages,
            state: (o) => {
                if (!pages.pageState[o.objectId]) pages.pageState[o.objectId] = {};
                return pages.pageState[o.objectId];
            },
            update: (type, containerId, byParent, listFilter, startRecord, recordCount, sys) => {
                pages.resultType = type;
                pages.containerId = containerId;
                pages.navigateByParent = byParent;
                pages.filter = listFilter || null;
                pages.startRecord = parseInt(startRecord) || 0;
                pages.recordCount = parseInt(recordCount) || defaultRecordCount;
                pages.listSystem = sys;
                if (pages.currentPage < 1) pages.currentPage = 1;
                doSearch();
            },
            filter: (value, redraw) => {
                pages.filter = value || null;
                pages.startRecord = 0;
                pages.currentPage = 1;
                doSearch();
                if (redraw) m.redraw();
            },
            new: () => {
                pages.currentPage = 1;
                pages.pageResults = [];
                pages.pageState = {};
                pages.counted = false;
            },
            stop: () => { /* no-op until real pagination */ },
            setEmbeddedMode: (b) => { /* no-op */ },
            next: () => {
                if (pages.currentPage < pages.pageCount) {
                    pages.currentPage++;
                    pages.startRecord = (pages.currentPage - 1) * pages.recordCount;
                    doSearch();
                }
            },
            prev: () => {
                if (pages.currentPage > 1) {
                    pages.currentPage--;
                    pages.startRecord = (pages.currentPage - 1) * pages.recordCount;
                    doSearch();
                }
            },
            pageButtons: () => {
                let btns = [];
                if (pages.pageCount <= 1) return m('span');
                btns.push(iconBtn('button', 'chevron_left', '', () => shim.prev()));
                btns.push(m('span', { class: 'page-indicator' }, pages.currentPage + ' / ' + pages.pageCount));
                btns.push(iconBtn('button', 'chevron_right', '', () => shim.next()));
                return btns;
            },
            button: iconBtn
        };
    }

    // ------------------------------------------------------------------
    //  Helpers
    // ------------------------------------------------------------------

    function iconBtn(sClass, sIco, sLabel, fHandler) {
        return m('button', { onclick: fHandler, class: sClass }, [
            sIco ? m('span', { class: 'material-symbols-outlined material-icons-24' }, sIco) : '',
            sLabel || ''
        ]);
    }

    function getType(o) {
        return o[am7model.jsonModelKey] || listType;
    }

    function getSelected() {
        let pg = pagination.pages();
        let results = pg.pageResults[pg.currentPage];
        if (!results) return [];
        return results.filter((v) => {
            return pg.pageState[v.objectId] && pg.pageState[v.objectId].checked;
        });
    }

    function getSelectedIndices() {
        let pg = pagination.pages();
        let results = pg.pageResults[pg.currentPage];
        if (!results) return [];
        let sel = [];
        Object.keys(pg.pageState).forEach((k) => {
            if (pg.pageState[k].checked) {
                let idx = results.findIndex((v) => v.objectId === k);
                if (idx > -1) sel.push(idx);
            }
        });
        return sel;
    }

    function selectResult(o) {
        let state = pagination.state(o);
        state.checked = !state.checked;
        m.redraw();
    }

    // ------------------------------------------------------------------
    //  Navigation
    // ------------------------------------------------------------------

    function navigateUp() {
        let pg = pagination.pages();
        if (!pg.container) return;

        let type = modType ? (modType.type || listType) : listType;
        if (type === 'auth.group') {
            let parentPath = pg.container.path.substring(0, pg.container.path.lastIndexOf('/'));
            // In Ux75, page-level navigateToPath is not yet ported; fall back to route set
            m.route.set('/list/' + (containerMode ? baseListType : type) + '/' + encodeURIComponent(parentPath), { key: Date.now() });
        } else {
            console.warn('navigateUp: unhandled type ' + type);
        }
    }

    function navigateDown(sel) {
        let idx = getSelectedIndices();
        let type = modType ? (modType.type || listType) : listType;
        let byParent = am7model.isParent(modType) && type !== 'auth.group';

        if (sel && !sel[am7model.jsonModelKey]) sel = null;
        if (!sel && !idx.length) return;

        let pg = pagination.pages();
        let obj = sel || pg.pageResults[pg.currentPage][idx[0]];
        let ltype = obj[am7model.jsonModelKey] || baseListType;
        m.route.set('/' + (byParent ? 'p' : '') + 'list/' + (containerMode ? baseListType : ltype) + '/' + obj.objectId, { key: Date.now() });
    }

    // ------------------------------------------------------------------
    //  CRUD actions
    // ------------------------------------------------------------------

    function addNew() {
        let pg = pagination.pages();
        let path = '/new/' + pg.resultType + '/' + pg.containerId;
        m.route.set(path, { key: Date.now() });
    }

    function editItem(o) {
        if (!o) {
            console.error('editItem: invalid object');
            return;
        }
        m.route.set('/view/' + getType(o) + '/' + o.objectId);
    }

    function editSelected() {
        let idx = getSelectedIndices();
        if (idx.length) {
            let pg = pagination.pages();
            editItem(pg.pageResults[pg.currentPage][idx[0]]);
        }
    }

    async function deleteSelected() {
        let selected = getSelectedIndices();
        if (!selected.length) return;

        let pg = pagination.pages();
        let subType = pg.containerSubType;
        let isBucket = subType && subType.match(/^(bucket|account|person)$/gi);
        let label = isBucket ? 'Remove' : 'Delete';

        if (!confirm(label + ' selected objects?')) return;

        let promises = [];
        for (let i = 0; i < selected.length; i++) {
            let obj = pg.pageResults[pg.currentPage][selected[i]];
            if (isBucket) {
                // membership removal
                promises.push(
                    new Promise((res) => {
                        am7client.member(pg.containerType, listContainerId, 'null', getType(obj), obj.objectId, false, (r) => {
                            if (r) page.toast('success', 'Removed member');
                            else page.toast('error', 'Failed to remove member');
                            res(r);
                        });
                    })
                );
            } else {
                promises.push(
                    am7client.delete(getType(obj), obj.objectId).then((r) => {
                        if (r) page.toast('success', 'Deleted object');
                        else page.toast('error', 'Failed to delete object');
                        return r;
                    })
                );
            }
        }
        await Promise.all(promises);
        pagination.new();
        initParams(lastVnode);
        updatePagination(lastVnode);
        m.redraw();
    }

    // ------------------------------------------------------------------
    //  Display
    // ------------------------------------------------------------------

    function displayList() {
        let pg = pagination.pages();
        let items = pg.pageResults[pg.currentPage];
        if (!items || !items.length) {
            return m('div', { class: 'list-empty' }, 'No results');
        }

        // When the decorator module is available, use:
        //   return decorator.tabularView(items, inst, ctl);
        // For now render a simple table.
        return renderSimpleTable(items);
    }

    function renderSimpleTable(items) {
        if (!items || !items.length) return m('div', 'No results');

        let type = items[0][am7model.jsonModelKey];
        let mod = type ? am7model.getModel(type) : null;

        // Choose display columns: name, plus a few useful inherited fields
        let columns = ['name'];
        if (mod) {
            let allFields = am7model.getModelFields(mod);
            let fieldNames = allFields.map(f => f.name);
            if (fieldNames.includes('description')) columns.push('description');
            if (fieldNames.includes('groupPath')) columns.push('groupPath');
            if (fieldNames.includes('type') && !columns.includes('type')) columns.push('type');
        }

        let rows = items.map((item) => {
            let state = pagination.state(item);
            let checked = !!(state && state.checked);
            return m('tr', {
                class: 'list-tr' + (checked ? ' list-tr-selected' : ''),
                onclick: () => selectResult(item),
                ondblclick: () => navigateDown(item)
            }, [
                m('td', { class: 'list-td list-td-check' },
                    m('input', {
                        type: 'checkbox',
                        checked,
                        onclick: (e) => { e.stopPropagation(); selectResult(item); }
                    })
                ),
                ...columns.map(c => m('td', { class: 'list-td' }, displayValue(item, c)))
            ]);
        });

        return m('table', { class: 'list-table' }, [
            m('thead', [
                m('tr', [
                    m('th', { class: 'list-th list-th-check' }, ''),
                    ...columns.map(c => m('th', { class: 'list-th' }, c))
                ])
            ]),
            m('tbody', rows)
        ]);
    }

    function displayValue(item, field) {
        let v = item[field];
        if (v == null || v === undefined) return '';
        if (typeof v === 'object') return JSON.stringify(v);
        return String(v);
    }

    // ------------------------------------------------------------------
    //  Filter
    // ------------------------------------------------------------------

    function doFilter(value) {
        navFilter = value && value.length ? value : null;
        pagination.filter(navFilter);
    }

    // ------------------------------------------------------------------
    //  Toolbar
    // ------------------------------------------------------------------

    function toolbar(type) {
        let hasSelection = getSelectedIndices().length > 0;
        let isGroupNav = containerMode || type === 'auth.group' || (modType && am7model.isParent(modType));

        let buttons = [];

        // Add / Edit / Delete
        if (!modType || !modType.systemNew) {
            buttons.push(iconBtn('button', 'add', '', addNew));
        }
        buttons.push(iconBtn('button' + (!hasSelection ? ' inactive' : ''), 'edit', '', editSelected));
        buttons.push(iconBtn('button' + (!hasSelection ? ' inactive' : ''), 'delete', '', deleteSelected));

        // Navigate up/down for group navigation
        if (isGroupNav) {
            buttons.push(iconBtn('button', 'north_west', '', navigateUp));
            buttons.push(iconBtn('button' + (!hasSelection ? ' inactive' : ''), 'south_east', '', () => navigateDown()));
        }

        return buttons;
    }

    function filterInput() {
        let pg = pagination.pages();
        let placeholder = (pg.container && pg.container.path) ? pg.container.path : 'Filter...';

        return m('input', {
            type: 'text',
            class: 'text-field',
            placeholder,
            onkeydown: (e) => {
                if (e.which === 13) {
                    doFilter(e.target.value);
                }
            }
        });
    }

    function pageButtons() {
        let pg = pagination.pages();
        if (!pg.pageCount || pg.pageCount <= 1) return m('span');
        return m('nav', { class: 'result-nav' }, [
            iconBtn('button', 'chevron_left', '', () => pagination.prev()),
            m('span', { class: 'page-indicator' },
                pg.currentPage + ' / ' + pg.pageCount
            ),
            iconBtn('button', 'chevron_right', '', () => pagination.next())
        ]);
    }

    // ------------------------------------------------------------------
    //  Lifecycle helpers
    // ------------------------------------------------------------------

    let lastVnode = null;

    function initParams(vnode) {
        if (!vnode) return;

        listType = vnode.attrs.type || m.route.param('type') || 'data';
        modType = am7model.getModel(listType);

        if (!modType) {
            console.error('list: missing model for type ' + listType);
        } else {
            navigateByParent = vnode.attrs.navigateByParent || (m.route.get().match(/^\/plist/) != null);
            if (navigateByParent && !am7model.isParent(modType)) {
                console.warn('list: type is not clustered by parent');
                navigateByParent = false;
            }
        }

        baseListType = listType;
        if (modType && modType.group && containerMode && !listType.match(/^auth\.group$/gi)) {
            listType = 'auth.group';
        }

        listContainerId = navContainerId || vnode.attrs.objectId || m.route.param('objectId');
        navContainerId = null;
    }

    function updatePagination(vnode) {
        if (!vnode) return;

        let listFilter = navFilter || vnode.attrs.filter || m.route.param('filter');
        if (listFilter) listFilter = decodeURI(listFilter);

        let startRecord = vnode.attrs.startRecord || m.route.param('startRecord') || 0;
        let recordCount = vnode.attrs.recordCount || m.route.param('recordCount') || defaultRecordCount;

        pagination.update(listType, listContainerId, navigateByParent, listFilter, startRecord, recordCount, systemList);
    }

    // ------------------------------------------------------------------
    //  View -- Mithril component
    // ------------------------------------------------------------------

    listPage.view = {
        oninit: function (vnode) {
            lastVnode = vnode;
            initParams(vnode);
        },

        oncreate: function (vnode) {
            lastVnode = vnode;
            updatePagination(vnode);
        },

        onupdate: function (vnode) {
            lastVnode = vnode;
            // Re-read route params in case the route changed
            let newType = vnode.attrs.type || m.route.param('type');
            let newId = vnode.attrs.objectId || m.route.param('objectId');
            if (newType !== listType || newId !== listContainerId) {
                initParams(vnode);
                updatePagination(vnode);
            }
        },

        onremove: function () {
            navFilter = null;
            pagination.stop();
        },

        view: function (vnode) {
            let type = vnode.attrs.type || m.route.param('type') || listType;

            return m('div', { class: 'content-outer' }, [
                m(navigation),
                m('div', { class: 'content-main' }, [
                    m('div', { class: 'list-results-container' }, [
                        m('div', { class: 'list-results' }, [
                            // Toolbar row
                            m('div', { class: 'result-nav-outer' }, [
                                m('div', { class: 'result-nav-inner' }, [
                                    m('div', { class: 'result-nav tab-container' }, [
                                        ...toolbar(type),
                                        filterInput()
                                    ]),
                                    pageButtons()
                                ])
                            ]),
                            // List body
                            displayList()
                        ])
                    ])
                ])
            ]);
        }
    };

    // Expose pagination on the controller for external access
    listPage.pagination = function () {
        return pagination;
    };

    return listPage;
}

// ---------------------------------------------------------------------------
//  Exports
// ---------------------------------------------------------------------------

export { newListControl };
export default newListControl;
