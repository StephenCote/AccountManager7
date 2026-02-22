(function() {
    /// Custom field renderers for object views
    /// These extend am7view to handle special formats

    /// Portrait format renderer (for profile.portrait)
    function renderPortraitField(inst, fieldName, attrs) {
        let object = inst.entity;
        // Get maxMode and active from attrs if available, otherwise from inst.api
        let maxMode = (attrs && attrs.maxMode !== undefined) ? attrs.maxMode : (inst.api.maxMode ? inst.api.maxMode() : false);
        let active = (attrs && attrs.active !== undefined) ? attrs.active : (inst.api.active ? inst.api.active() : true);

        if (!active) return m("span", "");

        // Check for portrait
        if (!object.profile || !object.profile.portrait || !object.profile.portrait.contentType) {
            return m("span", "No portrait available");
        }

        let pp = object.profile.portrait;
        let path = g_application_path + "/media/" +
                   am7client.dotPath(am7client.currentOrganization) +
                   "/data.data" + pp.groupPath + "/" + pp.name;
        let imgClass = "carousel-item-img" + (maxMode ? " carousel-item-img-max" : "");

        return m("img", {
            class: imgClass,
            src: path,
            alt: object.name || "Portrait"
        });
    }

    /// Image format renderer
    function renderImageField(inst, fieldName, attrs) {
        let object = inst.entity;
        // Get maxMode and active from attrs if available, otherwise from inst.api
        let maxMode = (attrs && attrs.maxMode !== undefined) ? attrs.maxMode : (inst.api.maxMode ? inst.api.maxMode() : false);
        let active = (attrs && attrs.active !== undefined) ? attrs.active : (inst.api.active ? inst.api.active() : true);

        if (!active) return m("span", "");

        let path = buildMediaPath(object);
        let imgClass = "carousel-item-img" + (maxMode ? " carousel-item-img-max" : "");

        return m("img", {
            class: imgClass,
            src: path,
            alt: object.name || "Image"
        });
    }

    /// Video format renderer
    function renderVideoField(inst, fieldName, attrs) {
        let object = inst.entity;
        // Get maxMode and active from attrs if available, otherwise from inst.api
        let maxMode = (attrs && attrs.maxMode !== undefined) ? attrs.maxMode : (inst.api.maxMode ? inst.api.maxMode() : false);
        let active = (attrs && attrs.active !== undefined) ? attrs.active : (inst.api.active ? inst.api.active() : true);

        if (!active) return m("span", "");

        let path = buildMediaPath(object);
        let mt = object.contentType;
        let vidClass = "carousel-item-img" + (maxMode ? " carousel-item-img-max" : "");

        return m("video", {
            class: vidClass,
            preload: "auto",
            controls: "controls",
            autoplay: "autoplay"
        }, [
            m("source", { src: path, type: mt })
        ]);
    }

    /// Audio format renderer
    function renderAudioField(inst, fieldName, attrs) {
        let object = inst.entity;
        // Get maxMode and active from attrs if available, otherwise from inst.api
        let maxMode = (attrs && attrs.maxMode !== undefined) ? attrs.maxMode : (inst.api.maxMode ? inst.api.maxMode() : false);
        let active = (attrs && attrs.active !== undefined) ? attrs.active : (inst.api.active ? inst.api.active() : true);

        if (!active) return m("span", "");

        let path = buildMediaPath(object);
        let mt = object.contentType;

        if (mt.match(/mpeg3$/)) mt = "audio/mpeg";

        let audClass = "carousel-item-img" + (maxMode ? " carousel-item-img-max" : "");

        return m("audio", {
            class: audClass,
            preload: "auto",
            controls: "controls",
            autoplay: "autoplay"
        }, [
            m("source", { src: path, type: mt })
        ]);
    }

    /// Text content renderer
    function renderTextField(inst, fieldName, attrs) {
        let object = inst.entity;
        let active = (attrs && attrs.active !== undefined) ? attrs.active : (inst.api.active ? inst.api.active() : true);

        if (!active) return m("span", "");

        let ctx = page.context();
        let objectId = object.objectId;

        // Clear invalid cache
        if (ctx.contextObjects[objectId] && ctx.contextObjects[objectId].detailsOnly) {
            console.warn("Data is cached as detailsOnly. This shouldn't have happened.");
            delete ctx.contextObjects[objectId];
        }

        // Load full object if needed
        if (!ctx.contextObjects[objectId]) {
            let model = inst.model.name;
            let q = am7view.viewQuery(am7model.newInstance(model));
            q.field("objectId", objectId);

            page.search(q).then(qr => {
                ctx.contextObjects[objectId] = (qr && qr.results.length ? qr.results[0] : null);
                ctx.tags[objectId] = [];
                m.redraw();
            });

            return m("div", { class: "carousel-article-outer" },
                m("div", { class: "carousel-article carousel-article-margin" },
                    "Loading..."
                )
            );
        }

        // Render loaded content
        let dat = ctx.contextObjects[objectId];
        let cnt = bbConverter.mInto(Base64.decode(dat.dataBytesStore));

        return m("div", { class: "carousel-article-outer" },
            m("div", { class: "carousel-article carousel-article-margin" }, cnt)
        );
    }

    /// PDF viewer renderer
    function renderPDFField(inst, fieldName, attrs) {
        let object = inst.entity;
        let active = (attrs && attrs.active !== undefined) ? attrs.active : (inst.api.active ? inst.api.active() : true);

        if (!active) return m("span", "");

        let ctx = page.context();
        let objectId = object.objectId;

        // Clear invalid cache
        if (ctx.contextObjects[objectId] && ctx.contextObjects[objectId].detailsOnly) {
            console.warn("Data is cached as detailsOnly. This shouldn't have happened.");
            delete ctx.contextObjects[objectId];
        }

        // Load full object if needed
        if (!ctx.contextObjects[objectId]) {
            let model = inst.model.name;
            let q = am7view.viewQuery(am7model.newInstance(model));
            q.field("objectId", objectId);

            page.search(q).then(qr => {
                ctx.contextObjects[objectId] = (qr && qr.results.length ? qr.results[0] : null);
                ctx.tags[objectId] = [];

                if (ctx.contextObjects[objectId]) {
                    page.components.pdf.viewer(am7model.prepareInstance(ctx.contextObjects[objectId]));
                }
                m.redraw();
            });

            return m("div", { class: "carousel-article-outer" },
                m("div", { class: "carousel-article carousel-article-margin" },
                    "Loading PDF..."
                )
            );
        }

        // Render PDF viewer
        let cnt = "";
        if (page.components.pdf.viewer(objectId)) {
            cnt = page.components.pdf.viewer(objectId).container();
        } else {
            cnt = m("div", "No PDF Viewer ...");
        }

        return m("div", { class: "carousel-article-outer" },
            m("div", { class: "carousel-article carousel-article-margin" }, cnt)
        );
    }

    /// Markdown/Note renderer
    function renderMarkdownField(inst, fieldName, attrs) {
        let value = inst.api[fieldName]();

        return m("div", { class: "carousel-article-outer" },
            m("div", { class: "carousel-article carousel-article-margin" },
                value
            )
        );
    }

    /// Message content renderer
    function renderMessageContentField(inst, fieldName, attrs) {
        let object = inst.entity;
        let active = (attrs && attrs.active !== undefined) ? attrs.active : (inst.api.active ? inst.api.active() : true);

        if (!active) return m("span", "");

        // Auto-acknowledge message on view
        if (object.spoolStatus === 'SPOOLED' || object.spoolStatus === 'TRANSMITTED') {
            object.spoolStatus = "ACKNOWLEGED_RECEIPT";
            page.patchObject(object);
        }

        let content = Base64.decode(object.data || "");

        return m("div", { class: "carousel-article-outer" },
            m("div", { class: "carousel-article carousel-article-margin" }, [
                m("div", content)
            ])
        );
    }

    /// Memory renderer — displays tool.memory records with type, importance, summary, and content.
    /// Fetches the full object on first render since list queries only include query fields.
    function renderMemoryField(inst, fieldName, attrs) {
        let object = inst.entity;
        let objectId = object.objectId;
        let ctx = page.context();

        /// Fetch full object if content is missing (list view only provides query fields)
        if (!ctx.contextObjects[objectId]) {
            let modelType = "tool.memory";
            let q = am7view.viewQuery(am7model.newInstance(modelType));
            q.field("objectId", objectId);

            page.search(q).then(function(qr) {
                ctx.contextObjects[objectId] = (qr && qr.results.length ? qr.results[0] : null);
                m.redraw();
            });

            return m("div", { class: "carousel-article-outer" },
                m("div", { class: "carousel-article carousel-article-margin p-4" },
                    "Loading memory..."
                )
            );
        }

        let dat = ctx.contextObjects[objectId] || object;
        let memType = dat.memoryType || "NOTE";
        let importance = dat.importance != null ? dat.importance : 5;
        let summary = dat.summary || "";
        let content = dat.content || "";
        let person1Name = (dat.person1 && dat.person1.name) ? dat.person1.name : "";
        let person2Name = (dat.person2 && dat.person2.name) ? dat.person2.name : "";

        let typeColors = {
            "RELATIONSHIP": "bg-purple-100 text-purple-800 dark:bg-purple-900 dark:text-purple-200",
            "FACT": "bg-blue-100 text-blue-800 dark:bg-blue-900 dark:text-blue-200",
            "DECISION": "bg-amber-100 text-amber-800 dark:bg-amber-900 dark:text-amber-200",
            "DISCOVERY": "bg-green-100 text-green-800 dark:bg-green-900 dark:text-green-200",
            "EMOTION": "bg-rose-100 text-rose-800 dark:bg-rose-900 dark:text-rose-200",
            "NOTE": "bg-gray-100 text-gray-800 dark:bg-gray-700 dark:text-gray-200"
        };
        let badgeClass = typeColors[memType] || typeColors["NOTE"];

        let header = [];
        header.push(m("span", { class: "inline-block px-2 py-0.5 rounded text-xs font-medium " + badgeClass }, memType));
        header.push(m("span", { class: "text-xs text-gray-500 dark:text-gray-400 ml-2" }, "Importance: " + importance + "/10"));
        if (person1Name || person2Name) {
            let people = [person1Name, person2Name].filter(function(n) { return n; }).join(" & ");
            header.push(m("span", { class: "text-xs text-gray-500 dark:text-gray-400 ml-2" }, people));
        }

        let sections = [];
        sections.push(m("div", { class: "flex items-center gap-1 mb-2 flex-wrap" }, header));
        if (summary) {
            sections.push(m("div", { class: "text-sm font-medium text-gray-700 dark:text-gray-200 mb-2" }, summary));
        }
        if (content) {
            sections.push(m("div", {
                class: "text-sm text-gray-600 dark:text-gray-300 whitespace-pre-wrap max-h-96 overflow-y-auto",
                style: "line-height: 1.5;"
            }, content));
        }

        return m("div", { class: "carousel-article-outer" },
            m("div", { class: "carousel-article carousel-article-margin p-4" }, sections)
        );
    }

    /// Helper to build media path
    function buildMediaPath(object) {
        return g_application_path + "/media/" +
               am7client.dotPath(am7client.currentOrganization) +
               "/data.data" + object.groupPath + "/" + object.name;
    }

    /// Register custom format renderers with am7view
    /// These can be used by setting format: "image", format: "video", etc. in form definitions

    if (!am7view.customRenderers) {
        am7view.customRenderers = {};
    }

    am7view.customRenderers.portrait = renderPortraitField;
    am7view.customRenderers.image = renderImageField;
    am7view.customRenderers.video = renderVideoField;
    am7view.customRenderers.audio = renderAudioField;
    am7view.customRenderers.text = renderTextField;
    am7view.customRenderers.pdf = renderPDFField;
    am7view.customRenderers.markdown = renderMarkdownField;
    am7view.customRenderers.messageContent = renderMessageContentField;
    am7view.customRenderers.memory = renderMemoryField;

    /// Export for external use
    window.objectViewRenderers = {
        portrait: renderPortraitField,
        image: renderImageField,
        video: renderVideoField,
        audio: renderAudioField,
        text: renderTextField,
        pdf: renderPDFField,
        markdown: renderMarkdownField,
        messageContent: renderMessageContentField,
        memory: renderMemoryField,
        buildMediaPath: buildMediaPath
    };

}());
