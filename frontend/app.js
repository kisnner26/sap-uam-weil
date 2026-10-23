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

const ACTIVE_TEST = window.SAP_UAM_TESTS.get(window.SAP_UAM_TEST_ID || window.SAP_UAM_TESTS.defaultTestId);
const TOTAL_PAGES = ACTIVE_TEST.sections.length;
const ITEMS_PER_PAGE = ACTIVE_TEST.itemsPerSection;
const TOTAL_ITEMS = TOTAL_PAGES * ITEMS_PER_PAGE;
const SECURITY_EVENT_LIMIT = 40;
const LAMINAS = ACTIVE_TEST.sections.map((_, index) => ACTIVE_TEST.media.sectionImage(index));
const ITEM_IMAGES = Array.from({ length: TOTAL_ITEMS }, (_, index) => ACTIVE_TEST.media.itemImage(index));
const NAV = {
  aspirante: [
    ['inicio', 'Inicio'],
    ['test', 'Prueba'],
    ['resultados', 'Resultados'],
    ['perfil', 'Perfil'],
    ['ajustes', 'Ajustes']
  ],
  staff: [
    ['panel', 'Panel'],
    ['aspirantes', 'Aspirantes'],
    ['baremo', 'Baremo'],
    ['ajustes', 'Ajustes']
  ]
};
const BAREMOS = ACTIVE_TEST.baremos;

const stateStore = window.SAP_UAM_STATE.create({
  mode: null,
  authMode: null,
  manualRegistering: false,
  authBusy: false,
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

async function openRemoteAttempt() {
  if (STATIC_PAGES_MODE) return null;
  const existing = readAttemptLease();
  const attempt = await apiRequest('/api/attempts', {
    testId: ACTIVE_TEST.id,
    testVersion: ACTIVE_TEST.version,
    leaseToken: existing?.leaseToken || null
  });
  storeAttemptLease(attempt);
  if (Array.isArray(attempt.responses)) {
    for (const response of attempt.responses) {
      if (appState.answers[response.itemIndex] === null) appState.answers[response.itemIndex] = response.selectedOption;
    }
  }
  return attempt;
}

async function renewRemoteAttemptLease() {
  if (STATIC_PAGES_MODE || !isExamLocked()) return;
  const attempt = appState.remoteAttempt || readAttemptLease();
  if (!attempt?.id || !attempt?.leaseToken) return;
  try {
    const renewed = await apiRequest(`/api/attempts/${attempt.id}/lease`, {
      leaseToken: attempt.leaseToken,
      expectedVersion: attempt.version
    });
    storeAttemptLease(renewed);
  } catch (error) {
    try {
      await openRemoteAttempt();
    } catch {
      toast(error.message || 'No se pudo renovar la sesión exclusiva.', 'err');
    }
  }
}

async function submitRemoteAttempt() {
  if (STATIC_PAGES_MODE) return null;
  const attempt = appState.remoteAttempt || readAttemptLease();
  if (!attempt?.id || !attempt?.leaseToken) throw new Error('No existe una sesión exclusiva para este intento.');
  const submitted = await apiRequest(`/api/attempts/${attempt.id}/submit`, {
    leaseToken: attempt.leaseToken,
    expectedVersion: attempt.version,
    answers: [...appState.answers]
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

function esc(value) {
  return String(value ?? '').replace(/[&<>'"]/g, char => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', "'": '&#39;', '"': '&quot;' }[char]));
}

function setFieldState(wrapSelector, msgSelector, kind, message) {
  const wrap = $(wrapSelector);
  const msg = $(msgSelector);
  if (wrap) {
    wrap.classList.toggle('is-ok', kind === 'ok');
    wrap.classList.toggle('is-err', kind === 'err');
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
    manualToggle.classList.toggle('app-hidden', false);
    manualToggle.textContent = isRegister ? 'Ya tengo cuenta, iniciar sesión' : 'Crear cuenta externa';
  }
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
    setFieldState('#wrap-unified-id', '#unified-id-msg', emailOk ? 'ok' : 'err', emailOk ? 'Correo listo' : 'Correo inválido');
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
  const button = $('#btn-unified');
  const label = $('#btn-unified-label');
  if (button) button.disabled = isBusy || !validateLogin();
  if (label && isBusy) label.textContent = 'Validando acceso...';
  if (!isBusy) validateLogin();
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
    throw new Error(message);
  }
  return data;
}

function staticAuthRequest(path, body = null) {
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
      password: String(body?.password || ''),
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
    const user = users.find(item => item.email === email && item.password === password);
    if (!user) return Promise.reject(new Error('Correo o contraseña incorrectos.'));
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
  const { password, ...safeUser } = user;
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
    handleAuthSuccess(response, { loginId: id });
  } catch (error) {
    playSound('error');
    toast(error.message || 'No se pudo iniciar sesión.', 'err');
  } finally {
    setAuthBusy(false);
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

function handleAuthSuccess(response, context = {}) {
  const user = response?.user;
  if (!user) {
    throw new Error('La sesión no devolvió datos de usuario.');
  }
  appState.sessionUser = user;
  localStorage.setItem(STORAGE.sessionUser, JSON.stringify(user));
  playSound('success');
  toast('Sesión iniciada.', 'ok');
  enterAspirante(profileFromUser(user, context));
}

async function restoreSessionFromBackend() {
  if ($('#view-auth')?.classList.contains('app-hidden')) return;
  try {
    const user = await apiRequest('/api/auth/me');
    if (!user?.id) return;
    appState.sessionUser = user;
    localStorage.setItem(STORAGE.sessionUser, JSON.stringify(user));
    enterAspirante(profileFromUser(user));
  } catch {
    localStorage.removeItem(STORAGE.sessionUser);
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

function correctAnswer(index) {
  return ACTIVE_TEST.scoring.correctAnswer(index);
}

function pageScore(pageIndex) {
  return ACTIVE_TEST.scoring.score(appState.answers).pageScores[pageIndex];
}

function totalScore() {
  return ACTIVE_TEST.scoring.score(appState.answers).total;
}

function resolveNormColumn(profile) {
  return ACTIVE_TEST.scoring.resolveNormColumn(profile);
}

function percentileFromScore(score, column) {
  return ACTIVE_TEST.scoring.percentile(score, column);
}

function ciFromPercentile(percentile) {
  return ACTIVE_TEST.scoring.ci(percentile);
}

function classifyCI(ci) {
  return ACTIVE_TEST.scoring.classify(ci);
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

function enterAspirante(profileOverride = null) {
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
  const restoredDraft = loadDraft(profile);
  if (!restoredDraft) {
    const latest = records().find(item => item.testId === ACTIVE_TEST.id && String(item.profile?.cif) === String(profile?.cif));
    appState.result = latest ? resultRepository.get(latest.id) : null;
  }
  if (appState.startedAt && !appState.result) appState.route = 'test';
  showApp();
  if (appState.startedAt && !appState.result && !STATIC_PAGES_MODE) {
    openRemoteAttempt().catch(error => toast(error.message || 'No se pudo recuperar la sesión exclusiva.', 'err'));
  }
}

function enterStaff() {
  appState.role = 'staff';
  appState.route = 'panel';
  appState.profile = { username: ($('#unified-id')?.value || '').trim() };
  showApp();
}

function showApp() {
  $('#view-auth')?.classList.add('app-hidden');
  $('#view-app')?.classList.remove('app-hidden');
  renderShell();
  render();
}

function logout() {
  if (isExamLocked()) {
    playSound('error');
    toast('La prueba ya inició. Finalizala para poder salir.', 'err');
    routeTo('test');
    return;
  }
  saveDraft();
  apiRequest('/api/auth/logout', {}, { method: 'POST' }).catch(() => {});
  localStorage.removeItem(STORAGE.sessionUser);
  localStorage.removeItem(STORAGE.legacy.sessionUser);
  appState.sessionUser = null;
  appState.role = null;
  appState.profile = null;
  $('#view-app')?.classList.add('app-hidden');
  $('#view-auth')?.classList.remove('app-hidden');
  toast('Sesión cerrada.', 'info');
}

function renderShell() {
  const nav = (NAV[appState.role] || []).filter(([route]) => route !== 'test' || testRouteAvailable());
  const locked = isExamLocked();
  $('#main-nav').innerHTML = nav.map(([route, label]) => {
    const isLocked = locked && route !== 'test';
    return `<button type="button" class="nav-btn ${appState.route === route ? 'is-active' : ''} ${isLocked ? 'is-locked' : ''}" data-route="${route}" ${isLocked ? 'aria-disabled="true"' : ''}>${label}</button>`;
  }).join('');
  const label = appState.role === 'staff'
    ? `Personal · ${esc(appState.profile?.username)}`
    : `${esc(appState.profile?.fullName)} · CIF ${esc(appState.profile?.cif)}`;
  $('#session-label').innerHTML = label;
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
  else if (appState.route === 'resultados') content.innerHTML = renderResults();
  else if (appState.route === 'perfil') content.innerHTML = renderProfile();
  else if (appState.route === 'ajustes') content.innerHTML = renderSettings();
  else content.innerHTML = renderHome();
}

function renderHome() {
  const count = answeredCount();
  const progress = Math.round((count / TOTAL_ITEMS) * 100);
  const result = appState.result;
  const profileLine = [appState.profile.fullName, normalizeCareer(appState.profile.carrera)].filter(Boolean).join(' · ');
  return `
    <div class="screen-title">
      <div><p class="eyebrow">Centro de evaluaciones</p><h2>Hola, ${esc(appState.profile.fullName.split(' ')[0])}</h2><p>${esc(profileLine)}</p></div>
    </div>
    <div class="dashboard-grid">
      <section class="hero-band assessment-card">
        <div class="hero-copy">
          <div>
            <div class="assessment-card__top"><p class="eyebrow">${esc(ACTIVE_TEST.shortName)}</p><span class="status-badge">${result ? 'Completada' : count ? 'En progreso' : 'Asignada'}</span></div>
            <h2>${esc(ACTIVE_TEST.name)}</h2>
            <p>${esc(ACTIVE_TEST.description)}</p>
            <dl class="assessment-meta">
              <div><dt>Ítems</dt><dd>${TOTAL_ITEMS}</dd></div>
              <div><dt>Secciones</dt><dd>${TOTAL_PAGES}</dd></div>
              <div><dt>Tiempo estimado</dt><dd>${ACTIVE_TEST.estimatedMinutes} min</dd></div>
            </dl>
          </div>
          <div>
            <div class="progress-label"><span>Progreso</span><strong>${progress}%</strong></div>
            <div class="progress-shell" role="progressbar" aria-label="Progreso de ${esc(ACTIVE_TEST.shortName)}" aria-valuemin="0" aria-valuemax="100" aria-valuenow="${progress}"><div class="progress-bar" style="width:${progress}%"></div></div>
            <div class="hero-actions">
              <button class="solid-btn" type="button" data-action="start-test">${result ? 'Revisar respuestas' : count ? 'Continuar evaluación' : 'Comenzar evaluación'}</button>
              ${result ? '<button class="ghost-btn" type="button" data-route="resultados">Ver resultado</button>' : ''}
            </div>
          </div>
        </div>
        <div class="hero-visual"><img src="${LAMINAS[0]}" alt="Vista parcial del material de ${esc(ACTIVE_TEST.shortName)}" loading="lazy" decoding="async"></div>
      </section>
      <aside class="status-stack">
        <section class="panel next-step-panel">
          <p class="eyebrow">Siguiente paso</p>
          <h3>${result ? 'Tu resultado está listo' : count ? `Continuá desde el ítem ${appState.currentIndex + 1}` : 'Prepará tu espacio'}</h3>
          <p>${result ? 'Consultá el reporte y la revisión de respuestas.' : 'Buscá un lugar sin interrupciones. El progreso se guarda automáticamente.'}</p>
          <div class="metric-strip">
            <div><strong>${count}</strong><span>respondidos</span></div>
            <div><strong>${TOTAL_ITEMS - count}</strong><span>pendientes</span></div>
          </div>
        </section>
        <section class="panel readiness-panel">
          <h3>Antes de comenzar</h3>
          <div class="check-list">
            <div class="check-row"><span class="check-dot">1</span><span>Leé cada figura con calma.</span></div>
            <div class="check-row"><span class="check-dot">2</span><span>Respondé todos los ítems.</span></div>
            <div class="check-row"><span class="check-dot">3</span><span>Finalizá para generar el reporte.</span></div>
          </div>
        </section>
      </aside>
    </div>`;
}

function pageAnswered(pageIndex) {
  return appState.answers.slice(pageIndex * ITEMS_PER_PAGE, (pageIndex + 1) * ITEMS_PER_PAGE).filter(value => value !== null).length;
}

function isExamLocked() {
  return appState.role === 'aspirante' && !!appState.startedAt && !appState.result;
}

function testRouteAvailable() {
  return appState.role === 'aspirante' && !!appState.startedAt;
}

function applyExamSecurityUi() {
  document.body.classList.toggle('exam-locked', isExamLocked());
}

function isFullscreenActive() {
  return !!(document.fullscreenElement || document.webkitFullscreenElement);
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
  if (!request) {
    recordSecurityEvent('fullscreen-unavailable', 'El navegador no permite pantalla completa.');
    toast('Este navegador no permite activar pantalla completa.', 'err');
    return;
  }
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

function canNavigateTo(route) {
  if (appState.role === 'aspirante' && route === 'test' && !testRouteAvailable()) return false;
  return !isExamLocked() || route === 'test';
}

function renderTest() {
  const index = appState.currentIndex;
  const page = Math.floor(index / ITEMS_PER_PAGE);
  const local = index % ITEMS_PER_PAGE;
  const row = Math.floor(local / 3) + 1;
  const col = (local % 3) + 1;
  const selected = appState.answers[index];
  const progress = Math.round((answeredCount() / TOTAL_ITEMS) * 100);
  const securityEvents = appState.securityEvents.length;
  return `
    <div class="exam-head">
      <div>
        <p class="eyebrow">Aplicación activa</p>
        <h2>Ítem ${index + 1} de ${TOTAL_ITEMS}</h2>
        <p>Lámina ${page + 1} · Ítem ${local + 1} · Fila ${row}, posición ${col}</p>
      </div>
      <div class="exam-head__meta">
        <span class="chip lock-chip">Prueba bloqueada</span>
        <button class="chip security-chip ${isFullscreenActive() ? 'is-ok' : 'is-alert'}" type="button" data-action="restore-fullscreen">${isFullscreenActive() ? 'Pantalla completa' : 'Activar pantalla completa'}</button>
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
          <div class="page-progress" aria-label="Cambiar lámina">${LAMINAS.map((_, i) => `<button type="button" class="page-dot ${i === page ? 'is-active' : ''} ${pageAnswered(i) === ITEMS_PER_PAGE ? 'is-complete' : ''}" data-page="${i}" aria-label="Lámina ${i + 1}">${i + 1}</button>`).join('')}</div>
        </div>
        <figure class="question-figure">
          <img src="${ITEM_IMAGES[index]}" alt="Ejercicio ${local + 1} de ${esc(ACTIVE_TEST.sections[page].label)}" width="1200" height="900" decoding="async" fetchpriority="high">
        </figure>
        <div class="item-rail" aria-label="Ítems de la lámina">${Array.from({ length: ITEMS_PER_PAGE }, (_, i) => {
          const absolute = page * ITEMS_PER_PAGE + i;
          const answered = appState.answers[absolute] !== null;
          return `<button type="button" class="rail-btn ${absolute === index ? 'is-active' : ''} ${answered ? 'is-answered' : ''}" data-item="${absolute}" aria-label="Ítem ${i + 1}${answered ? ', respondido' : ''}">${i + 1}</button>`;
        }).join('')}</div>
      </section>

      <aside class="response-column" aria-label="Panel de respuesta">
        <section class="response-card">
          <div class="response-card__top">
            <div>
              <p class="eyebrow">Respuesta</p>
              <h3>Seleccioná una opción</h3>
              <p class="response-hint">${esc(ACTIVE_TEST.instructions)}</p>
            </div>
            <span class="selected-pill" aria-label="Respuesta seleccionada">${selected || '-'}</span>
          </div>
          <div class="choice-grid">${Array.from({ length: ACTIVE_TEST.optionCount }, (_, i) => {
            const option = i + 1;
            return `<button type="button" class="choice-btn ${selected === option ? 'is-selected' : ''}" data-option="${option}" aria-label="Responder opción ${option}">${option}</button>`;
          }).join('')}</div>
        </section>

        <section class="progress-card" aria-label="Avance de la prueba">
          <div class="progress-card__stats">
            <div><strong>${answeredCount()}</strong><span>respondidos</span></div>
            <div><strong>${TOTAL_ITEMS - answeredCount()}</strong><span>pendientes</span></div>
          </div>
          <div class="progress-shell" aria-hidden="true"><div class="progress-bar" style="width:${progress}%"></div></div>
        </section>

        <section class="step-card">
          <div class="step-actions">
            <button class="ghost-btn" type="button" data-action="prev-item" ${index === 0 ? 'disabled' : ''}>Anterior</button>
            <button class="ghost-btn" type="button" data-action="next-item" ${index === TOTAL_ITEMS - 1 ? 'disabled' : ''}>Siguiente</button>
          </div>
          <button class="solid-btn wide-btn" type="button" data-action="finish-test">Finalizar y calificar</button>
          <button class="danger-btn wide-btn" type="button" data-action="reset-test">Reiniciar respuestas</button>
        </section>
      </aside>
    </div>`;
}

function prepareCurrentItemMedia() {
  const currentImage = $('.question-figure img');
  if (currentImage && !currentImage.complete) {
    currentImage.closest('.question-figure')?.classList.add('is-loading');
    currentImage.addEventListener('load', () => currentImage.closest('.question-figure')?.classList.remove('is-loading'), { once: true });
    currentImage.addEventListener('error', () => currentImage.closest('.question-figure')?.classList.add('is-error'), { once: true });
  }
  const nextIndex = appState.currentIndex + 1;
  if (nextIndex < ITEM_IMAGES.length) {
    const nextImage = new Image();
    nextImage.decoding = 'async';
    nextImage.src = ITEM_IMAGES[nextIndex];
  }
}

function updateTestViewAfterAnswer() {
  if (appState.route !== 'test') return;
  const index = appState.currentIndex;
  const selected = appState.answers[index];
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
  railButton?.classList.add('is-answered');
  railButton?.setAttribute('aria-label', `Ítem ${(index % ITEMS_PER_PAGE) + 1}, respondido`);
  const sectionCount = $('.stage-toolbar span');
  if (sectionCount) sectionCount.textContent = `${pageAnswered(Math.floor(index / ITEMS_PER_PAGE))} de ${ITEMS_PER_PAGE} respondidos`;
}

function elapsedLabel() {
  if (!appState.startedAt) return '0:00';
  return formatDuration((Date.now() - new Date(appState.startedAt).getTime()) / 1000);
}

function renderResults() {
  const result = appState.result;
  if (!result) {
    return `<div class="screen-title"><div><p class="eyebrow">Resultados</p><h2>Sin calificación</h2><p>La prueba todavía no fue finalizada.</p></div><button class="solid-btn" type="button" data-action="start-test">Ir a la prueba</button></div><div class="empty-state">No hay resultados para mostrar.</div>`;
  }
  return `
    <div class="screen-title">
      <div><p class="eyebrow">Resultados</p><h2>Reporte de ${esc(result.profile.fullName)}</h2><p>${formatDate(result.endedAt)} · Baremo ${esc(result.normColumn)}</p></div>
      <div class="result-actions">
        <button class="solid-btn" type="button" data-action="download-pdf">Descargar PDF</button>
        <button class="ghost-btn" type="button" data-action="start-test">Revisar respuestas</button>
      </div>
    </div>
    <div class="results-grid">
      <section class="score-card">
        <p class="eyebrow">Puntaje directo</p>
        <div class="score-big">${result.score}</div>
        <p>${esc(result.classification)}</p>
        <div class="score-meta">
          <div><strong>${result.percentile}</strong><span>Percentil</span></div>
          <div><strong>${result.ci}</strong><span>CI</span></div>
          <div><strong>${formatDuration(result.durationSec)}</strong><span>Tiempo</span></div>
          <div><strong>${result.total}</strong><span>Ítems</span></div>
          <div><strong>${(result.securityEvents || []).length}</strong><span>Incidencias</span></div>
        </div>
      </section>
      <section class="table-wrap">
        <table class="data-table">
          <thead><tr><th>Lámina</th><th>Aciertos</th><th>Estado</th><th>Respondidos</th></tr></thead>
          <tbody>${Array.from({ length: TOTAL_PAGES }, (_, page) => {
            const score = result.pageScores[page];
            return `<tr><td>${esc(ACTIVE_TEST.sections[page].label)}</td><td>${score}/${ITEMS_PER_PAGE}</td><td><span class="pill ${score >= 8 ? 'good' : 'warn'}">${score >= 8 ? 'Fuerte' : 'Revisar'}</span></td><td>${result.answers.slice(page * ITEMS_PER_PAGE, (page + 1) * ITEMS_PER_PAGE).filter(v => v !== null).length}/${ITEMS_PER_PAGE}</td></tr>`;
          }).join('')}</tbody>
        </table>
      </section>
    </div>
    <div class="divider"></div>
    <section class="panel">
      <h3>Revisión de respuestas</h3>
      <div class="review-grid">${result.answers.map((answer, index) => `<span class="review-cell ${answer === correctAnswer(index) ? 'ok' : 'bad'}" title="Ítem ${index + 1}: ${answer || '-'} / ${correctAnswer(index)}">${index + 1}</span>`).join('')}</div>
    </section>`;
}

function renderProfile() {
  const profile = appState.profile;
  const result = appState.result;
  return `
    <div class="screen-title"><div><p class="eyebrow">Perfil</p><h2>Datos del aspirante</h2><p>Información usada para la calificación local.</p></div></div>
    <div class="profile-grid">
      <section class="panel profile-card">
        <div class="avatar" aria-hidden="true">${esc(initials(profile.fullName))}</div>
        <div><h3>${esc(profile.fullName)}</h3><p class="muted">CIF ${esc(profile.cif)}</p></div>
        <button class="solid-btn" type="button" data-action="start-test">${answeredCount() ? 'Continuar prueba' : 'Iniciar prueba'}</button>
      </section>
      <section class="panel">
        <h3>Registro</h3>
        <div class="detail-list">
          <div class="detail-row"><span>Carrera</span><strong>${esc(normalizeCareer(profile.carrera))}</strong></div>
          <div class="detail-row"><span>Edad</span><strong>${esc(profile.edad)}</strong></div>
          <div class="detail-row"><span>Baremo</span><strong>${esc(resolveNormColumn(profile))}</strong></div>
          <div class="detail-row"><span>Avance</span><strong>${answeredCount()}/${TOTAL_ITEMS}</strong></div>
          <div class="detail-row"><span>Último resultado</span><strong>${result ? `${result.score}/${result.total}` : 'Sin resultado'}</strong></div>
        </div>
      </section>
    </div>`;
}

function renderSettings() {
  const profile = appState.profile || {};
  return `
    <div class="screen-title"><div><p class="eyebrow">Ajustes</p><h2>Preferencias locales</h2><p>Se guardan en este navegador.</p></div></div>
    <div class="settings-grid">
      <section class="panel settings-profile-panel"><h3>Datos del aspirante</h3><div class="settings-profile-form">
        <label class="setting-row setting-row--field"><div><strong>Carrera</strong><small>Se llena desde Moodle si está disponible; podés completarla manualmente.</small></div><input class="profile-input" type="text" data-profile-field="carrera" value="${esc(normalizeCareer(profile.carrera))}" placeholder="Ej. Medicina General"></label>
      </div></section>
      <section class="panel"><h3>Visual</h3><div class="detail-list">
        ${settingSwitch('dark', 'Modo oscuro', 'Alto contraste para ambientes con poca luz.')}
        ${settingSwitch('compact', 'Modo compacto', 'Reduce espacios verticales.')}
        ${settingSwitch('sound', 'Sonidos de interfaz', 'Activa señales breves al responder, avanzar y finalizar.')}
        ${settingSwitch('autoAdvance', 'Avance automático', 'Pasa al siguiente ítem al responder.')}
      </div></section>
      <section class="panel"><h3>Tiempo</h3>
        <div class="setting-row"><div><strong>Segundos por lámina</strong><small>${appState.settings.secondsPerPage}s sugeridos</small></div><input class="range" type="range" min="40" max="420" step="20" value="${appState.settings.secondsPerPage}" data-setting-range="secondsPerPage"></div>
        <div class="divider"></div>
        <button class="danger-btn" type="button" data-action="reset-test">Reiniciar respuestas del aspirante</button>
      </section>
    </div>`;
}

function settingSwitch(key, title, description) {
  return `<label class="setting-row"><div><strong>${title}</strong><small>${description}</small></div><span class="switch"><input type="checkbox" data-setting="${key}" ${appState.settings[key] ? 'checked' : ''}><span></span></span></label>`;
}

function renderStaff() {
  const content = $('#app-content');
  if (!content) return;
  if (appState.route === 'aspirantes') content.innerHTML = renderStaffApplicants();
  else if (appState.route === 'baremo') content.innerHTML = renderBaremo();
  else if (appState.route === 'ajustes') content.innerHTML = renderSettings();
  else content.innerHTML = renderStaffPanel();
}

function renderStaffPanel() {
  const list = records();
  const avg = list.length ? Math.round(list.reduce((sum, item) => sum + item.score, 0) / list.length) : '--';
  const top = list.length ? Math.max(...list.map(item => item.score)) : '--';
  return `
    <div class="screen-title"><div><p class="eyebrow">Personal</p><h2>Panel de aplicaciones</h2><p>${esc(appState.profile.username)}</p></div><button class="solid-btn" type="button" data-route="aspirantes">Ver aspirantes</button></div>
    <div class="staff-grid">
      <div class="metric"><strong>${list.length}</strong><span>reportes guardados</span></div>
      <div class="metric"><strong>${avg}</strong><span>promedio directo</span></div>
      <div class="metric"><strong>${top}</strong><span>mejor puntaje</span></div>
    </div>
    ${renderRecordsTable(list.slice(0, 8))}`;
}

function renderStaffApplicants() {
  const list = records();
  return `
    <div class="screen-title"><div><p class="eyebrow">Aspirantes</p><h2>Resultados locales</h2><p>${list.length} registros en este navegador.</p></div><button class="danger-btn" type="button" data-action="clear-records">Limpiar registros</button></div>
    ${renderRecordsTable(list)}`;
}

function renderRecordsTable(list) {
  if (!list.length) return '<div class="empty-state">Todavía no hay aplicaciones finalizadas.</div>';
  return `<section class="table-wrap"><table class="data-table"><thead><tr><th>Aspirante</th><th>Evaluación</th><th>Carrera</th><th>Puntaje</th><th>Percentil</th><th>CI</th><th>Fecha</th></tr></thead><tbody>${list.map(item => `<tr><td><strong>${esc(item.profile.fullName)}</strong><br><span class="muted tiny">CIF ${esc(item.profile.cif)}</span></td><td>${esc(item.testName || ACTIVE_TEST.shortName)}</td><td>${esc(normalizeCareer(item.profile.carrera))}</td><td>${item.score}/${item.total}</td><td>${item.percentile}</td><td>${item.ci}</td><td>${formatDate(item.endedAt)}</td></tr>`).join('')}</tbody></table></section>`;
}

function renderBaremo() {
  const columns = ['7','8','9','10','11','12','13','14','15','16','AN','AD','UN'];
  return `
    <div class="screen-title"><div><p class="eyebrow">Baremo</p><h2>Tabla de calificación</h2><p>Valores cargados desde la tabla de Weill.</p></div></div>
    <div class="baremo-table-wrap"><table class="data-table baremo-table"><thead><tr><th>Percentil</th>${columns.map(col => `<th>${col}</th>`).join('')}</tr></thead><tbody>${BAREMOS.map(row => `<tr><td>${row.p}</td>${columns.map(col => `<td>${row[col]}</td>`).join('')}</tr>`).join('')}</tbody></table></div>`;
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
  const pageOne = buildPdfSummaryPage(result);
  const pageTwo = buildPdfAnswersPage(result);
  return createPdfDocument([pageOne, pageTwo]);
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
  out += pdfText(42, 734, 24, 'Reporte psicometrico', 'F2');
  out += pdfText(42, 712, 10, 'Aplicacion digital con calificacion local por baremo.', 'F1', [0.39, 0.43, 0.46]);
  out += pdfRect(42, 628, 511, 62, [1, 1, 1], [0.82, 0.85, 0.86]);
  out += pdfText(58, 670, 9, 'Aspirante', 'F2', [0, 0.39, 0.43]);
  out += pdfText(58, 650, 13, result.profile.fullName, 'F2');
  out += pdfText(304, 670, 9, 'CIF', 'F2', [0, 0.39, 0.43]);
  out += pdfText(304, 650, 12, result.profile.cif, 'F1');
  out += pdfText(398, 670, 9, 'Carrera', 'F2', [0, 0.39, 0.43]);
  out += pdfText(398, 650, 10, normalizeCareer(result.profile.carrera), 'F1');
  out += pdfText(42, 594, 12, 'Resumen de calificacion', 'F2');
  const cards = [
    ['Puntaje directo', `${result.score}/${result.total}`],
    ['Percentil', result.percentile],
    ['CI estimado', result.ci],
    ['Baremo', result.normColumn]
  ];
  cards.forEach((card, i) => {
    const x = 42 + i * 128;
    out += pdfRect(x, 520, 112, 54, i === 0 ? [0.07, 0.08, 0.09] : [1, 1, 1], [0.82, 0.85, 0.86]);
    out += pdfText(x + 10, 556, 8, card[0], 'F2', i === 0 ? [0.86, 0.70, 0.16] : [0, 0.39, 0.43]);
    out += pdfText(x + 10, 532, 18, card[1], 'F2', i === 0 ? [1, 1, 1] : [0.07, 0.08, 0.09]);
  });
  out += pdfRect(42, 458, 511, 38, [1, 1, 1], [0.82, 0.85, 0.86]);
  out += pdfText(58, 482, 9, 'Interpretacion', 'F2', [0, 0.39, 0.43]);
  out += pdfText(58, 466, 12, result.classification, 'F2');
  out += pdfText(358, 482, 9, 'Modo examen', 'F2', [0, 0.39, 0.43]);
  out += pdfText(358, 466, 12, `${(result.securityEvents || []).length} incidencias`, 'F2');
  out += pdfText(42, 424, 12, 'Desempeno por lamina', 'F2');
  out += pdfRect(42, 392, 511, 24, [0.93, 0.95, 0.95], [0.82, 0.85, 0.86]);
  out += pdfText(58, 400, 9, 'Lamina', 'F2');
  out += pdfText(178, 400, 9, 'Aciertos', 'F2');
  out += pdfText(288, 400, 9, 'Respondidos', 'F2');
  out += pdfText(420, 400, 9, 'Estado', 'F2');
  result.pageScores.forEach((score, i) => {
    const y = 368 - i * 28;
    out += pdfRect(42, y, 511, 28, [1, 1, 1], [0.88, 0.90, 0.91]);
    out += pdfText(58, y + 10, 9, `Lamina ${i + 1}`, 'F1');
    out += pdfText(178, y + 10, 9, `${score}/12`, 'F2');
    out += pdfText(288, y + 10, 9, `${result.answers.slice(i * ITEMS_PER_PAGE, (i + 1) * ITEMS_PER_PAGE).filter(value => value !== null).length}/12`, 'F1');
    out += pdfText(420, y + 10, 9, score >= 8 ? 'Fuerte' : 'Revisar', 'F1');
  });
  out += pdfText(42, 188, 9, 'Nota tecnica', 'F2', [0, 0.39, 0.43]);
  out += pdfText(42, 170, 8, 'Este reporte resume la aplicacion digital del Test No Verbal de Weil. La interpretacion final debe ser revisada por personal autorizado.', 'F1', [0.39, 0.43, 0.46]);
  out += pdfLine(42, 54, 553, 54);
  out += pdfText(42, 36, 8, 'Universidad Americana - Sistema Digital de Aplicacion', 'F1', [0.39, 0.43, 0.46]);
  out += pdfText(516, 36, 8, 'Pagina 1/2', 'F1', [0.39, 0.43, 0.46]);
  return out;
}

function buildPdfAnswersPage(result) {
  let out = '';
  out += pdfRect(0, 0, 595, 842, [0.98, 0.97, 0.94]);
  out += pdfText(42, 800, 18, 'Detalle de respuestas', 'F2');
  out += pdfText(42, 780, 9, `${result.profile.fullName} - CIF ${result.profile.cif}`, 'F1', [0.39, 0.43, 0.46]);
  out += pdfRect(42, 742, 511, 24, [0.93, 0.95, 0.95], [0.82, 0.85, 0.86]);
  out += pdfText(56, 750, 9, 'Item', 'F2');
  out += pdfText(118, 750, 9, 'Resp.', 'F2');
  out += pdfText(174, 750, 9, 'Clave', 'F2');
  out += pdfText(236, 750, 9, 'Estado', 'F2');
  const cols = 3;
  const blockW = 164;
  result.answers.forEach((answer, index) => {
    const col = index % cols;
    const row = Math.floor(index / cols);
    const x = 42 + col * (blockW + 9);
    const y = 714 - row * 29;
    const ok = answer === correctAnswer(index);
    out += pdfRect(x, y, blockW, 24, [1, 1, 1], [0.88, 0.90, 0.91]);
    out += pdfText(x + 10, y + 8, 8, `${index + 1}`, 'F2');
    out += pdfText(x + 50, y + 8, 8, `${answer}`, 'F1');
    out += pdfText(x + 88, y + 8, 8, `${correctAnswer(index)}`, 'F1');
    out += pdfText(x + 124, y + 8, 8, ok ? 'OK' : 'Error', 'F2', ok ? [0.15, 0.45, 0.27] : [0.72, 0.23, 0.19]);
  });
  out += pdfLine(42, 54, 553, 54);
  out += pdfText(42, 36, 8, 'Documento generado localmente desde SAP-UAM.', 'F1', [0.39, 0.43, 0.46]);
  out += pdfText(516, 36, 8, 'Pagina 2/2', 'F1', [0.39, 0.43, 0.46]);
  return out;
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
  if (appState.result) {
    routeTo('test');
    return;
  }
  if (appState.startBusy) return;
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
    return;
  } finally {
    appState.startBusy = false;
    if (startButton?.isConnected) {
      startButton.disabled = false;
      startButton.textContent = answeredCount() ? 'Continuar evaluación' : 'Comenzar evaluación';
    }
  }
  if (!appState.startedAt) {
    appState.startedAt = new Date().toISOString();
    appState.focusLosses = 0;
    appState.securityEvents = [];
  }
  playSound('start');
  routeTo('test');
  if (isExamLocked()) startSecureExamMode();
  stateStore.publish('assessment-navigation', { reason: 'start' });
}

function selectAnswer(option) {
  if (appState.role !== 'aspirante' || appState.route !== 'test') return;
  if (!Number.isInteger(option) || option < 1 || option > ACTIVE_TEST.optionCount) return;
  appState.answers[appState.currentIndex] = option;
  playSound('tap');
  if (appState.settings.autoAdvance && appState.currentIndex < TOTAL_ITEMS - 1) {
    appState.currentIndex += 1;
    stateStore.publish('assessment-navigation', { reason: 'auto-advance' });
    return;
  }
  stateStore.publish('answer', { index: appState.currentIndex, option });
}

function requestFinishTest() {
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
    message: 'Todas las respuestas están completas. Al entregar, se cerrará la prueba y se generará la calificación.'
  });
  playSound('nav');
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
        <p class="eyebrow">${status === 'ready' ? 'Validación completa' : 'Revisión requerida'}</p>
        <h2 id="submit-dialog-title">${esc(title)}</h2>
        <p id="submit-dialog-message">${esc(message)}</p>
      </div>
      <div class="submit-dialog__stats" aria-label="Resumen de entrega">
        <div><strong>${answered}</strong><span>respondidas</span></div>
        <div><strong>${TOTAL_ITEMS - answered}</strong><span>pendientes</span></div>
      </div>
      ${status === 'incomplete' ? `
        <div class="submit-dialog__pending">
          <strong>Primer pendiente: ítem ${firstMissing + 1}</strong>
          <span>También faltan: ${esc(missingPreview)}${missing.length > 12 ? '...' : ''}</span>
        </div>` : `
        <div class="submit-dialog__ready">
          <strong>Listo para entregar</strong>
          <span>La sesión quedará cerrada y podrás descargar el PDF desde Resultados.</span>
        </div>`}
      <div class="submit-dialog__actions">
        <button class="ghost-btn" type="button" data-action="close-submit-dialog">Seguir revisando</button>
        ${status === 'incomplete'
          ? `<button class="solid-btn" type="button" data-action="go-first-missing" data-target-index="${firstMissing}">Ir al primer pendiente</button>`
          : '<button class="solid-btn" type="button" data-action="confirm-submit-test">Entregar y calificar</button>'}
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
      submitButton.textContent = 'Entregar y calificar';
    }
  }
  const score = Number.isInteger(remoteSubmission?.directScore) ? remoteSubmission.directScore : totalScore();
  const normColumn = resolveNormColumn(appState.profile);
  const percentile = percentileFromScore(score, normColumn);
  const ci = ciFromPercentile(percentile);
  const endedAt = new Date().toISOString();
  const durationSec = appState.startedAt ? (new Date(endedAt).getTime() - new Date(appState.startedAt).getTime()) / 1000 : 0;
  const result = {
    id: `${appState.profile.cif}-${Date.now()}`,
    testId: ACTIVE_TEST.id,
    testVersion: ACTIVE_TEST.version,
    testName: ACTIVE_TEST.shortName,
    profile: appState.profile,
    answers: [...appState.answers],
    score,
    total: TOTAL_ITEMS,
    pageScores: Array.isArray(remoteSubmission?.pageScores) && remoteSubmission.pageScores.length === TOTAL_PAGES
      ? remoteSubmission.pageScores
      : Array.from({ length: TOTAL_PAGES }, (_, page) => pageScore(page)),
    percentile,
    ci,
    normColumn,
    classification: classifyCI(ci),
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
  toast('Prueba calificada y guardada.', 'ok');
  routeTo('resultados');
}

function resetTest() {
  if (!window.confirm('¿Reiniciar las respuestas de esta sesión?')) return;
  const keepLocked = isExamLocked();
  appState.answers = Array(TOTAL_ITEMS).fill(null);
  appState.currentIndex = 0;
  appState.startedAt = keepLocked ? new Date().toISOString() : null;
  appState.result = null;
  if (!keepLocked) {
    appState.focusLosses = 0;
    appState.securityEvents = [];
  }
  if (appState.profile?.cif) {
    draftRepository.remove(sessionContext());
  }
  if (keepLocked) recordSecurityEvent('reset', 'El aspirante reinicio respuestas durante la prueba.');
  playSound('nav');
  toast('Respuestas reiniciadas.', 'info');
  stateStore.publish('assessment-reset');
}

function handleDocumentClick(event) {
  const actionTarget = event.target.closest('[data-action]');
  const routeTarget = event.target.closest('[data-route]');
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
    if (action === 'enter-demo') {
      enterAspirante({ fullName: 'Usuario de demostración', cif: 'DEMO', edad: 18, grupo: 'UN', carrera: '' });
      return;
    }
    if (action === 'logout') logout();
    if (action === 'go-home') routeTo(appState.role === 'staff' ? 'panel' : 'inicio');
    if (action === 'start-test') startTest();
    if (action === 'prev-item') { appState.currentIndex = Math.max(0, appState.currentIndex - 1); playSound('nav'); stateStore.publish('assessment-navigation', { reason: 'previous' }); }
    if (action === 'next-item') { appState.currentIndex = Math.min(TOTAL_ITEMS - 1, appState.currentIndex + 1); playSound('nav'); stateStore.publish('assessment-navigation', { reason: 'next' }); }
    if (action === 'finish-test') requestFinishTest();
    if (action === 'close-submit-dialog') closeSubmitDialog();
    if (action === 'go-first-missing') goFirstMissing(actionTarget.dataset.targetIndex);
    if (action === 'confirm-submit-test') completeTest();
    if (action === 'restore-fullscreen') enterFullscreen();
    if (action === 'download-pdf') downloadResultPdf();
    if (action === 'reset-test') resetTest();
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
    appState.currentIndex = Number(itemTarget.dataset.item);
    playSound('nav');
    stateStore.publish('assessment-navigation', { reason: 'item' });
    return;
  }
  if (pageTarget) {
    appState.currentIndex = Number(pageTarget.dataset.page) * ITEMS_PER_PAGE;
    playSound('nav');
    stateStore.publish('assessment-navigation', { reason: 'section' });
  }
}

function handleDocumentChange(event) {
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

function handleKeydown(event) {
  if (isTypingField(event.target)) return;
  const dialog = $('#submit-dialog');
  if (dialog && !dialog.classList.contains('app-hidden')) {
    if (event.key === 'Escape') {
      event.preventDefault();
      closeSubmitDialog();
      return;
    }
    if (event.key === 'Tab') {
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
    return;
  }
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
stateStore.subscribe('assessment-navigation', render);
stateStore.subscribe('assessment-reset', render);

$('#unified-id')?.addEventListener('input', validateLogin);
$('#fullname')?.addEventListener('input', validateLogin);
$('#cedula')?.addEventListener('input', validateLogin);
$('#unified-pass')?.addEventListener('input', validateLogin);

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

setInterval(() => {
  const clock = $('#test-clock');
  if (clock && appState.route === 'test') clock.textContent = elapsedLabel();
}, 1000);

setInterval(renewRemoteAttemptLease, 60000);

applySettings();
$('#demo-access')?.classList.toggle('app-hidden', !STATIC_PAGES_MODE);
validateLogin();
restoreSessionFromBackend();
