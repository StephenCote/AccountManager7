(function () {
    const tab = {};
    tab.component = {
        oninit: function (x) {
            model = x.attrs[am7model.jsonModelKey];
        },
        oncreate: function (x) {

        },
        onremove: function (x) {

        },

        view: function () {
            return m("div", {
                class: "panel-container"
            },
                m("div", { class: "panel-grid" },
                    modelPanel()
                )
            );
        }
    };

    page.components.tab = tab.component;
}());
