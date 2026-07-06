(function () {
    let object = {};

    /// ========================================
    /// INNER VIEW - Uses am7view pattern
    /// ========================================

    function renderInnerObjectView(attrs) {
        let obj = attrs.object;
        let model = attrs[am7model.jsonModelKey] || obj[am7model.jsonModelKey] || attrs.model;

        if (!obj || !model) {
            console.warn("Invalid object", obj);
            return "";
        }

        // Ensure attrs has the model in the correct format for am7view
        let attrsWithModel = Object.assign({}, attrs);
        attrsWithModel[am7model.jsonModelKey] = model;

        // Prepare view instance using am7view
        let inst = am7view.prepareObjectView(obj, attrsWithModel);

        if (!inst) {
            return m("div", "Unable to render object: " + (obj.name || obj.objectId));
        }

        // Get carousel classes
        let cls = 'carousel-item';
        if (attrs.active) cls += ' carousel-item-abs';
        cls += ' carousel-item-' + (attrs.active ? '100' : '0');

        // Render using the custom renderer
        let format = inst.entity.viewType;
        let renderer = am7view.customRenderers[format];

        if (!renderer) {
            return m("div", { class: cls },
                m("div", "No renderer available for: " + format)
            );
        }

        // Pass attrs to renderer so it can access dynamic values like maxMode
        let content = renderer(inst, "objectId", attrs);

        return m("div", { class: cls }, content);
    }

    /// ========================================
    /// OUTER VIEW - Carousel controls
    /// ========================================

    function renderObjectView(attrs) {
        return m("div", { class: "content-outer" }, [
            (fullMode ? "" : m(page.components.navigation)),
            m("div", { class: "content-main" }, [
                m("div", { class: "carousel" }, [
                    m("div", { class: "carousel-inner" }, [
                        renderInnerObjectView(attrs),
                        renderCarouselControls()
                    ])
                ])
            ])
        ]);
    }

    /// Render carousel control buttons
    function renderCarouselControls() {
        return [
            m("span", {
                onclick: toggleCarouselFull,
                class: "carousel-full"
            }, [
                m("span", { class: "material-icons" },
                    (fullMode ? "close_fullscreen" : "open_in_new"))
            ]),
            m("span", {
                onclick: toggleCarouselMax,
                class: "carousel-max"
            }, [
                m("span", { class: "material-icons" },
                    (maxMode ? "photo_size_select_small" : "aspect_ratio"))
            ]),
            m("span", {
                onclick: toggleInfo,
                class: "carousel-info"
            }, [
                m("span", {
                    class: "material-icons" + (info ? "" : "-outlined")
                }, (info ? "info" : "info"))
            ]),
            m("span", {
                onclick: function () {
                    editItem(pr[pages.currentItem]);
                },
                class: "carousel-edit"
            }, [
                m("span", { class: "material-icons" }, "edit")
            ]),
            m("span", {
                onclick: toggleCarousel,
                class: "carousel-exit"
            }, [
                m("span", { class: "material-icons" }, "close")
            ])
        ];
    }

    /// ========================================
    /// COMPONENT DEFINITION
    /// ========================================

    object.component = {
        oncreate: function (vnode) {
            // Initialization if needed
        },
        view: function (vnode) {
            if (vnode.attrs.inner) {
                return renderInnerObjectView(vnode.attrs);
            } else {
                return renderObjectView(vnode.attrs);
            }
        }
    };

    page.components.object = object.component;

}());
