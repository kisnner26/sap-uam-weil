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

test('la vista previa no presenta el material protegido como una acción disponible', () => {
  assert.match(app, /const isPublicPreview = STATIC_PAGES_MODE;/);
  assert.match(app, /isPublicPreview \? '<div class="demo-restriction" role="status"><span>vista previa · material protegido<\/span><p>iniciá sesión con una convocatoria activa para rendir.<\/p><\/div>'/);
  assert.match(app, /isPublicPreview \? 'Acceso institucional requerido'/);
  assert.match(theme, /\.demo-restriction\{display:grid/);
});

test('el acceso conserva feedback visible cuando falla una autenticación', () => {
  assert.match(app, /let failureMessage = '';/);
  assert.match(app, /setFieldState\('#wrap-unified-pass', '#unified-pass-msg', 'err', failureMessage\)/);
  assert.match(app, /\$\('#unified-pass'\)\?\.focus\(\)/);
  assert.match(app, /label\.textContent = appState\.manualRegistering \? 'Crear cuenta' : 'Ingresar';/);
});

test('el acceso publica su estado de carga', () => {
  assert.match(app, /\$\('#form-unified'\)\?\.setAttribute\('aria-busy', String\(isBusy\)\)/);
});

test('la verificación multifactor también comunica cuando está procesando', () => {
  assert.match(app, /function setMfaBusy\(isBusy\)/);
  assert.match(app, /\$\('#form-mfa'\)\?\.setAttribute\('aria-busy', String\(isBusy\)\)/);
  assert.match(app, /button\.textContent = isBusy \? 'Verificando\.\.\.' : 'Verificar e ingresar';/);
});

test('el consentimiento bloquea duplicados y anuncia su guardado', () => {
  assert.match(admission, /id="consent-panel" aria-busy="\$\{busy \? 'true' : 'false'\}"/);
  assert.match(app, /panel\?\.setAttribute\('aria-busy', 'true'\)/);
  assert.match(app, /acceptButton\.textContent = 'Registrando consentimiento\.\.\.';/);
  assert.match(app, /if \(checkbox\?\.isConnected\) checkbox\.disabled = false;/);
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

test('las ayudas de carga evitan animación de pintura continua', () => {
  assert.match(theme, /\.question-figure\.is-loading::after\{[^}]*animation:soft-pulse 900ms ease-in-out infinite alternate/);
  assert.match(theme, /\.puzzle-slot:not\(\.is-filled\)\{animation:none/);
  assert.match(theme, /\.question-figure\.is-loading::after\{animation:none\}/);
});

test('la navegación anuncia la vista actual', () => {
  assert.match(app, /const isActive = appState\.route === route;/);
  assert.match(app, /isActive \? 'aria-current="page"' : ''/);
});

test('la aplicación permite saltar la navegación hacia el contenido actualizado', () => {
  const index = fs.readFileSync(path.join(__dirname, 'index.html'), 'utf8');
  assert.match(index, /class="skip-link" href="#app-content">Saltar a contenido principal/);
  assert.match(index, /<main class="app-content" id="app-content" tabindex="-1"><\/main>/);
  assert.match(theme, /\.skip-link:focus-visible\{transform:translateY\(0\)/);
});

test('la navegación no fuerza desplazamiento animado si el sistema pide menos movimiento', () => {
  assert.match(app, /function prefersReducedMotion\(\) \{\s+return window\.matchMedia\?\.\('\(prefers-reduced-motion: reduce\)'\)\.matches === true;/);
  assert.match(app, /appState\.settings\.smooth && !prefersReducedMotion\(\) \? 'smooth' : 'auto'/);
});

test('reiniciar respuestas usa confirmación interna y conserva el contexto del intento', () => {
  assert.match(app, /function requestResetTest\(\)/);
  assert.match(app, /data-action="confirm-reset-test"/);
  assert.match(app, /if \(action === 'reset-test'\) requestResetTest\(\);/);
  assert.match(app, /if \(action === 'confirm-reset-test'\) \{\s+closeSubmitDialog\(\);\s+resetTest\(\);\s+requestAnimationFrame\(\(\) => \$\('\[data-action="reset-test"\]'\)\?\.focus\(\)\);/);
  assert.doesNotMatch(app, /function resetTest\(\) \{\s+if \(\!window\.confirm/);
});

test('el body no cuenta como destino de navegación en el manejador de clics', () => {
  const app = fs.readFileSync(path.join(__dirname, 'app.js'), 'utf8');
  // body[data-route] existe solo para el CSS; si el manejador lo tomara como ruta, cualquier clic repintaría la vista.
  assert.match(app, /routeCandidate !== document\.body/);
});
