/**
 * fileUpload.js — File upload component with drag-drop (ESM)
 *
 * Provides: FileUpload.view() Mithril component and FileUpload.upload() utility.
 * Handles multipart POST to REST endpoints for data.data and blob fields.
 */
import m from 'mithril';
import { am7model } from '../core/model.js';

function getClient() { return am7model._client; }
function getPage() { return am7model._page; }

let _uploading = false;
let _progress = 0;
let _error = null;

/**
 * Upload a file to the server.
 * @param {File} file - browser File object
 * @param {object} opts - { type, objectId, field, groupPath, name, onComplete }
 */
async function upload(file, opts) {
    let client = getClient();
    let page = getPage();
    if (!client) throw new Error("Client not available");

    _uploading = true;
    _progress = 0;
    _error = null;
    m.redraw();

    let url;
    let formData = new FormData();
    formData.append("file", file);

    if (opts.objectId && opts.field) {
        // Upload to specific object field
        url = client.base() + "/upload/" + opts.type + "/" + opts.objectId + "/" + opts.field;
    } else if (opts.groupPath && opts.name) {
        // Upload as new data.data
        url = client.base() + "/data" + opts.groupPath + "/" + (opts.name || file.name);
        formData.append("contentType", file.type || "application/octet-stream");
    } else {
        // Generic upload
        url = client.base() + "/upload";
        if (opts.type) formData.append("type", opts.type);
    }

    try {
        let xhr = new XMLHttpRequest();
        let result = await new Promise(function(resolve, reject) {
            xhr.open("POST", url, true);
            xhr.withCredentials = true;

            xhr.upload.onprogress = function(e) {
                if (e.lengthComputable) {
                    _progress = Math.round((e.loaded / e.total) * 100);
                    m.redraw();
                }
            };

            xhr.onload = function() {
                if (xhr.status >= 200 && xhr.status < 300) {
                    try { resolve(JSON.parse(xhr.responseText)); }
                    catch(e) { resolve(xhr.responseText); }
                } else {
                    reject(new Error("Upload failed: " + xhr.status));
                }
            };

            xhr.onerror = function() { reject(new Error("Upload network error")); };
            xhr.send(formData);
        });

        _uploading = false;
        _progress = 100;
        m.redraw();

        if (page) page.toast("success", "Uploaded: " + file.name);
        if (opts.onComplete) opts.onComplete(result);
        return result;
    } catch (e) {
        _uploading = false;
        _error = e.message;
        m.redraw();
        if (page) page.toast("error", "Upload failed: " + e.message);
        throw e;
    }
}

/**
 * Mithril component for file upload with drag-drop zone.
 * Attrs: { type, objectId, field, groupPath, name, accept, onComplete }
 */
const FileUploadView = {
    view: function(vnode) {
        let attrs = vnode.attrs || {};

        function handleFiles(files) {
            if (!files || !files.length) return;
            upload(files[0], {
                type: attrs.type,
                objectId: attrs.objectId,
                field: attrs.field,
                groupPath: attrs.groupPath,
                name: attrs.name,
                onComplete: attrs.onComplete
            });
        }

        return m("div", {
            class: "border-2 border-dashed border-gray-300 dark:border-gray-600 rounded-lg p-4 text-center " +
                   "hover:border-blue-400 dark:hover:border-blue-500 transition-colors cursor-pointer",
            ondragover: function(e) { e.preventDefault(); e.dataTransfer.dropEffect = "copy"; },
            ondrop: function(e) { e.preventDefault(); handleFiles(e.dataTransfer.files); },
            onclick: function() {
                let input = document.createElement("input");
                input.type = "file";
                if (attrs.accept) input.accept = attrs.accept;
                input.onchange = function() { handleFiles(input.files); };
                input.click();
            }
        }, [
            _uploading ? [
                m("div", { class: "text-sm text-blue-500 mb-2" }, "Uploading... " + _progress + "%"),
                m("div", { class: "w-full bg-gray-200 dark:bg-gray-700 rounded-full h-2" },
                    m("div", {
                        class: "bg-blue-500 h-2 rounded-full transition-all",
                        style: "width:" + _progress + "%"
                    })
                )
            ] : [
                m("span", { class: "material-symbols-outlined text-gray-400", style: "font-size:32px" }, "cloud_upload"),
                m("div", { class: "text-sm text-gray-500 mt-1" }, "Drop file or click to upload"),
                _error ? m("div", { class: "text-xs text-red-500 mt-1" }, _error) : null
            ]
        ]);
    }
};

const FileUpload = {
    upload: upload,
    View: FileUploadView,
    isUploading: function() { return _uploading; },
    getProgress: function() { return _progress; }
};

export { FileUpload };
export default FileUpload;
