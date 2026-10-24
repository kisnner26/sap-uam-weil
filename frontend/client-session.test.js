const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');

function loadModule(file) {
  const window = {};
  vm.runInNewContext(fs.readFileSync(`${__dirname}/${file}`, 'utf8'), { window });
  return window;
}

function memoryStorage(initial = {}) {
  const values = new Map(Object.entries(initial));
  return {
    getItem: key => values.get(key) ?? null,
    setItem: (key, value) => values.set(key, value),
    removeItem: key => values.delete(key),
    has: key => values.has(key)
  };
}

test('el estado publica solo a los observadores del evento', () => {
  const { SAP_UAM_STATE } = loadModule('platform-state.js');
  const store = SAP_UAM_STATE.create({ currentIndex: 0 });
  const received = [];
  store.subscribe('answer', event => received.push(event.detail.option));
  store.publish('navigation', { index: 1 });
  store.publish('answer', { option: 4 });
  assert.deepEqual(received, [4]);
});

test('el borrador guarda avance pero nunca el resultado ni el puntaje', () => {
  const { SAP_UAM_SESSION } = loadModule('client-session.js');
  const storage = memoryStorage();
  const drafts = SAP_UAM_SESSION.createDraftRepository(storage, context => `draft:${context.profileId}`);
  const context = { testId: 'weil-form-a', testVersion: 1, profileId: '123' };
  const saved = drafts.save(context, {
    answers: [2, null], currentIndex: 1, startedAt: '2026-01-01T00:00:00Z',
    focusLosses: 0, securityEvents: [], result: { score: 60 }, score: 60
  });
  assert.equal(saved.result, undefined);
  assert.equal(saved.score, undefined);
  assert.deepEqual(Array.from(drafts.load(context).answers), [2, null]);
});

test('los listados leen constancias sin resultados psicométricos', () => {
  const { SAP_UAM_SESSION } = loadModule('client-session.js');
  const storage = memoryStorage();
  const results = SAP_UAM_SESSION.createResultRepository(storage, 'results:index', 'results:detail:');
  const result = {
    id: 'r1', testId: 'weil-form-a', testVersion: 1, testName: 'Weil',
    profile: { cif: '123', fullName: 'Ana' }, total: 60, answeredCount: 60,
    endedAt: '2026-01-01'
  };
  results.save(result);
  const [summary] = results.list();
  assert.equal(summary.answers, undefined);
  assert.equal(summary.score, undefined);
  assert.equal(summary.percentile, undefined);
  assert.equal(results.get('r1').answeredCount, 60);
});
