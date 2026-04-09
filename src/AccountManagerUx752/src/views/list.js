import m from 'mithril';
import { am7model } from '../core/model.js';
import { am7view } from '../core/view.js';
import { am7client } from '../core/am7client.js';
import { page } from '../core/pageClient.js';
import { am7decorator } from '../components/decorator.js';
import { newPaginationControl } from '../components/pagination.js';
import { panel } from '../components/panel.js';
import { FullCanvasViewer, GridPreview, renderContent, injectCSS as ivCSS } from '../components/imageViewer.js';
// breadcrumb is now a component in navigation.js (Ux7 pattern), not inline

// ---------------------------------------------------------------------------
//  newListControl  --  factory that returns a list-page controller + view
//  Pure controller: state, navigation, selection, toolbar, keyboard.
//  ALL rendering delegated to decorator.js via getListController().
// ---------------------------------------------------------------------------

function newListControl() {

    const listPage = {};

    // --- State variables (Ux7 lean set) ---

    let listType;
    let baseListType;
    let listContainerId;
    let containerMode = false;
    let gridMode = 0;           // 0=table, 1=small grid, 2=large grid
    let carousel = false;       // separate boolean (Ux7 pattern)
    let fullMode = false;
    let maxMode = false;
    let info = true;
    // showBreadcrumb removed — breadcrumb is now in navigation.js (Ux7 pattern)
    let modType;
    let navigateByParent = false;
    let systemList = false;
    let navContainerId = null;
    let navFilter = null;
    let pickerMode = false;
    let pickerHandler = null;
    let pickerCancel = null;
    let embeddedMode = false;
    let embeddedController = null;
    let wentBack = false;
    let defaultRecordCount = 10;
    let defaultIconRecordCount = 40;

    // Picker overlay state (consolidated)
    let pickerType = null;
    let pickerContainerId = null;
    let pickerUserContainerId = null;
    let pickerLibraryContainerId = null;
    let pickerFavoritesContainerId = null;
    let pickerActiveSource = 'home';

    // Breadcrumb is in navigation.js (Ux7 pattern)

    // Tagging batch state
    let taggingInProgress = false;

    // Grid/gallery mode state
    let gridSelectedIdx = 0;
    let gridFullView = false;

    let dnd;
    let pagination = newPaginationControl();

    // ------------------------------------------------------------------
    //  Helpers
    // ------------------------------------------------------------------

    function textField(sClass, id, plc, fKeyHandler) {
        return m('input', { placeholder: plc, onkeydown: fKeyHandler, id: id, type: 'text', class: sClass });
    }

    function getType(o) {
        return o[am7model.jsonModelKey] || listType;
    }

    function getSelected() {
        let pg = pagination.pages();
        let results = pg.pageResults[pg.currentPage];
        if (!results) return [];
        return results.filter(function (v) {
            return pg.pageState[v.objectId] && pg.pageState[v.objectId].checked;
        });
    }

    function getSelectedIndices() {
        let pg = pagination.pages();
        let results = pg.pageResults[pg.currentPage];
        if (!results) return [];
        let sel = [];
        Object.keys(pg.pageState).forEach(function (k) {
            if (pg.pageState[k].checked) {
                let idx = results.findIndex(function (v) { return v.objectId === k; });
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

    // Context menu — Open calls openItem (not navigateDown)
    function showListContextMenu(e, item) {
        let cm = page.components.contextMenu;
        if (!cm) return;
        cm.show(e, [
            { label: 'Open', icon: 'open_in_new', action: function () { openItem(item); } },
            { label: 'Edit', icon: 'edit', action: function () { editItem(item); } },
            { divider: true },
            { label: 'Delete', icon: 'delete', action: function () {
                selectResult(item);
                deleteSelected();
            }}
        ]);
    }

    // ------------------------------------------------------------------
    //  CRUD actions
    // ------------------------------------------------------------------

    function addNew() {
        let pg = pagination.pages();
        if (embeddedMode && embeddedController && embeddedController.addNew) {
            embeddedController.addNew(pg.resultType, pg.containerId);
        } else {
            let path = '/' + (m.route.get().match(/plist/) ? 'pnew' : 'new') + '/' + pg.resultType + '/' + pg.containerId;
            m.route.set(path, { key: Date.now() });
        }
    }

    function editItem(o) {
        if (!o) { console.error('editItem: invalid object'); return; }
        if (embeddedMode && embeddedController && embeddedController.editItem) {
            embeddedController.editItem(o);
        } else {
            m.route.set('/view/' + getType(o) + '/' + o.objectId);
        }
    }

    function editSelected() {
        let idx = getSelectedIndices();
        if (idx.length) {
            let pg = pagination.pages();
            editItem(pg.pageResults[pg.currentPage][idx[0]]);
        }
    }

    async function deleteSelected() {
        let contType = pagination.pages().containerType;
        let subType = pagination.pages().containerSubType;
        let bBucket = subType && subType.match(/^(bucket|account|person)$/gi);
        let label = bBucket ? 'Remove' : 'Delete';

        page.components.dialog.confirm(label + ' selected objects?', async function () {
            let idx = getSelectedIndices();
            if (idx.length) {
                let pg = pagination.pages();
                let aP = [];
                for (let i = 0; i < idx.length; i++) {
                    let obj = pg.pageResults[pg.currentPage][idx[i]];
                    if (bBucket) {
                        aP.push(page.member(contType, listContainerId, getType(obj), obj.objectId, false).then(function (r) {
                            if (r) page.toast('success', 'Removed member');
                            else page.toast('error', 'Failed to remove member');
                        }));
                    } else {
                        aP.push(page.deleteObject(getType(obj), obj.objectId).then(function (r) {
                            if (r) page.toast('success', 'Deleted object');
                            else page.toast('error', 'Failed to delete object');
                        }));
                    }
                }
                await Promise.all(aP);
                pagination.new();
                m.redraw();
            }
        });
    }

    async function applyTagsToList() {
        if (taggingInProgress) return;
        let pg = pagination.pages();
        if (!pg.containerId || !pg.container) { page.toast('error', 'No container selected'); return; }
        let groupId = pg.container.id;
        if (!groupId) { page.toast('error', 'Container does not have a valid group ID'); return; }

        let q = am7client.newQuery('data.data');
        q.field('groupId', groupId);
        q.entity.request = ['id', 'objectId', 'name', 'contentType'];
        q.range(0, 500);
        page.toast('info', 'Searching for images...');
        let qr = await page.search(q);
        if (!qr || !qr.results || !qr.results.length) { page.toast('warn', 'No data found in this group'); return; }
        let images = qr.results.filter(function (r) { return r.contentType && r.contentType.match(/^image\//i); });
        if (!images.length) { page.toast('warn', 'No images found in this group'); return; }

        taggingInProgress = true;
        m.redraw();
        page.components.dialog.batchProgress('Tag Images', images, async function (img) {
            return await page.applyImageTags(img.objectId);
        }, function () {
            taggingInProgress = false;
            m.redraw();
        });
    }

    // ------------------------------------------------------------------
    //  Carousel (ported from Ux7)
    // ------------------------------------------------------------------

    function openItem(o) {
        if (!o) return;
        // Groups: navigate into them (not gallery view)
        let objType = o[am7model.jsonModelKey] || baseListType;
        let objModel = am7model.getModel(objType);
        if (objModel && (am7model.isGroup(objModel) || objType === 'auth.group')) {
            navigateDown(o);
            return;
        }
        // Data items: open in gallery/full view
        let pg = pagination.pages();
        let pr = pg.pageResults[pg.currentPage];
        if (!pr) return;
        let idx = pr.findIndex(function (v) { return v.objectId === o.objectId; });
        if (idx > -1) {
            gridSelectedIdx = idx;
            gridMode = 1;
            gridFullView = true;
            m.redraw();
        }
    }

    function openSelected() {
        if (page.components.pdf) page.components.pdf.clear();
        let idx = getSelectedIndices();
        if (idx.length) {
            let pg = pagination.pages();
            let pr = pg.pageResults[pg.currentPage];
            if (pr && pr[idx[0]]) {
                openItem(pr[idx[0]]);
            }
        }
    }

    function closeSelected() {
        carousel = false;
        pagination.pages().currentItem = undefined;
        m.redraw();
    }

    function toggleCarousel() {
        carousel = !carousel;
        if (!carousel) pagination.pages().currentItem = undefined;
        m.redraw();
    }

    function toggleCarouselFull(bForce) {
        if (typeof bForce === 'boolean') {
            fullMode = bForce;
        } else {
            fullMode = !fullMode;
            if (embeddedController && embeddedController.toggleFullMode) {
                embeddedController.toggleFullMode();
            } else {
                m.redraw();
            }
        }
    }

    function toggleCarouselMax() {
        maxMode = !maxMode;
        m.redraw();
    }

    function moveCarouselTo(i) {
        let pg = pagination.pages();
        wentBack = false;
        pg.currentItem = i;
        m.redraw();
    }

    function moveCarousel(delta) {
        let pg = pagination.pages();
        let pr = pg.pageResults[pg.currentPage];
        if (page.components.pdf) page.components.pdf.clear();
        wentBack = false;

        let idx = (pg.currentItem || 0) + delta;
        if (pr && idx >= pr.length) {
            if (pg.currentPage < pg.pageCount) {
                pagination.next(embeddedMode || pickerMode);
            }
        } else if (idx < 0) {
            if (pg.currentPage > 1) {
                wentBack = true;
                pagination.prev(embeddedMode || pickerMode);
            }
        } else {
            pg.currentItem = idx;
            m.redraw();
        }
    }

    function getCurrentResults() {
        let pg = pagination.pages();
        let pr = pg.pageResults[pg.currentPage];
        if (pr) {
            if (wentBack) {
                pg.currentItem = pr.length - 1;
                wentBack = false;
            }
            if (pg.currentItem < 0 && pr.length) pg.currentItem = 0;
        }
        return pr;
    }

    // ------------------------------------------------------------------
    //  Navigation (ported from Ux7 — FULL implementation)
    // ------------------------------------------------------------------

    async function navigateToPathId(grp) {
        let ltype = baseListType;
        if (embeddedMode || pickerMode) {
            let byParent = am7model.isParent(modType) && listType !== 'auth.group';
            navContainerId = grp.objectId;
            if (byParent) navigateByParent = true;
            pagination.new();
            m.redraw();
        } else {
            m.route.set('/list/' + (containerMode ? baseListType : ltype) + '/' + grp.objectId, { key: Date.now() });
        }
    }

    // In-place nav helper for picker/embedded/container modes
    function navInPlace(objectId, resetParent) {
        navContainerId = objectId;
        if (resetParent) navigateByParent = false;
        pagination.pages().container = null;
        pagination.new();
        // breadcrumb updates via navigation component
        m.redraw();
    }

    // Route-based nav for normal mode (Ux7 pattern)
    function navByRoute(objectId, bParent) {
        let ltype = containerMode ? baseListType : (modType ? modType.type || listType : listType);
        // breadcrumb clears via navigation component
        m.route.set('/' + (bParent ? 'plist' : 'list') + '/' + ltype + '/' + objectId, { key: Date.now() });
    }

    function navigateUp() {
        let pg = pagination.pages();
        if (!pg.container) return;
        let type = listType;
        if (modType) type = modType.type || type;

        if (type === 'auth.group') {
            let path = pg.container.path;
            if (!path) return;
            let parentPath = path.substring(0, path.lastIndexOf('/'));
            if (!parentPath) return;
            page.navigateToPath(type, modType, parentPath).then(function (id) {
                if (!id) return;
                if (embeddedMode || pickerMode) navInPlace(id);
                else navByRoute(id);
            });
        } else if (am7model.isParent(modType) && navigateByParent) {
            let objectId = m.route.param('objectId');
            let useType = modType.type || listType;
            let q = am7view.viewQuery(am7model.newInstance(useType));
            q.field('objectId', objectId);
            am7client.search(q, function (qr) {
                let v;
                if (qr && qr.results && qr.results.length) v = qr.results[0];
                if (v != null) {
                    if (v.parentId == 0 && am7model.isGroup(modType)) {
                        page.navigateToPath(useType, modType, v.groupPath).then(function (id) {
                            if (!id) return;
                            if (embeddedMode || pickerMode) navInPlace(id, true);
                            else navByRoute(id);
                        });
                    } else {
                        am7client.get(useType, v.parentId, function (v2) {
                            if (v2 != null) {
                                if (embeddedMode || pickerMode) navInPlace(v2.objectId);
                                else navByRoute(v2.objectId, true);
                            }
                        });
                    }
                }
            });
        } else {
            console.error('navigateUp: unhandled type ' + type);
        }
    }

    function navigateDown(sel) {
        let idx = getSelectedIndices();
        let type = listType;
        if (modType) type = modType.type || type;
        let byParent = am7model.isParent(modType) && type !== 'auth.group';

        if (sel && !sel[am7model.jsonModelKey]) sel = null;
        if (!sel && !idx.length) return;

        let pg = pagination.pages();
        let obj = sel || pg.pageResults[pg.currentPage][idx[0]];

        let objType = obj[am7model.jsonModelKey] || baseListType;
        let objModel = am7model.getModel(objType);
        let isGroup = objModel && (am7model.isGroup(objModel) || objType === 'auth.group');

        if (isGroup || containerMode) {
            if (embeddedMode || pickerMode || containerMode) {
                navContainerId = obj.objectId;
                if (byParent) navigateByParent = true;
                pagination.pages().container = null;
                pagination.new();
                m.redraw();
            } else {
                navByRoute(obj.objectId, byParent);
            }
        } else {
            openItem(obj);
        }
    }

    // ------------------------------------------------------------------
    //  System library navigation (ported from Ux7)
    // ------------------------------------------------------------------

    async function openSystemLibrary() {
        let libTypeMap = {
            'olio.llm.chatConfig': 'chat',
            'olio.llm.promptConfig': 'prompt',
            'olio.llm.promptTemplate': 'promptTemplate',
            'policy.policy': 'policy',
            'policy.rule': 'rule',
            'policy.pattern': 'pattern',
            'policy.fact': 'fact',
            'policy.operation': 'operation',
            'policy.function': 'function'
        };
        let libType = libTypeMap[baseListType];

        if (libType && typeof LLMConnector !== 'undefined') {
            let grp = await LLMConnector.getLibraryGroup(libType);
            if (grp) {
                // Ensure prompt templates are up to date (idempotent, admin-only)
                if (libType === 'prompt' || libType === 'promptTemplate') {
                    await LLMConnector.initPromptLibrary().catch(function () {});
                }
                navigateToPathId(grp);
                return;
            }
            if (libType === 'chat') {
                if (typeof ChatSetupWizard !== 'undefined') {
                    ChatSetupWizard.show(function () { openSystemLibrary(); });
                } else {
                    page.toast('info', 'Chat library not initialized');
                }
            } else if (libType === 'prompt' || libType === 'promptTemplate') {
                page.toast('info', 'Initializing prompt library...');
                let result = await LLMConnector.initPromptLibrary();
                if (result && result.status === 'ok') {
                    LLMConnector.resetLibraryCache();
                    grp = await LLMConnector.getLibraryGroup(libType);
                    if (grp) { navigateToPathId(grp); return; }
                }
                page.toast('error', 'Failed to initialize prompt library');
            } else if (libType.match(/^(policy|rule|pattern|fact|operation|function)$/)) {
                page.toast('info', 'Initializing policy library...');
                let result = await LLMConnector.initPolicyLibrary();
                if (result && result.status === 'ok') {
                    LLMConnector.resetLibraryCache();
                    grp = await LLMConnector.getLibraryGroup(libType);
                    if (grp) { navigateToPathId(grp); return; }
                }
                page.toast('error', 'Failed to initialize policy library');
            }
            return;
        }
        let grp = await page.systemLibrary(baseListType);
        if (!grp) { page.toast('info', 'System library not initialized'); return; }
        navigateToPathId(grp);
    }

    async function openOlio() {
        let og = await page.findObject('auth.group', 'DATA', '/Olio/Universes');
        navigateToPathId(og);
    }

    async function openFavorites() {
        let fav = await page.favorites();
        navigateToPathId(fav);
    }

    async function openPath(path) {
        let grp = await page.findObject('auth.group', 'DATA', path);
        if (!grp) { page.toast('error', 'Path not found: ' + path); return; }
        navigateToPathId(grp);
    }

    function openCloud() {
        let pg = pagination.pages();
        let containerId = pg.container ? pg.container.id : 0;
        page.components.dialog.memberCloud(baseListType, containerId);
    }

    // Breadcrumb is now a component in navigation.js (Ux7 pattern)
    // No inline breadcrumb code needed in list.js

    // ------------------------------------------------------------------
    //  Toggles
    // ------------------------------------------------------------------

    function listSystemType() {
        systemList = !systemList;
        pagination.new();
        m.redraw();
    }

    function toggleContainer() {
        if (containerMode && pagination.pages().containerId) {
            let currentId = navContainerId || pagination.pages().containerId;
            containerMode = false;
            pagination.new();
            // pagination.new() already clears container cache
            m.route.set('/list/' + baseListType + '/' + currentId + '?startRecord=0&recordCount=' + defaultRecordCount, { key: Date.now() });
        } else {
            containerMode = !containerMode;
            pagination.new();
            m.redraw();
        }
    }

    function toggleInfo() {
        info = !info;
        m.redraw();
    }

    // Toggle grid mode: table(0) ↔ icon/gallery(1)
    function toggleGrid(bOff) {
        if (typeof bOff === 'boolean' && !bOff) gridMode = 0;
        else gridMode = gridMode > 0 ? 0 : 1;
        gridFullView = false;
        gridSelectedIdx = 0;
        let rc = (gridMode === 1) ? defaultIconRecordCount : defaultRecordCount;
        pagination.new();
        pagination.setInfiniteScroll(gridMode > 0);
        pagination.update(listType, listContainerId, navigateByParent, navFilter, 0, rc, systemList);
        m.redraw();
    }

    function checkScrollPagination() {
        // Placeholder for scroll-based pagination (Ux7 pattern)
    }

    // ------------------------------------------------------------------
    //  Filter (Ux7 pattern: text field + Enter, not live debounce)
    // ------------------------------------------------------------------

    function doFilter() {
        // Scope search to our own list control's container to avoid grabbing
        // the wrong input when picker and main list both have listFilter
        let scope = pickerMode
            ? document.querySelector('.am7-picker-overlay [id=listFilter]')
            : document.querySelector('.result-nav-outer [id=listFilter]');
        let el = scope || document.querySelector('[id=listFilter]');
        navFilter = el ? el.value : null;
        if (navFilter && !navFilter.length) navFilter = null;

        if (navFilter && (navFilter.indexOf('..') > -1 || navFilter.indexOf('~') > -1 || navFilter.indexOf('/') > -1)) {
            let npath = page.normalizePath(navFilter, pagination.pages().container);
            if (npath) { openPath(npath); return; }
        }

        if (embeddedMode || pickerMode) {
            // In picker/embedded mode: reset pagination and re-fetch with filter
            // If filter cleared, revert to the picker's default container
            if (!navFilter && pickerContainerId) {
                listContainerId = pickerContainerId;
            }
            pagination.new();
            pagination.setInfiniteScroll(gridMode > 0);
            let rc = (gridMode === 1) ? defaultIconRecordCount : defaultRecordCount;
            pagination.update(listType, listContainerId, navigateByParent, navFilter, 0, rc, systemList);
            m.redraw();
        } else {
            pagination.filter(navFilter, false);
        }
    }

    // ------------------------------------------------------------------
    //  Keyboard handler (ported from Ux7 navListKey)
    // ------------------------------------------------------------------

    function navListKey(e) {
        if (e.target && (e.target.tagName === 'INPUT' || e.target.tagName === 'TEXTAREA' || e.target.tagName === 'SELECT')) return;
        // In grid mode, GridPreview/FullCanvasViewer handle their own arrow keys
        // via infinite scroll — don't let page-level handler interfere
        if (gridMode > 0 || gridFullView) return;

        wentBack = false;
        switch (e.keyCode) {
            case 37: // Left
                if (!carousel || e.shiftKey) {
                    wentBack = true;
                    pagination.prev(embeddedMode || pickerMode);
                } else {
                    moveCarousel(-1);
                }
                break;
            case 39: // Right
                if (!carousel || e.shiftKey) {
                    pagination.next(embeddedMode || pickerMode);
                } else {
                    moveCarousel(1);
                }
                break;
            case 27: // Escape
                if (gridFullView) { gridFullView = false; m.redraw(); }
                else if (carousel) toggleCarousel();
                else if (gridMode > 0) toggleGrid(false);
                else if (fullMode) toggleCarouselFull();
                break;
        }
    }

    // ------------------------------------------------------------------
    //  Controller interface (ported from Ux7 getListController)
    // ------------------------------------------------------------------

    function getListController() {
        return {
            containerMode,
            listPage,
            showIconFooter: false,
            embeddedMode,
            pickerMode,
            results: getCurrentResults,
            gridMode,
            info,
            pagination,
            fullMode,
            maxMode,
            listType,
            edit: editItem,
            move: moveCarousel,
            moveTo: moveCarouselTo,
            select: selectResult,
            confirmPick: function() {
                if (pickerMode && pickerHandler) pickerHandler(getSelected());
            },
            open: openItem,
            down: navigateDown,
            onscroll: checkScrollPagination,
            toggleCarousel,
            toggleCarouselFull,
            toggleCarouselMax,
            toggleInfo
        };
    }

    // ------------------------------------------------------------------
    //  Toolbar (ported from Ux7 getActionButtonBar — all 8 groups)
    // ------------------------------------------------------------------

    function getPickerButtons() {
        let buttons = [];
        if (pickerMode) {
            buttons.push(pagination.button('button', 'check', '', function () { pickerHandler(getSelected()); }));
            if (pickerCancel) {
                buttons.push(pagination.button('button', 'close', '', function () { pickerCancel(); }));
            }
            // Source navigation: Home, System/Library, Favorites
            if (pickerUserContainerId) {
                buttons.push(pagination.button(
                    'button' + (pickerActiveSource === 'home' ? ' active bg-blue-100 dark:bg-blue-900' : ''),
                    'home', '', function () {
                        pickerActiveSource = 'home';
                        pickerNavigateTo(pickerUserContainerId);
                    }
                ));
            }
            if (pickerLibraryContainerId) {
                buttons.push(pagination.button(
                    'button' + (pickerActiveSource === 'library' ? ' active bg-orange-100 dark:bg-orange-900' : ''),
                    'admin_panel_settings', '', function () {
                        pickerActiveSource = 'library';
                        pickerNavigateTo(pickerLibraryContainerId);
                    }
                ));
            }
            if (pickerFavoritesContainerId) {
                buttons.push(pagination.button(
                    'button' + (pickerActiveSource === 'favorites' ? ' active bg-yellow-100 dark:bg-yellow-900' : ''),
                    'star', '', function () {
                        pickerActiveSource = 'favorites';
                        pickerNavigateTo(pickerFavoritesContainerId);
                    }
                ));
            }
        }
        return buttons;
    }

    function getAdminButtons(type) {
        let rs = page.context().roles;
        let buttons = [];
        let cnt = pagination.pages().container;
        let favSel = '';
        if (rs.accountAdmin || (rs.roleReader && type && type.match(/^auth\.role$/gi)) || (rs.permissionReader && type && type.match(/^auth\.permission$/gi))) {
            buttons.push(pagination.button('button mr-4' + (systemList ? ' active' : ''), 'admin_panel_settings', '', listSystemType));
        } else if (am7model.system && am7model.system.library && am7model.system.library[type]) {
            if (cnt && cnt.path && cnt.path.match(/^\/Library/gi)) favSel = ' bg-orange-200 active';
            buttons.push(pagination.button('button mr-4' + (systemList ? ' active' + favSel : ''), 'admin_panel_settings', '', openSystemLibrary));
        }
        if (type && type.match(/^policy\.policy/)) {
            buttons.push(pagination.button('button', 'shield', '', function () {
                page.components.dialog.loadPolicyTemplate(function () {
                    pagination.new();
                    m.redraw();
                });
            }));
        }
        return buttons;
    }

    function getActionButtons(type) {
        let buttons = [];
        if (!pickerMode && !containerMode) {
            let selected = getSelectedIndices().length > 0;
            buttons.push(pagination.button('button mr-4', fullMode ? 'close_fullscreen' : 'open_in_new', '', toggleCarouselFull));
            if (!modType || !modType.systemNew) buttons.push(pagination.button('button', 'add', '', addNew));
            if (type && type === 'olio.charPerson' && am7model.forms.commands && am7model.forms.commands.characterWizard) {
                buttons.push(pagination.button('button', 'steppers', '', am7model.forms.commands.characterWizard));
            }
            buttons.push(pagination.button('button' + (!selected ? ' inactive' : ''), 'file_open', '', openSelected));
            buttons.push(pagination.button('button' + (!selected ? ' inactive' : ''), 'edit', '', editSelected));
            let bBucket = pagination.pages().containerSubType && pagination.pages().containerSubType.match(/^(bucket|account|person)$/gi);
            buttons.push(pagination.button('button' + (!selected ? ' inactive' : ''), bBucket ? 'playlist_remove' : 'delete', '', deleteSelected));
            if (type && type === 'data.data') {
                buttons.push(pagination.button('button' + (taggingInProgress ? ' active' : ''), 'sell', '', applyTagsToList));
            }
            if (type && (type === 'data.tag' || type === 'auth.role' || type === 'auth.group')) {
                buttons.push(pagination.button('button', 'cloud', '', openCloud));
            }
        }
        return buttons;
    }

    function getOlioButtons() {
        let buttons = [];
        let cnt = pagination.pages().container;
        let oliSel = '';
        if (cnt && cnt.path && cnt.path.match(/^\/olio/gi)) oliSel = ' bg-orange-200 active';
        buttons.push(pagination.button('button' + oliSel, 'globe', '', openOlio));
        return buttons;
    }

    function getFavoriteButtons() {
        let buttons = [];
        let cnt = pagination.pages().container;
        let favSel = '';
        if (cnt && cnt.name && cnt.name.match(/favorites/gi)) favSel = ' bg-orange-200 active';
        buttons.push(pagination.button('button' + favSel, 'favorite', '', openFavorites));
        return buttons;
    }

    function getOptionButtons(type) {
        let optButton;
        let selected = getSelectedIndices().length > 0;
        if (containerMode || type === 'auth.group' || am7model.isParent(modType)) {
            let disableUp = type !== 'auth.group' && am7model.isParent(modType) && !navigateByParent;
            optButton = [
                pagination.button('button' + (disableUp ? ' inactive' : ''), 'north_west', '', navigateUp),
                pagination.button('button' + (!selected ? ' inactive' : ''), 'south_east', '', navigateDown)
            ];
        }
        return optButton;
    }

    function getPageToggleButtons(type) {
        let buttons = [];
        buttons.push(pagination.button('button' + (gridMode > 0 ? ' active' : ''), 'apps', '', toggleGrid));
        if (!embeddedMode && (!containerMode || !type.match(/^auth\.group$/gi)) && modType && modType.group) {
            buttons.push(pagination.button('button' + (navigateByParent ? ' inactive' : (containerMode ? ' active' : '')), 'group_work', '', toggleContainer));
        }
        buttons.push(pagination.button('button' + (info ? ' active' : ''), 'info', '', toggleInfo));
        return buttons;
    }

    function getGroupSearchButtons() {
        let buttons = [];
        if (am7model.isGroup(modType)) {
            let cnt = pagination.pages().container;
            let plc = '';
            if (cnt && cnt.path) plc = cnt.path;
            buttons.push(textField('text-field', 'listFilter', plc, function (e) { if (e.which === 13) doFilter(); }));
            buttons.push(pagination.button('button', 'search', null, doFilter));
        }
        return buttons;
    }

    // Ux7 getActionButtonBar — all 8 groups in order
    function getActionButtonBar(type) {
        let buttons = [];
        buttons.push(getPickerButtons());
        buttons.push(getAdminButtons(type));
        buttons.push(getActionButtons(type));
        buttons.push(getOlioButtons());
        buttons.push(getFavoriteButtons());
        buttons.push(getOptionButtons(type));
        buttons.push(getPageToggleButtons(type));
        buttons.push(getGroupSearchButtons());
        return buttons;
    }

    // ------------------------------------------------------------------
    //  Display routing — delegates ALL rendering to decorator
    // ------------------------------------------------------------------

    function getListViewInner(type) {
        let buttons = getActionButtonBar(type);
        let fdoh = function (e) { e.preventDefault(); };
        let fdrh = dnd ? dnd.doDrop : undefined;

        let ctl = getListController();
        let content;
        if (gridMode > 0) {
            // Gallery-style grid + preview — infinite scroll mode
            ivCSS();
            let results = pagination.allResults();
            let pg = pagination.pages();
            let hasMore = pagination.hasMorePages();

            if (gridSelectedIdx >= results.length) gridSelectedIdx = Math.max(0, results.length - 1);
            if (gridSelectedIdx < 0) gridSelectedIdx = 0;

            function gridLoadMore() {
                pagination.loadNextPage();
            }

            if (gridFullView) {
                return m(FullCanvasViewer, {
                    images: results,
                    index: gridSelectedIdx,
                    onClose: function() { gridFullView = false; },
                    onIndexChange: function(i) { gridSelectedIdx = i; },
                    onLoadMore: hasMore ? gridLoadMore : null
                });
            }

            content = m(GridPreview, {
                images: results,
                index: gridSelectedIdx,
                onIndexChange: function(i) { gridSelectedIdx = i; },
                onFullView: function() { gridFullView = true; },
                onLoadMore: hasMore ? gridLoadMore : null,
                loading: pg.currentPage > 0 && !pg.pageResults[pg.currentPage],
                hasMore: hasMore,
                onDelete: function(img) {
                    page.components.dialog.confirm('Delete ' + img.name + '?', async function() {
                        await page.deleteObject(listType, img.objectId);
                        // Remove from the page results cache
                        for (let p = 1; p <= pg.currentPage; p++) {
                            let r = pg.pageResults[p];
                            if (r) {
                                let ix = r.findIndex(function(x) { return x.objectId === img.objectId; });
                                if (ix > -1) { r.splice(ix, 1); break; }
                            }
                        }
                        let allLen = pagination.allResults().length;
                        if (gridSelectedIdx >= allLen) gridSelectedIdx = Math.max(0, allLen - 1);
                        m.redraw();
                    });
                }
            });
        } else {
            let rset;
            let pg = pagination.pages();
            page.checkFavorites();
            if (pg.pageResults[pg.currentPage]) {
                rset = pg.pageResults[pg.currentPage];
            }
            content = am7decorator.tabularView(ctl, rset);
        }

        return m('div', { ondragover: fdoh, ondrop: fdrh, class: 'list-results-container' }, [
            m('div', { class: 'list-results flex flex-col h-full' }, [
                m('div', { class: 'result-nav-outer shrink-0' }, [
                    m('div', { class: 'result-nav-inner' }, [
                        m('div', { class: 'result-nav tab-container' }, buttons),
                        gridMode > 0 ? null : m('nav', { class: 'result-nav' }, [pagination.pageButtons()])
                    ])
                ]),
                // breadcrumb rendered by navigation.js (Ux7 pattern)
                m('div', { class: 'flex-1 min-h-0' }, content)
            ])
        ]);
    }

    function getListView() {
        let type = m.route.param('type');
        return m('div', { class: 'content-outer' }, [
            fullMode ? '' : (page.components.navigation ? m(page.components.navigation) : ''),
            m('div', { class: 'content-main' }, [getListViewInner(type)])
        ]);
    }

    // ------------------------------------------------------------------
    //  Lifecycle — initParams + update (ported from Ux7)
    // ------------------------------------------------------------------

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
        if (!embeddedMode && !pickerMode && !containerMode) navContainerId = null;
    }

    function update(vnode) {
        initParams(vnode);
        let listFilter = navFilter || vnode.attrs.filter || m.route.param('filter');
        if (listFilter) listFilter = decodeURI(listFilter);

        let pg = pagination.pages();
        if ((embeddedMode || pickerMode) && pg.counted && pg.currentPage > 0) return;

        // In infinite scroll mode, don't re-trigger pagination.update once initial load is done
        // (otherwise doSearchPage recalculates page from URL startRecord and resets currentPage)
        if (pagination.isInfiniteScroll() && pg.counted && pg.currentPage > 0 && pg.containerId === listContainerId) return;

        let currentDefaultRecordCount = (gridMode === 1) ? defaultIconRecordCount : defaultRecordCount;
        let startRecord, recordCount;
        // When container changed in-place (navContainerId was consumed), reset to page 1
        if (!pg.counted || pg.containerId !== listContainerId) {
            startRecord = 0;
            recordCount = currentDefaultRecordCount;
            pagination.setInfiniteScroll(gridMode > 0);
        } else {
            startRecord = vnode.attrs.startRecord || m.route.param('startRecord');
            recordCount = vnode.attrs.recordCount || m.route.param('recordCount') || currentDefaultRecordCount;
        }
        pagination.update(listType, listContainerId, navigateByParent, listFilter, startRecord, recordCount, systemList);
    }

    // ------------------------------------------------------------------
    //  Mithril component lifecycle
    // ------------------------------------------------------------------

    listPage.view = {
        oninit: function (vnode) {
            dnd = page.components.dnd;
            initParams(vnode);
            document.documentElement.addEventListener('keydown', navListKey);
        },
        oncreate: function (vnode) {
            fullMode = vnode.attrs.fullMode || fullMode;
            pickerMode = vnode.attrs.pickerMode || false;
            embeddedMode = vnode.attrs.embeddedMode || false;
            embeddedController = vnode.attrs.embeddedController || null;
            pickerHandler = vnode.attrs.pickerHandler || null;
            pickerCancel = vnode.attrs.pickerCancel || null;
            pagination.setEmbeddedMode(embeddedMode || pickerMode);
            update(vnode);
            // breadcrumb loads via navigation component
            let mod = am7model.getModel(listType);
            if (mod && panel && panel.trackRecent) {
                panel.trackRecent(mod.label || listType, m.route.get(), mod.icon || 'list');
            }
        },
        onupdate: function (vnode) {
            update(vnode);
            // Reload breadcrumb when container changes (fixes stale path)
            // breadcrumb reloads via navigation component on route change
        },
        onremove: function () {
            if (page.components.pdf) page.components.pdf.clear();
            navFilter = null;
            carousel = false;
            gridFullView = false;
            gridMode = 0;
            // breadcrumb clears via navigation component
            document.documentElement.removeEventListener('keydown', navListKey);
            pagination.stop();
        },
        view: function (vnode) {
            let v;
            if (vnode.attrs.pickerMode || vnode.attrs.embeddedMode) {
                v = getListViewInner(vnode.attrs.type);
            } else {
                v = getListView();
            }
            if (vnode.attrs.pickerMode || vnode.attrs.embeddedMode) return v;
            return [v, page.components.dialog.loadDialogs(), page.loadToast(), (typeof ChatSetupWizard !== 'undefined' ? ChatSetupWizard.view() : null)];
        }
    };

    // renderContent — called by router.js
    listPage.renderContent = function (vnode) {
        let type = (vnode && vnode.attrs && vnode.attrs.type) || m.route.param('type') || listType;
        return getListViewInner(type);
    };

    listPage.closeView = function () {
        closeSelected();
    };

    listPage.pagination = function () {
        return pagination;
    };

    // ------------------------------------------------------------------
    //  Picker mode API (used by picker.js)
    // ------------------------------------------------------------------

    function pickerNavigateTo(containerId) {
        pickerContainerId = containerId;
        listContainerId = containerId;
        // breadcrumb clears via navigation component
        pagination.new();
        let fakeVnode = { attrs: { type: pickerType, objectId: containerId } };
        initParams(fakeVnode);
        update(fakeVnode);
        // breadcrumb loads via navigation component
    }

    listPage.openForPicker = function (opts) {
        pickerMode = true;
        pickerHandler = opts.onSelect;
        pickerType = opts.type;
        pickerContainerId = opts.containerId;
        pickerUserContainerId = opts.userContainerId || null;
        pickerLibraryContainerId = opts.libraryContainerId || null;
        pickerFavoritesContainerId = opts.favoritesContainerId || null;
        if (opts.containerId === opts.libraryContainerId) pickerActiveSource = 'library';
        else if (opts.containerId === opts.favoritesContainerId) pickerActiveSource = 'favorites';
        else pickerActiveSource = 'home';

        gridMode = 0;
        fullMode = false;
        carousel = false;
        navFilter = null;
        // breadcrumb clears via navigation component

        pagination.new();
        pagination.setEmbeddedMode(true);

        let fakeVnode = { attrs: { type: opts.type, objectId: opts.containerId } };
        initParams(fakeVnode);
        update(fakeVnode);
        // breadcrumb loads via navigation component
    };

    listPage.closePickerMode = function () {
        pickerMode = false;
        pickerHandler = null;
        pickerCancel = null;
        pickerType = null;
        pickerContainerId = null;
        pickerUserContainerId = null;
        pickerLibraryContainerId = null;
        pickerFavoritesContainerId = null;
        pickerActiveSource = 'home';
        carousel = false;
        // breadcrumb clears via navigation component
        navFilter = null;
        pagination.setEmbeddedMode(false);
        pagination.stop();
    };

    listPage.isPickerMode = function () {
        return pickerMode;
    };

    // Test helpers
    listPage._testHelpers = function () {
        return {
            doFilter, navigateUp, navigateDown, openItem, closeSelected,
            toggleCarousel, moveCarousel, moveCarouselTo, toggleContainer,
            toggleInfo, listSystemType, toggleGrid, getListController,
            getCurrentResults
        };
    };

    if (typeof window !== 'undefined') window.dbgList = listPage;
    return listPage;
}

// ---------------------------------------------------------------------------
//  Exports
// ---------------------------------------------------------------------------

export { newListControl };
export default newListControl;
