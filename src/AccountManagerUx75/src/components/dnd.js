/**
 * Drag-and-Drop — workingSet management for dragging objects between views (ESM)
 * Port of Ux7 client/components/dnd.js
 */
import m from 'mithril';

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
     * Blend operation: check if working set contains config objects
     * that should be combined (e.g., chatConfig + promptConfig → navigate to chat).
     * @returns {object|null} Blend result with type and items, or null
     */
    checkBlend: function() {
        if (workingSet.length < 2) return null;
        let schemas = workingSet.map(function(i) { return i.schema || i['.type']; });
        let hasChatConfig = schemas.includes("olio.llm.chatConfig");
        let hasPromptConfig = schemas.includes("olio.llm.promptConfig") || schemas.includes("olio.llm.promptTemplate");
        if (hasChatConfig && hasPromptConfig) {
            return { type: "chatBlend", items: workingSet.slice() };
        }
        return null;
    }
};

export { dnd };
export default dnd;
