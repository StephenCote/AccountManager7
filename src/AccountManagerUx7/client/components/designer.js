(function () {
    const designer = {};
    let designStyle = 'edit';
    let designHtml = "";
    let caller;
    let init = false;
    let toggled = false;
    let pickData = false;
    let pickDataObject = null;
    let codeMirror;

    function handlePickData(dat) {
        if (dat && dat.length) {
            pickDataObject = dat[0];
            pickData = true;
        }
        caller.endPicker();
    }

    function insertObjectAtCaret(obj) {
        let frame = document.querySelector("[rid=designFrame]");
        let doc = (frame.contentWindow || frame.contentDocument);
        doc.document.body.focus();

        var oA = doc.document.createElement("a");


        let oI;
        var sOrg = am7client.dotPath(am7client.currentOrganization);
        var sThumbUrl = g_application_path + "/thumbnail/" + sOrg + "/data.data" + obj.groupPath + "/" + obj.name + "/250x250";
        var sUrl = g_application_path + "/media/" + sOrg + "/data.data" + obj.groupPath + "/" + obj.name;
        var sIco = 0;
        if (!obj.contentType.match(/^image/)) {
            oI = doc.document.createElement("span");
            oI.setAttribute("class", "material-symbols-outlined material-icons-48");
            oI.appendChild(document.createTextNode("widgets"));
        }
        else {
            oI = doc.document.createElement("img");
            oI.setAttribute("src", sThumbUrl);
            oI.setAttribute("title", obj.name);
        }
        oA.appendChild(oI);
        oA.setAttribute("href", sUrl);
        oA.setAttribute("target", "blank");
        oA.setAttribute("title", obj.name);
        insertNodeAtCaret(oA);
    }
    function insertNodeAtCaret(oNode) {
        var sel, range;
        let frame = document.querySelector("[rid=designFrame]");
        let doc = (frame.contentWindow || frame.contentDocument);
        if (doc.getSelection) {
            sel = doc.getSelection();
            if (sel.getRangeAt && sel.rangeCount) {
                range = sel.getRangeAt(0);
                range.deleteContents();
                range.insertNode(oNode);
            }
        }
    }

    function pickImage() {
        if (pickData) {
            pickData = false;
            insertObjectAtCaret(pickDataObject);
            pickDataObject = null;
        }
        else caller.preparePicker(caller.getInstance(), 'data.data', handlePickData, "~/Gallery");
    }
  
    function defAct(e, action) {
        //console.log("Default action: " + e.srcElement);
        let frame = document.querySelector("[rid=designFrame]");
        let doc = (frame.contentWindow || frame.contentDocument);
        let opt;
        doc.document.body.focus();
        switch (action) {
            case "Indent":
            case "Outdent":
            case "InsertHorizontalRule":
            case "InsertOrderedList":
            case "InsertUnorderedList":
            case "JustifyLeft":
            case "JustifyCenter":
            case "JustifyRight":
            case "Underline":
            case 'Bold':
            case 'Italic':
                console.log("Command: '" + action + "'");
                doc.document.execCommand(action, false, opt);
                break;
            default:
                console.log("Don't know what to do");
                break;
        }
        doc.focus();
    }

    function transitionHandler() {
        init = false;
        let entity = caller.getEntity();
        if (!entity || entity[am7model.jsonModelKey] != 'data.data') {
            console.log("Ignoring non data or invalid entity");
            return;
        }
        let source;
        if (designStyle == 'code') {
            source = codeMirror.getValue();
        }
        else {
            source = refreshDesignSources();
        }

        caller.getInstance().api.dataBytesStore(source);
        if (caller.getInstance().api.compressionType) {
            caller.getInstance().api.compressionType("none");
        }
        designHtml = "";

    }
    function alignSource() {
        toggled = false;
        if (!init) {
            setupDesigner();
        }
        else {
            switch (designStyle) {
                case 'source':
                    let txt = document.querySelector("[rid=designText]");
                    if (txt) {
                        txt.value = designHtml;
                    }
                    break;
                case 'edit':
                case 'preview':
                    let frame = document.querySelector("[rid=designFrame]");
                    if (frame) {
                        let content = designDocumentHeader + bbConverter.import(designHtml) + designDocumentFooter;
                        writeFrame(frame, content);
                    }
                    break;
            }
        }
    }
    function toggleStyle(sStyle) {
        designHtml = refreshDesignSources();
        designStyle = sStyle;
        toggled = true;
        m.redraw();
    }

    let formatOptions = ['BLOCK', 'H1', 'H2', 'H3', 'H4', 'H5', 'H6', 'BlockQuote', 'P'];
    let fontOptions = ['FONT', 'Arial', 'Times', 'Verdana', 'Courier'];
    let fontSizeOptions = ['SIZE', 'Size 1', 'Size 2', 'Size 3', 'Size 4', 'Size 5', 'Size 6', 'Size 7'];
    let fontColorOptions = ['COLOR', 'Black', 'Grey', 'White', 'Yellow', 'Green', 'Blue', 'Violet'];
    let fillColorOptions = ['FILL', 'Black', 'Grey', 'White', 'Yellow', 'Green', 'Blue', 'Violet'];

    let designDocumentHeader = `<html lang="en">
        <head>
            <meta charset="utf-8">
            <title>forms</title>
            <link rel="icon" href="/media/5715116.png" />  
            <link href = "/dist/basiTail.css" rel = "stylesheet" />
            <link href="/node_modules/material-icons/iconfont/material-icons.css" rel="stylesheet" />
            <link rel="stylesheet" href="/styles/pageStyle.css" />
        </head>
        <body class = 'carousel-article'>
    `;
    let designDocumentDefContent = `<h1>Title</h1>
    <h2>Sub Title</h2>
    <div>[ body ]</div>
    `;

    let designDocumentFooter = `</body></html>`;

    function setupDesigner() {
        if (init) return;
        let entity = caller.getEntity();
        if (!entity) {
            return;
        }
        if (!entity[am7model.jsonModelKey] || entity[am7model.jsonModelKey] != 'data.data') {
            console.warn("Unexpected model: " + entity[am7model.jsonModelKey]);
            return;
        }
        if (designStyle != 'edit' && designStyle != 'code') {
            console.warn("Setup should only be invoked for edit or code mode");
            return;
        }

        let bytes = caller.getInstance().api.dataBytesStore();
        if (designStyle == 'code') {
            let tarea = document.querySelector("[rid=designText]");
            if (!tarea) {
                return;
            }
            tarea.value = bytes;
            let cfg = {
                lineNumbers: true,
                mode: "javascript",
                indentUnit: 4,
                extraKeys: {
                    Tab: function (cm) {
                        cm.replaceSelection("    ", "end");
                    }
                },
                autoFocus: false,
                gutters: ["CodeMirror-lint-markers"],
                lint: {
                    globals: ["m"],
                    onUpdateLinting: function (annotations) {
                        m.redraw();
                    }
                }
            };
            codeMirror = CodeMirror.fromTextArea(tarea, cfg);
            codeMirror.setSize("100%", "100%");
            window.dbgCode = codeMirror;
        }
        else {
            let frame = document.querySelector("[rid=designFrame]");
            if (!frame) {
                return;
            }
            if (bytes.length) {
                console.log("Write byte store");
                writeFrame(frame, designDocumentHeader + bbConverter.import(bytes) + designDocumentFooter);
            }
            else {
                writeFrame(frame, designDocumentHeader + designDocumentDefContent + designDocumentFooter);
            }
        }
        init = true;
    }

    function writeFrame(frame, content) {

        //console.log("Write: " + content);
        let docType = '<!doctype html>';
        let doc = (frame.contentWindow || frame.contentDocument);
        try {
            if (!doc || !doc.document) {
                console.error("WriteContent: Unable to find document; " + this.writeFrame.caller);
                return;
            }
            var d = doc.document;
            d.open();
            d.write(docType + content);
            d.close();
            enableDesigner(d);
        }
        catch (e) {
            console.error("Unexpected UI Error: " + (e.message ? e.message : e.description));
        }
    }
    function enableDesigner(doc) {
        try {
            if (doc) {
                doc.designMode = (designStyle == 'edit' ? "on" : "off");
                console.log("Design mode: " + doc.designMode);
                doc.documentElement.focus();
            }
            else {
                console.error("Could not find designer document to enabled");
            }
        }
        catch (e) {
            console.error("Error enabling designer: " + (e.message ? e.message : e.description));
        }
    }

    function refreshDesignSources() {
        let sSource;
        switch (designStyle) {
            case 'edit':
                let frame = document.querySelector("[rid=designFrame]");
                if (frame) {
                    var doc = (frame.contentWindow || frame.contentDocument);
                    if (!doc || !doc.document) return null;
                    sSource = bbConverter.convertNodes(doc.document.body.childNodes);
                }
                break;
            case 'source':
                let txt = document.querySelector("[rid=designText]");
                if (txt) sSource = txt.value;
                break;
        }
        if (!sSource) sSource = designHtml;
        else designHtml = sSource;
        return sSource;
    }

    function getDesignField() {
        let field = "";
        let cls = 'full-block' + (page.components.picker.pickerEnabled() ? " hidden" : "");
        switch (designStyle) {
            case 'edit':
            case 'preview':
                /// src : '/blank.html'
                field = m("iframe", { src: 'about:blank', rid: 'designFrame', class: cls });
                break;
            case 'code':
            case 'source':
                field = m("textarea", { rid: 'designText', class: cls });
                break;
        }
        return field;
    }

    function getDesigner(attrs) {
        if (attrs && attrs.entity) {
            let mt = attrs.entity.contentType;
            if (mt) {
                if (mt.match(/^text\//)) {
                    if (designStyle == 'code') designStyle = 'edit';
                    return getRichTextDesigner();
                }
                if (mt.match(/(css|javascript)$/)) {
                    designStyle = 'code';
                    return getCodeDesigner();
                }
            }
        }
        return "";
    }
    function getCodeDesigner() {
        return [
            m("div", { class: "results-overflow" }, getDesignField())
        ];
    }
    function getRichTextDesigner() {
        return [
            m("div", { class: "result-nav-outer" + (page.components.picker.pickerEnabled() ? " hidden" : "") }, [
                m("div", { class: "result-nav-inner" }, [
                    m("div", { class: "result-nav" }, [
                        page.iconButton("button" + (designStyle == 'source' ? ' active' : ''), "code", "", function () { toggleStyle('source'); }),
                        page.iconButton("button" + (designStyle == 'edit' ? ' active' : ''), "design_services", "", function () { toggleStyle('edit'); }),
                        page.iconButton("button" + (designStyle == 'preview' ? ' active' : ''), "preview", "", function () { toggleStyle('preview'); }),
                        page.iconButton("button" + (designStyle != 'edit' ? ' inactive' : ''), "format_italic", "", function (e) { defAct(e, 'Italic'); }),
                        page.iconButton("button" + (designStyle != 'edit' ? ' inactive' : ''), "format_bold", "", function (e) { defAct(e, 'Bold'); }),
                        page.iconButton("button" + (designStyle != 'edit' ? ' inactive' : ''), "format_underlined", "", function (e) { defAct(e, 'Underline'); }),
                        page.iconButton("button" + (designStyle != 'edit' ? ' inactive' : ''), "format_align_left", "", function (e) { defAct(e, 'JustifyLeft'); }),
                        page.iconButton("button" + (designStyle != 'edit' ? ' inactive' : ''), "format_align_center", "", function (e) { defAct(e, 'JustifyCenter'); }),
                        page.iconButton("button" + (designStyle != 'edit' ? ' inactive' : ''), "format_align_justify", "", function (e) { defAct(e, 'JustifyFull'); }),
                        page.iconButton("button" + (designStyle != 'edit' ? ' inactive' : ''), "format_align_right", "", function (e) { defAct(e, 'JustifyRight'); }),
                        page.iconButton("button" + (designStyle != 'edit' ? ' inactive' : ''), "format_list_bulleted", "", function (e) { defAct(e, 'InsertOrderedList'); }),
                        page.iconButton("button" + (designStyle != 'edit' ? ' inactive' : ''), "format_list_numbered", "", function (e) { defAct(e, 'InsertUnorderedList'); }),
                        page.iconButton("button" + (designStyle != 'edit' ? ' inactive' : ''), "format_indent_increase", "", function (e) { defAct(e, 'Indent'); }),
                        page.iconButton("button" + (designStyle != 'edit' ? ' inactive' : ''), "format_indent_decrease", "", function (e) { defAct(e, 'Outdent'); }),
                        page.iconButton("button" + (designStyle != 'edit' ? ' inactive' : ''), "horizontal_rule", "", function (e) { defAct(e, 'InsertHorizontalRule'); }),
                        page.iconButton("button" + (designStyle != 'edit' ? ' inactive' : (pickData ? ' focus' : '')), (pickData ? "content_paste_go" : "image"), "", pickImage),
                        page.iconButton("button" + (designStyle != 'edit' ? ' inactive' : ''), "link", "", defAct)
                    ]),
                    (designStyle != 'edit' ? '' : m("div", { class: "result-nav" }, [
                        m("select", { class: "nav-select reactive-arrow" }, formatOptions.map((o) => m("option", o))),
                        m("select", { class: "nav-select reactive-arrow" }, fontOptions.map((o) => m("option", o))),
                        m("select", { class: "nav-select reactive-arrow" }, fontSizeOptions.map((o) => m("option", o))),
                        m("select", { class: "nav-select reactive-arrow" }, fontColorOptions.map((o) => m("option", o))),
                        m("select", { class: "nav-select reactive-arrow" }, fillColorOptions.map((o) => m("option", o)))
                    ]))
                ])
            ]),
            m("div", { class: "results-overflow" }, getDesignField())
        ];
    }

    designer.component = {

        oninit: function (x) {
            if (x.attrs.caller) {
                caller = x.attrs.caller;
                caller.transitionHandler = transitionHandler;
            }
        },
        oncreate: function (x) {
            alignSource();
        },
        onupdate: function (x) {
            if (!init) setupDesigner();
            else if (toggled) alignSource();
        },
        onremove: function (x) {
            init = false;
            caller = null;
            toggled = false;
            pickData = false;
            pickDataObject = null;
        },

        view: function (v) {
            return (v.attrs.visible ? getDesigner(v.attrs) : "");
        }
    };

    page.components.designer = designer.component;
}());
