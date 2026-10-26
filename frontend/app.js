const $ = (selector, scope = document) => scope.querySelector(selector);
const $$ = (selector, scope = document) => Array.from(scope.querySelectorAll(selector));

const STORAGE = {
  settings: 'sapUam.settings.v1',
  legacyResultIndex: 'sapUam.results.v1',
  resultIndex: 'sapUam.results.index.v2',
  resultDetailPrefix: 'sapUam.results.detail.v2:',
  draftPrefix: 'sapUam.draft.v1:',
  profileEditsPrefix: 'sapUam.profile.v1:',
  staticUsers: 'sapUam.demo.users.v1',
  staticSession: 'sapUam.demo.session.v1',
  sessionUser: 'sapUam.session.user',
  attemptLeasePrefix: 'sapUam.attempt.lease.v1:',
  legacy: {
    settings: 'sapWeillSettingsV2',
    results: 'sapWeillResultsV2',
    draftPrefix: 'sapWeillDraftV2:',
    profileEditsPrefix: 'sapWeillProfileEditsV1:',
    staticUsers: 'sapWeillStaticUsersV1',
    staticSession: 'sapWeillStaticSessionV1',
    sessionUser: 'sapWeillSessionUser'
  }
};

const API_BASE = (() => {
  if (window.SAP_UAM_API_BASE) return String(window.SAP_UAM_API_BASE).replace(/\/$/, '');
  const apiPort = '8080';
  const isLocal = ['localhost', '127.0.0.1', '::1', ''].includes(location.hostname);
  if (location.protocol === 'http:' || location.protocol === 'https:') {
    if (location.port === apiPort) return '';
    return isLocal ? `${location.protocol}//${location.hostname}:${apiPort}` : '';
  }
  return `http://localhost:${apiPort}`;
})();
const STATIC_PAGES_MODE = ((/\.github\.io$/i.test(location.hostname) || new URLSearchParams(location.search).has('demo')) && !window.SAP_UAM_API_BASE);
const QA_SCENARIO = (() => {
  const scenario = new URLSearchParams(location.search).get('qa');
  const isLocal = ['localhost', '127.0.0.1', '::1'].includes(location.hostname);
  return isLocal && !STATIC_PAGES_MODE && ['aspirante', 'staff', 'admin'].includes(scenario) ? scenario : null;
})();

const ACTIVE_TEST = window.SAP_UAM_TESTS.get(window.SAP_UAM_TEST_ID || window.SAP_UAM_TESTS.defaultTestId);
const TOTAL_PAGES = ACTIVE_TEST.sections.length;
const ITEMS_PER_PAGE = ACTIVE_TEST.itemsPerSection;
const TOTAL_ITEMS = TOTAL_PAGES * ITEMS_PER_PAGE;
const SECURITY_EVENT_LIMIT = 40;
const NAV = {
  aspirante: [
    ['inicio', 'Inicio'],
    ['test', 'Prueba'],
    ['resultados', 'Entrega'],
    ['perfil', 'Perfil'],
    ['ajustes', 'Ajustes']
  ],
  staff: [
    ['panel', 'Panel'],
    ['convocatorias', 'Convocatorias'],
    ['aspirantes', 'Resultados'],
    ['baremo', 'Baremo'],
    ['ajustes', 'Ajustes']
  ],
  admin: [
    ['panel', 'Panel'],
    ['convocatorias', 'Convocatorias'],
    ['aspirantes', 'Resultados'],
    ['usuarios', 'Personal'],
    ['auditoria', 'Auditoría'],
    ['baremo', 'Baremo'],
    ['ajustes', 'Configuración']
  ]
};

const stateStore = window.SAP_UAM_STATE.create({
  mode: null,
  authMode: null,
  manualRegistering: false,
  authBusy: false,
  mfaChallenge: null,
  startBusy: false,
  submitBusy: false,
  sessionUser: null,
  role: null,
  route: 'inicio',
  profile: null,
  answers: Array(TOTAL_ITEMS).fill(null),
  currentIndex: 0,
  startedAt: null,
  result: null,
  focusLosses: 0,
  securityEvents: [],
  remoteAttempt: null,
  availability: null,
  availabilityError: '',
  privacyNotice: null,
  consentBusy: false,
  sessions: null,
  sessionsError: '',
  sessionDetail: null,
  resultsFilters: { sessionId: '', status: '', q: '' },
  staffAttempts: null,
  staffNorms: null,
  staffLoading: false,
  staffNormsLoading: false,
  staffError: '',
  staffNormsError: '',
  adminUsers: null,
  adminAudit: null,
  adminLoading: false,
  adminError: '',
  settings: loadSettings()
});
const appState = stateStore.state;

const draftRepository = window.SAP_UAM_SESSION.createDraftRepository(
  localStorage,
  context => `${STORAGE.draftPrefix}${context.testId}:${context.profileId}`,
  context => `${STORAGE.legacy.draftPrefix}${context.profileId}`
);
const resultRepository = window.SAP_UAM_SESSION.createResultRepository(
  localStorage,
  STORAGE.resultIndex,
  STORAGE.resultDetailPrefix,
  [STORAGE.legacyResultIndex, STORAGE.legacy.results]
);

function sessionContext(profile = appState.profile) {
  return {
    testId: ACTIVE_TEST.id,
    testVersion: ACTIVE_TEST.version,
    profileId: profile?.cif || 'anon'
  };
}

function attemptLeaseKey(profile = appState.profile) {
  return `${STORAGE.attemptLeasePrefix}${ACTIVE_TEST.id}:${profile?.cif || 'anon'}`;
}

function readAttemptLease() {
  return safeJson(sessionStorage.getItem(attemptLeaseKey())) || null;
}

function storeAttemptLease(attempt) {
  appState.remoteAttempt = attempt;
  sessionStorage.setItem(attemptLeaseKey(), JSON.stringify({
    id: attempt.id,
    version: attempt.version,
    leaseToken: attempt.leaseToken,
    leaseExpiresAt: attempt.leaseExpiresAt
  }));
}

function materialUrl(itemIndex, asset) {
  const attemptId = appState.remoteAttempt?.id;
  if (!attemptId) return '';
  return `${API_BASE}/api/attempts/${attemptId}/material/${itemIndex}/${asset}`;
}

function puzzleFor(itemIndex) {
  const geometry = ACTIVE_TEST.media.puzzleGeometry?.(itemIndex);
  if (!geometry || !appState.remoteAttempt?.id) return null;
  return {
    ...geometry,
    base: materialUrl(itemIndex, 'base'),
    pieces: Array.from({ length: ACTIVE_TEST.optionCount }, (_, index) => materialUrl(itemIndex, `p${index + 1}`))
  };
}

async function openRemoteAttempt({ recoverQaLease = true } = {}) {
  if (STATIC_PAGES_MODE) return null;
  const existing = readAttemptLease();
  let attempt;
  try {
    attempt = await apiRequest('/api/attempts', {
      testId: ACTIVE_TEST.id,
      testVersion: ACTIVE_TEST.version,
      leaseToken: existing?.leaseToken || null
    });
  } catch (error) {
    if (!QA_SCENARIO || !recoverQaLease || error.status !== 409) throw error;
    await apiRequest(`/api/dev/qa/login/${QA_SCENARIO}`, null, { method: 'POST' });
    sessionStorage.removeItem(attemptLeaseKey());
    appState.remoteAttempt = null;
    return openRemoteAttempt({ recoverQaLease: false });
  }
  storeAttemptLease(attempt);
  appState.answers = Array(TOTAL_ITEMS).fill(null);
  for (const response of attempt.responses || []) appState.answers[response.itemIndex] = response.selectedOption;
  appState.startedAt = attempt.startedAt;
  const firstPending = firstMissingIndex();
  appState.currentIndex = firstPending < 0 ? TOTAL_ITEMS - 1 : firstPending;
  return attempt;
}

let remoteWriteQueue = Promise.resolve();

function syncRemoteAnswer({ index, option }) {
  if (STATIC_PAGES_MODE) return;
  const send = async () => {
    const attempt = appState.remoteAttempt || readAttemptLease();
    if (!attempt?.id || !attempt?.leaseToken) throw new Error('No existe una sesión exclusiva para guardar la respuesta.');
    const path = `/api/attempts/${attempt.id}/responses/${index}`;
    const body = option === null
      ? { leaseToken: attempt.leaseToken, expectedVersion: attempt.version }
      : { option, leaseToken: attempt.leaseToken, expectedVersion: attempt.version };
    const updated = await apiRequest(path, body, { method: option === null ? 'DELETE' : 'PUT' });
    storeAttemptLease(updated);
  };
  remoteWriteQueue = remoteWriteQueue.then(async () => {
    try {
      await send();
    } catch (error) {
      if (error.code !== 'STALE_VERSION') throw error;
      // Versión desfasada: se resincroniza con el servidor y se reenvía la respuesta pendiente una vez.
      await openRemoteAttempt();
      appState.answers[index] = option;
      await send();
      render();
    }
  }).catch(async error => {
    toast(error.message || 'No se pudo guardar la respuesta.', 'err');
    try {
      await openRemoteAttempt();
      render();
    } catch {}
  });
}

function syncSecurityEvent(event) {
  if (STATIC_PAGES_MODE) return;
  const attempt = appState.remoteAttempt || readAttemptLease();
  if (!attempt?.id || !attempt?.leaseToken) return;
  apiRequest(`/api/attempts/${attempt.id}/events`, {
    leaseToken: attempt.leaseToken,
    type: event.type,
    detail: event.detail || ''
  }).catch(() => {});
}

async function renewRemoteAttemptLease() {
  if (STATIC_PAGES_MODE || !isExamLocked()) return;
  remoteWriteQueue = remoteWriteQueue.then(async () => {
    const attempt = appState.remoteAttempt || readAttemptLease();
    if (!attempt?.id || !attempt?.leaseToken) return;
    const renewed = await apiRequest(`/api/attempts/${attempt.id}/lease`, {
      leaseToken: attempt.leaseToken,
      expectedVersion: attempt.version
    });
    storeAttemptLease(renewed);
  }).catch(async error => {
    try {
      await openRemoteAttempt();
    } catch {
      toast(error.message || 'No se pudo renovar la sesión exclusiva.', 'err');
    }
  });
  await remoteWriteQueue;
}

function normProfileForServer(profile) {
  const group = ['7-16', 'AN', 'AD', 'UN'].includes(profile?.grupo) ? profile.grupo : 'UN';
  const age = Number(profile?.edad);
  return { normGroup: group, age: Number.isFinite(age) && age >= 3 && age <= 120 ? age : null };
}

async function submitRemoteAttempt() {
  if (STATIC_PAGES_MODE) return null;
  await remoteWriteQueue;
  const attempt = appState.remoteAttempt || readAttemptLease();
  if (!attempt?.id || !attempt?.leaseToken) throw new Error('No existe una sesión exclusiva para este intento.');
  const submitted = await apiRequest(`/api/attempts/${attempt.id}/submit`, {
    leaseToken: attempt.leaseToken,
    expectedVersion: attempt.version,
    answers: [...appState.answers],
    ...normProfileForServer(appState.profile)
  });
  storeAttemptLease(submitted);
  return submitted;
}

function toast(message, kind = 'info') {
  const wrap = $('#toast-wrap');
  if (!wrap) return;
  const el = document.createElement('div');
  el.className = `toast toast--${kind}`;
  el.textContent = message;
  wrap.appendChild(el);
  requestAnimationFrame(() => el.classList.add('show'));
  setTimeout(() => {
    el.classList.remove('show');
    setTimeout(() => el.remove(), 220);
  }, 2800);
}

function plural(n, one, many) {
  return n === 1 ? one : many;
}

function esc(value) {
  return String(value ?? '').replace(/[&<>'"]/g, char => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', "'": '&#39;', '"': '&quot;' }[char]));
}

function setFieldState(wrapSelector, msgSelector, kind, message) {
  const wrap = $(wrapSelector);
  const msg = $(msgSelector);
  if (wrap) {
    wrap.classList.toggle('is-ok', kind === 'ok');
    wrap.classList.toggle('is-err', kind === 'err');
    const field = wrap.querySelector('input, select, textarea');
    if (field) {
      if (kind === 'err') field.setAttribute('aria-invalid', 'true');
      else field.removeAttribute('aria-invalid');
    }
  }
  if (msg) msg.textContent = message || '';
}

function clearFieldState(wrapSelector, msgSelector) {
  setFieldState(wrapSelector, msgSelector, '', '');
}

function inferLoginMode(identifier) {
  const value = String(identifier || '').trim();
  if (!value) return null;
  if (/^[0-9]{4,12}$/.test(value)) return 'moodle';
  if (/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(value)) return 'manual';
  return null;
}

function setMode(mode) {
  appState.mode = mode;
  appState.authMode = mode;
  const isRegister = appState.manualRegistering;
  $('#manual-extra')?.classList.toggle('app-hidden', !isRegister);
  $('#staff-pass-wrap')?.classList.toggle('app-hidden', false);
  const label = $('#unified-id-label');
  const input = $('#unified-id');
  const passLabel = $('#unified-pass-label');
  const pass = $('#unified-pass');
  const foot = $('#unified-foot');
  const buttonLabel = $('#btn-unified-label');
  const manualToggle = $('#manual-register-toggle');
  const loginTab = $('#login-tab');
  const registerTab = $('#register-tab');
  const heading = $('#auth-heading');
  const description = $('#auth-description');
  const registerNote = $('#register-note');
  if (label) label.textContent = isRegister ? 'Correo electrónico' : 'CIF o correo';
  if (input) {
    input.inputMode = isRegister ? 'email' : 'text';
    input.placeholder = isRegister ? 'correo para tu cuenta externa' : 'CIF UAM o correo registrado';
    input.autocomplete = isRegister ? 'email' : 'username';
  }
  if (passLabel) passLabel.textContent = 'Contraseña';
  if (pass) pass.autocomplete = isRegister ? 'new-password' : 'current-password';
  if (buttonLabel) buttonLabel.textContent = isRegister ? 'Crear cuenta' : 'Ingresar';
  if (manualToggle) {
    manualToggle.classList.add('app-hidden');
    manualToggle.textContent = isRegister ? 'Ya tengo cuenta, iniciar sesión' : 'Crear cuenta externa';
  }
  loginTab?.classList.toggle('is-active', !isRegister);
  registerTab?.classList.toggle('is-active', isRegister);
  loginTab?.setAttribute('aria-pressed', String(!isRegister));
  registerTab?.setAttribute('aria-pressed', String(isRegister));
  registerNote?.classList.toggle('app-hidden', !isRegister);
  if (heading) heading.textContent = isRegister ? 'crear cuenta externa' : 'iniciar sesión';
  if (description) description.textContent = isRegister
    ? 'solo necesitás registrarte si no tenés un cif institucional activo.'
    : 'usá tu cif institucional o el correo de tu cuenta externa.';
  if (foot) foot.textContent = isRegister
    ? 'Creá una cuenta externa con correo, contraseña, nombre y cédula.'
    : 'Usá tu CIF UAM o tu correo registrado en este mismo campo.';
  clearFieldState('#wrap-unified-pass', '#unified-pass-msg');
  clearFieldState('#wrap-name', '#name-msg');
  clearFieldState('#wrap-cedula', '#cedula-msg');
}

function validateLogin() {
  const id = ($('#unified-id')?.value || '').trim();
  const mode = appState.manualRegistering ? 'manual' : inferLoginMode(id);
  setMode(mode);
  const pass = $('#unified-pass')?.value || '';
  let valid = false;
  if (mode === 'moodle') {
    const passOk = pass.length > 0;
    setFieldState('#wrap-unified-id', '#unified-id-msg', 'ok', 'CIF listo');
    if (pass) setFieldState('#wrap-unified-pass', '#unified-pass-msg', passOk ? 'ok' : 'err', passOk ? 'Contraseña lista' : 'Contraseña obligatoria');
    else clearFieldState('#wrap-unified-pass', '#unified-pass-msg');
    valid = passOk;
  } else if (mode === 'manual') {
    const emailOk = /^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(id);
    const passOk = appState.manualRegistering ? pass.length >= 8 : pass.length > 0;
    if (id) setFieldState('#wrap-unified-id', '#unified-id-msg', emailOk ? 'ok' : 'err', emailOk ? 'Correo listo' : 'Correo inválido');
    else clearFieldState('#wrap-unified-id', '#unified-id-msg');
    if (pass) setFieldState('#wrap-unified-pass', '#unified-pass-msg', passOk ? 'ok' : 'err', passOk ? 'Contraseña lista' : appState.manualRegistering ? 'Mínimo 8 caracteres' : 'Contraseña obligatoria');
    else clearFieldState('#wrap-unified-pass', '#unified-pass-msg');
    valid = emailOk && passOk;
    if (appState.manualRegistering) {
      const name = ($('#fullname')?.value || '').trim();
      const cedula = ($('#cedula')?.value || '').trim();
      const nameOk = name.length >= 5;
      const cedulaOk = cedula.length >= 6;
      if (name) setFieldState('#wrap-name', '#name-msg', nameOk ? 'ok' : 'err', nameOk ? 'Nombre listo' : 'Escribí tu nombre completo');
      else clearFieldState('#wrap-name', '#name-msg');
      if (cedula) setFieldState('#wrap-cedula', '#cedula-msg', cedulaOk ? 'ok' : 'err', cedulaOk ? 'Cédula lista' : 'Revisá la cédula');
      else clearFieldState('#wrap-cedula', '#cedula-msg');
      valid = valid && nameOk && cedulaOk;
    }
  } else {
    if (id) setFieldState('#wrap-unified-id', '#unified-id-msg', 'err', 'Usá un CIF numérico o un correo válido');
    else clearFieldState('#wrap-unified-id', '#unified-id-msg');
    if (pass) setFieldState('#wrap-unified-pass', '#unified-pass-msg', 'ok', 'Contraseña lista');
    else clearFieldState('#wrap-unified-pass', '#unified-pass-msg');
  }
  const button = $('#btn-unified');
  if (button) button.disabled = !valid || appState.authBusy;
  return valid;
}

function setAuthBusy(isBusy) {
  appState.authBusy = isBusy;
  $('#form-unified')?.setAttribute('aria-busy', String(isBusy));
  const button = $('#btn-unified');
  const label = $('#btn-unified-label');
  if (button) button.disabled = isBusy || !validateLogin();
  if (label && isBusy) label.textContent = 'Validando acceso...';
  if (!isBusy) {
    if (label) label.textContent = appState.manualRegistering ? 'Crear cuenta' : 'Ingresar';
    validateLogin();
  }
}

async function apiRequest(path, body = null, options = {}) {
  if (STATIC_PAGES_MODE && path.startsWith('/api/auth/')) {
    return staticAuthRequest(path, body);
  }
  const response = await fetch(`${API_BASE}${path}`, {
    method: body ? 'POST' : 'GET',
    credentials: 'include',
    headers: body ? { 'Content-Type': 'application/json', 'Accept': 'application/json' } : { 'Accept': 'application/json' },
    body: body ? JSON.stringify(body) : null,
    ...options
  });
  const text = await response.text();
  const data = text ? safeJson(text) : null;
  if (!response.ok) {
    const message = data?.message || data?.error || 'No se pudo completar la solicitud.';
    const failure = new Error(message);
    failure.code = data?.code || null;
    failure.status = response.status;
    throw failure;
  }
  return data;
}

async function apiJson(method, path, body = null) {
  return apiRequest(path, body === null ? undefined : body, { method });
}

async function downloadFromApi(path, fallbackName) {
  const response = await fetch(`${API_BASE}${path}`, { credentials: 'include', headers: { Accept: 'text/csv' } });
  if (!response.ok) {
    const data = safeJson(await response.text());
    throw new Error(data?.message || 'No se pudo generar el archivo.');
  }
  const name = /filename="?([^";]+)"?/i.exec(response.headers.get('Content-Disposition') || '')?.[1] || fallbackName;
  const url = URL.createObjectURL(await response.blob());
  const link = document.createElement('a');
  link.href = url;
  link.download = name;
  document.body.appendChild(link);
  link.click();
  link.remove();
  setTimeout(() => URL.revokeObjectURL(url), 2000);
}

const demoCredentials = window.SAP_UAM_DEMO_CREDENTIALS.createVerifier(window.crypto);

async function staticAuthRequest(path, body = null) {
  const users = readStaticUsers();
  if (path === '/api/auth/me') {
    const user = safeJson(localStorage.getItem(STORAGE.staticSession) || localStorage.getItem(STORAGE.legacy.staticSession));
    if (!user?.id) throw new Error('Necesitas iniciar sesión.');
    return Promise.resolve(user);
  }
  if (path === '/api/auth/logout') {
    localStorage.removeItem(STORAGE.staticSession);
    localStorage.removeItem(STORAGE.legacy.staticSession);
    return Promise.resolve({ ok: true });
  }
  if (path === '/api/auth/moodle-login') {
    return Promise.reject(new Error('El acceso Moodle requiere el backend. En GitHub Pages usá "Crear cuenta externa" para probar la interfaz.'));
  }
  if (path === '/api/auth/register') {
    const email = String(body?.email || '').trim().toLowerCase();
    if (users.some(user => user.email === email)) {
      return Promise.reject(new Error('Ya existe una cuenta con ese correo en este navegador.'));
    }
    const user = {
      id: Date.now(),
      fullName: String(body?.fullName || 'Usuario Demo').trim(),
      email,
      ...(await demoCredentials.seal(String(body?.password || ''))),
      pictureUrl: '',
      career: '',
      role: 'CLIENTE',
      authProvider: 'manual',
      moodleId: null
    };
    users.push(user);
    localStorage.setItem(STORAGE.staticUsers, JSON.stringify(users));
    localStorage.setItem(STORAGE.staticSession, JSON.stringify(publicStaticUser(user)));
    return Promise.resolve({ token: 'github-pages-demo', expiresInSeconds: 86400, user: publicStaticUser(user) });
  }
  if (path === '/api/auth/login') {
    const email = String(body?.email || '').trim().toLowerCase();
    const password = String(body?.password || '');
    const user = users.find(item => item.email === email);
    const check = user ? await demoCredentials.verify(user, password) : { ok: false };
    if (!check.ok) return Promise.reject(new Error('Correo o contraseña incorrectos.'));
    if (check.upgrade) {
      delete user.password;
      Object.assign(user, check.upgrade);
      localStorage.setItem(STORAGE.staticUsers, JSON.stringify(users));
    }
    localStorage.setItem(STORAGE.staticSession, JSON.stringify(publicStaticUser(user)));
    return Promise.resolve({ token: 'github-pages-demo', expiresInSeconds: 86400, user: publicStaticUser(user) });
  }
  return Promise.reject(new Error('Esta acción requiere el backend.'));
}

function readStaticUsers() {
  try {
    return JSON.parse(localStorage.getItem(STORAGE.staticUsers) || localStorage.getItem(STORAGE.legacy.staticUsers) || '[]');
  } catch {
    return [];
  }
}

function publicStaticUser(user) {
  const { password, passwordHash, passwordSalt, ...safeUser } = user;
  return safeUser;
}

function safeJson(text) {
  try {
    return JSON.parse(text);
  } catch {
    return null;
  }
}

async function submitUnifiedLogin() {
  if (!validateLogin()) {
    toast('Revisá los campos marcados.', 'err');
    return;
  }
  setAuthBusy(true);
  let failureMessage = '';
  try {
    const id = ($('#unified-id')?.value || '').trim();
    const password = $('#unified-pass')?.value || '';
    const mode = appState.manualRegistering ? 'manual' : inferLoginMode(id);
    let response;
    if (mode === 'moodle') {
      response = await apiRequest('/api/auth/moodle-login', { cif: id, password });
    } else if (appState.manualRegistering) {
      response = await apiRequest('/api/auth/register', {
        fullName: ($('#fullname')?.value || '').trim(),
        email: id,
        password,
        cedula: ($('#cedula')?.value || '').trim()
      });
    } else {
      response = await apiRequest('/api/auth/login', { email: id, password });
    }
    if (response?.mfaRequired) {
      showMfaChallenge(response);
    } else {
      handleAuthSuccess(response, { loginId: id });
    }
  } catch (error) {
    playSound('error');
    failureMessage = error.message || 'No se pudo iniciar sesión.';
    toast(failureMessage, 'err');
  } finally {
    setAuthBusy(false);
    if (failureMessage) {
      setFieldState('#wrap-unified-pass', '#unified-pass-msg', 'err', failureMessage);
      $('#unified-pass')?.focus();
    }
  }
}

function showMfaChallenge(response) {
  appState.mfaChallenge = response;
  $('#form-unified')?.classList.add('app-hidden');
  $('.auth-tabs')?.classList.add('app-hidden');
  $('#form-mfa')?.classList.remove('app-hidden');
  const enrollment = !!response.mfaEnrollmentRequired;
  $('#mfa-enrollment')?.classList.toggle('app-hidden', !enrollment);
  const help = $('#mfa-help');
  if (help) help.textContent = enrollment
    ? 'Configurá primero una cuenta en tu aplicación de autenticación y luego ingresá el código actual.'
    : 'Abrí tu aplicación de autenticación e ingresá el código actual.';
  const secret = $('#mfa-secret');
  if (secret) secret.textContent = enrollment ? response.enrollmentSecret || '' : '';
  const openApp = $('#mfa-open-app');
  if (openApp) {
    openApp.classList.toggle('app-hidden', !enrollment || !response.otpauthUri);
    openApp.href = response.otpauthUri || '#';
  }
  const input = $('#mfa-code');
  if (input) {
    input.value = '';
    input.setAttribute('aria-invalid', 'false');
    requestAnimationFrame(() => input.focus());
  }
  validateMfa();
}

function cancelMfa() {
  appState.mfaChallenge = null;
  $('#form-mfa')?.classList.add('app-hidden');
  $('.auth-tabs')?.classList.remove('app-hidden');
  $('#form-unified')?.classList.remove('app-hidden');
  $('#unified-pass')?.focus();
}

function validateMfa() {
  const input = $('#mfa-code');
  const valid = /^\d{6}$/.test(input?.value || '');
  const button = $('#btn-mfa');
  if (button) button.disabled = !valid || appState.authBusy;
  if (input) input.setAttribute('aria-invalid', input.value && !valid ? 'true' : 'false');
  return valid;
}

function setMfaBusy(isBusy) {
  appState.authBusy = isBusy;
  $('#form-mfa')?.setAttribute('aria-busy', String(isBusy));
  const button = $('#btn-mfa');
  if (button) button.textContent = isBusy ? 'Verificando...' : 'Verificar e ingresar';
  validateMfa();
}

async function submitMfa() {
  if (!validateMfa() || !appState.mfaChallenge?.challengeToken) return;
  setMfaBusy(true);
  try {
    const response = await apiRequest('/api/auth/mfa/verify', {
      challengeToken: appState.mfaChallenge.challengeToken,
      code: $('#mfa-code').value
    });
    appState.mfaChallenge = null;
    handleAuthSuccess(response);
  } catch (error) {
    const input = $('#mfa-code');
    input?.setAttribute('aria-invalid', 'true');
    const message = $('#mfa-code-msg');
    if (message) message.textContent = error.message || 'El código no es válido.';
    input?.focus();
    input?.select();
  } finally {
    setMfaBusy(false);
  }
}

function profileFromUser(user, context = {}) {
  const id = user?.moodleId || user?.id || context.loginId || user?.email || 'anon';
  const profile = {
    cif: String(id),
    fullName: user?.fullName || context.loginId || 'Usuario',
    email: user?.email || '',
    moodleId: user?.moodleId || null,
    pictureUrl: user?.pictureUrl || '',
    authProvider: user?.authProvider || 'manual',
    edad: 18,
    grupo: 'UN',
    carrera: extractCareer(user)
  };
  return { ...profile, ...loadProfileEdits(profile) };
}

function normalizeCareer(value) {
  const text = String(value ?? '').replace(/\s+/g, ' ').trim();
  return text;
}

function extractCareer(user) {
  return normalizeCareer(
    user?.career ||
    user?.carrera ||
    user?.department ||
    user?.institution ||
    user?.program ||
    user?.programa ||
    user?.major
  );
}

// Borra del navegador todo dato personal (borradores, resultados, perfil, sesión) y conserva solo las preferencias.
function purgePersonalLocalData() {
  const personal = [STORAGE.draftPrefix, STORAGE.profileEditsPrefix, STORAGE.resultDetailPrefix,
    STORAGE.resultIndex, STORAGE.legacyResultIndex, STORAGE.sessionUser, STORAGE.legacy.results,
    STORAGE.legacy.draftPrefix, STORAGE.legacy.profileEditsPrefix, STORAGE.legacy.sessionUser];
  try {
    for (const key of Object.keys(localStorage)) {
      if (personal.some(prefix => key.startsWith(prefix))) localStorage.removeItem(key);
    }
    for (const key of Object.keys(sessionStorage)) {
      if (key.startsWith(STORAGE.attemptLeasePrefix)) sessionStorage.removeItem(key);
    }
  } catch {}
}

function handleAuthSuccess(response, context = {}) {
  const user = response?.user;
  if (!user) {
    throw new Error('La sesión no devolvió datos de usuario.');
  }
  const previous = safeJson(localStorage.getItem(STORAGE.sessionUser) || 'null');
  if (previous?.id !== user.id) purgePersonalLocalData();
  appState.sessionUser = user;
  localStorage.setItem(STORAGE.sessionUser, JSON.stringify(user));
  playSound('success');
  toast('Sesión iniciada.', 'ok');
  if (isStaffRole(user.role)) {
    enterStaff(user);
    return;
  }
  enterAspirante(profileFromUser(user, context), { restoreLocalProgress: !context.qaLocal });
}

async function restoreSessionFromBackend() {
  if ($('#view-auth')?.classList.contains('app-hidden')) return;
  try {
    const user = await apiRequest('/api/auth/me');
    if (!user?.id) return;
    appState.sessionUser = user;
    localStorage.setItem(STORAGE.sessionUser, JSON.stringify(user));
    if (isStaffRole(user.role)) enterStaff(user);
    else enterAspirante(profileFromUser(user));
  } catch {
    localStorage.removeItem(STORAGE.sessionUser);
  }
}

async function enterLocalQaScenario() {
  if (!QA_SCENARIO) return false;
  try {
    const response = await apiRequest(`/api/dev/qa/login/${QA_SCENARIO}`, null, { method: 'POST' });
    handleAuthSuccess(response, { loginId: response?.user?.email || 'qa-local', qaLocal: true });
    return true;
  } catch (error) {
    toast(error.message || 'No se pudo abrir el escenario local de QA.', 'err');
    return false;
  }
}

function loadSettings() {
  const fallback = { dark: false, compact: false, smooth: true, sound: true, autoAdvance: false, secondsPerPage: 240 };
  try {
    const saved = localStorage.getItem(STORAGE.settings) || localStorage.getItem(STORAGE.legacy.settings) || '{}';
    return { ...fallback, ...JSON.parse(saved) };
  } catch {
    return fallback;
  }
}

function saveSettings() {
  localStorage.setItem(STORAGE.settings, JSON.stringify(appState.settings));
  applySettings();
}

function applySettings() {
  document.body.classList.toggle('theme-dark', !!appState.settings.dark);
  document.body.classList.toggle('body-compact', !!appState.settings.compact);
  applyExamSecurityUi();
}

let audioContext = null;
let securityToastAt = 0;

function playSound(kind = 'tap') {
  if (!appState.settings.sound) return;
  try {
    const AudioCtx = window.AudioContext || window.webkitAudioContext;
    if (!AudioCtx) return;
    audioContext ||= new AudioCtx();
    if (audioContext.state === 'suspended') audioContext.resume();
    const osc = audioContext.createOscillator();
    const gain = audioContext.createGain();
    const now = audioContext.currentTime;
    const tone = {
      tap: [520, 0.045, 0.028],
      nav: [360, 0.04, 0.022],
      error: [170, 0.09, 0.035],
      success: [720, 0.12, 0.032],
      start: [620, 0.1, 0.026]
    }[kind] || [440, 0.05, 0.025];
    osc.type = kind === 'error' ? 'sawtooth' : 'sine';
    osc.frequency.setValueAtTime(tone[0], now);
    if (kind === 'success') osc.frequency.exponentialRampToValueAtTime(980, now + tone[1]);
    gain.gain.setValueAtTime(0.0001, now);
    gain.gain.exponentialRampToValueAtTime(tone[2], now + 0.01);
    gain.gain.exponentialRampToValueAtTime(0.0001, now + tone[1]);
    osc.connect(gain).connect(audioContext.destination);
    osc.start(now);
    osc.stop(now + tone[1] + 0.02);
  } catch {}
}

function notifySecurity(message = 'Modo examen activo: no podés salir de la prueba.') {
  const now = Date.now();
  if (now - securityToastAt < 1200) return;
  securityToastAt = now;
  playSound('error');
  toast(message, 'err');
}

function records() {
  return resultRepository.list();
}

function saveRecord(result) {
  resultRepository.save(result);
}

function profileEditKey(profile = appState.profile) {
  const id = profile?.moodleId || profile?.cif || profile?.email || 'anon';
  return `${STORAGE.profileEditsPrefix}${id}`;
}

function legacyProfileEditKey(profile = appState.profile) {
  const id = profile?.moodleId || profile?.cif || profile?.email || 'anon';
  return `${STORAGE.legacy.profileEditsPrefix}${id}`;
}

function loadProfileEdits(profile) {
  try {
    const saved = localStorage.getItem(profileEditKey(profile)) || localStorage.getItem(legacyProfileEditKey(profile)) || '{}';
    const data = JSON.parse(saved);
    return {
      ...(data.carrera !== undefined ? { carrera: normalizeCareer(data.carrera) } : {}),
      ...(data.edad !== undefined ? { edad: Number(data.edad) || 18 } : {}),
      ...(data.grupo ? { grupo: data.grupo } : {})
    };
  } catch {
    return {};
  }
}

function saveProfileEdits() {
  if (!appState.profile) return;
  localStorage.setItem(profileEditKey(), JSON.stringify({
    carrera: normalizeCareer(appState.profile.carrera),
    edad: Number(appState.profile.edad) || 18,
    grupo: appState.profile.grupo || 'UN'
  }));
  saveDraft();
}

function saveDraft() {
  if (!appState.profile || appState.role !== 'aspirante') return;
  draftRepository.save(sessionContext(), appState);
}

function loadDraft(profile) {
  try {
    const draft = draftRepository.load(sessionContext(profile)) || {};
    if (Array.isArray(draft.answers) && draft.answers.length === TOTAL_ITEMS) {
      appState.answers = draft.answers;
      appState.currentIndex = Number(draft.currentIndex) || 0;
      appState.startedAt = draft.startedAt || null;
      appState.focusLosses = Number(draft.focusLosses) || 0;
      appState.securityEvents = Array.isArray(draft.securityEvents) ? draft.securityEvents.slice(-SECURITY_EVENT_LIMIT) : [];
      const latest = records().find(item => item.testId === ACTIVE_TEST.id && String(item.profile?.cif) === String(profile?.cif));
      appState.result = latest ? resultRepository.get(latest.id) : null;
      return true;
    }
  } catch {}
  return false;
}

function answeredCount() {
  return appState.answers.filter(value => value !== null).length;
}

function formatDate(value) {
  if (!value) return 'Sin fecha';
  return new Intl.DateTimeFormat('es-NI', { dateStyle: 'medium', timeStyle: 'short' }).format(new Date(value));
}

function formatDuration(seconds) {
  const total = Math.max(0, Math.round(seconds || 0));
  const min = Math.floor(total / 60);
  const sec = total % 60;
  return `${min}:${String(sec).padStart(2, '0')}`;
}

function initials(name) {
  return String(name || 'U').split(/\s+/).filter(Boolean).slice(0, 2).map(part => part[0]?.toUpperCase()).join('') || 'U';
}

function displayPersonName(name) {
  const value = String(name || '').trim();
  if (!value || value !== value.toUpperCase()) return value;
  return value.toLocaleLowerCase('es-NI').replace(/(^|[\s-])\p{L}/gu, letter => letter.toLocaleUpperCase('es-NI'));
}

function enterAspirante(profileOverride = null, options = {}) {
  const profile = profileOverride || {
    cif: ($('#unified-id')?.value || '').trim(),
    fullName: ($('#fullname')?.value || '').trim(),
    edad: 18,
    grupo: 'UN',
    carrera: ''
  };
  appState.role = 'aspirante';
  appState.route = 'inicio';
  appState.profile = profile;
  appState.answers = Array(TOTAL_ITEMS).fill(null);
  appState.currentIndex = 0;
  appState.startedAt = null;
  appState.result = null;
  appState.focusLosses = 0;
  appState.securityEvents = [];
  const restoredDraft = options.restoreLocalProgress === false ? false : loadDraft(profile);
  if (!restoredDraft && options.restoreLocalProgress !== false) {
    const latest = records().find(item => item.testId === ACTIVE_TEST.id && String(item.profile?.cif) === String(profile?.cif));
    appState.result = latest ? resultRepository.get(latest.id) : null;
  }
  if (appState.startedAt && !appState.result) appState.route = 'test';
  appState.availability = null;
  appState.availabilityError = '';
  appState.privacyNotice = null;
  showApp();
  loadAvailability();
  if (appState.startedAt && !appState.result && !STATIC_PAGES_MODE) {
    openRemoteAttempt()
      .then(() => render())
      .catch(error => toast(error.message || 'No se pudo recuperar la sesión exclusiva.', 'err'));
  }
}

function isStaffRole(role) {
  return role === 'STAFF' || role === 'ADMIN';
}

function enterStaff(user = null) {
  appState.role = 'staff';
  appState.route = 'panel';
  appState.profile = { username: user?.fullName || user?.email || ($('#unified-id')?.value || '').trim() || 'Personal' };
  appState.sessionUser = user || appState.sessionUser;
  appState.staffAttempts = null;
  appState.staffError = '';
  appState.sessions = null;
  appState.sessionDetail = null;
  appState.resultsFilters = { sessionId: '', status: '', q: '' };
  showApp();
}

async function loadAdminData(kind, force = false) {
  if (STATIC_PAGES_MODE || appState.sessionUser?.role !== 'ADMIN' || appState.adminLoading) return;
  const current = kind === 'users' ? appState.adminUsers : appState.adminAudit;
  if (current && !force) return;
  appState.adminLoading = true;
  appState.adminError = '';
  try {
    const rows = await apiRequest(kind === 'users' ? '/api/admin/users' : '/api/admin/audit');
    if (kind === 'users') appState.adminUsers = Array.isArray(rows) ? rows : [];
    else appState.adminAudit = Array.isArray(rows) ? rows : [];
  } catch (error) {
    appState.adminError = error.message || 'No se pudo cargar la administración.';
  } finally {
    appState.adminLoading = false;
    if (appState.role === 'staff') render();
  }
}

async function createStaffAccount(form) {
  const data = new FormData(form);
  const error = $('#admin-form-error');
  if (error) error.textContent = '';
  form.setAttribute('aria-busy', 'true');
  try {
    await apiRequest('/api/admin/users', {
      fullName: String(data.get('fullName') || '').trim(),
      email: String(data.get('email') || '').trim(),
      temporaryPassword: String(data.get('temporaryPassword') || ''),
      role: String(data.get('role') || 'STAFF')
    });
    form.reset();
    appState.adminUsers = null;
    await loadAdminData('users', true);
    toast('Cuenta de personal creada.', 'ok');
  } catch (requestError) {
    if (error) error.textContent = requestError.message || 'No se pudo crear la cuenta.';
  } finally {
    form.setAttribute('aria-busy', 'false');
  }
}

async function updateStaffAccount(userId, active) {
  const role = $(`[data-admin-role="${userId}"]`)?.value || 'STAFF';
  const current = (appState.adminUsers || []).find(user => user.id === userId);
  const activeChanged = !current || current.active !== active;
  if (current && !active && activeChanged
    && !window.confirm(`¿Desactivar la cuenta de ${current.fullName}? Sus sesiones abiertas se cierran de inmediato.`)) return;
  try {
    await apiRequest(`/api/admin/users/${userId}`, { role, active }, { method: 'PATCH' });
    appState.adminUsers = null;
    await loadAdminData('users', true);
    toast(!activeChanged ? 'Rol guardado. Sus sesiones abiertas se cerraron.' : active ? 'Cuenta activada.' : 'Cuenta desactivada.', 'ok');
  } catch (error) {
    toast(error.message || 'No se pudo cambiar el acceso.', 'err');
  }
}

async function resetStaffMfa(userId) {
  if (!window.confirm('¿Restablecer el segundo factor? La persona tendrá que configurarlo de nuevo.')) return;
  try {
    await apiRequest(`/api/admin/users/${userId}/mfa-reset`, {}, { method: 'POST' });
    appState.adminUsers = null;
    await loadAdminData('users', true);
    toast('Segundo factor restablecido.', 'ok');
  } catch (error) {
    toast(error.message || 'No se pudo restablecer el segundo factor.', 'err');
  }
}

async function loadStaffAttempts(force = false) {
  if (STATIC_PAGES_MODE || appState.staffLoading || (appState.staffAttempts && !force)) return;
  appState.staffLoading = true;
  appState.staffError = '';
  try {
    const rows = await apiRequest(`/api/staff/attempts?${resultsQuery()}`);
    appState.staffAttempts = Array.isArray(rows) ? rows : [];
  } catch (error) {
    appState.staffError = error.message || 'No se pudieron cargar los resultados.';
  } finally {
    appState.staffLoading = false;
    if (appState.role === 'staff') render();
  }
}

async function loadStaffNorms(force = false) {
  if (STATIC_PAGES_MODE || appState.staffNormsLoading || (appState.staffNorms && !force)) return;
  appState.staffNormsLoading = true;
  appState.staffNormsError = '';
  try {
    const rows = await apiRequest('/api/staff/attempts/norms');
    appState.staffNorms = Array.isArray(rows) ? rows : [];
  } catch (error) {
    appState.staffNormsError = error.message || 'No se pudo cargar el baremo.';
  } finally {
    appState.staffNormsLoading = false;
    if (appState.role === 'staff' && appState.route === 'baremo') render();
  }
}


// ---------------------------------------------------------------- flujo de admision
const ADM = window.SAP_UAM_ADMISSION;

async function loadAvailability() {
  if (STATIC_PAGES_MODE) return;
  appState.availabilityError = '';
  try {
    appState.availability = await apiRequest('/api/applicant/availability');
    await hydrateProgressFromAttempt();
  } catch (error) {
    appState.availabilityError = error.message || 'No se pudo consultar tu evaluación.';
  }
  if (appState.role === 'aspirante' && ['inicio', 'privacidad'].includes(appState.route)) render();
}

// El inicio muestra el avance real aunque la prueba todavía no se haya reabierto en esta pestaña.
async function hydrateProgressFromAttempt() {
  const attemptId = appState.availability?.attemptId;
  if (!attemptId || isExamLocked()) return;
  try {
    const attempt = await apiRequest(`/api/attempts/${attemptId}`);
    if (attempt.status !== 'IN_PROGRESS' || isExamLocked()) return;
    const answers = Array(TOTAL_ITEMS).fill(null);
    for (const response of attempt.responses || []) answers[response.itemIndex] = response.selectedOption;
    appState.answers = answers;
  } catch {}
}

async function claimAccessCode(form) {
  const errorBox = $('#claim-error');
  if (errorBox) errorBox.textContent = '';
  const code = String(new FormData(form).get('code') || '').trim();
  if (!code) return;
  form.setAttribute('aria-busy', 'true');
  try {
    await apiRequest('/api/applicant/claim', { code });
    toast('Código canjeado. Ya estás habilitado.', 'ok');
    await loadAvailability();
  } catch (error) {
    if (errorBox) errorBox.textContent = error.message || 'No se pudo canjear el código.';
  } finally {
    form.setAttribute('aria-busy', 'false');
  }
}

async function openConsent() {
  appState.route = 'privacidad';
  renderShell();
  render();
  if (appState.privacyNotice) return;
  try {
    appState.privacyNotice = await apiRequest('/api/privacy/notice');
  } catch (error) {
    toast(error.message || 'No se pudo cargar el aviso de privacidad.', 'err');
    appState.route = 'inicio';
    renderShell();
  }
  render();
}

async function acceptConsent() {
  const notice = appState.privacyNotice;
  if (!notice || appState.consentBusy) return;
  appState.consentBusy = true;
  const errorBox = $('#consent-error');
  const panel = $('#consent-panel');
  const checkbox = $('#consent-check');
  const acceptButton = $('[data-action="accept-consent"]');
  panel?.setAttribute('aria-busy', 'true');
  if (checkbox) checkbox.disabled = true;
  if (acceptButton) {
    acceptButton.disabled = true;
    acceptButton.textContent = 'Registrando consentimiento...';
  }
  try {
    await apiRequest('/api/privacy/consent', { version: notice.version });
    await loadAvailability();
    toast('Consentimiento registrado.', 'ok');
    routeTo('inicio');
  } catch (error) {
    if (errorBox) errorBox.textContent = error.message || 'No se pudo registrar el consentimiento.';
  } finally {
    appState.consentBusy = false;
    if (panel?.isConnected) panel.setAttribute('aria-busy', 'false');
    if (checkbox?.isConnected) checkbox.disabled = false;
    if (acceptButton?.isConnected) {
      acceptButton.disabled = !checkbox?.checked;
      acceptButton.textContent = 'Aceptar y continuar';
    }
  }
}

async function loadSessions(force = false) {
  if (STATIC_PAGES_MODE || (appState.sessions && !force)) return;
  appState.sessionsError = '';
  try {
    const rows = await apiRequest('/api/staff/sessions');
    appState.sessions = Array.isArray(rows) ? rows : [];
  } catch (error) {
    appState.sessionsError = error.message || 'No se pudieron cargar las convocatorias.';
  }
  if (appState.role === 'staff' && ['convocatorias', 'aspirantes'].includes(appState.route)) render();
}

async function openSessionDetail(sessionId) {
  const session = (appState.sessions || []).find(item => item.id === sessionId);
  appState.sessionDetail = { session, rows: null, lastResult: null, error: '' };
  routeTo('convocatoria');
  await reloadSessionDetail();
}

async function reloadSessionDetail() {
  const detail = appState.sessionDetail;
  if (!detail?.session) return;
  try {
    detail.rows = await apiRequest(`/api/staff/sessions/${detail.session.id}/enrollments`);
    detail.error = '';
  } catch (error) {
    detail.error = error.message || 'No se pudieron cargar los aspirantes.';
  }
  if (appState.role === 'staff' && appState.route === 'convocatoria') render();
}

let staffDialogReturnFocus = null;

function showStaffDialog(title, bodyHtml) {
  closeStaffDialog();
  staffDialogReturnFocus = document.activeElement instanceof HTMLElement ? document.activeElement : null;
  const root = document.createElement('div');
  root.id = 'staff-dialog-root';
  root.innerHTML = ADM.dialogHtml(title, bodyHtml);
  document.body.appendChild(root);
  $('#view-app').inert = true;
  root.querySelector('input,select,textarea,button')?.focus();
}

function closeStaffDialog() {
  const root = $('#staff-dialog-root');
  if (!root) return;
  root.remove();
  $('#view-app').inert = false;
  staffDialogReturnFocus?.focus({ preventScroll: true });
  staffDialogReturnFocus = null;
}

function dialogError(message) {
  const box = $('#dialog-error');
  if (box) box.textContent = message;
}

async function submitSessionForm(form) {
  const data = new FormData(form);
  const id = form.dataset.sessionId;
  const minutes = String(data.get('timeLimitMinutes') || '').trim();
  const body = {
    name: String(data.get('name') || '').trim(),
    opensAt: ADM.fromLocalInput(data.get('opensAt')),
    closesAt: ADM.fromLocalInput(data.get('closesAt')),
    timeLimitMinutes: minutes ? Number(minutes) : null,
    instructions: String(data.get('instructions') || '').trim() || null
  };
  if (!body.opensAt || !body.closesAt) return dialogError('Revisá las fechas.');
  try {
    await apiJson(id ? 'PUT' : 'POST', id ? `/api/staff/sessions/${id}` : '/api/staff/sessions', body);
    closeStaffDialog();
    toast(id ? 'Convocatoria actualizada.' : 'Convocatoria creada.', 'ok');
    await loadSessions(true);
  } catch (error) {
    dialogError(error.message);
  }
}

async function cancelSession(sessionId) {
  const session = (appState.sessions || []).find(item => item.id === sessionId);
  if (!session || !window.confirm(`¿Cancelar "${session.name}"? Los aspirantes habilitados dejarán de poder rendir.`)) return;
  try {
    await apiJson('POST', `/api/staff/sessions/${sessionId}/cancel`, {});
    toast('Convocatoria cancelada.', 'ok');
    await loadSessions(true);
  } catch (error) {
    toast(error.message, 'err');
  }
}

async function submitEnroll(form) {
  const detail = appState.sessionDetail;
  const { applicants, invalid } = ADM.parseApplicants(new FormData(form).get('applicants'));
  const errorBox = $('#enroll-error');
  if (errorBox) errorBox.textContent = invalid.length ? `Correo inválido en: ${invalid.slice(0, 3).join(' | ')}` : '';
  if (invalid.length || !applicants.length) return;
  form.setAttribute('aria-busy', 'true');
  try {
    detail.lastResult = await apiRequest(`/api/staff/sessions/${detail.session.id}/enrollments`, { applicants });
    await loadSessions(true);
    detail.session = (appState.sessions || []).find(item => item.id === detail.session.id) || detail.session;
    await reloadSessionDetail();
    toast('Invitaciones enviadas.', 'ok');
  } catch (error) {
    if (errorBox) errorBox.textContent = error.message;
  } finally {
    form.setAttribute('aria-busy', 'false');
  }
}

async function enrollmentAction(kind, enrollmentId) {
  const row = appState.sessionDetail?.rows?.find(item => item.id === enrollmentId);
  if (!row) return;
  if (kind === 'adjust') { showStaffDialog('Ajustar habilitación', ADM.adjustFormHtml(row)); return; }
  if (kind === 'revoke' && !window.confirm(`¿Revocar la habilitación de ${row.fullName || row.email}?`)) return;
  try {
    if (kind === 'resend') await apiJson('POST', `/api/staff/enrollments/${enrollmentId}/resend`, {});
    else await apiJson('DELETE', `/api/staff/enrollments/${enrollmentId}`);
    toast(kind === 'resend' ? 'Código nuevo enviado.' : 'Habilitación revocada.', 'ok');
    await reloadSessionDetail();
  } catch (error) {
    toast(error.message, 'err');
  }
}

async function submitAdjust(form) {
  const data = new FormData(form);
  const age = String(data.get('age') || '').trim();
  try {
    await apiJson('PATCH', `/api/staff/enrollments/${form.dataset.enrollmentId}`, {
      extraMinutes: Number(data.get('extraMinutes') || 0),
      age: age ? Number(age) : null,
      normGroup: String(data.get('normGroup') || 'UN'),
      note: String(data.get('note') || '').trim() || null
    });
    closeStaffDialog();
    toast('Habilitación actualizada.', 'ok');
    await reloadSessionDetail();
  } catch (error) {
    dialogError(error.message);
  }
}

function resultsQuery() {
  const f = appState.resultsFilters;
  const params = new URLSearchParams({ testId: ACTIVE_TEST.id });
  if (f.sessionId) params.set('sessionId', f.sessionId);
  if (f.status) params.set('status', f.status);
  if (f.q.trim()) params.set('q', f.q.trim());
  return params.toString();
}

async function exportResults() {
  try {
    await downloadFromApi(`/api/staff/attempts/export?${resultsQuery()}`, 'resultados.csv');
    toast('Archivo generado. Contiene datos personales: guardalo con cuidado.', 'info');
  } catch (error) {
    toast(error.message, 'err');
  }
}

async function attemptAction(kind, attemptId) {
  const row = appState.staffAttempts?.find(item => item.id === attemptId);
  if (!row) return;
  if (kind === 'invalidate') { showStaffDialog('Invalidar prueba', ADM.invalidateFormHtml(row)); return; }
  if (kind === 'reapply') {
    await loadSessions();
    showStaffDialog('Reaplicar prueba', ADM.reapplyFormHtml(row, appState.sessions));
    return;
  }
  try {
    await apiJson('POST', `/api/staff/attempts/${attemptId}/review`, {});
    toast('Marcada como revisada.', 'ok');
    await loadStaffAttempts(true);
  } catch (error) {
    toast(error.message, 'err');
  }
}

async function submitInvalidate(form) {
  const reason = String(new FormData(form).get('reason') || '').trim();
  if (reason.length < 10) return dialogError('Escribí al menos 10 caracteres.');
  try {
    await apiJson('POST', `/api/staff/attempts/${form.dataset.attemptId}/invalidate`, { reason });
    closeStaffDialog();
    toast('Prueba invalidada. El aspirante recibió un aviso sin el motivo.', 'ok');
    await loadStaffAttempts(true);
  } catch (error) {
    dialogError(error.message);
  }
}

async function submitReapply(form) {
  const data = new FormData(form);
  try {
    await apiJson('POST', `/api/staff/attempts/${form.dataset.attemptId}/reapply`, {
      sessionId: Number(data.get('sessionId')),
      extraMinutes: Number(data.get('extraMinutes') || 0)
    });
    closeStaffDialog();
    toast('Reaplicación habilitada. Se envió un código nuevo.', 'ok');
    await loadStaffAttempts(true);
    await loadSessions(true);
  } catch (error) {
    dialogError(error.message);
  }
}

function showApp() {
  $('#view-auth')?.classList.add('app-hidden');
  $('#view-app')?.classList.remove('app-hidden');
  renderShell();
  render();
}

async function logout() {
  if (isExamLocked() && !window.confirm('La prueba sigue en curso y el tiempo continúa corriendo. Tu avance queda guardado, pero solo podrás retomarla desde una sesión nueva. ¿Salir ahora?')) {
    return;
  }
  try {
    await apiRequest('/api/auth/logout', {}, { method: 'POST' });
  } catch {}
  purgePersonalLocalData();
  appState.startedAt = null;
  appState.remoteAttempt = null;
  appState.answers = Array(TOTAL_ITEMS).fill(null);
  appState.route = 'inicio';
  appState.sessionUser = null;
  appState.role = null;
  appState.profile = null;
  $('#view-app')?.classList.add('app-hidden');
  $('#view-auth')?.classList.remove('app-hidden');
  toast('Sesión cerrada.', 'info');
}

async function refreshBackendSession() {
  if (STATIC_PAGES_MODE || !appState.sessionUser) return;
  try {
    const response = await apiRequest('/api/auth/refresh', {}, { method: 'POST' });
    if (response?.user) {
      appState.sessionUser = response.user;
      localStorage.setItem(STORAGE.sessionUser, JSON.stringify(response.user));
    }
  } catch {
    if (!isExamLocked()) logout();
  }
}

// Las cuentas externas no tienen CIF: se dice asi en lugar de mostrar el numero interno de usuario.
function identityLabel(profile) {
  return appState.sessionUser?.authProvider === 'manual' ? 'Cuenta externa' : `CIF ${profile?.cif ?? ''}`;
}

function renderShell() {
  document.body.dataset.role = appState.sessionUser?.role || appState.role || 'guest';
  document.body.dataset.route = appState.route || 'inicio';
  const navKey = appState.role === 'staff' && appState.sessionUser?.role === 'ADMIN' ? 'admin' : appState.role;
  const nav = (NAV[navKey] || []).filter(([route]) => route !== 'test' || testRouteAvailable());
  const locked = isExamLocked();
  $('#main-nav').innerHTML = nav.map(([route, label]) => {
    const isActive = appState.route === route;
    const isLocked = locked && route !== 'test';
    return `<button type="button" class="nav-btn ${isActive ? 'is-active' : ''} ${isLocked ? 'is-locked' : ''}" data-route="${route}" ${isActive ? 'aria-current="page"' : ''} ${isLocked ? 'aria-disabled="true"' : ''}>${label}</button>`;
  }).join('');
  const sessionLabel = $('#session-label');
  if (appState.role === 'staff') {
    const roleLabel = appState.sessionUser?.role === 'ADMIN' ? 'Administración' : 'Personal';
    sessionLabel.innerHTML = `<b>${esc(appState.profile?.username)}</b><small>${roleLabel}</small>`;
    sessionLabel.title = `${roleLabel} · ${appState.profile?.username || ''}`;
  } else {
    sessionLabel.innerHTML = `<b>${esc(appState.profile?.fullName)}</b><small>${esc(identityLabel(appState.profile))}</small>`;
    sessionLabel.title = `${appState.profile?.fullName || ''} · ${identityLabel(appState.profile)}`;
  }
  // En móvil el menú se desplaza: la pestaña activa siempre queda a la vista.
  $('#main-nav .is-active')?.scrollIntoView?.({ inline: 'center', block: 'nearest' });
}

function routeTo(route) {
  if (!canNavigateTo(route)) {
    playSound('error');
    toast(isExamLocked() ? 'La prueba está bloqueada hasta finalizarla.' : 'Iniciá la prueba desde Inicio para abrir esa pantalla.', 'err');
    if (isExamLocked()) appState.route = 'test';
    else if (appState.route === 'test') appState.route = 'inicio';
    renderShell();
    render();
    return;
  }
  appState.route = route;
  renderShell();
  render();
  requestAnimationFrame(() => window.scrollTo({ top: 0, behavior: appState.settings.smooth ? 'smooth' : 'auto' }));
  $('#app-content')?.focus({ preventScroll: true });
}

function render() {
  applyExamSecurityUi();
  if (appState.role === 'staff') renderStaff();
  else renderAspirante();
  if (appState.route === 'test') prepareCurrentItemMedia();
}

function renderAspirante() {
  const content = $('#app-content');
  if (!content) return;
  if (appState.route === 'test') content.innerHTML = renderTest();
  else if (appState.route === 'privacidad') content.innerHTML = ADM.consentView(appState.privacyNotice, appState.consentBusy);
  else if (appState.route === 'resultados') content.innerHTML = renderResults();
  else if (appState.route === 'perfil') content.innerHTML = renderProfile();
  else if (appState.route === 'ajustes') content.innerHTML = renderSettings();
  else content.innerHTML = renderHome();
}

function renderHome() {
  const count = answeredCount();
  const progress = Math.round((count / TOTAL_ITEMS) * 100);
  const result = appState.result;
  const home = STATIC_PAGES_MODE ? null : ADM.aspirantHome(appState.availability, { count, hasResult: Boolean(result), error: appState.availabilityError });
  const readableName = displayPersonName(appState.profile.fullName);
  const profileLine = [readableName, normalizeCareer(appState.profile.carrera)].filter(Boolean).join(' · ');
  return `
    <div class="screen-title">
      <div><p class="eyebrow">Centro de evaluaciones</p><h2>Hola, ${esc(readableName.split(' ')[0])}</h2><p>${esc(profileLine)}</p></div>
    </div>
    <div class="dashboard-grid">
      <section class="hero-band assessment-card">
        <div class="hero-copy">
          <div>
            <div class="assessment-card__top"><p class="eyebrow">${esc(ACTIVE_TEST.shortName)}</p><span class="status-badge">${home ? esc(home.badge) : result ? 'Entregada' : count ? 'En progreso' : 'Asignada'}</span></div>
            <h2>${esc(ACTIVE_TEST.name)}</h2>
            <p>${esc(ACTIVE_TEST.description)}</p>
            <dl class="assessment-meta">
              <div><dt>Ítems</dt><dd>${TOTAL_ITEMS}</dd></div>
              <div><dt>Secciones</dt><dd>${TOTAL_PAGES}</dd></div>
              <div><dt>${!STATIC_PAGES_MODE && appState.availability?.minutes ? 'Tiempo disponible' : 'Tiempo estimado'}</dt><dd>${(!STATIC_PAGES_MODE && appState.availability?.minutes) || ACTIVE_TEST.estimatedMinutes} min</dd></div>
            </dl>
          </div>
          <div>
            <div class="progress-label"><span>Progreso</span><strong>${progress}%</strong></div>
            <div class="progress-shell" role="progressbar" aria-label="Progreso de ${esc(ACTIVE_TEST.shortName)}" aria-valuemin="0" aria-valuemax="100" aria-valuenow="${progress}"><div class="progress-bar" style="width:${progress}%"></div></div>
            <div class="hero-actions">
              ${home ? home.action : result
                ? '<button class="solid-btn" type="button" data-route="resultados">Ver constancia de entrega</button>'
                : `<button class="solid-btn" type="button" data-action="start-test">${STATIC_PAGES_MODE ? 'Material disponible solo en el sistema' : count ? 'Continuar evaluación' : 'Comenzar evaluación'}</button>`}
            </div>
          </div>
        </div>
        <div class="hero-visual"><img src="assets/weil-brand-hero.webp" alt="Identidad visual de ${esc(ACTIVE_TEST.shortName)}" loading="lazy" decoding="async"></div>
      </section>
      <aside class="status-stack">
        <section class="panel next-step-panel">
          <p class="eyebrow">Siguiente paso</p>
          <h3>${home ? esc(home.title) : result ? 'Tu entrega está registrada' : count ? `Continuá desde el ítem ${appState.currentIndex + 1}` : 'Prepará tu espacio'}</h3>
          <p>${home ? esc(home.text) : result ? 'Tu constancia confirma que el equipo recibió las respuestas.' : 'Buscá un lugar sin interrupciones. El progreso se guarda automáticamente.'}</p>
          <div class="metric-strip">
            <div><strong>${count}</strong><span>${plural(count,'respondido','respondidos')}</span></div>
            <div><strong>${TOTAL_ITEMS - count}</strong><span>${plural(TOTAL_ITEMS - count,'pendiente','pendientes')}</span></div>
          </div>
        </section>
        <section class="panel readiness-panel">
          <h3>Antes de comenzar</h3>
          <div class="check-list">
            <div class="check-row"><span class="check-dot">1</span><span>Leé cada figura con calma.</span></div>
            <div class="check-row"><span class="check-dot">2</span><span>Respondé todos los ítems.</span></div>
            <div class="check-row"><span class="check-dot">3</span><span>Finalizá para registrar la entrega.</span></div>
          </div>
        </section>
      </aside>
    </div>`;
}

function pageAnswered(pageIndex) {
  return appState.answers.slice(pageIndex * ITEMS_PER_PAGE, (pageIndex + 1) * ITEMS_PER_PAGE).filter(value => value !== null).length;
}

function isExamLocked() {
  return appState.role === 'aspirante' && !!appState.startedAt && !appState.result && !attemptExpired();
}

function testRouteAvailable() {
  return appState.role === 'aspirante' && !!appState.startedAt && !appState.result && !attemptExpired();
}

function attemptExpired() {
  const deadline = appState.remoteAttempt?.deadlineAt;
  return !!deadline && Date.now() >= new Date(deadline).getTime();
}

function applyExamSecurityUi() {
  document.body.classList.toggle('exam-locked', isExamLocked());
}

function isFullscreenActive() {
  return !!(document.fullscreenElement || document.webkitFullscreenElement);
}

// iPhone y algunos navegadores no ofrecen pantalla completa: no se le exige ni se le anota como incidencia.
function fullscreenSupported() {
  const root = document.documentElement;
  return !!(root.requestFullscreen || root.webkitRequestFullscreen);
}

function recordSecurityEvent(type, detail = '') {
  if (!isExamLocked()) return;
  const event = {
    type,
    detail,
    at: new Date().toISOString()
  };
  appState.securityEvents = [...appState.securityEvents, event].slice(-SECURITY_EVENT_LIMIT);
  if (type === 'window-blur' || type === 'visibility-hidden' || type === 'fullscreen-exit') appState.focusLosses += 1;
  stateStore.publish('security', { securityEvent: event });
}

async function enterFullscreen() {
  if (!isExamLocked()) return;
  const root = document.documentElement;
  const request = root.requestFullscreen || root.webkitRequestFullscreen;
  if (!request) return;
  try {
    await request.call(root);
    toast('Modo examen activado en pantalla completa.', 'ok');
  } catch {
    recordSecurityEvent('fullscreen-denied', 'No se pudo activar pantalla completa.');
    toast('Activá pantalla completa para continuar en modo examen.', 'err');
  } finally {
    render();
  }
}

async function exitFullscreen() {
  const exit = document.exitFullscreen || document.webkitExitFullscreen;
  if (!exit || !isFullscreenActive()) return;
  try {
    await exit.call(document);
  } catch {}
}

function startSecureExamMode() {
  applyExamSecurityUi();
  enterFullscreen();
}

function endSecureExamMode() {
  applyExamSecurityUi();
  exitFullscreen();
}

function firstMissingIndex() {
  return appState.answers.findIndex(value => value === null);
}

function missingIndexes() {
  return appState.answers
    .map((answer, index) => answer === null ? index : null)
    .filter(index => index !== null);
}

function canAccessItem(index) {
  const firstPending = firstMissingIndex();
  const exposedThrough = firstPending < 0 ? TOTAL_ITEMS - 1 : Math.min(TOTAL_ITEMS - 1, firstPending + 1);
  return appState.answers[index] !== null || index <= exposedThrough;
}

function canNavigateTo(route) {
  if (appState.role === 'aspirante' && route === 'test' && !testRouteAvailable()) return false;
  return !isExamLocked() || route === 'test';
}

function renderTest() {
  const index = appState.currentIndex;
  const page = Math.floor(index / ITEMS_PER_PAGE);
  const local = index % ITEMS_PER_PAGE;
  const selected = appState.answers[index];
  const puzzle = puzzleFor(index);
  const progress = Math.round((answeredCount() / TOTAL_ITEMS) * 100);
  const securityEvents = appState.securityEvents.length;
  return `
    <div class="exam-head">
      <div>
        <p class="eyebrow">Aplicación activa</p>
        <h2>Ítem ${index + 1} de ${TOTAL_ITEMS}</h2>
        <p>Lámina ${page + 1} · Ítem ${local + 1} de ${ITEMS_PER_PAGE}</p>
      </div>
      <div class="exam-head__meta">
        <span class="chip lock-chip">Prueba bloqueada</span>
        ${fullscreenSupported() ? `<button class="chip security-chip ${isFullscreenActive() ? 'is-ok' : 'is-alert'}" type="button" data-action="restore-fullscreen">${isFullscreenActive() ? 'Pantalla completa' : 'Activar pantalla completa'}</button>` : ''}
        <span class="chip ${securityEvents ? 'security-chip is-alert' : 'security-chip'}">Incidencias ${securityEvents}</span>
        <span class="chip" id="test-clock">${elapsedLabel()}</span>
        <span class="chip">${progress}% completado</span>
      </div>
    </div>
    <div class="test-workbench">
      <section class="question-stage" aria-label="Ejercicio actual">
        <div class="stage-toolbar">
          <div>
            <strong>${esc(ACTIVE_TEST.sections[page].label)}</strong>
            <span>${pageAnswered(page)} de ${ITEMS_PER_PAGE} respondidos</span>
          </div>
          <div class="page-progress" aria-label="Cambiar lámina">${ACTIVE_TEST.sections.map((_, i) => `<button type="button" class="page-dot ${i === page ? 'is-active' : ''} ${pageAnswered(i) === ITEMS_PER_PAGE ? 'is-complete' : ''}" data-page="${i}" aria-label="Lámina ${i + 1}" ${canAccessItem(i * ITEMS_PER_PAGE) ? '' : 'disabled'}>${i + 1}</button>`).join('')}</div>
        </div>
        ${puzzle ? renderPuzzle(puzzle, index, local, page, selected) : '<div class="empty-state">No se pudo cargar el material de este ítem.</div>'}
        <div class="item-rail" aria-label="Ítems de la lámina">${Array.from({ length: ITEMS_PER_PAGE }, (_, i) => {
          const absolute = page * ITEMS_PER_PAGE + i;
          const answered = appState.answers[absolute] !== null;
          return `<button type="button" class="rail-btn ${absolute === index ? 'is-active' : ''} ${answered ? 'is-answered' : ''}" data-item="${absolute}" aria-label="Ítem ${i + 1}${answered ? ', respondido' : ''}" ${canAccessItem(absolute) ? '' : 'disabled'}>${i + 1}</button>`;
        }).join('')}</div>
      </section>

      <aside class="response-column" aria-label="Panel de respuesta">
        <section class="response-card">
          <div class="response-card__top">
            <div>
              <p class="eyebrow">Respuesta</p>
              <h3>${puzzle ? 'Completá la figura' : 'Seleccioná una opción'}</h3>
              <p class="response-hint">${puzzle ? 'Arrastrá la pieza que completa la figura hasta el hueco. También podés tocarla, o usar las teclas del 1 al 8.' : esc(ACTIVE_TEST.instructions)}</p>
            </div>
            <span class="selected-pill" aria-label="Respuesta seleccionada">${selected || '-'}</span>
          </div>
          ${puzzle ? `<button type="button" class="ghost-btn wide-btn puzzle-clear" data-action="clear-answer" ${selected ? '' : 'disabled'}>Quitar pieza</button>` : `<div class="choice-grid">${Array.from({ length: ACTIVE_TEST.optionCount }, (_, i) => {
            const option = i + 1;
            return `<button type="button" class="choice-btn ${selected === option ? 'is-selected' : ''}" data-option="${option}" aria-label="Responder opción ${option}">${option}</button>`;
          }).join('')}</div>`}
        </section>

        <section class="progress-card" aria-label="Avance de la prueba">
          <div class="progress-card__stats">
            <div><strong>${answeredCount()}</strong><span>${plural(answeredCount(),'respondido','respondidos')}</span></div>
            <div><strong>${TOTAL_ITEMS - answeredCount()}</strong><span>${plural(TOTAL_ITEMS - answeredCount(),'pendiente','pendientes')}</span></div>
          </div>
          <div class="progress-shell" aria-hidden="true"><div class="progress-bar" style="width:${progress}%"></div></div>
        </section>

        <section class="step-card">
          <div class="step-actions">
            <button class="ghost-btn" type="button" data-action="prev-item" ${index === 0 ? 'disabled' : ''}>Anterior</button>
            <button class="ghost-btn" type="button" data-action="next-item" ${index === TOTAL_ITEMS - 1 ? 'disabled' : ''}>Siguiente</button>
          </div>
          <button class="solid-btn wide-btn" type="button" data-action="finish-test">Finalizar y entregar</button>
          <button class="danger-btn wide-btn" type="button" data-action="reset-test">Reiniciar respuestas</button>
        </section>
      </aside>
    </div>`;
}

function renderPuzzle(puzzle, index, local, page, selected) {
  const label = `Ejercicio ${local + 1} de ${ACTIVE_TEST.sections[page].label}`;
  const placed = selected ? puzzle.pieces[selected - 1] : '';
  return `
        <figure class="question-figure puzzle">
          <div class="puzzle-stage">
            <div class="puzzle-board" style="--pw:${puzzle.width};--ph:${puzzle.height}">
              <img class="puzzle-base" src="${puzzle.base}" alt="${esc(label)}: figura incompleta" width="${puzzle.width}" height="${puzzle.height}" decoding="async" fetchpriority="high" draggable="false">
              <div class="puzzle-slot ${selected ? 'is-filled' : ''}" data-slot style="${window.SAP_UAM_PUZZLE_VIEW.slotStyle(puzzle)}" aria-label="Hueco de la figura${selected ? `: pieza ${selected}` : ' vacío'}">
                <img class="puzzle-placed" src="${placed}" alt="" draggable="false" ${selected ? `data-slot-piece="${selected}"` : 'hidden'}>
              </div>
            </div>
          </div>
          <div class="puzzle-tray" role="group" aria-label="Piezas disponibles">${puzzle.pieces.map((src, i) => `
            <button type="button" class="puzzle-piece ${selected === i + 1 ? 'is-used' : ''}" data-piece="${i + 1}" data-option="${i + 1}" aria-label="Pieza ${i + 1}${selected === i + 1 ? ', colocada' : ''}">
              <img src="${src}" alt="" draggable="false" decoding="async">
            </button>`).join('')}
          </div>
        </figure>`;
}

function syncPuzzleView() {
  const slot = $('[data-slot]');
  if (!slot) return;
  const selected = appState.answers[appState.currentIndex];
  const placed = slot.querySelector('.puzzle-placed');
  const piece = selected ? $(`.puzzle-piece[data-piece="${selected}"] img`) : null;
  if (placed) {
    if (piece) {
      placed.src = piece.currentSrc || piece.src;
      placed.dataset.slotPiece = selected;
      placed.hidden = false;
    } else {
      placed.hidden = true;
      placed.removeAttribute('data-slot-piece');
    }
  }
  slot.classList.toggle('is-filled', !!piece);
  slot.setAttribute('aria-label', piece ? `Hueco de la figura: pieza ${selected}` : 'Hueco de la figura vacío');
  $$('.puzzle-piece').forEach(button => {
    const used = Number(button.dataset.piece) === selected;
    button.classList.toggle('is-used', used);
    button.setAttribute('aria-label', `Pieza ${button.dataset.piece}${used ? ', colocada' : ''}`);
  });
  const clear = $('.puzzle-clear');
  if (clear) clear.disabled = !selected;
}

function clearAnswer() {
  if (appState.role !== 'aspirante' || appState.route !== 'test') return;
  if (attemptExpired()) return toast('El tiempo de la prueba terminó.', 'err');
  if (appState.answers[appState.currentIndex] === null) return;
  appState.answers[appState.currentIndex] = null;
  playSound('tap');
  stateStore.publish('answer', { index: appState.currentIndex, option: null });
}

function prepareCurrentItemMedia() {
  const puzzleBase = $('.puzzle-base');
  if (puzzleBase) {
    const board = puzzleBase.closest('.puzzle-board');
    const ready = () => board?.classList.add('is-ready');
    if (puzzleBase.complete && puzzleBase.naturalWidth) ready();
    else {
      puzzleBase.addEventListener('load', ready, { once: true });
      puzzleBase.addEventListener('error', () => board?.classList.add('is-error'), { once: true });
    }
  }
  const currentImage = puzzleBase || $('.question-figure img');
  if (currentImage && !currentImage.complete) {
    currentImage.closest('.question-figure')?.classList.add('is-loading');
    currentImage.addEventListener('load', () => currentImage.closest('.question-figure')?.classList.remove('is-loading'), { once: true });
    currentImage.addEventListener('error', () => currentImage.closest('.question-figure')?.classList.add('is-error'), { once: true });
  }
  const nextIndex = appState.currentIndex + 1;
  if (nextIndex < TOTAL_ITEMS) {
    const nextPuzzle = puzzleFor(nextIndex);
    (nextPuzzle ? [nextPuzzle.base, ...nextPuzzle.pieces] : []).forEach(src => {
      const nextImage = new Image();
      nextImage.decoding = 'async';
      nextImage.src = src;
    });
  }
}

function updateTestViewAfterAnswer() {
  if (appState.route !== 'test') return;
  const index = appState.currentIndex;
  const selected = appState.answers[index];
  syncPuzzleView();
  $$('.choice-btn').forEach(button => button.classList.toggle('is-selected', Number(button.dataset.option) === selected));
  const selectedPill = $('.selected-pill');
  if (selectedPill) selectedPill.textContent = selected || '-';
  const answered = answeredCount();
  const progress = Math.round((answered / TOTAL_ITEMS) * 100);
  const stats = $$('.progress-card__stats strong');
  if (stats[0]) stats[0].textContent = answered;
  if (stats[1]) stats[1].textContent = TOTAL_ITEMS - answered;
  const bar = $('.progress-card .progress-bar');
  if (bar) bar.style.width = `${progress}%`;
  const progressChip = $$('.exam-head__meta .chip').at(-1);
  if (progressChip) progressChip.textContent = `${progress}% completado`;
  const railButton = $(`.rail-btn[data-item="${index}"]`);
  railButton?.classList.toggle('is-answered', selected !== null);
  railButton?.setAttribute('aria-label', `Ítem ${(index % ITEMS_PER_PAGE) + 1}${selected !== null ? ', respondido' : ''}`);
  const sectionCount = $('.stage-toolbar span');
  if (sectionCount) sectionCount.textContent = `${pageAnswered(Math.floor(index / ITEMS_PER_PAGE))} de ${ITEMS_PER_PAGE} respondidos`;
}

function elapsedLabel() {
  const deadline = appState.remoteAttempt?.deadlineAt;
  if (!deadline) return 'Tiempo no disponible';
  const remaining = Math.max(0, (new Date(deadline).getTime() - Date.now()) / 1000);
  return `Restan ${formatDuration(remaining)}`;
}

function renderResults() {
  const result = appState.result;
  if (!result) {
    return `<div class="screen-title"><div><p class="eyebrow">Entrega</p><h2>Prueba pendiente</h2><p>La constancia se habilita automáticamente cuando registrás la entrega.</p></div></div><section class="empty-state empty-state--action"><div><p class="empty-state__code">ENTREGA / 00</p><h3>Todavía no hay una entrega registrada</h3><p>Respondé todos los ítems y finalizá la prueba para generar tu constancia.</p></div><button class="solid-btn" type="button" data-action="start-test">Ir a la prueba</button></section>`;
  }
  const receipt = appState.remoteAttempt?.id ? `N.º ${String(appState.remoteAttempt.id).padStart(6, '0')}` : '';
  const answered = result.answeredCount ?? result.total;
  return `
    <div class="screen-title">
      <div><p class="eyebrow">Entrega</p><h2>Prueba entregada</h2><p>Gracias, ${esc(String(result.profile.fullName).split(' ')[0])}.</p></div>
    </div>
    <section class="panel confirmation-card" aria-live="polite">
      <div class="confirmation-mark" aria-hidden="true">✓</div>
      <h3>Recibimos tus respuestas</h3>
      <p>El equipo de Admisión revisará tu evaluación y te comunicará los resultados por los medios oficiales de la universidad. No necesitas hacer nada más.</p>
      <dl class="confirmation-meta">
        <div><dt>Evaluación</dt><dd>${esc(result.testName || ACTIVE_TEST.shortName)}</dd></div>
        <div><dt>Entregada</dt><dd>${formatDate(result.endedAt)}</dd></div>
        <div><dt>Respondiste</dt><dd>${answered} de ${result.total} ítems</dd></div>
        <div><dt>Tiempo</dt><dd>${formatDuration(result.durationSec)}</dd></div>
        ${receipt ? `<div><dt>Comprobante</dt><dd>${receipt}</dd></div>` : ''}
      </dl>
      <div class="confirmation-actions">
        <button class="ghost-btn" type="button" data-route="inicio">Volver al inicio</button>
        <button class="solid-btn" type="button" data-action="logout">Cerrar sesión</button>
      </div>
    </section>`;
}

function renderProfile() {
  const profile = appState.profile;
  const result = appState.result;
  const readableName = displayPersonName(profile.fullName);
  const career = normalizeCareer(profile.carrera) || 'Sin registrar';
  const progress = `${answeredCount()}/${TOTAL_ITEMS}`;
  const state = result ? 'Entregada' : answeredCount() ? 'En progreso' : 'Sin iniciar';
  return `
    <div class="screen-title"><div><p class="eyebrow">Perfil</p><h2>Datos del aspirante</h2><p>Estos son los datos con los que te registraste.</p></div></div>
    <div class="profile-grid">
      <section class="panel profile-card">
        <div class="avatar" aria-hidden="true">${esc(initials(readableName))}</div>
        <div><p class="profile-card__eyebrow">Expediente del aspirante</p><h3>${esc(readableName)}</h3><p class="muted">${esc(identityLabel(profile))}</p></div>
        <div class="profile-card__actions"><span class="pill ${result ? 'good' : 'warn'}">${state}</span><button class="solid-btn" type="button" data-action="start-test">${answeredCount() ? 'Continuar prueba' : 'Iniciar prueba'}</button></div>
      </section>
      <section class="panel">
        <h3>Registro</h3>
        <div class="detail-list">
          <div class="detail-row"><span>Carrera</span><strong>${esc(career)}</strong></div>
          <div class="detail-row"><span>Edad</span><strong>${esc(profile.edad)}</strong></div>
          <div class="detail-row"><span>Avance</span><strong>${progress}</strong></div>
          <div class="detail-row"><span>Estado</span><strong>${state}</strong></div>
        </div>
      </section>
    </div>`;
}

function renderSettings() {
  const profile = appState.profile || {};
  return `
    <div class="screen-title"><div><p class="eyebrow">Ajustes</p><h2>Tu entorno de evaluación</h2><p>Personalizá la experiencia en este navegador sin afectar tu prueba.</p></div></div>
    <div class="settings-grid">
      ${appState.role === 'aspirante' ? `<section class="panel settings-profile-panel"><h3>Datos del aspirante</h3><div class="settings-profile-form">
        <label class="setting-row setting-row--field"><div><strong>Carrera</strong><small>Se llena desde Moodle si está disponible; podés completarla manualmente.</small></div><input class="profile-input" type="text" data-profile-field="carrera" value="${esc(normalizeCareer(profile.carrera))}" placeholder="Ej. Medicina General"></label>
      </div></section>` : ''}
      <section class="panel"><div class="settings-panel__head"><div><h3>Experiencia</h3><p class="muted tiny">Los cambios se guardan en este navegador.</p></div><span class="settings-panel__status">LOCAL</span></div><div class="detail-list">
        ${settingSwitch('dark', 'Modo oscuro', 'Alto contraste para ambientes con poca luz.')}
        ${settingSwitch('compact', 'Modo compacto', 'Reduce espacios verticales.')}
        ${settingSwitch('sound', 'Sonidos de interfaz', 'Activa señales breves al responder, avanzar y finalizar.')}
        ${settingSwitch('autoAdvance', 'Avance automático', 'Pasa al siguiente ítem al responder.')}
      </div></section>
      <section class="panel settings-session-panel"><h3>Sesión de evaluación</h3>
        <p class="muted">El tiempo oficial lo controla el servidor y continúa aunque recargués la página.</p>
        <div class="divider"></div>
        <p class="settings-warning">reiniciar elimina las respuestas guardadas en este intento.</p>
        <button class="danger-btn" type="button" data-action="reset-test">Reiniciar respuestas del aspirante</button>
      </section>
      ${passwordPanel()}
    </div>`;
}

function renderAdminSettings() {
  const mfa = appState.sessionUser?.mfaEnabled ? 'Configurado' : 'Pendiente';
  return `
    <div class="screen-title"><div><p class="eyebrow">Administración</p><h2>Configuración</h2><p>Preferencias locales y accesos de control para esta cuenta administrativa.</p></div></div>
    <div class="settings-grid">
      <section class="panel settings-profile-panel"><div class="settings-panel__head"><div><h3>Cuenta administrativa</h3><p class="muted tiny">${esc(appState.sessionUser?.email || '')}</p></div><span class="settings-panel__status">${esc(mfa)}</span></div>
        <div class="detail-list">
          <div class="detail-row"><span>Segundo factor</span><strong>${esc(mfa)}</strong></div>
          <div class="detail-row"><span>Permiso activo</span><strong>Administración</strong></div>
        </div>
        <div class="row-actions"><button class="ghost-btn" type="button" data-route="usuarios">Gestionar personal</button><button class="ghost-btn" type="button" data-route="auditoria">Ver auditoría</button></div>
      </section>
      <section class="panel"><div class="settings-panel__head"><div><h3>Experiencia local</h3><p class="muted tiny">Estos cambios solo afectan este navegador.</p></div><span class="settings-panel__status">LOCAL</span></div><div class="detail-list">
        ${settingSwitch('dark', 'Modo oscuro', 'Alto contraste para ambientes con poca luz.')}
        ${settingSwitch('compact', 'Modo compacto', 'Reduce espacios verticales.')}
        ${settingSwitch('sound', 'Sonidos de interfaz', 'Activa señales breves al operar el sistema.')}
      </div></section>
      <section class="panel settings-session-panel"><h3>Operación segura</h3>
        <p class="muted">Las reglas de evaluación, tiempos y baremos se controlan en el servidor. No se modifican desde preferencias locales.</p>
        <div class="divider"></div>
        <p class="settings-warning">los cambios de roles, convocatorias y baremos quedan registrados en auditoría.</p>
      </section>
      ${passwordPanel()}
    </div>`;
}

function passwordPanel() {
  // Las cuentas UAM se autentican con Moodle: su contraseña no se cambia aquí.
  if (!appState.sessionUser || appState.sessionUser.authProvider !== 'manual') return '';
  const min = appState.sessionUser.role === 'CLIENTE' ? 8 : 12;
  return `<section class="panel"><div class="settings-panel__head"><div><h3>Contraseña</h3><p class="muted tiny">Al cambiarla se cierran tus otras sesiones.</p></div></div>
    <form id="form-password" class="settings-profile-form" autocomplete="off" novalidate>
      <label class="setting-row setting-row--field"><div><strong>Contraseña actual</strong></div><input name="currentPassword" type="password" autocomplete="current-password" required></label>
      <label class="setting-row setting-row--field"><div><strong>Contraseña nueva</strong><small>Mínimo ${min} caracteres.</small></div><input name="newPassword" type="password" autocomplete="new-password" minlength="${min}" maxlength="72" required></label>
      <label class="setting-row setting-row--field"><div><strong>Repetir contraseña nueva</strong></div><input name="confirmPassword" type="password" autocomplete="new-password" required></label>
      <p id="password-error" class="form-error" role="alert"></p>
      <div class="row-actions"><button class="solid-btn" type="submit">Cambiar contraseña</button></div>
    </form></section>`;
}

async function submitPasswordChange(form) {
  const error = $('#password-error');
  const data = new FormData(form);
  const currentPassword = String(data.get('currentPassword') || '');
  const newPassword = String(data.get('newPassword') || '');
  const min = appState.sessionUser?.role === 'CLIENTE' ? 8 : 12;
  const fail = message => { if (error) error.textContent = message; };
  if (error) error.textContent = '';
  if (!currentPassword) return fail('Escribe tu contraseña actual.');
  if (newPassword.length < min) return fail(`La contraseña nueva debe tener al menos ${min} caracteres.`);
  if (newPassword !== String(data.get('confirmPassword') || '')) return fail('Las contraseñas nuevas no coinciden.');
  form.setAttribute('aria-busy', 'true');
  try {
    await apiRequest('/api/auth/password', { currentPassword, newPassword });
    form.reset();
    toast('Contraseña actualizada.', 'ok');
  } catch (failure) {
    fail(failure.message || 'No se pudo cambiar la contraseña.');
  } finally {
    form.setAttribute('aria-busy', 'false');
  }
}

function settingSwitch(key, title, description) {
  const checked = Boolean(appState.settings[key]);
  const status = checked ? 'Activo' : 'Inactivo';
  return `<button class="setting-row setting-row--toggle" type="button" role="switch" aria-label="${esc(title)}: ${status.toLowerCase()}" aria-checked="${checked}" data-action="toggle-setting" data-setting="${key}"><span><strong>${title}</strong><small>${description}</small></span><span class="setting-state" aria-hidden="true">${status}</span><span class="switch" aria-hidden="true"><span class="${checked ? 'is-checked' : ''}"></span></span></button>`;
}

function renderStaff() {
  const content = $('#app-content');
  if (!content) return;
  if (!STATIC_PAGES_MODE && ['panel', 'aspirantes'].includes(appState.route)) loadStaffAttempts();
  if (!STATIC_PAGES_MODE && ['convocatorias', 'aspirantes'].includes(appState.route)) loadSessions();
  if (!STATIC_PAGES_MODE && appState.route === 'baremo') loadStaffNorms();
  if (appState.route === 'usuarios' && appState.sessionUser?.role === 'ADMIN') {
    loadAdminData('users');
    content.innerHTML = renderAdminUsers();
  } else if (appState.route === 'auditoria' && appState.sessionUser?.role === 'ADMIN') {
    loadAdminData('audit');
    content.innerHTML = renderAdminAudit();
  } else if (appState.route === 'convocatorias') content.innerHTML = ADM.sessionsView(appState.sessions, appState.sessionsError);
  else if (appState.route === 'convocatoria') content.innerHTML = ADM.enrollmentsView(appState.sessionDetail?.session, appState.sessionDetail?.rows, appState.sessionDetail?.lastResult, appState.sessionDetail?.error);
  else if (appState.route === 'aspirantes') content.innerHTML = renderStaffApplicants();
  else if (appState.route === 'baremo') content.innerHTML = renderBaremo();
  else if (appState.route === 'ajustes') content.innerHTML = appState.sessionUser?.role === 'ADMIN' ? renderAdminSettings() : renderSettings();
  else content.innerHTML = renderStaffPanel();
}

function renderAdminUsers() {
  const rows = appState.adminUsers;
  return `
    <div class="screen-title">
      <div><p class="eyebrow">Administración</p><h2>Personal autorizado</h2><p>Creá cuentas, asigná permisos y cortá el acceso sin editar la base de datos.</p></div>
      <button class="ghost-btn" type="button" data-action="reload-admin-users">Actualizar</button>
    </div>
    <div class="admin-layout">
      <section class="panel admin-create">
        <h3>Nueva cuenta</h3>
        <form id="form-admin-user" class="admin-form">
          <label><span>Nombre completo</span><input name="fullName" type="text" minlength="5" maxlength="160" autocomplete="name" required></label>
          <label><span>Correo institucional</span><input name="email" type="email" maxlength="160" autocomplete="email" required></label>
          <label><span>Contraseña temporal</span><input name="temporaryPassword" type="password" minlength="12" maxlength="128" autocomplete="new-password" aria-describedby="admin-password-help" required></label>
          <small id="admin-password-help">Mínimo 12 caracteres. El segundo factor se configura en el primer acceso.</small>
          <label><span>Rol</span><select name="role"><option value="STAFF">Personal</option><option value="ADMIN">Administración</option></select></label>
          <p id="admin-form-error" class="form-error" role="alert"></p>
          <button class="solid-btn" type="submit">Crear cuenta</button>
        </form>
      </section>
      <section class="table-wrap admin-table-wrap">
        ${appState.adminError ? `<div class="empty-state">${esc(appState.adminError)}</div>` : !rows ? '<div class="empty-state" role="status">Cargando personal…</div>' : renderAdminUsersTable(rows)}
      </section>
    </div>`;
}

function renderAdminUsersTable(rows) {
  if (!rows.length) return '<div class="empty-state">No hay cuentas de personal.</div>';
  return `<table class="data-table admin-table">
    <caption class="sr-only">Cuentas de personal autorizado</caption>
    <thead><tr><th scope="col">Persona</th><th scope="col">Rol</th><th scope="col">Segundo factor</th><th scope="col">Estado</th><th scope="col">Acciones</th></tr></thead>
    <tbody>${rows.map(user => {
      const own = String(user.id) === String(appState.sessionUser?.id);
      const lock = own ? 'disabled title="Es tu propia cuenta"' : '';
      return `<tr>
      <td><strong>${esc(user.fullName)}</strong>${own ? ' <span class="pill">Tu cuenta</span>' : ''}<br><span class="muted tiny">${esc(user.email)}</span></td>
      <td><label class="sr-only" for="role-${user.id}">Rol de ${esc(user.fullName)}</label><select id="role-${user.id}" class="table-select" data-admin-role="${user.id}" ${lock}><option value="STAFF" ${user.role === 'STAFF' ? 'selected' : ''}>Personal</option><option value="ADMIN" ${user.role === 'ADMIN' ? 'selected' : ''}>Administración</option></select></td>
      <td><span class="pill ${user.mfaEnabled ? 'good' : 'warn'}">${user.mfaEnabled ? 'Configurado' : 'Pendiente'}</span></td>
      <td><span class="pill ${user.active ? 'good' : 'warn'}">${user.active ? 'Activa' : 'Desactivada'}</span></td>
      <td><div class="row-actions">
        <button class="ghost-btn" type="button" data-action="save-staff-access" data-user-id="${user.id}" data-next-active="${user.active}" ${lock}>Guardar rol</button>
        <button class="${user.active ? 'danger-btn' : 'solid-btn'}" type="button" data-action="toggle-staff-access" data-user-id="${user.id}" data-next-active="${!user.active}" ${lock}>${user.active ? 'Desactivar' : 'Activar'}</button>
        <button class="ghost-btn" type="button" data-action="reset-staff-mfa" data-user-id="${user.id}" ${lock}>Restablecer MFA</button>
      </div></td>
    </tr>`;
    }).join('')}</tbody>
  </table>`;
}

function renderAdminAudit() {
  const rows = appState.adminAudit;
  return `
    <div class="screen-title">
      <div><p class="eyebrow">Trazabilidad</p><h2>Registro de auditoría</h2><p>Últimos 200 accesos y cambios administrativos. No se pueden editar desde el sistema.</p></div>
      <button class="ghost-btn" type="button" data-action="reload-admin-audit">Actualizar</button>
    </div>
    ${appState.adminError ? `<div class="empty-state">${esc(appState.adminError)}</div>` : !rows ? '<div class="empty-state" role="status">Cargando auditoría…</div>' : renderAuditTable(rows)}`;
}

function renderAuditTable(rows) {
  if (!rows.length) return '<div class="empty-state">Todavía no hay eventos de auditoría.</div>';
  return `<section class="table-wrap"><table class="data-table audit-table">
    <caption class="sr-only">Registro de auditoría</caption>
    <thead><tr><th scope="col">Fecha</th><th scope="col">Responsable</th><th scope="col">Acción</th><th scope="col">Recurso</th><th scope="col">Detalle</th></tr></thead>
    <tbody>${rows.map(event => `<tr><td>${formatDate(event.occurredAt)}</td><td>${esc(event.actorName)}</td><td><span class="pill">${esc(event.action)}</span></td><td>${esc(event.targetType)} ${esc(event.targetId || '')}</td><td>${esc(event.detail || '—')}</td></tr>`).join('')}</tbody>
  </table></section>`;
}

function staffStatusCard() {
  if (appState.staffError) {
    return `<div class="empty-state">${esc(appState.staffError)}<br><button class="ghost-btn" type="button" data-action="reload-staff">Reintentar</button></div>`;
  }
  return '<div class="empty-state">Cargando resultados…</div>';
}

function renderStaffPanel() {
  if (STATIC_PAGES_MODE) {
    return '<div class="empty-state">El panel del personal requiere conexión segura con el servidor.</div>';
  }
  const rows = appState.staffAttempts;
  const avg = rows?.length ? Math.round(rows.reduce((sum, item) => sum + item.directScore, 0) / rows.length) : '--';
  const top = rows?.length ? Math.max(...rows.map(item => item.directScore)) : '--';
  return `
    <div class="screen-title"><div><p class="eyebrow">${appState.sessionUser?.role === 'ADMIN' ? 'Administración' : 'Personal'}</p><h2>Panel de aplicaciones</h2><p>${esc(appState.profile.username)}</p></div><button class="solid-btn" type="button" data-route="aspirantes">Ver aspirantes</button></div>
    <div class="staff-grid">
      <div class="metric"><strong>${rows ? rows.length : '--'}</strong><span>pruebas entregadas</span></div>
      <div class="metric"><strong>${avg}</strong><span>promedio directo</span></div>
      <div class="metric"><strong>${top}</strong><span>mejor puntaje</span></div>
    </div>
    ${rows ? renderStaffAttemptsTable(rows.slice(0, 8)) : staffStatusCard()}`;
}

function renderStaffApplicants() {
  if (STATIC_PAGES_MODE) {
    return '<div class="empty-state">Los resultados solo están disponibles desde el servidor institucional.</div>';
  }
  const rows = appState.staffAttempts;
  return `
    <div class="screen-title"><div><p class="eyebrow">Aspirantes</p><h2>Resultados</h2><p>${rows ? `${rows.length} pruebas con los filtros actuales.` : 'Pruebas entregadas por los aspirantes.'}</p></div><button class="ghost-btn" type="button" data-action="reload-staff">Actualizar</button></div>
    ${ADM.resultsToolbar(appState.sessions, appState.resultsFilters)}
    ${rows ? ADM.attemptsTable(rows, TOTAL_ITEMS) : staffStatusCard()}`;
}

function renderStaffAttemptsTable(rows) {
  if (!rows.length) return '<div class="empty-state">Todavía no hay pruebas entregadas.</div>';
  return `<section class="table-wrap"><table class="data-table"><caption class="sr-only">Resultados de aspirantes</caption><thead><tr><th scope="col">Aspirante</th><th scope="col">Carrera</th><th scope="col">Puntaje</th><th scope="col">Percentil</th><th scope="col">CI</th><th scope="col">Clasificación</th><th scope="col">Incidencias</th><th scope="col">Entregada</th><th scope="col">Tiempo</th></tr></thead><tbody>${rows.map(item => `<tr><td><strong>${esc(item.fullName)}</strong><br><span class="muted tiny">${esc(item.email || '')}</span></td><td>${esc(normalizeCareer(item.career))}</td><td>${item.directScore}/${TOTAL_ITEMS}</td><td>${item.percentile}</td><td>${item.ci}</td><td>${esc(item.classification)}</td><td>${renderSecurityEvents(item.securityEvents)}</td><td>${formatDate(item.submittedAt)}</td><td>${formatDuration(item.durationSeconds)}</td></tr>`).join('')}</tbody></table></section>`;
}

function renderSecurityEvents(events = []) {
  if (!events.length) return '0';
  return `<details><summary>${events.length}</summary>${events.map(event => `<p class="tiny"><strong>${esc(event.type)}</strong><br>${esc(event.detail || 'Sin detalle')}<br>${formatDate(event.occurredAt)}</p>`).join('')}</details>`;
}

function renderBaremo() {
  const columns = ['7','8','9','10','11','12','13','14','15','16','AN','AD','UN'];
  if (appState.staffNormsError) return `<div class="empty-state">${esc(appState.staffNormsError)}<br><button class="ghost-btn" type="button" data-action="reload-norms">Reintentar</button></div>`;
  if (!appState.staffNorms) return '<div class="empty-state">Cargando baremo…</div>';
  return `
    <div class="screen-title"><div><p class="eyebrow">Baremo</p><h2>Tabla de calificación</h2><p>Valores servidos exclusivamente al personal autorizado.</p></div></div>
    <div class="baremo-table-wrap"><table class="data-table baremo-table"><caption class="sr-only">Baremo de calificación por grupo</caption><thead><tr><th scope="col">Percentil</th>${columns.map(col => `<th scope="col">${col}</th>`).join('')}</tr></thead><tbody>${appState.staffNorms.map(row => `<tr><td>${row.p}</td>${columns.map(col => `<td>${row[col]}</td>`).join('')}</tr>`).join('')}</tbody></table></div>`;
}

function downloadResultPdf(result = appState.result) {
  if (!result) {
    playSound('error');
    toast('No hay resultado para descargar.', 'err');
    return;
  }
  const pdf = buildResultPdf(result);
  const blob = new Blob([pdf], { type: 'application/pdf' });
  const url = URL.createObjectURL(blob);
  const link = document.createElement('a');
  link.href = url;
  link.download = `Reporte-Weill-${safeFileName(result.profile.cif)}.pdf`;
  document.body.appendChild(link);
  link.click();
  link.remove();
  setTimeout(() => URL.revokeObjectURL(url), 1000);
  playSound('success');
  toast('Reporte PDF descargado.', 'ok');
}

function safeFileName(value) {
  return String(value || 'resultado').replace(/[^a-zA-Z0-9._-]+/g, '-');
}

function buildResultPdf(result) {
  return createPdfDocument([buildPdfSummaryPage(result)]);
}

function pdfHex(text) {
  const value = String(text ?? '');
  let hex = 'FEFF';
  for (let i = 0; i < value.length; i += 1) {
    const code = value.charCodeAt(i);
    hex += code.toString(16).padStart(4, '0').toUpperCase();
  }
  return hex;
}

function pdfText(x, y, size, text, font = 'F1', color = [0.07, 0.08, 0.09]) {
  return `${color.join(' ')} rg BT /${font} ${size} Tf 1 0 0 1 ${x} ${y} Tm <${pdfHex(text)}> Tj ET\n`;
}

function pdfRect(x, y, w, h, fill = [1, 1, 1], stroke = null, lineWidth = 1) {
  const fillCmd = `${fill.join(' ')} rg ${x} ${y} ${w} ${h} re f\n`;
  if (!stroke) return fillCmd;
  return `${fillCmd}${stroke.join(' ')} RG ${lineWidth} w ${x} ${y} ${w} ${h} re S\n`;
}

function pdfLine(x1, y1, x2, y2, color = [0.82, 0.85, 0.86], lineWidth = 1) {
  return `${color.join(' ')} RG ${lineWidth} w ${x1} ${y1} m ${x2} ${y2} l S\n`;
}

function buildPdfSummaryPage(result) {
  let out = '';
  out += pdfRect(0, 0, 595, 842, [0.98, 0.97, 0.94]);
  out += pdfRect(0, 772, 595, 70, [0, 0.39, 0.43]);
  out += pdfText(42, 810, 16, 'SAP-UAM', 'F2', [1, 1, 1]);
  out += pdfText(42, 790, 10, 'Test de Inteligencia No Verbal de Weil - Forma A', 'F1', [0.87, 0.95, 0.96]);
  out += pdfText(404, 810, 10, `Reporte generado: ${formatDate(result.endedAt)}`, 'F1', [1, 1, 1]);
  out += pdfText(42, 734, 24, 'Constancia de entrega', 'F2');
  out += pdfText(42, 712, 10, 'Comprobante de recepcion de respuestas. No contiene resultados psicometricos.', 'F1', [0.39, 0.43, 0.46]);
  out += pdfRect(42, 628, 511, 62, [1, 1, 1], [0.82, 0.85, 0.86]);
  out += pdfText(58, 670, 9, 'Aspirante', 'F2', [0, 0.39, 0.43]);
  out += pdfText(58, 650, 13, result.profile.fullName, 'F2');
  out += pdfText(304, 670, 9, 'CIF', 'F2', [0, 0.39, 0.43]);
  out += pdfText(304, 650, 12, result.profile.cif, 'F1');
  out += pdfText(398, 670, 9, 'Carrera', 'F2', [0, 0.39, 0.43]);
  out += pdfText(398, 650, 10, normalizeCareer(result.profile.carrera), 'F1');
  out += pdfText(42, 594, 12, 'Resumen de la entrega', 'F2');
  const cards = [
    ['Estado', 'Entregada'],
    ['Respondidos', `${result.answeredCount}/${result.total}`],
    ['Duracion', formatDuration(result.durationSec)],
    ['Fecha', formatDate(result.endedAt)]
  ];
  cards.forEach((card, i) => {
    const x = 42 + i * 128;
    out += pdfRect(x, 520, 112, 54, i === 0 ? [0.07, 0.08, 0.09] : [1, 1, 1], [0.82, 0.85, 0.86]);
    out += pdfText(x + 10, 556, 8, card[0], 'F2', i === 0 ? [0.86, 0.70, 0.16] : [0, 0.39, 0.43]);
    out += pdfText(x + 10, 532, 18, card[1], 'F2', i === 0 ? [1, 1, 1] : [0.07, 0.08, 0.09]);
  });
  out += pdfRect(42, 458, 511, 38, [1, 1, 1], [0.82, 0.85, 0.86]);
  out += pdfText(58, 482, 9, 'Evaluacion', 'F2', [0, 0.39, 0.43]);
  out += pdfText(58, 466, 12, result.testName || ACTIVE_TEST.shortName, 'F2');
  out += pdfText(42, 424, 12, 'Siguiente paso', 'F2');
  out += pdfText(42, 400, 9, 'El equipo de Admision revisara la evaluacion y comunicara los resultados por los medios oficiales.', 'F1', [0.39, 0.43, 0.46]);
  out += pdfText(42, 188, 9, 'Nota tecnica', 'F2', [0, 0.39, 0.43]);
  out += pdfText(42, 170, 8, 'Esta constancia confirma la entrega. No presenta puntajes, claves ni interpretaciones.', 'F1', [0.39, 0.43, 0.46]);
  out += pdfLine(42, 54, 553, 54);
  out += pdfText(42, 36, 8, 'Universidad Americana - Sistema Digital de Aplicacion', 'F1', [0.39, 0.43, 0.46]);
  out += pdfText(516, 36, 8, 'Pagina 1/1', 'F1', [0.39, 0.43, 0.46]);
  return out;
}

function buildPdfAnswersPage(result) {
  return buildPdfSummaryPage(result);
}

function createPdfDocument(pageContents) {
  const objects = [];
  objects[1] = '<< /Type /Catalog /Pages 2 0 R >>';
  objects[3] = '<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>';
  objects[4] = '<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica-Bold >>';
  const pageIds = [];
  let id = 5;
  pageContents.forEach(content => {
    const contentId = id++;
    const pageId = id++;
    objects[contentId] = `<< /Length ${content.length} >>\nstream\n${content}endstream`;
    objects[pageId] = `<< /Type /Page /Parent 2 0 R /MediaBox [0 0 595 842] /Resources << /Font << /F1 3 0 R /F2 4 0 R >> >> /Contents ${contentId} 0 R >>`;
    pageIds.push(pageId);
  });
  objects[2] = `<< /Type /Pages /Kids [${pageIds.map(pageId => `${pageId} 0 R`).join(' ')}] /Count ${pageIds.length} >>`;
  let pdf = '%PDF-1.4\n';
  const offsets = [0];
  for (let i = 1; i < objects.length; i += 1) {
    offsets[i] = pdf.length;
    pdf += `${i} 0 obj\n${objects[i]}\nendobj\n`;
  }
  const xref = pdf.length;
  pdf += `xref\n0 ${objects.length}\n0000000000 65535 f \n`;
  for (let i = 1; i < objects.length; i += 1) {
    pdf += `${String(offsets[i]).padStart(10, '0')} 00000 n \n`;
  }
  pdf += `trailer\n<< /Size ${objects.length} /Root 1 0 R >>\nstartxref\n${xref}\n%%EOF`;
  return pdf;
}

async function startTest() {
  if (STATIC_PAGES_MODE) {
    toast('La demostración pública no contiene material real ni calificación.', 'info');
    return;
  }
  if (appState.result) {
    routeTo('resultados');
    return;
  }
  if (appState.startBusy) return;
  if (appState.availability?.needsConsent) {
    openConsent();
    return;
  }
  appState.startBusy = true;
  const startButton = $('[data-action="start-test"]');
  if (startButton) {
    startButton.disabled = true;
    startButton.textContent = 'Reservando intento...';
  }
  try {
    await openRemoteAttempt();
  } catch (error) {
    playSound('error');
    toast(error.message || 'No se pudo reservar el intento.', 'err');
    if (error.code) loadAvailability();
    if (error.code === 'CONSENT_REQUIRED') openConsent();
    return;
  } finally {
    appState.startBusy = false;
    if (startButton?.isConnected) {
      startButton.disabled = false;
      startButton.textContent = answeredCount() ? 'Continuar evaluación' : 'Comenzar evaluación';
    }
  }
  appState.focusLosses = 0;
  appState.securityEvents = [];
  playSound('start');
  routeTo('test');
  if (isExamLocked()) startSecureExamMode();
  stateStore.publish('assessment-navigation', { reason: 'start' });
}

function selectAnswer(option) {
  if (appState.role !== 'aspirante' || appState.route !== 'test') return;
  if (attemptExpired()) return toast('El tiempo de la prueba terminó.', 'err');
  if (!Number.isInteger(option) || option < 1 || option > ACTIVE_TEST.optionCount) return;
  const index = appState.currentIndex;
  appState.answers[index] = option;
  playSound('tap');
  stateStore.publish('answer', { index, option });
  if (appState.settings.autoAdvance && appState.currentIndex < TOTAL_ITEMS - 1) {
    appState.currentIndex += 1;
    stateStore.publish('assessment-navigation', { reason: 'auto-advance' });
  }
}

function requestFinishTest() {
  if (attemptExpired()) {
    toast('El tiempo de la prueba terminó. No se aceptaron más cambios.', 'err');
    return;
  }
  const missing = missingIndexes();
  if (missing.length) {
    showSubmitDialog({
      status: 'incomplete',
      missing,
      title: 'Entrega incompleta',
      message: `Faltan ${missing.length} respuestas para poder entregar el test.`
    });
    playSound('error');
    return;
  }
  showSubmitDialog({
    status: 'ready',
    missing: [],
    title: 'Confirmar entrega',
    message: 'Todas las respuestas están completas. Al entregar, se cerrará la prueba y ya no podrás cambiar tus respuestas.'
  });
  playSound('nav');
}

function requestResetTest() {
  const answered = answeredCount();
  if (!answered) {
    toast('Todavía no hay respuestas para reiniciar.', 'info');
    return;
  }
  showSubmitDialog({
    status: 'reset',
    missing: [],
    title: 'Reiniciar respuestas',
    message: `Vas a eliminar ${answered} respuesta${answered === 1 ? '' : 's'} de este intento. Esta acción no se puede deshacer.`
  });
  playSound('error');
}

let dialogReturnFocus = null;

function showSubmitDialog({ status, missing, title, message }) {
  const dialog = $('#submit-dialog');
  if (!dialog) return;
  dialogReturnFocus = document.activeElement instanceof HTMLElement ? document.activeElement : null;
  const answered = answeredCount();
  const firstMissing = missing[0] ?? null;
  const missingPreview = missing.slice(0, 12).map(index => index + 1).join(', ');
  dialog.innerHTML = `
    <div class="submit-dialog__backdrop" data-action="close-submit-dialog"></div>
    <section class="submit-dialog__card submit-dialog__card--${status}" role="document">
      <div class="submit-dialog__head">
        <p class="eyebrow">${status === 'ready' ? 'Validación completa' : status === 'reset' ? 'Acción irreversible' : 'Revisión requerida'}</p>
        <h2 id="submit-dialog-title">${esc(title)}</h2>
        <p id="submit-dialog-message">${esc(message)}</p>
      </div>
      <div class="submit-dialog__stats" aria-label="Resumen de entrega">
        <div><strong>${answered}</strong><span>respondidas</span></div>
        <div><strong>${TOTAL_ITEMS - answered}</strong><span>${plural(TOTAL_ITEMS - answered,'pendiente','pendientes')}</span></div>
      </div>
      ${status === 'incomplete' ? `
        <div class="submit-dialog__pending">
          <strong>Primer pendiente: ítem ${firstMissing + 1}</strong>
          <span>También faltan: ${esc(missingPreview)}${missing.length > 12 ? '...' : ''}</span>
        </div>` : status === 'reset' ? `
        <div class="submit-dialog__pending submit-dialog__pending--danger">
          <strong>Las respuestas eliminadas no se pueden recuperar</strong>
          <span>El cronómetro oficial continúa si la prueba ya fue iniciada.</span>
        </div>` : `
        <div class="submit-dialog__ready">
          <strong>Listo para entregar</strong>
          <span>La sesión quedará cerrada y se mostrará tu constancia de entrega.</span>
        </div>`}
      <div class="submit-dialog__actions">
        <button class="ghost-btn" type="button" data-action="close-submit-dialog">Seguir revisando</button>
        ${status === 'incomplete'
          ? `<button class="solid-btn" type="button" data-action="go-first-missing" data-target-index="${firstMissing}">Ir al primer pendiente</button>`
          : status === 'reset'
            ? '<button class="danger-btn" type="button" data-action="confirm-reset-test">Reiniciar respuestas</button>'
          : '<button class="solid-btn" type="button" data-action="confirm-submit-test">Entregar prueba</button>'}
      </div>
    </section>`;
  dialog.classList.remove('app-hidden');
  dialog.setAttribute('aria-describedby', 'submit-dialog-message');
  $('#view-app').inert = true;
  requestAnimationFrame(() => dialog.querySelector('.solid-btn, .ghost-btn')?.focus());
}

function closeSubmitDialog() {
  const dialog = $('#submit-dialog');
  if (!dialog) return;
  dialog.classList.add('app-hidden');
  dialog.innerHTML = '';
  $('#view-app').inert = false;
  dialogReturnFocus?.focus({ preventScroll: true });
  dialogReturnFocus = null;
}

function goFirstMissing(index) {
  closeSubmitDialog();
  const target = Number(index);
  if (Number.isInteger(target) && target >= 0 && target < TOTAL_ITEMS) {
    appState.currentIndex = target;
    stateStore.publish('assessment-navigation', { reason: 'missing-answer' });
  }
}

async function completeTest() {
  const missing = TOTAL_ITEMS - answeredCount();
  if (missing) {
    requestFinishTest();
    return;
  }
  if (!appState.startedAt) {
    playSound('error');
    toast('La prueba no está iniciada.', 'err');
    return;
  }
  if (appState.submitBusy) return;
  appState.submitBusy = true;
  const submitButton = $('[data-action="confirm-submit-test"]');
  if (submitButton) {
    submitButton.disabled = true;
    submitButton.textContent = 'Entregando...';
  }
  let remoteSubmission = null;
  try {
    remoteSubmission = await submitRemoteAttempt();
  } catch (error) {
    playSound('error');
    toast(error.message || 'No se pudo entregar el intento.', 'err');
    return;
  } finally {
    appState.submitBusy = false;
    if (submitButton?.isConnected) {
      submitButton.disabled = false;
      submitButton.textContent = 'Entregar prueba';
    }
  }
  const endedAt = remoteSubmission?.submittedAt || new Date().toISOString();
  const durationSec = appState.startedAt ? (new Date(endedAt).getTime() - new Date(appState.startedAt).getTime()) / 1000 : 0;
  const result = {
    id: `${appState.profile.cif}-${Date.now()}`,
    testId: ACTIVE_TEST.id,
    testVersion: ACTIVE_TEST.version,
    testName: ACTIVE_TEST.shortName,
    profile: appState.profile,
    answeredCount: answeredCount(),
    total: TOTAL_ITEMS,
    startedAt: appState.startedAt,
    endedAt,
    durationSec,
    focusLosses: appState.focusLosses,
    securityEvents: [...appState.securityEvents]
  };
  appState.result = result;
  closeSubmitDialog();
  endSecureExamMode();
  saveRecord(result);
  saveDraft();
  playSound('success');
  toast('Prueba entregada. Gracias.', 'ok');
  loadAvailability();
  routeTo('resultados');
}

function resetTest() {
  const keepLocked = isExamLocked();
  const answeredIndexes = appState.answers
    .map((answer, index) => answer === null ? null : index)
    .filter(index => index !== null);
  appState.answers = Array(TOTAL_ITEMS).fill(null);
  appState.currentIndex = 0;
  appState.startedAt = keepLocked ? appState.startedAt : null;
  appState.result = null;
  if (!keepLocked) {
    appState.focusLosses = 0;
    appState.securityEvents = [];
  }
  if (appState.profile?.cif) {
    draftRepository.remove(sessionContext());
  }
  if (keepLocked) answeredIndexes.forEach(index => syncRemoteAnswer({ index, option: null }));
  if (keepLocked) recordSecurityEvent('reset', 'El aspirante reinicio respuestas durante la prueba.');
  playSound('nav');
  toast('Respuestas reiniciadas.', 'info');
  stateStore.publish('assessment-reset');
}

function handleDocumentClick(event) {
  const actionTarget = event.target.closest('[data-action]');
  const routeCandidate = event.target.closest('[data-route]');
  // El body lleva data-route solo para el CSS: no es un destino de navegación.
  const routeTarget = routeCandidate && routeCandidate !== document.body ? routeCandidate : null;
  const optionTarget = event.target.closest('[data-option]');
  const itemTarget = event.target.closest('[data-item]');
  const pageTarget = event.target.closest('[data-page]');
  if (actionTarget) {
    event.preventDefault();
    const action = actionTarget.dataset.action;
    if (action === 'toggle-manual-register') {
      appState.manualRegistering = !appState.manualRegistering;
      validateLogin();
      return;
    }
    if (action === 'show-login' || action === 'show-register') {
      const nextRegistering = action === 'show-register';
      if (appState.manualRegistering !== nextRegistering) {
        ['#unified-id', '#unified-pass', '#fullname', '#cedula'].forEach(selector => {
          const field = $(selector);
          if (field) field.value = '';
        });
        const passField = $('#unified-pass');
        if (passField) passField.type = 'password';
        $('#icon-eye-show-u')?.classList.remove('app-hidden');
        $('#icon-eye-hide-u')?.classList.add('app-hidden');
        $('#toggle-pass-u')?.setAttribute('aria-label', 'Mostrar contraseña');
      }
      appState.manualRegistering = nextRegistering;
      validateLogin();
      $('#unified-id')?.focus();
      return;
    }
    if (action === 'cancel-mfa') {
      cancelMfa();
      return;
    }
    if (action === 'enter-demo') {
      enterAspirante({ fullName: 'Usuario de demostración', cif: 'DEMO', edad: 18, grupo: 'UN', carrera: '' });
      return;
    }
    if (action === 'toggle-setting') {
      const key = actionTarget.dataset.setting;
      if (!['dark', 'compact', 'sound', 'autoAdvance'].includes(key)) return;
      const title = actionTarget.querySelector('strong')?.textContent || 'Ajuste';
      appState.settings[key] = !appState.settings[key];
      saveSettings();
      if (key === 'sound' && appState.settings[key]) playSound('success');
      render();
      toast(`${title}: ${appState.settings[key] ? 'activado' : 'desactivado'}.`, 'info');
      return;
    }
    if (action === 'toggle-consent') {
      const checkbox = $('#consent-check');
      if (!checkbox || checkbox.disabled) return;
      checkbox.checked = !checkbox.checked;
      updateConsentSelection(checkbox.checked);
      checkbox.focus();
      return;
    }
    if (action === 'logout') logout();
    if (action === 'go-home') routeTo(appState.role === 'staff' ? 'panel' : 'inicio');
    if (action === 'start-test') startTest();
    if (action === 'prev-item') { appState.currentIndex = Math.max(0, appState.currentIndex - 1); playSound('nav'); stateStore.publish('assessment-navigation', { reason: 'previous' }); }
    if (action === 'next-item') { appState.currentIndex = Math.min(TOTAL_ITEMS - 1, appState.currentIndex + 1); playSound('nav'); stateStore.publish('assessment-navigation', { reason: 'next' }); }
    if (action === 'clear-answer') clearAnswer();
    if (action === 'reload-staff') loadStaffAttempts(true);
    if (action === 'reload-availability') loadAvailability();
    if (action === 'open-consent') openConsent();
    if (action === 'accept-consent') acceptConsent();
    if (action === 'reload-sessions') loadSessions(true);
    if (action === 'new-session') showStaffDialog('Nueva convocatoria', ADM.sessionFormHtml(null));
    if (action === 'edit-session') showStaffDialog('Editar convocatoria', ADM.sessionFormHtml((appState.sessions || []).find(item => item.id === Number(actionTarget.dataset.sessionId))));
    if (action === 'cancel-session') cancelSession(Number(actionTarget.dataset.sessionId));
    if (action === 'open-session') openSessionDetail(Number(actionTarget.dataset.sessionId));
    if (action === 'reload-enrollments') reloadSessionDetail();
    if (action === 'resend-invitation') enrollmentAction('resend', Number(actionTarget.dataset.enrollmentId));
    if (action === 'adjust-enrollment') enrollmentAction('adjust', Number(actionTarget.dataset.enrollmentId));
    if (action === 'revoke-enrollment') enrollmentAction('revoke', Number(actionTarget.dataset.enrollmentId));
    if (action === 'export-results') exportResults();
    if (action === 'review-attempt') attemptAction('review', Number(actionTarget.dataset.attemptId));
    if (action === 'invalidate-attempt') attemptAction('invalidate', Number(actionTarget.dataset.attemptId));
    if (action === 'reapply-attempt') attemptAction('reapply', Number(actionTarget.dataset.attemptId));
    if (action === 'close-dialog') closeStaffDialog();
    if (action === 'reload-norms') loadStaffNorms(true);
    if (action === 'reload-admin-users') loadAdminData('users', true);
    if (action === 'reload-admin-audit') loadAdminData('audit', true);
    if (action === 'save-staff-access' || action === 'toggle-staff-access') {
      updateStaffAccount(Number(actionTarget.dataset.userId), actionTarget.dataset.nextActive === 'true');
    }
    if (action === 'reset-staff-mfa') resetStaffMfa(Number(actionTarget.dataset.userId));
    if (action === 'finish-test') requestFinishTest();
    if (action === 'close-submit-dialog') closeSubmitDialog();
    if (action === 'go-first-missing') goFirstMissing(actionTarget.dataset.targetIndex);
    if (action === 'confirm-submit-test') completeTest();
    if (action === 'restore-fullscreen') enterFullscreen();
    if (action === 'reset-test') requestResetTest();
    if (action === 'confirm-reset-test') {
      closeSubmitDialog();
      resetTest();
      requestAnimationFrame(() => $('[data-action="reset-test"]')?.focus());
    }
    if (action === 'clear-records' && window.confirm('¿Eliminar todos los reportes locales?')) {
      resultRepository.clear();
      toast('Registros eliminados.', 'info');
      render();
    }
    return;
  }
  if (routeTarget) {
    if (isExamLocked() && routeTarget.dataset.route !== 'test') {
      event.preventDefault();
      playSound('error');
      toast('Finalizá la prueba antes de cambiar de pantalla.', 'err');
      return;
    }
    routeTo(routeTarget.dataset.route);
    return;
  }
  if (optionTarget) {
    selectAnswer(Number(optionTarget.dataset.option));
    return;
  }
  if (itemTarget) {
    const target = Number(itemTarget.dataset.item);
    if (!canAccessItem(target)) return;
    appState.currentIndex = target;
    playSound('nav');
    stateStore.publish('assessment-navigation', { reason: 'item' });
    return;
  }
  if (pageTarget) {
    const target = Number(pageTarget.dataset.page) * ITEMS_PER_PAGE;
    if (!canAccessItem(target)) return;
    appState.currentIndex = target;
    playSound('nav');
    stateStore.publish('assessment-navigation', { reason: 'section' });
  }
}

function handleDocumentChange(event) {
  if (event.target.id === 'consent-check') {
    updateConsentSelection(event.target.checked);
    return;
  }
  const setting = event.target.closest('[data-setting]');
  const range = event.target.closest('[data-setting-range]');
  const profileField = event.target.closest('[data-profile-field]');
  if (profileField && appState.profile) {
    const key = profileField.dataset.profileField;
    if (key === 'carrera') appState.profile.carrera = normalizeCareer(profileField.value);
    saveProfileEdits();
    renderShell();
    render();
    toast('Datos del aspirante actualizados.', 'ok');
    return;
  }
  if (setting) {
    appState.settings[setting.dataset.setting] = setting.checked;
    saveSettings();
    if (setting.dataset.setting === 'sound' && setting.checked) playSound('success');
    render();
  }
  if (range) {
    appState.settings[range.dataset.settingRange] = Number(range.value);
    saveSettings();
    render();
  }
}

function updateConsentSelection(selected) {
  const accept = $('[data-action="accept-consent"]');
  const toggle = $('[data-action="toggle-consent"]');
  if (accept) accept.disabled = !selected;
  if (toggle) {
    toggle.textContent = selected ? 'Lectura confirmada' : 'Marcar como leído';
    toggle.setAttribute('aria-pressed', String(selected));
  }
}

function isTypingField(element) {
  if (!element) return false;
  const tag = element.tagName;
  return tag === 'INPUT' || tag === 'TEXTAREA' || tag === 'SELECT' || element.isContentEditable;
}

function isForbiddenExamKey(event) {
  if (!isExamLocked()) return false;
  const key = String(event.key || '').toLowerCase();
  if (event.metaKey || event.ctrlKey || event.altKey) return true;
  return ['escape', 'f5', 'f11', 'f12', 'printscreen'].includes(key);
}

function keepFocusInsideDialog(event, dialog, close) {
  if (event.key === 'Escape') {
    event.preventDefault();
    close();
    return;
  }
  if (event.key !== 'Tab') return;
  const controls = $$('button:not([disabled]), [href], input:not([disabled]), select:not([disabled]), textarea:not([disabled]), [tabindex]:not([tabindex="-1"])', dialog);
  if (!controls.length) return;
  const first = controls[0];
  const last = controls[controls.length - 1];
  if (event.shiftKey && document.activeElement === first) {
    event.preventDefault();
    last.focus();
  } else if (!event.shiftKey && document.activeElement === last) {
    event.preventDefault();
    first.focus();
  }
}

function handleKeydown(event) {
  const submitDialog = $('#submit-dialog');
  if (submitDialog && !submitDialog.classList.contains('app-hidden')) {
    keepFocusInsideDialog(event, submitDialog, closeSubmitDialog);
    return;
  }
  const staffDialog = $('#staff-dialog-root .submit-dialog');
  if (staffDialog) {
    keepFocusInsideDialog(event, staffDialog, closeStaffDialog);
    return;
  }
  if (isTypingField(event.target)) return;
  if (isForbiddenExamKey(event)) {
    event.preventDefault();
    event.stopPropagation();
    recordSecurityEvent('blocked-key', event.key || 'unknown');
    notifySecurity('Modo examen activo: atajo bloqueado.');
    render();
    return;
  }
  if (appState.role !== 'aspirante' || appState.route !== 'test') return;
  const numericOption = Number(event.key);
  if (Number.isInteger(numericOption) && numericOption >= 1 && numericOption <= ACTIVE_TEST.optionCount) {
    event.preventDefault();
    selectAnswer(numericOption);
    return;
  }
  if (event.key === 'Enter' || event.key === 'ArrowRight') {
    event.preventDefault();
    appState.currentIndex = Math.min(TOTAL_ITEMS - 1, appState.currentIndex + 1);
    playSound('nav');
    stateStore.publish('assessment-navigation', { reason: 'keyboard-next' });
    return;
  }
  if (event.key === 'ArrowLeft') {
    event.preventDefault();
    appState.currentIndex = Math.max(0, appState.currentIndex - 1);
    playSound('nav');
    stateStore.publish('assessment-navigation', { reason: 'keyboard-previous' });
  }
}

function handleExamBlockedDomEvent(event) {
  if (!isExamLocked()) return;
  event.preventDefault();
  event.stopPropagation();
  recordSecurityEvent(`blocked-${event.type}`, 'Accion bloqueada durante la prueba.');
  notifySecurity();
  render();
}

function handleVisibilityChange() {
  if (!isExamLocked() || document.visibilityState !== 'hidden') return;
  recordSecurityEvent('visibility-hidden', 'La pestana dejo de estar visible.');
}

function handleWindowBlur() {
  if (!isExamLocked()) return;
  recordSecurityEvent('window-blur', 'La ventana perdio el foco.');
}

function handleFullscreenChange() {
  if (!isExamLocked() || isFullscreenActive()) return;
  recordSecurityEvent('fullscreen-exit', 'Se salio de pantalla completa durante la prueba.');
  notifySecurity('Pantalla completa requerida para continuar la prueba.');
  render();
}

const persistedAssessmentEvents = new Set(['answer', 'assessment-navigation', 'assessment-reset', 'security']);
stateStore.subscribe('*', event => {
  if (persistedAssessmentEvents.has(event.type)) saveDraft();
});
stateStore.subscribe('answer', updateTestViewAfterAnswer);
stateStore.subscribe('answer', event => syncRemoteAnswer(event.detail));
stateStore.subscribe('security', event => syncSecurityEvent(event.detail.securityEvent));
stateStore.subscribe('assessment-navigation', render);
stateStore.subscribe('assessment-reset', render);

$('#unified-id')?.addEventListener('input', validateLogin);
$('#fullname')?.addEventListener('input', validateLogin);
$('#cedula')?.addEventListener('input', validateLogin);
$('#unified-pass')?.addEventListener('input', validateLogin);
$('#mfa-code')?.addEventListener('input', event => {
  event.target.value = event.target.value.replace(/\D/g, '').slice(0, 6);
  $('#mfa-code-msg').textContent = '';
  validateMfa();
});

window.SAP_UAM_PUZZLE_VIEW.attachDrag(document, {
  canInteract: () => appState.role === 'aspirante' && appState.route === 'test',
  onDrop: option => selectAnswer(option),
  onClear: clearAnswer
});
document.addEventListener('click', handleDocumentClick);
document.addEventListener('change', handleDocumentChange);
document.addEventListener('keydown', handleKeydown);
['contextmenu', 'copy', 'cut', 'paste', 'drop', 'dragstart'].forEach(type => {
  document.addEventListener(type, handleExamBlockedDomEvent);
});
document.addEventListener('visibilitychange', handleVisibilityChange);
document.addEventListener('fullscreenchange', handleFullscreenChange);
document.addEventListener('webkitfullscreenchange', handleFullscreenChange);
window.addEventListener('blur', handleWindowBlur);
window.addEventListener('beforeunload', event => {
  if (!isExamLocked()) return;
  recordSecurityEvent('beforeunload', 'Intento de cerrar o recargar la pagina.');
  event.preventDefault();
  event.returnValue = '';
});

$('#toggle-pass-u')?.addEventListener('click', () => {
  const input = $('#unified-pass');
  if (!input) return;
  const show = input.type === 'password';
  input.type = show ? 'text' : 'password';
  $('#icon-eye-show-u')?.classList.toggle('app-hidden', show);
  $('#icon-eye-hide-u')?.classList.toggle('app-hidden', !show);
  $('#toggle-pass-u')?.setAttribute('aria-label', show ? 'Ocultar contraseña' : 'Mostrar contraseña');
});

$('#form-unified')?.addEventListener('submit', event => {
  event.preventDefault();
  submitUnifiedLogin();
});

$('#form-mfa')?.addEventListener('submit', event => {
  event.preventDefault();
  submitMfa();
});

document.addEventListener('submit', event => {
  const handlers = {
    'form-admin-user': createStaffAccount,
    'form-claim': claimAccessCode,
    'form-password': submitPasswordChange,
    'form-session': submitSessionForm,
    'form-enroll': submitEnroll,
    'form-adjust': submitAdjust,
    'form-invalidate': submitInvalidate,
    'form-reapply': submitReapply,
    'form-results-filter': form => {
      const data = new FormData(form);
      appState.resultsFilters = { sessionId: String(data.get('sessionId') || ''), status: String(data.get('status') || ''), q: String(data.get('q') || '') };
      return loadStaffAttempts(true);
    }
  };
  const handler = handlers[event.target.id];
  if (!handler) return;
  event.preventDefault();
  handler(event.target);
});

setInterval(() => {
  const clock = $('#test-clock');
  if (clock && appState.route === 'test') clock.textContent = elapsedLabel();
}, 1000);

setInterval(renewRemoteAttemptLease, 60000);
setInterval(refreshBackendSession, 10 * 60 * 1000);

applySettings();
$('#demo-access')?.classList.toggle('app-hidden', !STATIC_PAGES_MODE);
validateLogin();
if (QA_SCENARIO) enterLocalQaScenario();
else restoreSessionFromBackend();
