import m from 'mithril';
import { am7model } from '../core/model.js';
import { am7view } from '../core/view.js';

/**
 * Tabular view — renders items as an HTML table.
 * @param {Array} items — array of data objects
 * @param {object} inst — prepared model instance (for field names/labels)
 * @param {object} ctl — list control with select(item), open(item), isSelected(item)
 */
function tabularView(items, inst, ctl) {
    if (!items || items.length === 0) {
        return m("div", { class: "p-4 text-gray-500 dark:text-gray-400" }, "No results");
    }

    let fields = getDisplayFields(inst);

    let headerCells = fields.map(function (f) {
        let label = inst ? inst.label(f) : f;
        return m("th", {}, label);
    });
    // Checkbox header
    headerCells.unshift(m("th", { style: "width: 30px" }, ""));

    let rows = items.map(function (item) {
        let selected = ctl && ctl.isSelected ? ctl.isSelected(item) : false;
        let rowClass = "tabular-row" + (selected ? " tabular-row-active" : "");
        let cells = fields.map(function (f) {
            let val = item[f];
            if (val === undefined || val === null) val = "";
            if (typeof val === 'object') val = JSON.stringify(val);
            if (typeof val === 'string' && val.length > 80) val = val.substring(0, 80) + "...";
            return m("td", {}, String(val));
        });
        // Checkbox cell
        cells.unshift(m("td", { style: "width: 30px" },
            m("input", {
                type: "checkbox",
                checked: selected,
                onclick: function (e) {
                    e.stopPropagation();
                    if (ctl && ctl.select) ctl.select(item);
                }
            })
        ));

        return m("tr", {
            class: rowClass,
            onclick: function () {
                if (ctl && ctl.open) ctl.open(item);
            }
        }, cells);
    });

    return m("div", { class: "tabular-results-overflow" }, [
        m("table", { class: "tabular-results-table" }, [
            m("thead", {}, m("tr", { class: "tabular-header" }, headerCells)),
            m("tbody", {}, rows)
        ])
    ]);
}

/**
 * List item view — renders items as a vertical list with flex-polar layout.
 */
function listItemView(items, inst, ctl) {
    if (!items || items.length === 0) {
        return m("div", { class: "p-4 text-gray-500 dark:text-gray-400" }, "No results");
    }

    return m("ul", { class: "list-results-overflow" },
        items.map(function (item) {
            let selected = ctl && ctl.isSelected ? ctl.isSelected(item) : false;
            let cls = "result-item" + (selected ? " result-item-active" : "");
            let nameField = item.name || item.objectId || '';
            let typeField = item.schema || '';

            return m("li", {
                class: cls,
                onclick: function () {
                    if (ctl && ctl.open) ctl.open(item);
                }
            }, [
                m("div", { class: "flex-polar" }, [
                    m("div", { class: "flex items-center gap-2" }, [
                        m("input", {
                            type: "checkbox",
                            checked: selected,
                            onclick: function (e) {
                                e.stopPropagation();
                                if (ctl && ctl.select) ctl.select(item);
                            }
                        }),
                        m("span", { class: "label" }, nameField)
                    ]),
                    m("span", { class: "text-xs text-gray-400" }, typeField)
                ])
            ]);
        })
    );
}

/**
 * Get display fields for an instance — the fields to show in tabular/list views.
 * Filters out hidden, ephemeral, and overly wide fields.
 */
function getDisplayFields(inst) {
    if (!inst || !inst.form || !inst.form.fields) {
        return ['name', 'objectId'];
    }
    let fields = Object.keys(inst.form.fields).filter(function (f) {
        let ff = inst.form.fields[f];
        if (ff && ff.hide) return false;
        // Skip large fields in list view
        if (f === 'description' || f === 'content' || f === 'data') return false;
        return true;
    });
    // Ensure we have at least name and objectId
    if (fields.length === 0) {
        fields = ['name'];
    }
    // Limit to reasonable number for table display
    if (fields.length > 8) {
        fields = fields.slice(0, 8);
    }
    return fields;
}

const decorator = {
    tabularView,
    listItemView,
    getDisplayFields
};

export { decorator, tabularView, listItemView };
export default decorator;
