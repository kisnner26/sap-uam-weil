const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const test = require('node:test');

const styles = fs.readFileSync(path.join(__dirname, 'styles.css'), 'utf8');
const theme = fs.readFileSync(path.join(__dirname, 'theme-v2.css'), 'utf8');
const app = fs.readFileSync(path.join(__dirname, 'app.js'), 'utf8');

test('los interruptores usan toda la fila como objetivo interactivo', () => {
  assert.match(styles, /\.setting-row--toggle\{[^}]*width:100%[^}]*min-height:64px[^}]*cursor:pointer/);
  assert.match(styles, /\.setting-row--toggle \.switch\{pointer-events:none\}/);
  assert.match(app, /role="switch" aria-checked="\$\{checked\}" data-action="toggle-setting"/);
  assert.match(app, /if \(action === 'toggle-setting'\)/);
});

test('las tablas del personal conservan desplazamiento horizontal', () => {
  assert.match(theme, /\.table-wrap\{overflow:auto/);
  assert.match(styles, /\.table-wrap\{overflow-x:auto\}/);
});

test('las imágenes principales muestran el contenido completo', () => {
  assert.match(theme, /\.hero-visual img\{[^}]*object-fit:contain/);
  assert.match(theme, /\.lamina-card img\{object-fit:contain/);
});
