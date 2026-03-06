import m from 'mithril';
import { am7model } from '../core/model.js';
import { am7view } from '../core/view.js';
import { am7client } from '../core/am7client.js';
import { page } from '../core/pageClient.js';
// Navigation is handled by router's pageLayout wrapper

/**
 * ESM object view — edit/create single entity.
 * Routes: /view/:type/:objectId (edit) | /new/:type/:objectId (create)
 * Supports: formFieldRenderers, blob fields, file upload, thumbnail display,
 *           object copy, tab navigation, form definitions from formDef.js,
 *           fullMode, design mode, commands, table editing, picker, membership.
 */
function newObjectPage() {
    let objectPage = {};
    let entity, inst;
    let tabIndex = 0;
    let objectType, objectId, objectNew;
    let fullMode = false;
    let designMode = false;
    let foreignData = {};
    let valuesState = {};
    let pickerMode = { enabled: false, type: null, callback: null };

    // --- Entity loading ---

    /** Build a blank primitive, inheriting groupId/parentId from context container. */
    function getPrimitive(type) {
        let ctx = page.context();
        let modType = am7model.getModel(type);
        let primitive = am7model.newPrimitive(type);
        let cobj;
        if (objectNew && (cobj = ctx.contextObjects[objectId])) {
            if (am7model.isGroup(modType)) {
                if (type === 'auth.group') primitive.parentId = cobj.id;
                else { primitive.groupPath = cobj.path; primitive.groupId = cobj.id; }
                if (page.authenticated()) primitive.organizationPath = cobj.organizationPath;
            } else if (am7model.isParent(modType) && type.match(/^(auth\.role|auth\.permission)$/gi)) {
                primitive.parentId = cobj.id;
                primitive.path = cobj.path;
            }
        }
        if (ctx.pendingEntity) {
            Object.keys(ctx.pendingEntity).forEach((k) => {
                if (!k.match(/^(parentId|parentPath|groupPath)$/)) primitive[k] = ctx.pendingEntity[k];
            });
            ctx.pendingEntity = undefined;
        }
        return primitive;
    }

    function setInst() {
        inst = undefined;
        if (entity) {
            let modelKey = entity[am7model.jsonModelKey];
            let fname = modelKey.substring(modelKey.lastIndexOf('.') + 1);
            let form = am7model.forms[fname];
            if (!form) {
                form = generateDefaultForm(modelKey);
                if (form) am7model.forms[fname] = form;
            }
            inst = am7model.prepareInstance(entity, form);
        }
    }

    function generateDefaultForm(type) {
        let model = am7model.getModel(type);
        if (!model) return null;
        let fields = am7model.getModelFields(model);
        let formDef = { label: model.label || model.name, fields: {} };
        let skip = ['id', 'ownerId', 'organizationId', 'organizationPath', 'populated', 'score'];
        fields.forEach(function (f) {
            if (skip.includes(f.name)) return;
            let format = am7view.getFormatForType(f.type);
            let layout = 'half';
            if (f.name === 'description' || f.name === 'content' || f.name === 'text') layout = 'full';
            if (f.type === 'blob') {
                formDef.fields[f.name] = { label: f.label || f.name, format: 'contentType', layout: 'full' };
                return;
            }
            formDef.fields[f.name] = { label: f.label || f.name, format: format, layout: layout };
        });
        return formDef;
    }

    function resetEntity(e) {
        if (e && e[am7model.jsonModelKey]) entity = e;
        setInst();
    }

    /** Resolve entity from route: /new => primitive, /view => search load. */
    function loadEntity() {
        let ctx = page.context();
        let type = objectType;
        let modType = am7model.getModel(type);
        entity = null;
        tabIndex = 0;

        if (objectNew) {
            if (objectId && !ctx.contextObjects[objectId]) {
                let useType = am7model.isGroup(modType) ? 'auth.group' : type;
                let q = am7view.viewQuery(am7model.newInstance(useType));
                q.field('objectId', objectId);
                am7client.search(q, function (qr) {
                    if (qr && qr.count) {
                        ctx.contextObjects[objectId] = qr.results[0];
                        resetEntity(getPrimitive(type));
                        m.redraw();
                    } else console.warn('Failed to resolve parent container: ' + objectId);
                });
            } else {
                resetEntity(getPrimitive(type));
            }
        } else if (objectId) {
            if (!ctx.contextObjects[objectId]) {
                let q = am7view.viewQuery(am7model.newInstance(type));
                q.field('objectId', objectId);
                am7client.search(q, function (qr) {
                    if (qr && qr.count) {
                        ctx.contextObjects[objectId] = qr.results[0];
                        resetEntity(qr.results[0]);
                        m.redraw();
                    } else console.warn('Failed to load entity: ' + objectId);
                });
            } else {
                resetEntity(ctx.contextObjects[objectId]);
            }
        }
    }

    // --- Actions ---

    function doUpdate() {
        if (!entity) { console.error('No entity defined'); return; }
        if (!entity[am7model.jsonModelKey] || entity[am7model.jsonModelKey] === 'UNKNOWN') {
            console.error('Unknown entity type'); return;
        }
        if (!inst.validate(true)) { page.toast('warn', 'Please fill in all fields'); return; }

        let bpatch = am7model.hasIdentity(entity);
        if (!inst.changes.length) { page.toast('warn', 'No changes detected'); return; }

        let uint = bpatch ? inst.patch() : entity;
        if (!uint) { page.toast('warn', 'No patch fields identified'); return; }

        am7client[bpatch ? 'patch' : 'create'](entity[am7model.jsonModelKey], uint, function (v) {
            if (v != null) {
                page.toast('success', 'Saved!');
                let wasNew = objectNew;
                inst.resetChanges();
                am7client.clearCache(entity[am7model.jsonModelKey], false, function () {
                    if (!bpatch) entity = v;
                    page.clearContextObject(entity.objectId);
                    objectNew = false;
                    if (wasNew) {
                        m.route.set('/view/' + entity[am7model.jsonModelKey] + '/' + entity.objectId, { key: entity.objectId });
                    } else m.redraw();
                });
            } else {
                page.toast('error', 'An error occurred while trying to update the object');
            }
        });
    }

    function doDelete() {
        if (!entity || !entity.objectId) return;
        page.components.dialog.confirm('Delete this object?', async function () {
            await page.deleteObject(entity[am7model.jsonModelKey], entity.objectId);
            history.back();
        });
    }

    function doCancel() {
        let returnUrl = page.context().listReturnUrl;
        if (returnUrl) {
            delete page.context().listReturnUrl;
            m.route.set(returnUrl);
        } else {
            history.back();
        }
    }

    function toggleFullMode() { fullMode = !fullMode; m.redraw(); }

    function isDesignable() {
        if (!entity || !entity.contentType) return false;
        return /^text\/(css|plain)|javascript$/i.test(entity.contentType);
    }

    function toggleDesignMode() {
        designMode = !designMode;
        m.redraw();
    }

    function doCopy() {
        if (!entity || !entity.objectId) return;
        page.components.dialog.confirm('Copy this object?', function() {
            let copy = Object.assign({}, entity);
            delete copy.objectId;
            delete copy.id;
            copy.name = (copy.name || '') + ' (copy)';
            am7client.create(entity[am7model.jsonModelKey], copy, function(v) {
                if (v) {
                    page.toast('success', 'Copied!');
                    am7client.clearCache(entity[am7model.jsonModelKey]);
                    m.route.set('/view/' + v[am7model.jsonModelKey] + '/' + v.objectId);
                } else {
                    page.toast('error', 'Copy failed');
                }
            });
        });
    }

    function getThumbnail() {
        if (!entity || !entity.objectId) return null;
        let ct = entity.contentType || '';
        if (ct.match(/^image/)) {
            return am7client.mediaDataPath(entity, true);
        }
        if (entity.profile && entity.profile.portrait && entity.profile.portrait.contentType) {
            return am7client.mediaDataPath(entity.profile.portrait, true);
        }
        return null;
    }

    // --- Picker integration ---

    function preparePicker(type, callback) {
        if (page.components.picker) {
            return page.components.picker.open({
                type: type,
                onSelect: function(selected) {
                    if (callback) callback(Array.isArray(selected) ? selected : [selected]);
                    cancelPicker();
                }
            });
        }
        return Promise.resolve(null);
    }

    function cancelPicker() {
        pickerMode.enabled = false;
        pickerMode.type = null;
        pickerMode.callback = null;
        m.redraw();
    }

    // --- Foreign data + table editing ---

    function resetValuesState() {
        valuesState = {};
        m.redraw();
    }

    function updateChange() {
        if (inst) inst.change(Object.keys(valuesState).length ? 'table' : '');
        m.redraw();
    }

    /** Build context for table/membership operations */
    function buildTableCtx(name, field, fieldView) {
        return {
            entity, inst, field, fieldView,
            name, useName: name,
            foreignData, valuesState,
            objectPage,
            preparePicker,
            cancelPicker,
            resetValuesState,
            updateChange,
            modelField: function(fk, tfv, tf, inputName, defVal, isTable, entry, tableForm) {
                return modelField(fk, tfv, tf);
            }
        };
    }

    /** Render a table/list field using tableListEditor */
    function renderTableField(name, fieldView, field) {
        let tle = page.components.tableListEditor;
        if (!tle) return m("div", { class: "text-xs text-gray-400" }, "Table editor not available");

        let ctx = buildTableCtx(name, field, fieldView);
        ctx.show = am7view.showField(inst, fieldView);
        return tle.render(ctx);
    }

    // --- Command system ---

    function getFormCommands() {
        if (!inst || !inst.form || !inst.form.commands) return null;
        let commands = inst.form.commands;
        let buttons = [];

        Object.keys(commands).forEach(function(cmdKey) {
            let cmd = commands[cmdKey];
            if (cmd.toolbar === false) return;

            let icon = cmd.icon || "play_arrow";
            let label = cmd.label || cmdKey;
            let active = true;

            // Check required attribute condition
            if (cmd.requiredAttribute && entity) {
                active = !!entity[cmd.requiredAttribute];
            }

            let handler = function() {
                if (!active) return;
                let fn = cmd.function;
                if (objectPage[fn]) {
                    objectPage[fn](entity, inst, cmd);
                } else if (page.components.membership && page.components.membership[fn]) {
                    let ctx = buildTableCtx(cmd.field || '', cmd, {});
                    page.components.membership[fn](ctx, cmd.field || '', cmd);
                } else {
                    console.warn("Command function not found: " + fn);
                }
            };

            buttons.push(page.iconButton(
                'button' + (active ? '' : ' opacity-30'),
                icon, '', handler
            ));
        });

        return buttons.length ? buttons : null;
    }

    // --- Membership delegation ---

    objectPage.objectMembers = function(name, field) {
        let ms = page.components.membership;
        if (!ms) return Promise.resolve([]);
        return ms.objectMembers({ entity }, name, field);
    };

    objectPage.memberObjects = function(name, field) {
        let ms = page.components.membership;
        if (!ms) return Promise.resolve([]);
        return ms.memberObjects({ entity }, name, field);
    };

    objectPage.objectControls = function(name, field) {
        let ms = page.components.membership;
        if (!ms) return Promise.resolve([]);
        return ms.objectControls({ entity }, name, field);
    };

    objectPage.objectRequests = function(name, field) {
        let ms = page.components.membership;
        if (!ms) return Promise.resolve([]);
        return ms.objectRequests({ entity }, name, field);
    };

    // --- Tab support ---

    function switchTab(i) { tabIndex = i; }

    function buttonTab(active, label, handler) {
        let cls = 'button-tab' + (active ? ' button-tab-active' : '');
        return m('button', { class: cls, onclick: handler },
            m('span', { class: 'material-icons-outlined mr-2' }, active ? 'tab' : 'tab_unselected'), label);
    }

    function getFormTabs() {
        if (!entity || !inst) return '';
        let form = inst.form;
        if (!form || !form.forms || !form.forms.length) return '';
        let tabs = [buttonTab(tabIndex === 0, form.label || objectType, function () { switchTab(0); })];
        form.forms.forEach(function (f, i) {
            let cForm = am7model.forms[f];
            if (!cForm) return;
            if (am7view.showField(inst, cForm)) {
                tabs.push(buttonTab(tabIndex === (i + 1), cForm.label || f, function () { switchTab(i + 1); }));
            }
        });
        return tabs;
    }

    // --- Form rendering ---

    function modelFieldContainer(name, fieldView, field) {
        let className = 'field-grid-item-full';
        if (fieldView.layout === 'half') className = 'field-grid-item-half';
        else if (fieldView.layout === 'fifth') className = 'field-grid-item-fifth';
        else if (fieldView.layout === 'two-thirds') className = 'field-grid-item-two-thirds';
        else if (fieldView.layout === 'third') className = 'field-grid-item-third';
        else if (fieldView.layout === 'one') className = 'field-grid-item-one';

        let show = am7view.showField(inst, fieldView);
        let lbl = fieldView.label || field.label || field.name;
        return m('div', { class: className }, [
            m('label', { class: 'field-label' + (show ? '' : ' text-gray-300'), for: name }, lbl),
            modelField(name, fieldView, field)
        ]);
    }

    function modelField(name, fieldView, field) {
        // List/table fields — delegate to tableListEditor
        if (field.type === 'list' && field.baseModel && fieldView.form) {
            let tle = page.components.tableListEditor;
            if (tle && tle.isStandardCrud(fieldView.form)) {
                return renderTableField(name, fieldView, field);
            }
        }

        let format = fieldView.format || field.format || am7view.getFormatForType(field.type);
        let view = [];
        let disabled = false;
        let fieldClass = '';

        if (inst.validationErrors[name]) fieldClass += 'field-error';
        else if (field.readOnly || fieldView.readOnly) { disabled = true; fieldClass += 'field-disabled'; }
        else if (field.required) fieldClass += 'field-required';

        let defVal = (inst && inst.api[name]) ? inst.api[name]() : undefined;
        let fHandler = (!disabled && !field.command) ? inst.handleChange(name) : undefined;
        let show = am7view.showField(inst, fieldView);
        let annot = inst.validationErrors[name] ? am7view.errorLabel(inst.validationErrors[name]) : '';
        if (!show) fieldClass += ' hidden';

        let rendererCtx = {
            inst, entity, useEntity: entity, field, fieldView,
            name, useName: name, defVal, fieldClass, disabled, fHandler, show, objectPage,
            foreignData, preparePicker, cancelPicker
        };

        if (page.components.formFieldRenderers && page.components.formFieldRenderers.has(format)) {
            let rendered = page.components.formFieldRenderers.render(format, rendererCtx);
            if (rendered) { view.push(...rendered); view.push(annot); return view; }
        }

        // Basic fallback renderers
        let inputAttrs = { class: 'text-field-compact ' + fieldClass, disabled: disabled || false };
        if (format === 'checkbox') {
            view.push(m('input', Object.assign(inputAttrs, {
                type: 'checkbox', class: 'check-field ' + fieldClass,
                checked: !!defVal, onchange: fHandler
            })));
        } else if (format === 'select') {
            let vals = am7view.defaultValuesForField(field) || [];
            let options = [m('option', { value: '' }, '-- Select --')];
            vals.forEach(function (v) {
                let val = (typeof v === 'object') ? v.name || v : v;
                options.push(m('option', { value: val, selected: defVal === val }, val));
            });
            view.push(m('select', Object.assign(inputAttrs, {
                class: 'select-field-full ' + fieldClass, value: defVal || '', onchange: fHandler
            }), options));
        } else if (format === 'datetime-local') {
            view.push(m('input', Object.assign(inputAttrs, {
                type: 'datetime-local', value: defVal || '', oninput: fHandler
            })));
        } else {
            // text, string, number, and other types
            let inputType = (field.type === 'int' || field.type === 'double' || field.type === 'long') ? 'number' : 'text';
            if (format === 'password') inputType = 'password';
            view.push(m('input', Object.assign(inputAttrs, {
                type: inputType, value: defVal != null ? String(defVal) : '', oninput: fHandler
            })));
        }
        view.push(annot);
        return view;
    }

    function modelForm(active, type, form) {
        let outF = [];
        Object.keys(form.fields).forEach(function (e) {
            let field = form.fields[e].field || am7model.getModelField(type, e);
            if (!field) console.warn('Failed to find field ' + e + ' for ' + type);
            else outF.push(modelFieldContainer(e, form.fields[e], field));
        });
        return m('div', { class: 'field-grid-6' + (active ? '' : ' hidden') }, outF);
    }

    function getForm() {
        let form = inst.form;
        if (!form) { console.error('Invalid form for ' + objectType); return ''; }
        let forms = [modelForm(tabIndex === 0, objectType, form)];
        (form.forms || []).forEach(function (f, i) {
            let cForm = am7model.forms[f];
            if (!cForm) console.warn('Failed to find form ' + f);
            else forms.push(modelForm(tabIndex === (i + 1), objectType, cForm));
        });
        return m('div', { class: 'results-overflow' }, forms);
    }

    // --- View ---

    function getObjectViewInner() {
        let form = inst.form;
        if (!form) return '';
        let altCls = inst.changes.length ? ' warn' : '';
        let thumb = getThumbnail();
        let cmds = getFormCommands();

        let toolbarButtons = [
            thumb ? m('img', {
                src: thumb,
                class: 'w-8 h-8 rounded object-cover mr-2',
                onerror: function(e) { e.target.style.display = 'none'; }
            }) : null,
            page.iconButton('button' + altCls, 'save', '', doUpdate),
            page.iconButton('button', 'cancel', '', doCancel),
            (!objectNew ? page.iconButton('button', 'delete_outline', '', doDelete) : ''),
            (!objectNew ? page.iconButton('button', 'content_copy', '', doCopy) : ''),
            page.iconButton('button' + (fullMode ? ' active' : ''),
                fullMode ? 'close_fullscreen' : 'open_in_new', '', toggleFullMode),
            (isDesignable() ? page.iconButton('button' + (designMode ? ' active' : ''),
                'code', '', toggleDesignMode) : '')
        ];

        // Add form commands to toolbar
        if (cmds) toolbarButtons = toolbarButtons.concat(cmds);

        let containerClass = fullMode
            ? 'fixed inset-0 z-50 bg-white dark:bg-gray-900 overflow-auto'
            : 'list-results-container';

        return m('div', { class: containerClass }, [
            m('div', { class: 'list-results' }, [
                m('div', { class: 'result-nav-outer' }, [
                    m('div', { class: 'result-nav-inner' }, [
                        m('div', { class: 'result-nav tab-container' }, toolbarButtons),
                        m('div', { class: 'result-nav tab-container' }, getFormTabs())
                    ])
                ]),
                getForm()
            ])
        ]);
    }

    // --- Mithril component lifecycle ---

    objectPage.view = {
        oninit: function (vnode) {
            objectType = vnode.attrs.type || m.route.param('type');
            objectId = vnode.attrs.objectId || m.route.param('objectId');
            objectNew = vnode.attrs.new || (m.route.get() && m.route.get().match(/^\/new/gi));
            loadEntity();
        },
        onremove: function () {
            entity = null; inst = null; tabIndex = 0;
            fullMode = false; designMode = false;
            foreignData = {}; valuesState = {};
            pickerMode = { enabled: false, type: null, callback: null };
            if (page.components.tableListEditor) page.components.tableListEditor.resetState();
        },
        view: function () {
            return objectPage.renderContent();
        }
    };

    // Inner content renderer — called by router's pageLayout wrapper
    objectPage.renderContent = function () {
        if (!page.authenticated() || !inst) return m('div', { style: 'padding:20px' }, 'Loading...');
        let inner = getObjectViewInner();
        if (!inner) return m('div', { style: 'padding:20px' }, 'No form definition for: ' + objectType);
        return inner;
    };

    objectPage.getEntity = function () { return entity; };
    objectPage.getInstance = function () { return inst; };
    objectPage.resetEntity = resetEntity;
    objectPage.tabIndex = function () { return tabIndex; };

    return objectPage;
}

page.views.object = newObjectPage;

export { newObjectPage };
export default newObjectPage;
