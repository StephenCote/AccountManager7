import { describe, it, expect, beforeAll } from 'vitest';
import { am7model } from '../core/model.js';
import { forms } from '../core/formDef.js';
import { characters } from '../cardGame/services/characters.js';

// Custom race + optional label. `race` is a list<string> of RaceEnumType constant
// names; "O" (Custom) is named by the free-text `raceLabel`, which substitutes ONLY
// the O element. A label on a record with no O entry is ignored, and an O with no
// label is dropped (never shown as a bare "O", never replaced with an invented word)
// so the next element, or the card's HUMAN default, shows instead.

beforeAll(() => {
    am7model._view = { path: () => '', pathForType: () => '', formField: () => null };
    am7model._page = { user: null, context: () => ({ roles: {} }) };
    am7model._client = { newQuery: () => ({ entity: { request: [] }, field: () => {} }) };
});

describe('characters.raceDisplay', () => {
    it('substitutes the label for the O element only, keeping other elements', () => {
        // str() keeps first-element semantics for the card: Z ("Fairy") is first.
        expect(characters.raceDisplay({ race: ['Z', 'O'], raceLabel: 'Fae' })).toBe('Z');
        // With O first, the label is the display value.
        expect(characters.raceDisplay({ race: ['O', 'Z'], raceLabel: 'Fae' })).toBe('Fae');
        expect(characters.raceDisplay({ race: ['O'], raceLabel: 'Fae' })).toBe('Fae');
    });

    it('trims the label', () => {
        expect(characters.raceDisplay({ race: ['O'], raceLabel: '  Fae ' })).toBe('Fae');
    });

    it('drops an unlabeled O instead of showing the bare letter', () => {
        expect(characters.raceDisplay({ race: ['O'] })).toBe('');
        expect(characters.raceDisplay({ race: ['O'], raceLabel: '' })).toBe('');
        expect(characters.raceDisplay({ race: ['O'], raceLabel: '   ' })).toBe('');
        expect(characters.raceDisplay({ race: ['O'], raceLabel: null })).toBe('');
        // The next real element takes over when the unlabeled O is first.
        expect(characters.raceDisplay({ race: ['O', 'Z'] })).toBe('Z');
        expect(characters.raceDisplay({ race: ['o', 'Z'], raceLabel: ' ' })).toBe('Z');
    });

    it('ignores a label when the race list has no O entry', () => {
        expect(characters.raceDisplay({ race: ['E'], raceLabel: 'Fae' })).toBe('E');
        expect(characters.raceDisplay({ race: ['E', 'Z'], raceLabel: 'Fae' })).toBe('E');
    });

    it('matches O case-insensitively and tolerates a scalar race', () => {
        expect(characters.raceDisplay({ race: ['o'], raceLabel: 'Fae' })).toBe('Fae');
        expect(characters.raceDisplay({ race: 'O', raceLabel: 'Fae' })).toBe('Fae');
        expect(characters.raceDisplay({ race: 'E', raceLabel: 'Fae' })).toBe('E');
    });

    it('is empty for a missing character or race', () => {
        expect(characters.raceDisplay(null)).toBe('');
        expect(characters.raceDisplay({})).toBe('');
        expect(characters.raceDisplay({ race: [] , raceLabel: 'Fae' })).toBe('');
    });
});

describe('characters.assembleCharacterCard race', () => {
    it('renders the custom label upper-cased on the card', () => {
        let card = characters.assembleCharacterCard({ name: 'A B', race: ['O'], raceLabel: 'Fae' });
        expect(card.race).toBe('FAE');
        expect(card.name).toBe('A B');
    });

    it('never renders the word Custom or a bare O, and defaults to HUMAN with no usable race', () => {
        let card = characters.assembleCharacterCard({ name: 'A B', race: ['O'] });
        expect(card.race).toBe('HUMAN');
        expect(card.race.toLowerCase()).not.toContain('custom');
        expect(characters.assembleCharacterCard({ name: 'A B', race: ['O', 'Z'] }).race).toBe('Z');
        expect(characters.assembleCharacterCard({ name: 'A B' }).race).toBe('HUMAN');
        expect(characters.assembleCharacterCard({ name: 'A B', race: ['E'], raceLabel: 'Fae' }).race).toBe('E');
    });
});

describe('raceLabel model + form definition', () => {
    it('resolves olio.charPerson.raceLabel through inheritance with maxLength 64', () => {
        let f = am7model.getModelField('olio.charPerson', 'raceLabel');
        expect(f).toBeTruthy();
        expect(f.type).toBe('string');
        expect(f.maxLength).toBe(64);
        expect(f.shortName).toBe('racl');
        // Declared on identity.person, inherited by charPerson.
        let pf = am7model.getModelField('identity.person', 'raceLabel');
        expect(pf).toBeTruthy();
        expect(pf.maxLength).toBe(64);
    });

    it('newPrimitive(olio.charPerson) carries the field alongside race', () => {
        let ent = am7model.newPrimitive('olio.charPerson');
        expect('raceLabel' in ent).toBe(true);
        expect('race' in ent).toBe(true);
    });

    it('is on the charPerson form', () => {
        expect(forms.charPerson).toBeTruthy();
        expect(forms.charPerson.fields.raceLabel).toBeTruthy();
        expect(forms.charPerson.fields.raceLabel.label).toBe('Custom Race Label');
    });

    it('the Custom constant O is a raceEnumType value', () => {
        expect(am7model.enums.raceEnumType).toContain('O');
        let race = am7model.getModelField('olio.charPerson', 'race');
        expect(race).toBeTruthy();
        expect(race.type).toBe('list');
    });
});
