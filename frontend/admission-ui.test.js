const test = require('node:test');
const assert = require('node:assert/strict');
const ui = require('./admission-ui.js');

test('parseApplicants acepta correo, nombre y minutos extra, y reporta lineas invalidas', () => {
  const { applicants, invalid } = ui.parseApplicants('Ana@Ejemplo.com, Ana Perez\n\nluis@ejemplo.com; Luis Mora; +15\nmalo@\nsolo@ejemplo.com,+30');
  assert.deepEqual(applicants, [
    { email: 'ana@ejemplo.com', fullName: 'Ana Perez' },
    { email: 'luis@ejemplo.com', extraMinutes: 15, fullName: 'Luis Mora' },
    { email: 'solo@ejemplo.com', extraMinutes: 30 }
  ]);
  assert.deepEqual(invalid, ['malo@']);
});

test('el inicio del aspirante muestra la accion que corresponde a cada estado', () => {
  const open = { state: 'OPEN', sessionName: 'Julio', minutes: 60, needsConsent: false, opensAt: '2026-07-01T14:00:00Z', closesAt: '2026-07-01T20:00:00Z' };
  assert.match(ui.aspirantHome({ state: 'NO_ENROLLMENT' }).action, /form-claim/);
  assert.match(ui.aspirantHome({ ...open, needsConsent: true }).action, /open-consent/);
  assert.match(ui.aspirantHome(open).action, /start-test/);
  assert.match(ui.aspirantHome({ ...open, state: 'IN_PROGRESS' }).action, /Continuar/);
  assert.equal(ui.aspirantHome({ ...open, state: 'UPCOMING' }).action, '');
  assert.equal(ui.aspirantHome({ ...open, state: 'CLOSED' }).action, '');
  assert.match(ui.aspirantHome(null, { error: 'sin red' }).action, /reload-availability/);
});

test('el aspirante invalidado no ve el motivo ni puntajes', () => {
  const home = ui.aspirantHome({ state: 'INVALIDATED', invalidReason: 'copia detectada', sessionName: 'Julio' });
  const text = JSON.stringify(home);
  assert.doesNotMatch(text, /copia detectada/);
  assert.doesNotMatch(text, /percentil|puntaje/i);
  assert.doesNotMatch(text, /\bCI\b/);
});

test('el aviso de privacidad se escapa antes de darle formato', () => {
  const html = ui.noticeHtml('## Titulo <img src=x onerror=alert(1)>\n\n- **uno** <script>x</script>\n- dos');
  assert.doesNotMatch(html, /<img|<script/);
  assert.match(html, /<h4>Titulo/);
  assert.match(html, /<li><strong>uno<\/strong>/);
});

test('las tablas del personal escapan datos de los aspirantes', () => {
  const row = { id: 1, fullName: '<b>Eva</b>', email: 'e@x.com', sessionName: 'S', directScore: 30, percentile: 50, ci: 100, classification: 'Medio', securityEvents: [], invalidated: false, reviewed: false };
  const html = ui.attemptsTable([row], 60);
  assert.doesNotMatch(html, /<b>Eva/);
  assert.match(html, /data-action="invalidate-attempt"/);
  const bad = ui.attemptsTable([{ ...row, invalidated: true, invalidReason: 'x', reviewed: false }], 60);
  assert.match(bad, /reapply-attempt/);
  assert.doesNotMatch(bad, /invalidate-attempt/);
});

test('las fechas del formulario viajan como instantes UTC y vuelven iguales', () => {
  const iso = '2026-07-01T14:30:00.000Z';
  assert.equal(ui.fromLocalInput(ui.toLocalInput(iso)), iso);
  assert.equal(ui.fromLocalInput('no es fecha'), null);
});
