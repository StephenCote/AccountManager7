import m from 'mithril';
import { am7model } from '../core/model.js';
import { am7client } from '../core/am7client.js';
import { page } from '../core/pageClient.js';
import { Dialog } from '../components/dialogCore.js';

/**
 * Vectorize workflow — opens dialog to select chunking options,
 * then calls /vector/vectorize endpoint to create embeddings.
 */

async function vectorize(entity, inst) {
    let chunkType = 'PARAGRAPH';
    let chunk = '500';

    function renderContent() {
        return m('div', { class: 'p-4 space-y-4' }, [
            m('div', [
                m('label', { class: 'field-label' }, 'Chunk Type'),
                m('select', {
                    class: 'select-field-full',
                    value: chunkType,
                    onchange: function (e) { chunkType = e.target.value; }
                }, ['PARAGRAPH', 'SENTENCE', 'TOKEN', 'PAGE'].map(v =>
                    m('option', { value: v }, v)
                ))
            ]),
            m('div', [
                m('label', { class: 'field-label' }, 'Chunk Size'),
                m('input', {
                    class: 'text-field-compact',
                    type: 'number',
                    value: chunk,
                    oninput: function (e) { chunk = e.target.value; }
                })
            ])
        ]);
    }

    Dialog.open({
        title: 'Vector Options',
        size: 'sm',
        content: { view: renderContent },
        actions: [
            {
                label: 'Cancel', icon: 'cancel',
                onclick: function () { Dialog.close(); }
            },
            {
                label: 'Vectorize', icon: 'check', primary: true,
                onclick: async function () {
                    Dialog.close();
                    page.toast('info', 'Vectorizing — chunking and embedding content...', -1);
                    try {
                        let x = await m.request({
                            method: 'GET',
                            url: am7client.base() + '/vector/vectorize/' + inst.model.name + '/' + inst.api.objectId() + '/' + chunkType.toUpperCase() + '/' + chunk,
                            withCredentials: true
                        });
                        page.clearToast();
                        if (x) {
                            page.toast('success', 'Vectorization complete');
                        } else {
                            page.toast('error', 'Vectorization failed — no chunks created');
                        }
                    } catch (e) {
                        page.clearToast();
                        page.toast('error', 'Vectorization failed: ' + (e.message || e));
                    }
                }
            }
        ]
    });
}

export { vectorize };
export default vectorize;
