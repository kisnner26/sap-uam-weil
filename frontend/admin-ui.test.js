const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');

const app = fs.readFileSync(path.join(__dirname, 'app.js'), 'utf8');
const html = fs.readFileSync(path.join(__dirname, 'index.html'), 'utf8');

test('el acceso reforzado exige un codigo de seis digitos y conserva nombres accesibles', () => {
  assert.match(html, /id="form-mfa"/);
  assert.match(html, /autocomplete="one-time-code"/);
  assert.match(html, /aria-describedby="mfa-code-msg"/);
  assert.match(app, /\/api\/auth\/mfa\/verify/);
});

test('la administracion usa la api para personal y auditoria', () => {
  assert.match(app, /\/api\/admin\/users/);
  assert.match(app, /\/api\/admin\/audit/);
  assert.doesNotMatch(app, /UPDATE\s+users/i);
  assert.match(app, /appState\.sessionUser\?\.role === 'ADMIN'/);
});
