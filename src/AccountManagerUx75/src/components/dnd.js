/**
 * Drag-and-Drop — workingSet management for dragging objects between views (ESM)
 * Port of Ux7 client/components/dnd.js
 */
import m from 'mithril';
import { am7model } from '../core/model.js';
import { am7client } from '../core/am7client.js';
import { page } from '../core/pageClient.js';

let workingSet = [];

const dnd = {
    workingSet: workingSet,

    /**
     * Start a drag operation with the given objects.
     * @param {Array} items - Objects being dragged [{schema, objectId, name, ...}]
     */
    startDrag: function(items) {
        workingSet.length = 0;
        if (Array.isArray(items)) {
            items.forEach(function(item) { workingSet.push(item); });
        } else if (items) {
            workingSet.push(items);
        }
    },

    /**
     * Clear the working set after drop or cancel.
     */
    clear: function() {
        workingSet.length = 0;
    },

    /**
     * Get current working set.
     * @returns {Array}
     */
    getItems: function() {
        return workingSet;
    },

    /**
     * Check if a drag is in progress.
     * @returns {boolean}
     */
    isDragging: function() {
        return workingSet.length > 0;
    },

    /**
     * Create ondragstart handler for a list item.
     * @param {object} item - The item object (must have schema, objectId)
     * @returns {Function} ondragstart handler
     */
    dragStartHandler: function(item) {
        return function(e) {
            dnd.startDrag(item);
            e.dataTransfer.effectAllowed = "copy";
            e.dataTransfer.setData("text/plain", JSON.stringify({
                schema: item.schema || item['.type'],
                objectId: item.objectId,
                name: item.name
            }));
        };
    },

    /**
     * Create ondrop handler that processes dropped items.
     * @param {Function} onDrop - Callback with dropped items array
     * @returns {Function} ondrop handler
     */
    dropHandler: function(onDrop) {
        return function(e) {
            e.preventDefault();
            if (workingSet.length > 0) {
                if (onDrop) onDrop(workingSet.slice());
                dnd.clear();
            } else {
                // Try to parse from dataTransfer
                try {
                    let data = JSON.parse(e.dataTransfer.getData("text/plain"));
                    if (data && data.objectId) {
                        if (onDrop) onDrop([data]);
                    }
                } catch(ex) {}
            }
            m.redraw();
        };
    },

    /**
     * Blend operation: check if working set contains objects
     * that should be combined. Matches Ux7 blend types:
     *   chatConfig+promptConfig → chat, tag+items → tag membership,
     *   role+actors → role membership, group+items → group membership,
     *   charPerson+charPerson → character blend
     * @returns {object|null} Blend result with type and items, or null
     */
    checkBlend: function() {
        if (workingSet.length < 2) return null;
        let schemas = workingSet.map(function(i) { return i.schema || i['.type']; });

        // chatConfig + promptConfig → navigate to chat
        let hasChatConfig = schemas.includes("olio.llm.chatConfig");
        let hasPromptConfig = schemas.includes("olio.llm.promptConfig") || schemas.includes("olio.llm.promptTemplate");
        if (hasChatConfig && hasPromptConfig) {
            return { type: "chatBlend", items: workingSet.slice() };
        }

        // tag + tagged items → tag membership
        let hasTag = schemas.includes("data.tag");
        if (hasTag && schemas.some(function(s) { return s !== "data.tag"; })) {
            return { type: "tagBlend", items: workingSet.slice() };
        }

        // role + actors → role membership
        let hasRole = schemas.includes("auth.role");
        if (hasRole && schemas.some(function(s) { return s !== "auth.role"; })) {
            return { type: "roleBlend", items: workingSet.slice() };
        }

        // group (bucket) + items → group membership
        let hasGroup = schemas.includes("auth.group");
        if (hasGroup && schemas.some(function(s) { return s !== "auth.group"; })) {
            return { type: "groupBlend", items: workingSet.slice() };
        }

        // charPerson + charPerson → character blend
        let charCount = schemas.filter(function(s) { return s === "olio.charPerson"; }).length;
        if (charCount >= 2) {
            return { type: "characterBlend", items: workingSet.slice() };
        }

        return null;
    },

    /**
     * Upload files via drag-and-drop or file picker.
     * For non-data.data types: reads JSON, strips identity fields, creates object.
     * For data.data: uploads as media via /mediaForm endpoint.
     */
    uploadFiles: async function(inst, files) {
        let entity = inst?.entity;
        if (!entity || !files || !files.length) return;

        // Non-data.data: JSON import (create object from dropped JSON file)
        if (inst.model && inst.model.name !== "data.data") {
            let file = files[0];
            return new Promise(function(resolve) {
                let reader = new FileReader();
                reader.onload = function() {
                    try {
                        let obj = JSON.parse(reader.result);
                        let clearFields = ["objectId", "id", "ownerId", "urn", "groupPath", "organizationId", "organizationPath", "vaultId", "vaulted", "keyId"];
                        for (let f of clearFields) delete obj[f];

                        if (inst.api.groupId) {
                            obj.groupId = inst.api.groupId();
                            obj.groupPath = inst.api.groupPath();
                            page.createObject(obj).then(function(o) {
                                if (o && o.objectId) {
                                    m.route.set("/view/" + obj[am7model.jsonModelKey] + "/" + o.objectId);
                                }
                            });
                        } else {
                            page.toast("warn", "No group context for import");
                        }
                    } catch (e) {
                        page.toast("error", "Failed to parse JSON file");
                    }
                    resolve();
                };
                reader.readAsText(file);
            });
        }

        // data.data: upload as media files
        let appPath = am7client.base().replace("/rest", "");
        let promises = [];
        for (let i = 0; i < files.length; i++) {
            let formData = new FormData();
            formData.append("organizationPath", am7client.currentOrganization);
            if (entity.groupId) formData.append("groupId", entity.groupId);
            formData.append("groupPath", entity.groupPath);
            formData.append("name", files[i].name);
            formData.append("dataFile", files[i]);
            promises.push(new Promise(function(resolve) {
                let xhr = new XMLHttpRequest();
                xhr.withCredentials = true;
                xhr.open("POST", appPath + "/mediaForm");
                xhr.onload = function() { resolve(files[i].name); };
                xhr.onerror = function() { resolve(null); };
                xhr.send(formData);
            }));
        }
        let results = await Promise.all(promises);
        let uploaded = results.filter(Boolean);
        if (uploaded.length === 1 && entity.groupPath) {
            let grp = await page.findObject("auth.group", "data", entity.groupPath);
            if (grp) {
                let obj = await page.openObjectByName("data.data", grp.objectId, uploaded[0]);
                if (obj && obj.objectId) {
                    m.route.set("/view/data.data/" + obj.objectId);
                }
            }
        }
    },

    /**
     * Returns Mithril vnode attributes for a drop zone that accepts file drops.
     * @param {object} inst - Prepared model instance
     * @returns {object} Mithril attrs { ondrop, ondragover, ondragenter, ondragleave }
     */
    dropProps: function(inst) {
        return {
            ondrop: function(e) {
                e.preventDefault();
                if (e.dataTransfer && e.dataTransfer.files && e.dataTransfer.files.length) {
                    dnd.uploadFiles(inst, e.dataTransfer.files);
                }
            },
            ondragover: function(e) { e.preventDefault(); },
            ondragenter: function(e) { e.preventDefault(); },
            ondragleave: function(e) { e.preventDefault(); }
        };
    }
};

export { dnd };
export default dnd;
