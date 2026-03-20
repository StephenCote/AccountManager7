/**
 * pdfViewer.js — PDF viewer component using pdfjs-dist (ESM)
 * Port of Ux7 client/components/pdf.js
 *
 * Paginated canvas rendering with page nav and zoom control.
 * Requires pdfjs-dist as npm dependency.
 */
import m from 'mithril';
import { am7model } from '../core/model.js';

let pdfjsLib = null;
let pdfjsViewer = null;
let viewers = {};

async function loadPdfjsLib() {
    if (pdfjsLib) return;
    try {
        pdfjsLib = await import(/* @vite-ignore */ 'pdfjs-dist');
        pdfjsViewer = await import(/* @vite-ignore */ 'pdfjs-dist/web/pdf_viewer.mjs');
        // Set worker
        if (pdfjsLib.GlobalWorkerOptions) {
            pdfjsLib.GlobalWorkerOptions.workerSrc = 'pdfjs-dist/build/pdf.worker.min.mjs';
        }
    } catch (e) {
        console.warn("[pdfViewer] pdfjs-dist not available:", e.message);
    }
}

function pdfViewerFactory(inInst) {
    if (typeof inInst === "string") {
        return viewers[inInst];
    }

    let eventBus;
    let currentPage = 1;
    let pageCount = 0;
    let scale = 1.0;
    let pdfDocument;
    let inst = inInst;
    let didInit = false;

    function pdfControls() {
        return m("div", { class: "flex items-center gap-2 px-2 py-1.5 bg-gray-50 dark:bg-gray-800 border-b border-gray-200 dark:border-gray-700" }, [
            m("button", {
                class: "p-1 rounded hover:bg-gray-200 dark:hover:bg-gray-700",
                onclick: function() {
                    let container = document.querySelector("#pdfContainer");
                    if (container) container.innerHTML = "";
                    render(1, scale);
                }
            }, m("span", { class: "material-symbols-outlined", style: "font-size:18px" }, "keyboard_double_arrow_left")),
            m("button", {
                class: "p-1 rounded hover:bg-gray-200 dark:hover:bg-gray-700",
                onclick: function() {
                    let container = document.querySelector("#pdfContainer");
                    if (container) container.innerHTML = "";
                    render(Math.max(1, currentPage - 1), scale);
                }
            }, m("span", { class: "material-symbols-outlined", style: "font-size:18px" }, "chevron_left")),
            m("span", { class: "text-xs text-gray-600 dark:text-gray-300 whitespace-nowrap" },
                "Page " + currentPage + " of " + pageCount),
            m("button", {
                class: "p-1 rounded hover:bg-gray-200 dark:hover:bg-gray-700",
                onclick: function() {
                    let container = document.querySelector("#pdfContainer");
                    if (container) container.innerHTML = "";
                    render(Math.min(pageCount, currentPage + 1), scale);
                }
            }, m("span", { class: "material-symbols-outlined", style: "font-size:18px" }, "chevron_right")),
            m("button", {
                class: "p-1 rounded hover:bg-gray-200 dark:hover:bg-gray-700",
                onclick: function() {
                    let container = document.querySelector("#pdfContainer");
                    if (container) container.innerHTML = "";
                    render(pageCount, scale);
                }
            }, m("span", { class: "material-symbols-outlined", style: "font-size:18px" }, "keyboard_double_arrow_right")),
            m("input", {
                type: "range", step: 5, max: 300, min: 25,
                class: "w-24 h-1 accent-blue-500",
                value: (scale * 100),
                onchange: function(e) {
                    let container = document.querySelector("#pdfContainer");
                    if (container) container.innerHTML = "";
                    scale = parseInt(e.target.value) / 100;
                    render(currentPage, scale);
                }
            }),
            m("span", { class: "text-xs text-gray-500 dark:text-gray-400 whitespace-nowrap" },
                (scale * 100).toFixed(0) + "%")
        ]);
    }

    function pdfContainer() {
        return [pdfControls(), m("div", { id: "pdfContainer", class: "overflow-auto" })];
    }

    async function loadPdf() {
        if (pdfDocument) return pdfDocument;
        await loadPdfjsLib();
        if (!pdfjsLib) return null;

        if (pdfjsViewer && pdfjsViewer.EventBus && !eventBus) {
            eventBus = new pdfjsViewer.EventBus();
        }

        let raw = atob(inst.entity.dataBytesStore);
        let bits = new Uint8Array(raw.length);
        for (let i = 0; i < raw.length; i++) bits[i] = raw.charCodeAt(i);
        let loadingTask = pdfjsLib.getDocument(bits);
        pdfDocument = await loadingTask.promise;
        return pdfDocument;
    }

    async function init(initScale) {
        let container = document.querySelector("#pdfContainer");
        if (container && inst && !didInit) {
            didInit = true;
            await render(1, initScale || 1.0);
        }
    }

    async function render(pdfPageNum, pdfScale) {
        if (!inst || inst.model.name !== "data.data") return;
        if (!inst.entity.dataBytesStore) return;

        if (pdfPageNum) currentPage = pdfPageNum;
        if (pdfScale) scale = pdfScale;

        let container = document.querySelector("#pdfContainer");
        if (!container) return;

        try {
            let doc = await loadPdf();
            if (!doc) return;

            pageCount = doc.numPages;
            let pdfPage = await doc.getPage(currentPage);
            if (!pdfPage) return;

            let viewport = pdfPage.getViewport({ scale: scale });

            if (pdfjsViewer && pdfjsViewer.PDFPageView) {
                let pdfPageView = new pdfjsViewer.PDFPageView({
                    container,
                    id: pdfPageNum,
                    scale: scale,
                    defaultViewport: viewport,
                    eventBus,
                });
                pdfPageView.setPdfPage(pdfPage);
                pdfPageView.draw();
            } else {
                // Fallback: render to canvas directly
                let canvas = document.createElement("canvas");
                let ctx = canvas.getContext("2d");
                canvas.height = viewport.height;
                canvas.width = viewport.width;
                container.appendChild(canvas);
                await pdfPage.render({ canvasContext: ctx, viewport: viewport }).promise;
            }

            currentPage = pdfPageNum;
            m.redraw();
        } catch (e) {
            console.error("PDF render error:", e);
        }
    }

    let pdf = {
        controls: pdfControls,
        init,
        render,
        container: pdfContainer,
        clear: function() {
            didInit = false;
            pdfDocument = null;
        }
    };

    if (inst && inst.entity.objectId) {
        viewers[inst.entity.objectId] = pdf;
    }

    return pdf;
}

// ── Public API ──────────────────────────────────────────────────────

const pdfViewerComponent = {
    viewer: pdfViewerFactory,
    viewers,
    clear: function(objectId) {
        if (!objectId) {
            for (let k in viewers) {
                viewers[k].clear();
            }
        } else if (viewers[objectId]) {
            viewers[objectId].clear();
        }
    }
};

export { pdfViewerComponent };
export default pdfViewerComponent;
