(function () {
    let object = {};

    function renderObjectView(attrs) {
        let object = attrs
        return m("div", { class: "content-outer" }, [(fullMode ? "" : m(page.components.navigation)),
        m("div", { class: "content-main" }, [
            m("div", { class: "carousel" }, [
                m("div", { class: "carousel-inner" }, [
                    renderInnerObjectView(attrs),
                    m("span", { onclick: toggleCarouselFull, class: "carousel-full" }, [m("span", { class: "material-icons" }, (fullMode ? "close_fullscreen" : "open_in_new"))]),
                    m("span", { onclick: toggleCarouselMax, class: "carousel-max" }, [m("span", { class: "material-icons" }, (maxMode ? "photo_size_select_small" : "aspect_ratio"))]),
                    m("span", { onclick: toggleInfo, class: "carousel-info" }, [m("span", { class: "material-icons" + (info ? "" : "-outlined") }, (info ? "info" : "info"))]),
                    m("span", { onclick: function () { editItem(pr[pages.currentItem]); }, class: "carousel-edit" }, [m("span", { class: "material-icons" }, "edit")]),
                    m("span", { onclick: toggleCarousel, class: "carousel-exit" }, [m("span", { class: "material-icons" }, "close")])
                ])
            ])
        ])
        ]);
    }

    function renderInnerObjectView(attrs) {

        let cls = 'carousel-item';
        let object = attrs.object;
        let active = attrs.active;
        if (active) cls += ' carousel-item-abs';
        let maxMode = attrs.maxMode;
        let model = attrs[am7model.jsonModelKey] || object[am7model.jsonModelKey];
        if (!object || !model) {
            console.warn("Invalid object", object);
            return "";
        }

        let type = am7model.getModel(model);
        let objView = "";
        let mt = object.contentType || "";
        if (object.profile && object.profile.portrait && object.profile.portrait.contentType) {
            let pp = object.profile.portrait;
            let path = g_application_path + "/media/" + am7client.dotPath(am7client.currentOrganization) + "/data.data" + pp.groupPath + "/" + pp.name;
            if (active) objView = m("img", { class: "carousel-item-img" + (maxMode ? " carousel-item-img-max" : ""), src: path });
        }
        else if (model === 'note') {
            objView = m("div", { class: "carousel-article-outer" }, m("div", { class: "carousel-article carousel-article-margin" }, object.text));
        }
        else if (model === 'form') {
            if (active) objView = m(page.components.form, attrs);
        }
        else if (model === 'message') {
            if (active) {
                if (object.spoolStatus === 'SPOOLED' || object.spoolStatus === 'TRANSMITTED') {
                    object.spoolStatus = "ACKNOWLEGED_RECEIPT";
                    page.patchObject(object);
                }
                objView = m("div", { class: "carousel-article-outer" }, m("div", { class: "carousel-article carousel-article-margin" }, [
                    m("div", Base64.decode(object.data))
                ]));
            }
        }
        else if (mt.match(/^(video|image|audio)/)) {

            let path = g_application_path + "/media/" + am7client.dotPath(am7client.currentOrganization) + "/data.data" + object.groupPath + "/" + object.name;
            if (mt.match(/^image/) || mt.match(/webp$/)) {
                if (active) objView = m("img", { class: "carousel-item-img" + (maxMode ? " carousel-item-img-max" : ""), src: path });
            }
            else if (mt.match(/^video/)) {
                // Video playback
                if (active) objView = m("video", { class: "carousel-item-img" + (maxMode ? " carousel-item-img-max" : ""), preload: "auto", controls: "controls", autoplay: "autoplay" },
                    m("source", { src: path, type: mt })
                );
            }
            else if (mt.match(/^audio/)) {
                // Audio playback with visualizer
                if (active) {
                    // Use standard HTML5 audio with controls
                    objView = m("div", { class: "flex flex-col items-center justify-center w-full h-full" }, [
                        m("audio", {
                            class: "w-full max-w-2xl",
                            preload: "auto",
                            controls: "controls",
                            autoplay: "autoplay"
                        }, m("source", { src: path, type: mt.match(/mpeg3$/) ? "audio/mpeg" : mt }))
                    ]);
                }
            }
        }
        else if (mt.match(/^text/) || mt.match(/pdf$/)) {
            let ctx = page.context();
            if (active) {

                if (ctx.contextObjects[object.objectId] && ctx.contextObjects[object.objectId].detailsOnly) {
                    logger.warn("Data is cached as detailsOnly.  This shouldn't have happened.");
                    delete ctx.contextObjects[object.objectId];
                }
                if (!ctx.contextObjects[object.objectId]) {
                    // console.log("Request full obj");
                    let q = am7view.viewQuery(am7model.newInstance(model));
                    q.field("objectId", object.objectId);
                    page.search(q).then(qr => {
                        ctx.contextObjects[object.objectId] = (qr && qr.results.length ? qr.results[0] : null);
                        ctx.tags[object.objectId] = [];
                        if(ctx.contextObjects[object.objectId] && mt.match(/pdf$/)){
                            page.components.pdf.viewer(am7model.prepareInstance(ctx.contextObjects[object.objectId]));
                        }
                    });
                }
                else {
                    // console.log("Use cached obj");
                    cls = 'carousel-item carousel-item-flex';
                    let dat = ctx.contextObjects[object.objectId];
                    let cnt = "";
                    if(mt.match(/^text/)){
                        cnt = bbConverter.mInto(Base64.decode(dat.dataBytesStore))
                    }
                    else if(mt.match(/pdf$/)){
                        //cnt = page.components.pdf.container();
                        if(page.components.pdf.viewer(object.objectId)){
                            cnt = page.components.pdf.viewer(object.objectId).container();
                        }
                        else{
                            cnt = m("div", "No PDF Viewer ...");
                        }
                    }
                    
                    objView = m("div", { class: "carousel-article-outer" }, m("div", { class: "carousel-article carousel-article-margin" }, cnt));
                }
            }
        }

        else {
            objView = m("div", "Working on it: " + object.urn);
            //console.warn(object);
        }
        let useCls = cls + " carousel-item-" + (active ? "100" : "0");
        return m("div", { class: useCls }, objView);
    }

    object.component = {
        oncreate: function (vnode) {

        },
        view: function (vnode) {
            if (vnode.attrs.inner) return renderInnerObjectView(vnode.attrs);
            else return renderObjectView(vnode.attrs);
        }
    };

    page.components.object = object.component;

}());
