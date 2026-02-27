(function(){
let viewers = {};
function pdfViewer(inInst){

    if(typeof inInst == "string"){
        return viewers[inInst];
    }

    let eventBus;

    let currentPage = 1;
    let pageCount = 0;
    let scale = 1.0;
    let raster = [];
    let pdfDocument;
    let inst = inInst;
    let didInit = false;
    function pdfControls(){

        let cls = "button";
        let fp = page.iconButton("button ml-4", "keyboard_double_arrow_left", "", function(){
            document.querySelector("#pdfContainer").innerHTML = "";
           render(1, scale);
        });

        let zoom = [m("input", {type: "range", step: 5, max: 300, min: 25, class: "inline-block ml-2 h-10 range-field-full", value: (scale * 100), onchange: function(e){
			    document.querySelector("#pdfContainer").innerHTML = "";    
                scale = parseInt(e.target.value) / 100;
                render(currentPage, scale);
            }}),
            m("span", {"class": "inline-block text-content mr-2 mt-0 mb-0"}, (scale * 100).toFixed(0) + "%"),
            m("span", {"class": "inline-block text-content w-72 ml-2 mr-2 mt-0 mb-0"}, "Page " + currentPage + " of " + pageCount)
        ];

        
        
        let pb = page.iconButton("button", "chevron_left", "", function(){
            document.querySelector("#pdfContainer").innerHTML = "";
            render(Math.max(1, currentPage - 1), scale);
        });
        let lp = page.iconButton("button", "keyboard_double_arrow_right", "", function(){
            document.querySelector("#pdfContainer").innerHTML = "";
            render(pageCount, scale);
         });
         
         let np = page.iconButton("button", "chevron_right", "", function(){
            document.querySelector("#pdfContainer").innerHTML = "";
             render(Math.min(pageCount, currentPage + 1), scale);
         });

        return m("div",{class: "result-nav-outer"},[
            m("div",{class: "result-nav-inner"},[
                m("div",{class: "result-nav"},
                    [
                        fp, pb, zoom, np, lp
                    ]
                )
            ])
        ]);
    }

    function pdfContainer(){
        return[pdfControls(), m("div", {id: 'pdfContainer', class: "pdfViewer singlePageView"})];
    }
    async function loadPdf(){
        if(pdfDocument){
            return pdfDocument
        }
        if(!eventBus){
            eventBus = new pdfjsViewer.EventBus();
        }
        // Worker is auto-configured by webpack.mjs (loaded via <script> in index.html)
        // which sets GlobalWorkerOptions.workerPort to a module Worker.

        let bits = am7model.base64ToUint8(inst.entity.dataBytesStore);
        let loadingTask = pdfjsLib.getDocument(bits);
        pdfDocument = await loadingTask.promise;
        return pdfDocument;
    }
    
    async function init(scale){
        let container = document.querySelector("#pdfContainer");
        if(container && inst && !didInit){
            didInit = true;
            await render(1, scale || 1.0);
        }
        else if(didInit){
            // console.warn("PDF already initialized.");
        }
    }

    async function render(pdfPageNum, pdfScale){
    
        if(!inst || !inst.model.name == "data.data"){
            console.error("Invalid instance or model name.", inst);
            return;
        }
        if(!inst.entity.dataBytesStore){
            console.info("Data is empty.", inst);
            return;
        }
        if(pdfPageNum){
            currentPage = pdfPageNum;
        }
        if(pdfScale){
            scale = pdfScale;
        }

        let container = document.querySelector("#pdfContainer");
        if(container != null){
            let doc = await loadPdf(inst);
            if(!doc){
                console.error("Failed to load pdf document.");
            }

            pageCount = doc.numPages;
            let pdfPage = await doc.getPage(currentPage);
            if(!pdfPage){
                console.error("Failed to load page " + currentPage);
                return;
            }

           let viewport = pdfPage.getViewport({ scale: scale });
            let pdfPageView = new pdfjsViewer.PDFPageView({
                container,
                id: pdfPageNum,
                scale: scale,
                defaultViewport: viewport,
                eventBus,
            });
            pdfPageView.setPdfPage(pdfPage);
            pdfPageView.draw();
            currentPage = pdfPageNum;
        }

    }

    let pdf = {
        controls: pdfControls,
        init,
        render,
        container: pdfContainer,
        clear: function(){
            didInit = false;
            /*
            if(inst && inst.entity.objectId){
                delete viewers[inst.entity.objectId];
            }
            */
        }
    }
    if(inst && inst.entity.objectId){
        viewers[inst.entity.objectId] = pdf;
    }

    return pdf;
}
    page.components.pdf = {
        viewer: pdfViewer,
        viewers,
        clear: function(objectId){
            if(!objectId){
                for(let k in viewers){
                    viewers[k].clear();
                }
            }
            else if(viewers[objectId]){
                viewers[objectId].clear();
            }
        }
    };
}())