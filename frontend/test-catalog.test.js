const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const test = require('node:test');
const vm = require('node:vm');

function loadCatalog() {
  const source = fs.readFileSync(path.join(__dirname, 'test-catalog.js'), 'utf8');
  const context = { window: {} };
  vm.runInNewContext(source, context);
  return context.window.SAP_UAM_TESTS;
}

test('registra weil como definición versionada y completa', () => {
  const catalog = loadCatalog();
  const definition = catalog.get('weil-form-a');

  assert.equal(catalog.defaultTestId, 'weil-form-a');
  assert.equal(definition.version, 1);
  assert.equal(definition.sections.length * definition.itemsPerSection, 60);
  assert.equal(definition.optionCount, 8);
  assert.equal(definition.media.itemImage(12), 'assets/weill-items/lamina-2-item-01.png?v=2');
});

test('conserva la regla de puntaje por lámina y total', () => {
  const definition = loadCatalog().get('weil-form-a');
  const perfectAnswers = Array.from({ length: 60 }, (_, index) => definition.scoring.correctAnswer(index));
  const perfect = definition.scoring.score(perfectAnswers);

  assert.equal(perfect.total, 60);
  assert.deepEqual(Array.from(perfect.pageScores), [12, 12, 12, 12, 12]);

  perfectAnswers[0] = 1;
  const oneError = definition.scoring.score(perfectAnswers);
  assert.equal(oneError.total, 59);
  assert.deepEqual(Array.from(oneError.pageScores), [11, 12, 12, 12, 12]);
});

test('mantiene la selección de baremo y clasificación existentes', () => {
  const scoring = loadCatalog().get('weil-form-a').scoring;

  assert.equal(scoring.resolveNormColumn({ grupo: '7-16', edad: 12 }), '12');
  assert.equal(scoring.resolveNormColumn({ grupo: 'AD', edad: 25 }), 'AD');
  assert.equal(scoring.resolveNormColumn({ grupo: 'UN', edad: 18 }), 'UN');
  assert.equal(scoring.percentile(57, 'UN'), 90);
  assert.equal(scoring.ci(90), 119);
  assert.equal(scoring.classify(119), 'Buena inteligencia');
});

test('rechaza identificadores de test no registrados', () => {
  const catalog = loadCatalog();
  assert.throws(() => catalog.get('inexistente'), /Test no registrado/);
});
