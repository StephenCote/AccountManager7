import m from 'mithril';
import { am7model } from '../core/model.js';
import { am7client } from '../core/am7client.js';
import { page } from '../core/pageClient.js';
import { Dialog } from '../components/dialogCore.js';

/**
 * Summarize workflow — opens dialog to select chat/prompt configs,
 * then calls /vector/summarize endpoint to create a summary note.
 */

async function loadChatList() {
    let grp = await page.findObject('auth.group', 'data', '~/Chat');
    if (!grp) return [];
    let q = am7client.newQuery('olio.llm.chatConfig');
    q.field('groupId', grp.id);
    q.range(0, 50);
    let qr = await page.search(q);
    if (qr && qr.results) {
        am7model.updateListModel(qr.results);
        return qr.results;
    }
    return [];
}

async function loadPromptList() {
    let grp = await page.findObject('auth.group', 'data', '~/Chat');
    if (!grp) return [];
    let q = am7client.newQuery('olio.llm.promptConfig');
    q.field('groupId', grp.id);
    q.range(0, 50);
    let qr = await page.search(q);
    if (qr && qr.results) {
        am7model.updateListModel(qr.results);
        return qr.results;
    }
    return [];
}

async function summarize(entity, inst) {
    let settingsEntity = am7model.newPrimitive('summarizeSettings');
    let acfg = await loadChatList();
    if (acfg.length) settingsEntity.chat = acfg[0].name;
    let chatField = am7model.getModelField('summarizeSettings', 'chat');
    if (chatField) chatField.limit = acfg.map(c => c.name);

    let pcfg = await loadPromptList();
    if (pcfg.length) settingsEntity.prompt = pcfg[0].name;
    let promptField = am7model.getModelField('summarizeSettings', 'prompt');
    if (promptField) promptField.limit = pcfg.map(c => c.name);

    let selectedChat = settingsEntity.chat || '';
    let selectedPrompt = settingsEntity.prompt || '';
    let chunkType = settingsEntity.chunkType || 'PARAGRAPH';
    let chunk = settingsEntity.chunk || '500';

    function renderContent() {
        return m('div', { class: 'p-4 space-y-4' }, [
            m('div', [
                m('label', { class: 'field-label' }, 'Chat Config'),
                m('select', {
                    class: 'select-field-full',
                    value: selectedChat,
                    onchange: function (e) { selectedChat = e.target.value; }
                }, acfg.map(c => m('option', { value: c.name }, c.name)))
            ]),
            m('div', [
                m('label', { class: 'field-label' }, 'Prompt Config'),
                m('select', {
                    class: 'select-field-full',
                    value: selectedPrompt,
                    onchange: function (e) { selectedPrompt = e.target.value; }
                }, pcfg.map(c => m('option', { value: c.name }, c.name)))
            ]),
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
        title: 'Summarize Options',
        size: 'md',
        content: { view: renderContent },
        actions: [
            {
                label: 'Cancel', icon: 'cancel',
                onclick: function () { Dialog.close(); }
            },
            {
                label: 'Summarize', icon: 'check', primary: true,
                onclick: async function () {
                    let ycfg = pcfg.filter(a => a.name.toLowerCase() === selectedPrompt.toLowerCase());
                    if (!ycfg.length) {
                        page.toast('error', 'Invalid prompt selection: ' + selectedPrompt);
                        return;
                    }
                    let vcfg = acfg.filter(a => a.name.toLowerCase() === selectedChat.toLowerCase());
                    if (!vcfg.length) {
                        page.toast('error', 'Invalid chat selection: ' + selectedChat);
                        return;
                    }
                    Dialog.close();
                    page.toast('info', 'Summarizing...', -1);
                    let creq = am7model.newPrimitive('olio.llm.chatRequest');
                    creq.chatConfig = { name: vcfg[0].name, id: vcfg[0].id };
                    creq.promptConfig = { name: ycfg[0].name, id: ycfg[0].id };
                    creq.data = [
                        JSON.stringify({ schema: inst.model.name, objectId: inst.api.objectId() })
                    ];
                    try {
                        let x = await m.request({
                            method: 'POST',
                            url: am7client.base() + '/vector/summarize/' + chunkType.toUpperCase() + '/' + chunk,
                            body: creq,
                            withCredentials: true
                        });
                        page.clearToast();
                        if (x) {
                            page.toast('success', 'Summarization complete — summary note created in ~/Notes');
                        } else {
                            page.toast('error', 'Summarization failed — no result returned');
                        }
                    } catch (e) {
                        page.clearToast();
                        page.toast('error', 'Summarization failed: ' + (e.message || e));
                    }
                }
            }
        ]
    });
}

export { summarize };
export default summarize;
