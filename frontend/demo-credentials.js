(function initDemoCredentials(global) {
  'use strict';

  // Solo protege las cuentas del modo demo (GitHub Pages, sin backend): evita
  // dejar contrasenas legibles en localStorage. No sustituye al backend real.
  const ITERATIONS = 100000;
  const encoder = new TextEncoder();

  function toHex(buffer) {
    return Array.from(new Uint8Array(buffer), byte => byte.toString(16).padStart(2, '0')).join('');
  }

  function randomSalt(cryptoApi) {
    return toHex(cryptoApi.getRandomValues(new Uint8Array(16)));
  }

  async function derive(cryptoApi, password, salt) {
    const key = await cryptoApi.subtle.importKey('raw', encoder.encode(String(password)), 'PBKDF2', false, ['deriveBits']);
    const bits = await cryptoApi.subtle.deriveBits(
      { name: 'PBKDF2', hash: 'SHA-256', salt: encoder.encode(salt), iterations: ITERATIONS }, key, 256);
    return toHex(bits);
  }

  function createVerifier(cryptoApi) {
    return Object.freeze({
      /** Devuelve los campos a guardar en lugar de la contrasena. */
      async seal(password) {
        const salt = randomSalt(cryptoApi);
        return { passwordSalt: salt, passwordHash: await derive(cryptoApi, password, salt) };
      },
      /**
       * Comprueba la contrasena. Si la cuenta es anterior y aun guarda la
       * contrasena en claro, la acepta una vez y devuelve `upgrade` con los
       * campos protegidos para que quien llama la migre.
       */
      async verify(user, password) {
        if (user.passwordHash && user.passwordSalt) {
          const candidate = await derive(cryptoApi, password, user.passwordSalt);
          return { ok: candidate === user.passwordHash, upgrade: null };
        }
        if (typeof user.password === 'string' && user.password === String(password)) {
          return { ok: true, upgrade: await this.seal(password) };
        }
        return { ok: false, upgrade: null };
      }
    });
  }

  global.SAP_UAM_DEMO_CREDENTIALS = Object.freeze({ createVerifier });
})(typeof window !== 'undefined' ? window : globalThis);
