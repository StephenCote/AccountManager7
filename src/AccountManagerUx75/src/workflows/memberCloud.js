import m from 'mithril';
import { am7model } from '../core/model.js';
import { am7client } from '../core/am7client.js';
import { page } from '../core/pageClient.js';
import { Dialog } from '../components/dialogCore.js';

/**
 * Member Cloud workflow — renders tag/membership cloud visualization.
 * Shows tag cloud sized by member count, drill into members with thumbnails.
 */

async function memberCloud(modelType, containerId) {
    let stats = await am7client.membershipStats(modelType, 'any', containerId, 200);
    if (!stats || !stats.length) {
        page.toast('info', 'No membership data found');
        return;
    }

    let cloudMode = true;
    let selectedStat = null;
    let memberList = [];
    let previewSrc = null;
    let searchFilter = '';

    let minCount = Math.min(...stats.map(s => s.count));
    let maxCount = Math.max(...stats.map(s => s.count));

    function getFilteredStats() {
        if (!searchFilter.length) return stats;
        let lf = searchFilter.toLowerCase();
        return stats.filter(s => s.name && s.name.toLowerCase().indexOf(lf) > -1);
    }

    function fontSize(count) {
        if (maxCount === minCount) return 1.5;
        return 0.8 + ((count - minCount) / (maxCount - minCount)) * 2.2;
    }

    function cloudColor(count) {
        let ratio = (maxCount === minCount) ? 0.5 : (count - minCount) / (maxCount - minCount);
        let hue = 210 - (ratio * 160);
        let sat = 55 + (ratio * 25);
        let lit = 92 - (ratio * 20);
        return 'hsl(' + hue + ',' + sat + '%,' + lit + '%)';
    }

    function cloudTextColor(count) {
        let ratio = (maxCount === minCount) ? 0.5 : (count - minCount) / (maxCount - minCount);
        let hue = 210 - (ratio * 160);
        let lit = 35 - (ratio * 10);
        return 'hsl(' + hue + ',60%,' + lit + '%)';
    }

    async function selectStat(stat) {
        selectedStat = stat;
        cloudMode = false;
        memberList = [];
        m.redraw();
        let mems = await am7client.members(modelType, stat.objectId, stat.type || 'any', 0, 100);
        if (mems && mems.length) am7model.updateListModel(mems);
        memberList = mems || [];
        m.redraw();
    }

    function backToCloud() {
        cloudMode = true;
        selectedStat = null;
        memberList = [];
        previewSrc = null;
        m.redraw();
    }

    function getThumbnailSrc(mem) {
        let memType = mem[am7model.jsonModelKey] || '';
        let orgPath = am7client.dotPath(am7client.currentOrganization);
        if (memType === 'olio.charPerson' && mem.profile && mem.profile.portrait && mem.profile.portrait.contentType) {
            let pp = mem.profile.portrait;
            return am7client.base().replace('/rest', '') + '/thumbnail/' + orgPath + '/data.data' + pp.groupPath + '/' + pp.name;
        }
        if (memType === 'data.data' && mem.contentType && mem.contentType.match(/^image/)) {
            return am7client.base().replace('/rest', '') + '/thumbnail/' + orgPath + '/data.data' + mem.groupPath + '/' + mem.name;
        }
        return null;
    }

    function renderCloudView() {
        let filtered = getFilteredStats();
        return m('div', { style: 'display:flex;flex-direction:column;max-height:60vh;' }, [
            m('div', { style: 'padding:10px 20px;border-bottom:1px solid #e0e0e0;' }, [
                m('input', {
                    type: 'text',
                    placeholder: 'Search tags...',
                    value: searchFilter,
                    oninput: function (e) { searchFilter = e.target.value; },
                    class: 'text-field-compact',
                    style: 'width:100%;border-radius:20px;'
                })
            ]),
            m('div', { style: 'padding:20px;text-align:center;line-height:1.4;overflow-y:auto;flex:1;' },
                filtered.length ? filtered.map(function (stat) {
                    let size = fontSize(stat.count);
                    let bg = cloudColor(stat.count);
                    let fg = cloudTextColor(stat.count);
                    return m('span', {
                        style: 'font-size:' + size + 'em;cursor:pointer;margin:5px 6px;display:inline-block;' +
                            'background:' + bg + ';color:' + fg + ';' +
                            'padding:4px 12px;border-radius:20px;' +
                            'border:1px solid ' + fg + '20;' +
                            'transition:transform 0.15s,box-shadow 0.15s;' +
                            'box-shadow:0 1px 3px rgba(0,0,0,0.08);',
                        title: stat.name + ' (' + stat.count + ')',
                        onmouseenter: function (e) { e.target.style.transform = 'scale(1.08)'; e.target.style.boxShadow = '0 3px 8px rgba(0,0,0,0.15)'; },
                        onmouseleave: function (e) { e.target.style.transform = ''; e.target.style.boxShadow = '0 1px 3px rgba(0,0,0,0.08)'; },
                        onclick: function () { selectStat(stat); }
                    }, [stat.name, m('sup', { style: 'margin-left:3px;font-size:0.65em;opacity:0.7;' }, stat.count)]);
                }) : m('div', { style: 'color:#888;padding:20px;' }, 'No tags match "' + searchFilter + '"')
            )
        ]);
    }

    function renderDetailView() {
        return m('div', { style: 'padding:16px;position:relative;display:flex;flex-direction:column;height:100%;' }, [
            previewSrc ? m('div', {
                style: 'position:absolute;top:0;left:0;right:0;bottom:0;background:rgba(0,0,0,0.85);z-index:10;display:flex;align-items:center;justify-content:center;cursor:pointer;',
                onclick: function () { previewSrc = null; m.redraw(); }
            }, [
                m('img', { src: previewSrc, style: 'max-width:90%;max-height:90%;object-fit:contain;border-radius:4px;box-shadow:0 4px 24px rgba(0,0,0,0.5);' })
            ]) : '',
            m('div', { style: 'margin-bottom:12px;display:flex;align-items:center;gap:16px;' }, [
                m('button', { class: 'am7-dialog-btn am7-dialog-btn-secondary', onclick: backToCloud }, [
                    m('span', { class: 'material-symbols-outlined md-18', style: 'margin-right:4px;' }, 'arrow_back'),
                    'Back'
                ]),
                m('div', [
                    m('h4', { style: 'margin:0 0 4px 0;' }, [
                        m('a', { href: '#!/view/' + modelType + '/' + selectedStat.objectId, target: '_blank' }, selectedStat.name)
                    ]),
                    m('span', { style: 'color:#666;' }, selectedStat.count + ' member' + (selectedStat.count !== 1 ? 's' : ''))
                ])
            ]),
            m('div', { style: 'flex:1;overflow-y:auto;width:100%;' },
                memberList.length === 0 ? m('em', 'Loading members...') :
                    m('div', { style: 'display:grid;grid-template-columns:repeat(auto-fill,minmax(180px,1fr));gap:16px;padding:8px;' },
                        memberList.map(function (mem) {
                            let memType = mem[am7model.jsonModelKey] || 'unknown';
                            let memName = mem.name || mem.objectId;
                            let thumbBase = getThumbnailSrc(mem);
                            let thumb = '';

                            if (thumbBase) {
                                thumb = m('img', {
                                    style: 'width:100%;aspect-ratio:1;border-radius:8px;object-fit:cover;cursor:pointer;',
                                    src: thumbBase + '/256x256',
                                    onerror: function (e) { e.target.style.display = 'none'; },
                                    onclick: function (e) { e.preventDefault(); previewSrc = thumbBase + '/512x512'; m.redraw(); }
                                });
                            } else if (memType === 'olio.charPerson') {
                                thumb = m('div', { style: 'width:100%;aspect-ratio:1;display:flex;align-items:center;justify-content:center;background:#f5f5f5;border-radius:8px;' }, [
                                    m('span', { class: 'material-symbols-outlined', style: 'font-size:64px;color:#999;' }, 'person')
                                ]);
                            }

                            return m('div', {
                                style: 'background:#fafafa;border-radius:12px;padding:12px;box-shadow:0 1px 3px rgba(0,0,0,0.1);transition:box-shadow 0.2s;',
                                onmouseenter: function (e) { e.currentTarget.style.boxShadow = '0 4px 12px rgba(0,0,0,0.15)'; },
                                onmouseleave: function (e) { e.currentTarget.style.boxShadow = '0 1px 3px rgba(0,0,0,0.1)'; }
                            }, [
                                thumb,
                                m('div', { style: 'margin-top:8px;text-align:center;' }, [
                                    m('a', {
                                        href: '#!/view/' + memType + '/' + mem.objectId, target: '_blank',
                                        style: 'font-weight:500;color:#333;text-decoration:none;display:block;overflow:hidden;text-overflow:ellipsis;white-space:nowrap;'
                                    }, memName),
                                    m('span', { style: 'color:#999;font-size:0.75em;' }, memType.split('.').pop())
                                ])
                            ]);
                        })
                    )
            )
        ]);
    }

    Dialog.open({
        title: 'Membership Cloud',
        size: 'xl',
        content: {
            view: function () {
                return cloudMode ? renderCloudView() : renderDetailView();
            }
        },
        actions: [
            {
                label: 'Close', icon: 'close',
                onclick: function () { Dialog.close(); }
            }
        ]
    });
}

export { memberCloud };
export default memberCloud;
