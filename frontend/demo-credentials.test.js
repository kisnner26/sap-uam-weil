const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const { webcrypto } = require('node:crypto');

function loadVerifier() {
  const window = {};
  vm.runInNewContext(fs.readFileSync(`${__dirname}/demo-credentials.js`, 'utf8'),
    { window, TextEncoder, Uint8Array });
  return window.SAP_UAM_DEMO_CREDENTIALS.createVerifier(webcrypto);
}

test('la contraseña se guarda protegida, nunca en claro', async () => {
  const verifier = loadVerifier();
  const sealed = await verifier.seal('clave-segura-1');
  assert.match(sealed.passwordHash, /^[0-9a-f]{64}$/);
  assert.ok(!JSON.stringify(sealed).includes('clave-segura-1'));
});

test('la misma contraseña da hashes distintos por cuenta (sal propia)', async () => {
  const verifier = loadVerifier();
  const first = await verifier.seal('clave-segura-1');
  const second = await verifier.seal('clave-segura-1');
  assert.notEqual(first.passwordSalt, second.passwordSalt);
  assert.notEqual(first.passwordHash, second.passwordHash);
});

test('acepta la contraseña correcta y rechaza la incorrecta', async () => {
  const verifier = loadVerifier();
  const user = { ...(await verifier.seal('clave-segura-1')) };
  assert.equal((await verifier.verify(user, 'clave-segura-1')).ok, true);
  assert.equal((await verifier.verify(user, 'otra-clave')).ok, false);
});

test('una cuenta anterior en claro entra una vez y se migra a hash', async () => {
  const verifier = loadVerifier();
  const legacy = { email: 'a@b.c', password: 'vieja-clave-1' };
  const wrong = await verifier.verify(legacy, 'no-es');
  assert.equal(wrong.ok, false);

  const result = await verifier.verify(legacy, 'vieja-clave-1');
  assert.equal(result.ok, true);
  assert.ok(result.upgrade.passwordHash && result.upgrade.passwordSalt);
  const migrated = { email: legacy.email, ...result.upgrade };
  assert.equal((await verifier.verify(migrated, 'vieja-clave-1')).ok, true);
});
