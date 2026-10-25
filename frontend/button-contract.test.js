const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const test = require('node:test');

const sources = ['index.html', 'app.js', 'admission-ui.js']
  .map(file => fs.readFileSync(path.join(__dirname, file), 'utf8'));
const app = sources[1];
const source = sources.join('\n');

function uniqueMatches(pattern, group = 1) {
  return [...new Set([...source.matchAll(pattern)].map(match => match[group]))].sort();
}

test('cada botón declara una acción, ruta, selección o envío', () => {
  const buttons = [...source.matchAll(/<button\b[^>]*>/g)].map(match => match[0]);
  const orphaned = buttons.filter(button => ![
    /data-action=/,
    /data-route=/,
    /data-option=/,
    /data-item=/,
    /data-page=/,
    /type="submit"/,
    /id="toggle-pass-u"/
  ].some(pattern => pattern.test(button)));
  assert.deepEqual(orphaned, []);
});

test('cada data-action renderizada tiene un manejador', () => {
  const rendered = uniqueMatches(/data-action="([a-z-]+)"/g);
  const handled = [...new Set([...app.matchAll(/action === ['"]([a-z-]+)['"]/g)].map(match => match[1]))];
  assert.deepEqual(rendered.filter(action => !handled.includes(action)), []);
});

test('cada formulario renderizado tiene un manejador de envío', () => {
  const forms = uniqueMatches(/<form[^>]+id="([a-z-]+)"/g);
  const mapped = [...new Set([...app.matchAll(/['"](form-[a-z-]+)['"]:\s*/g)].map(match => match[1]))];
  const direct = [...new Set([...app.matchAll(/\$\(['"]#(form-[a-z-]+)['"]\)\?\.addEventListener\(['"]submit['"]/g)].map(match => match[1]))];
  assert.deepEqual(forms.filter(form => !mapped.includes(form) && !direct.includes(form)), []);
});

test('los diálogos de personal bloquean el fondo y administran el foco', () => {
  assert.match(app, /staffDialogReturnFocus = document\.activeElement/);
  assert.match(app, /\$\('#view-app'\)\.inert = true/);
  assert.match(app, /keepFocusInsideDialog\(event, staffDialog, closeStaffDialog\)/);
});
