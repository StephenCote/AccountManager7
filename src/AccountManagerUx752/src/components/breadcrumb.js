import m from 'mithril';
import { am7model } from '../core/model.js';

const breadcrumb = {
    view: function () {
        let route = m.route.get() || '';
        let parts = route.split('/').filter(function (p) { return p.length > 0; });
        if (parts.length === 0) return '';

        let crumbs = [
            { label: 'Home', route: '/main' }
        ];

        // Parse route: /list/:type/:objectId or /view/:type/:objectId
        if (parts.length >= 2) {
            let action = parts[0]; // list, view, new
            let type = parts[1];
            let model = am7model.getModel(type);
            let label = model ? (model.label || model.name) : type;

            if (action === 'list') {
                crumbs.push({ label: label, route: null });
            } else if (action === 'view' || action === 'new') {
                crumbs.push({ label: label, route: '/list/' + type });
                crumbs.push({ label: action === 'new' ? 'New' : 'Detail', route: null });
            }
        }

        return m("nav", { class: "breadcrumb-bar", 'aria-label': "Breadcrumb" }, [
            m("div", { class: "breadcrumb-container" }, [
                m("nav", { class: "breadcrumb" }, [
                    m("ol", { class: "breadcrumb-list" },
                        crumbs.map(function (c, i) {
                            let isLast = i === crumbs.length - 1;
                            let sep = i > 0 ? m("span", { class: "mx-1 text-gray-400" }, "/") : null;
                            if (isLast || !c.route) {
                                return m("li", {}, [sep, m("span", { class: "font-semibold" }, c.label)]);
                            }
                            return m("li", {}, [
                                sep,
                                m("button", {
                                    onclick: function () { m.route.set(c.route); }
                                }, c.label)
                            ]);
                        })
                    )
                ])
            ])
        ]);
    }
};

export { breadcrumb };
export default breadcrumb;
