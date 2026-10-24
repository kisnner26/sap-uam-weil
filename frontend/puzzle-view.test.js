const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');

function load(file, globals = {}) {
  const window = {};
  vm.runInNewContext(fs.readFileSync(`${__dirname}/${file}`, 'utf8'), { window, Math, Array, Object, Number, String, ...globals });
  return window;
}

const { SAP_UAM_PUZZLE_VIEW: view } = load('puzzle-view.js');

test('el hueco se ubica en porcentajes de la figura', () => {
  const style = view.slotStyle({ width: 700, height: 500, patch: [350, 100, 175, 125] });
  assert.equal(style, 'left:50%;top:20%;width:25%;height:25%');
});

test('soltar dentro del hueco o a un margen razonable cuenta como acierto de punteria', () => {
  const rect = { left: 100, right: 200, top: 100, bottom: 160 };
  assert.equal(view.hitTest(rect, { x: 150, y: 130 }), true);
  assert.equal(view.hitTest(rect, { x: 205, y: 95 }), true);
  assert.equal(view.hitTest(rect, { x: 260, y: 130 }), false);
  assert.equal(view.hitTest(rect, { x: 150, y: 400 }), false);
});

test('un toque corto no se confunde con un arrastre', () => {
  assert.ok(view.dragDistance({ x: 10, y: 10 }, { x: 12, y: 11 }) < view.DRAG_THRESHOLD);
  assert.ok(view.dragDistance({ x: 10, y: 10 }, { x: 40, y: 50 }) > view.DRAG_THRESHOLD);
});

test('cada item tiene geometría y 8 piezas protegidas en el servidor', () => {
  const { SAP_UAM_PUZZLE } = load('puzzle-data.js');
  const items = SAP_UAM_PUZZLE['weil-form-a'];
  assert.equal(items.length, 60);
  items.forEach((entry, index) => {
    assert.ok(entry, `item ${index + 1} sin datos`);
    const [x, y, w, h] = entry.patch;
    assert.ok(x >= 0 && y >= 0 && x + w <= entry.w && y + h <= entry.h, `hueco fuera de la figura en el item ${index + 1}`);
    assert.ok(w > 60 && h > 40, `hueco demasiado pequeno en el item ${index + 1}`);
    const stem = `${__dirname}/../backend/src/main/resources/test-material/weil-form-a/puzzle/l${Math.floor(index / 12) + 1}i${String((index % 12) + 1).padStart(2, '0')}`;
    assert.ok(fs.existsSync(`${stem}-base.png`), `falta la base del item ${index + 1}`);
    for (let n = 1; n <= 8; n += 1) assert.ok(fs.existsSync(`${stem}-p${n}.png`), `falta la pieza ${n} del item ${index + 1}`);
  });
});

test('el catalogo expone geometría pero nunca rutas públicas del material', () => {
  const data = load('puzzle-data.js');
  const sandbox = { SAP_UAM_PUZZLE: data.SAP_UAM_PUZZLE };
  const window = sandbox;
  vm.runInNewContext(fs.readFileSync(`${__dirname}/test-catalog.js`, 'utf8'), { window });
  const weil = window.SAP_UAM_TESTS.get('weil-form-a');
  const puzzle = weil.media.puzzleGeometry(13);
  assert.equal(typeof puzzle.width, 'number');
  assert.equal(Array.isArray(puzzle.patch), true);
  assert.equal(puzzle.base, undefined);
  assert.equal(puzzle.pieces, undefined);
  assert.equal(weil.media.puzzleGeometry(999), null);
});
