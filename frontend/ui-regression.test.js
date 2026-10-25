const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const test = require('node:test');

const styles = fs.readFileSync(path.join(__dirname, 'styles.css'), 'utf8');
const theme = fs.readFileSync(path.join(__dirname, 'theme-v2.css'), 'utf8');
const app = fs.readFileSync(path.join(__dirname, 'app.js'), 'utf8');
const admission = fs.readFileSync(path.join(__dirname, 'admission-ui.js'), 'utf8');

test('los interruptores usan toda la fila como objetivo interactivo', () => {
  assert.match(styles, /\.setting-row--toggle\{[^}]*width:100%[^}]*min-height:64px[^}]*cursor:pointer/);
  assert.match(styles, /\.setting-row--toggle \.switch\{pointer-events:none\}/);
  assert.match(app, /role="switch" aria-label="\$\{esc\(title\)\}: \$\{status\.toLowerCase\(\)\}" aria-checked="\$\{checked\}" data-action="toggle-setting"/);
  assert.match(app, /if \(action === 'toggle-setting'\)/);
});

test('las preferencias anuncian su estado y el cambio aplicado', () => {
  assert.match(app, /aria-label="\$\{esc\(title\)\}: \$\{status\.toLowerCase\(\)\}"/);
  assert.match(app, /class="setting-state" aria-hidden="true">\$\{status\}/);
  assert.match(app, /toast\(`\$\{title\}: \$\{appState\.settings\[key\] \? 'activado' : 'desactivado'\}\.`/);
});

test('perfil y entrega pendiente explican el siguiente paso sin dejar datos vacíos', () => {
  assert.match(app, /const career = normalizeCareer\(profile\.carrera\) \|\| 'Sin registrar';/);
  assert.match(app, /class="empty-state empty-state--action"/);
  assert.match(app, /Todavía no hay una entrega registrada/);
});

test('el acceso conserva feedback visible cuando falla una autenticación', () => {
  assert.match(app, /setFieldState\('#wrap-unified-pass', '#unified-pass-msg', 'err', message\)/);
  assert.match(app, /\$\('#unified-pass'\)\?\.focus\(\)/);
  assert.match(app, /label\.textContent = appState\.manualRegistering \? 'Crear cuenta' : 'Ingresar';/);
});

test('las tablas del personal conservan desplazamiento horizontal', () => {
  assert.match(theme, /\.table-wrap\{overflow:auto/);
  assert.match(styles, /\.table-wrap\{overflow-x:auto\}/);
});

test('las tablas tienen título y encabezados identificables', () => {
  assert.match(app, /<caption class="sr-only">Cuentas de personal autorizado<\/caption>/);
  assert.match(app, /<caption class="sr-only">Resultados de aspirantes<\/caption>/);
  assert.match(admission, /<caption class="sr-only">Convocatorias de evaluación<\/caption>/);
  assert.match(admission, /<th scope="col"><span class="sr-only">Acciones<\/span><\/th>/);
});

test('las imágenes principales muestran el contenido completo', () => {
  assert.match(theme, /\.hero-visual img\{[^}]*object-fit:contain/);
  assert.match(theme, /\.lamina-card img\{object-fit:contain/);
});

test('la interfaz de instrumentación conserva identidad y movimiento seguro', () => {
  assert.match(theme, /--future-cyan:#0d9da8/);
  assert.match(theme, /SAP-UAM \/ INTERFAZ DE EVALUACIÓN \/ NODO 01/);
  assert.match(theme, /transition:transform 150ms ease-out,opacity 150ms ease-out/);
  assert.match(theme, /@media\(prefers-reduced-motion:reduce\)/);
});

test('los controles mantienen foco, presión y estados deshabilitados claros', () => {
  assert.match(theme, /\.setting-row--toggle:focus-visible/);
  assert.match(theme, /\.solid-btn:active:not\(:disabled\)/);
  assert.match(theme, /button:disabled[^}]*cursor:not-allowed/);
});
