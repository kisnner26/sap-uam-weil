const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const test = require('node:test');
const vm = require('node:vm');

function loadCatalog() {
  const source = fs.readFileSync(path.join(__dirname, 'test-catalog.js'), 'utf8');
  const context = { window: { SAP_UAM_PUZZLE: { 'weil-form-a': { 0: { w: 10, h: 20, patch: [1, 2, 3, 4] } } } } };
  vm.runInNewContext(source, context);
  return context.window.SAP_UAM_TESTS;
}

test('registra la definición pública sin datos de calificación', () => {
  const definition = loadCatalog().get('weil-form-a');
  assert.equal(definition.version, 1);
  assert.equal(definition.sections.length * definition.itemsPerSection, 60);
  assert.equal(definition.optionCount, 8);
  assert.equal(definition.scoring, undefined);
  assert.equal(definition.answerPattern, undefined);
  assert.equal(definition.baremos, undefined);
  assert.equal(definition.ciEquivalences, undefined);
});

test('expone solo geometría, nunca rutas públicas del material', () => {
  const media = loadCatalog().get('weil-form-a').media;
  assert.deepEqual(Array.from(media.puzzleGeometry(0).patch), [1, 2, 3, 4]);
  assert.equal(media.puzzleGeometry(1), null);
  assert.equal(media.itemImage, undefined);
  assert.equal(media.sectionImage, undefined);
});

test('rechaza identificadores de test no registrados', () => {
  assert.throws(() => loadCatalog().get('inexistente'), /Test no registrado/);
});

test('mantiene inmutable la definición publicada', () => {
  const definition = loadCatalog().get('weil-form-a');
  assert.equal(Object.isFrozen(definition), true);
  assert.equal(Object.isFrozen(definition.sections), true);
  assert.equal(Object.isFrozen(definition.media), true);
});

test('los archivos ejecutables del aspirante no contienen lógica de calificación', () => {
  const publicSource = ['test-catalog.js', 'app.js']
    .map(file => fs.readFileSync(path.join(__dirname, file), 'utf8'))
    .join('\n');
  assert.doesNotMatch(publicSource, /answerPattern|ciEquivalences|correctAnswer\s*\(|\.scoring\b/);
  assert.doesNotMatch(publicSource, /assets\/weill-(?:items|pages|puzzle)/);
});
