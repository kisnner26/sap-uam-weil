(function initAdmissionUi(global) {
  'use strict';

  // Vistas del flujo de admision (convocatorias, codigo de acceso, consentimiento, resultados del personal).
  // Solo construyen HTML y transforman datos; las llamadas a la API y los eventos viven en app.js.

  const TIME_ZONE = 'America/Managua';

  const SESSION_STATE = { UPCOMING: 'Próxima', OPEN: 'Abierta', CLOSED: 'Cerrada', CANCELLED: 'Cancelada' };
  const PROGRESS = {
    INVITED: 'Invitado', CLAIMED: 'Código canjeado', IN_PROGRESS: 'En curso',
    SUBMITTED: 'Entregada', INVALIDATED: 'Invalidada', REVOKED: 'Revocada'
  };

  function esc(value) {
    return String(value ?? '').replace(/[&<>"']/g, ch => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[ch]));
  }

  function formatDateTime(value) {
    if (!value) return '—';
    const date = new Date(value);
    if (Number.isNaN(date.getTime())) return '—';
    return date.toLocaleString('es-NI', { timeZone: TIME_ZONE, day: '2-digit', month: 'short', year: 'numeric', hour: '2-digit', minute: '2-digit' });
  }

  // <input type="datetime-local"> trabaja en la hora local del navegador; el servidor guarda instantes UTC.
  function toLocalInput(value) {
    if (!value) return '';
    const date = new Date(value);
    if (Number.isNaN(date.getTime())) return '';
    const pad = n => String(n).padStart(2, '0');
    return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}T${pad(date.getHours())}:${pad(date.getMinutes())}`;
  }

  function fromLocalInput(value) {
    const date = new Date(value);
    return Number.isNaN(date.getTime()) ? null : date.toISOString();
  }

  /** Una linea por aspirante: "correo", "correo, nombre" o "correo, nombre, +15" (minutos adicionales). */
  function parseApplicants(text) {
    const applicants = [];
    const invalid = [];
    for (const raw of String(text || '').split(/\r?\n/)) {
      const line = raw.trim();
      if (!line) continue;
      const parts = line.split(/[,;\t]/).map(part => part.trim());
      const email = parts[0].toLowerCase();
      if (!/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(email)) { invalid.push(line); continue; }
      const entry = { email };
      const extra = parts.find((part, index) => index > 0 && /^\+\d{1,3}$/.test(part));
      if (extra) entry.extraMinutes = Number(extra.slice(1));
      const name = parts.slice(1).find(part => part && !/^\+\d{1,3}$/.test(part));
      if (name) entry.fullName = name;
      applicants.push(entry);
    }
    return { applicants, invalid };
  }

  // ------------------------------------------------------------------ aspirante

  /** Texto y accion de la tarjeta principal del inicio, segun lo que el servidor dice que le corresponde al aspirante. */
  function aspirantHome(availability, context = {}) {
    const { count = 0, hasResult = false, error = '' } = context;
    if (hasResult || availability?.state === 'SUBMITTED') {
      return {
        badge: 'Entregada', title: 'Tu entrega está registrada',
        text: 'Tu constancia confirma que el equipo recibió las respuestas. El resultado lo comunica Admisión.',
        action: hasResult ? '<button class="solid-btn" type="button" data-route="resultados">Ver constancia de entrega</button>' : ''
      };
    }
    if (!availability) {
      return {
        badge: error ? 'Sin conexión' : 'Cargando', title: error ? 'No pudimos consultar tu evaluación' : 'Consultando tu evaluación…',
        text: error || 'Un momento, por favor.',
        action: error ? '<button class="ghost-btn" type="button" data-action="reload-availability">Reintentar</button>' : ''
      };
    }
    const when = `${formatDateTime(availability.opensAt)} a ${formatDateTime(availability.closesAt)}`;
    switch (availability.state) {
      case 'NO_ENROLLMENT':
        return {
          badge: 'Sin habilitar', title: 'Ingresá tu código de acceso',
          text: 'Te lo enviamos por correo con la invitación. Es de un solo uso: no lo compartas.',
          action: claimForm()
        };
      case 'UPCOMING':
        return {
          badge: 'Próxima', title: 'Tu evaluación todavía no abre',
          text: `${availability.sessionName}. Abre ${formatDateTime(availability.opensAt)} (hora de Nicaragua).`, action: ''
        };
      case 'CLOSED':
        return {
          badge: 'Cerrada', title: 'La convocatoria ya cerró',
          text: 'Escribile a Admisión si crees que es un error.', action: ''
        };
      case 'CANCELLED':
        return {
          badge: 'Cancelada', title: 'La convocatoria fue cancelada',
          text: 'Admisión te avisará si hay una nueva fecha.', action: ''
        };
      case 'INVALIDATED':
        return {
          badge: 'En revisión', title: 'Tu evaluación no pudo registrarse',
          text: 'Admisión se pondrá en contacto con vos para indicarte los pasos a seguir.', action: ''
        };
      case 'IN_PROGRESS':
      case 'OPEN': {
        const resume = availability.state === 'IN_PROGRESS' || count > 0;
        const minutes = availability.minutes ? `Tenés ${availability.minutes} minutos desde que comenzás. ` : '';
        if (availability.needsConsent) {
          return {
            badge: 'Falta un paso', title: 'Antes de comenzar, leé el aviso de privacidad',
            text: `${availability.sessionName}. ${minutes}Necesitamos tu consentimiento para tratar tus datos.`,
            action: '<button class="solid-btn" type="button" data-action="open-consent">Leer y aceptar</button>'
          };
        }
        return {
          badge: resume ? 'En progreso' : 'Habilitada', title: resume ? 'Continuá tu evaluación' : 'Ya podés comenzar',
          text: `${availability.sessionName}. ${minutes}Cierra ${formatDateTime(availability.closesAt)}.`,
          action: `<button class="solid-btn" type="button" data-action="start-test">${resume ? 'Continuar evaluación' : 'Comenzar evaluación'}</button>`
        };
      }
      default:
        return { badge: 'Asignada', title: 'Tu evaluación', text: when, action: '' };
    }
  }

  function claimForm() {
    return `<form id="form-claim" class="claim-form" autocomplete="off">
      <label for="claim-code">Código de acceso</label>
      <div class="claim-form__row">
        <input id="claim-code" name="code" type="text" inputmode="text" autocapitalize="characters" spellcheck="false" maxlength="40" placeholder="XXXX-XXXX-XXXX" aria-describedby="claim-error" required>
        <button class="solid-btn" type="submit">Canjear</button>
      </div>
      <p id="claim-error" class="form-error" role="alert"></p>
    </form>`;
  }

  /** El aviso llega del servidor como texto con titulos "#", listas "-", citas ">" y parrafos; se escapa todo antes de formatear. */
  function noticeHtml(markdown) {
    const out = [];
    let list = null;
    let para = null;
    let quote = null;
    const flush = () => {
      if (list) out.push(`<ul>${list.map(item => `<li>${inline(item)}</li>`).join('')}</ul>`);
      if (para) out.push(`<p>${para.map(inline).join('<br>')}</p>`);
      if (quote) out.push(`<blockquote>${quote.map(inline).join(' ')}</blockquote>`);
      list = para = quote = null;
    };
    for (const raw of String(markdown || '').split('\n')) {
      const line = raw.trim();
      let match;
      if (!line) flush();
      else if ((match = /^#{1,3}\s+(.*)$/.exec(line))) { flush(); out.push(`<h4>${esc(match[1])}</h4>`); }
      else if ((match = /^[-*]\s+(.*)$/.exec(line))) { if (!list) { flush(); list = []; } list.push(match[1]); }
      else if ((match = /^>\s?(.*)$/.exec(line))) { if (!quote) { flush(); quote = []; } quote.push(match[1]); }
      else { if (!para) { flush(); para = []; } para.push(line); }
    }
    flush();
    return out.join('');
  }

  function inline(text) {
    return esc(text).replace(/\*\*([^*]+)\*\*/g, '<strong>$1</strong>');
  }

  function consentView(notice, busy) {
    if (!notice) return '<div class="empty-state">Cargando aviso de privacidad…</div>';
    return `<div class="screen-title"><div><p class="eyebrow">Privacidad</p><h2>Aviso de privacidad</h2><p>Versión ${esc(notice.version)}. Leélo completo antes de aceptar.</p></div></div>
      <section class="panel consent-panel" id="consent-panel" aria-busy="${busy ? 'true' : 'false'}">
        <article class="consent-text" tabindex="0" aria-label="Texto del aviso de privacidad">${noticeHtml(notice.text)}</article>
        <div class="consent-check">
          <input type="checkbox" id="consent-check" ${busy ? 'disabled' : ''} aria-describedby="consent-label">
          <label id="consent-label" for="consent-check">Leí el aviso y acepto el tratamiento de mis datos para el proceso de admisión.</label>
          <button class="ghost-btn consent-check__toggle" type="button" data-action="toggle-consent" aria-controls="consent-check">Marcar como leído</button>
        </div>
        <p id="consent-error" class="form-error" role="alert"></p>
        <div class="consent-actions">
          <button class="ghost-btn" type="button" data-route="inicio">Volver</button>
          <button class="solid-btn" type="button" data-action="accept-consent" disabled>Aceptar y continuar</button>
        </div>
      </section>`;
  }

  // ------------------------------------------------------------------ personal: convocatorias

  function badge(label, kind) {
    return `<span class="pill pill--${esc(kind)}">${esc(label)}</span>`;
  }

  function sessionsView(sessions, error) {
    const head = `<div class="screen-title"><div><p class="eyebrow">Admisión</p><h2>Convocatorias</h2><p>Definí cuándo se puede rendir y quiénes están habilitados.</p></div>
      <div class="title-actions"><button class="ghost-btn" type="button" data-action="reload-sessions">Actualizar</button><button class="solid-btn" type="button" data-action="new-session">Nueva convocatoria</button></div></div>`;
    if (error) return `${head}<div class="empty-state">${esc(error)}<br><button class="ghost-btn" type="button" data-action="reload-sessions">Reintentar</button></div>`;
    if (!sessions) return `${head}<div class="empty-state">Cargando convocatorias…</div>`;
    if (!sessions.length) return `${head}<div class="empty-state">Todavía no hay convocatorias. Creá la primera para habilitar aspirantes.</div>`;
    const kind = { UPCOMING: 'info', OPEN: 'ok', CLOSED: 'muted', CANCELLED: 'err' };
    return `${head}<section class="table-wrap"><table class="data-table"><caption class="sr-only">Convocatorias de evaluación</caption><thead><tr><th scope="col">Convocatoria</th><th scope="col">Ventana</th><th scope="col">Estado</th><th scope="col">Habilitados</th><th scope="col">Canjearon</th><th scope="col">Entregaron</th><th scope="col">Invalidadas</th><th scope="col"><span class="sr-only">Acciones</span></th></tr></thead><tbody>${sessions.map(s => `<tr>
      <td><strong>${esc(s.name)}</strong><br><span class="muted tiny">${s.timeLimitMinutes} min${s.defaultTime ? ' (predeterminado)' : ''}</span></td>
      <td class="tiny">${esc(formatDateTime(s.opensAt))}<br>${esc(formatDateTime(s.closesAt))}</td>
      <td>${badge(SESSION_STATE[s.state] || s.state, kind[s.state] || 'muted')}</td>
      <td>${s.enrolled}</td><td>${s.claimed}</td><td>${s.submitted}</td><td>${s.invalidated}</td>
      <td class="row-actions"><button class="ghost-btn" type="button" data-action="open-session" data-session-id="${s.id}">Aspirantes</button>${s.state === 'CANCELLED' ? '' : `<button class="ghost-btn" type="button" data-action="edit-session" data-session-id="${s.id}">Editar</button><button class="danger-btn" type="button" data-action="cancel-session" data-session-id="${s.id}">Cancelar</button>`}</td></tr>`).join('')}</tbody></table></section>`;
  }

  function sessionFormHtml(session) {
    const editing = Boolean(session);
    return `<form id="form-session" class="dialog-form" data-session-id="${editing ? session.id : ''}">
      <label><span>Nombre</span><input name="name" type="text" minlength="3" maxlength="160" value="${esc(session?.name || '')}" required></label>
      <div class="dialog-form__split">
        <label><span>Abre</span><input name="opensAt" type="datetime-local" value="${esc(toLocalInput(session?.opensAt))}" required></label>
        <label><span>Cierra</span><input name="closesAt" type="datetime-local" value="${esc(toLocalInput(session?.closesAt))}" required></label>
      </div>
      <label><span>Minutos para responder (vacío = predeterminado)</span><input name="timeLimitMinutes" type="number" min="5" max="240" value="${esc(session && !session.defaultTime ? session.timeLimitMinutes : '')}"></label>
      <label><span>Indicaciones para el aspirante (opcional)</span><textarea name="instructions" rows="3" maxlength="500">${esc(session?.instructions || '')}</textarea></label>
      <p id="dialog-error" class="form-error" role="alert"></p>
      <div class="dialog-actions"><button class="ghost-btn" type="button" data-action="close-dialog">Cancelar</button><button class="solid-btn" type="submit">${editing ? 'Guardar cambios' : 'Crear convocatoria'}</button></div>
    </form>`;
  }

  function enrollmentsView(session, rows, lastResult, error) {
    const back = '<button class="ghost-btn" type="button" data-route="convocatorias">← Convocatorias</button>';
    const head = `<div class="screen-title"><div><p class="eyebrow">Convocatoria</p><h2>${esc(session?.name || '')}</h2><p>${session ? `${esc(formatDateTime(session.opensAt))} → ${esc(formatDateTime(session.closesAt))} · ${esc(SESSION_STATE[session.state] || session.state)}` : ''}</p></div><div class="title-actions">${back}<button class="ghost-btn" type="button" data-action="reload-enrollments">Actualizar</button></div></div>`;
    const paste = `<section class="panel enroll-panel"><h3>Habilitar aspirantes</h3>
      <form id="form-enroll" class="dialog-form">
        <label><span>Un aspirante por línea: correo, nombre, +minutos extra (los dos últimos son opcionales)</span>
        <textarea name="applicants" rows="5" placeholder="ana@ejemplo.com, Ana Pérez&#10;luis@ejemplo.com, Luis Mora, +15" required></textarea></label>
        <p id="enroll-error" class="form-error" role="alert"></p>
        <button class="solid-btn" type="submit">Habilitar y enviar invitaciones</button>
      </form>${enrollResultHtml(lastResult)}</section>`;
    let table;
    if (error) table = `<div class="empty-state">${esc(error)}</div>`;
    else if (!rows) table = '<div class="empty-state">Cargando aspirantes…</div>';
    else if (!rows.length) table = '<div class="empty-state">Todavía no hay aspirantes habilitados en esta convocatoria.</div>';
    else {
      const kind = { INVITED: 'muted', CLAIMED: 'info', IN_PROGRESS: 'warn', SUBMITTED: 'ok', INVALIDATED: 'err', REVOKED: 'err' };
      table = `<section class="table-wrap"><table class="data-table"><caption class="sr-only">Aspirantes habilitados</caption><thead><tr><th scope="col">Aspirante</th><th scope="col">Estado</th><th scope="col">Minutos extra</th><th scope="col">Canjeó</th><th scope="col"><span class="sr-only">Acciones</span></th></tr></thead><tbody>${rows.map(r => `<tr>
        <td><strong>${esc(r.fullName || r.email)}</strong><br><span class="muted tiny">${esc(r.email)}${r.reappliesAttemptId ? ' · reaplicación' : ''}</span></td>
        <td>${badge(PROGRESS[r.progress] || r.progress, kind[r.progress] || 'muted')}</td>
        <td>${r.extraMinutes ? `+${r.extraMinutes}` : '—'}</td><td class="tiny">${esc(formatDateTime(r.claimedAt))}</td>
        <td class="row-actions">${['INVITED', 'CLAIMED'].includes(r.progress) ? `<button class="ghost-btn" type="button" data-action="resend-invitation" data-enrollment-id="${r.id}">Reenviar código</button>` : ''}${['INVITED', 'CLAIMED'].includes(r.progress) ? `<button class="ghost-btn" type="button" data-action="adjust-enrollment" data-enrollment-id="${r.id}">Ajustar</button><button class="danger-btn" type="button" data-action="revoke-enrollment" data-enrollment-id="${r.id}">Revocar</button>` : ''}</td></tr>`).join('')}</tbody></table></section>`;
    }
    return `${head}${paste}${table}`;
  }

  function enrollResultHtml(result) {
    if (!result) return '';
    const created = (result.created || []).length;
    const skipped = result.skipped || [];
    return `<div class="enroll-result" role="status"><strong>${created} habilitado${created === 1 ? '' : 's'}.</strong>${skipped.length ? ` No se habilitaron ${skipped.length}:<ul>${skipped.map(item => `<li>${esc(item.email)} — ${esc(item.reason)}</li>`).join('')}</ul>` : ''}</div>`;
  }

  function adjustFormHtml(row) {
    return `<form id="form-adjust" class="dialog-form" data-enrollment-id="${row.id}">
      <p class="muted">${esc(row.fullName || row.email)}</p>
      <label><span>Minutos adicionales (adecuación)</span><input name="extraMinutes" type="number" min="0" max="120" value="${esc(row.extraMinutes)}"></label>
      <label><span>Edad</span><input name="age" type="number" min="3" max="120" value="${esc(row.age ?? '')}"></label>
      <label><span>Grupo de baremo</span><select name="normGroup">${['UN', 'AN', 'AD', '7-16'].map(g => `<option value="${g}" ${row.normGroup === g ? 'selected' : ''}>${g}</option>`).join('')}</select></label>
      <label><span>Nota interna (opcional)</span><input name="note" type="text" maxlength="300" value="${esc(row.note || '')}"></label>
      <p id="dialog-error" class="form-error" role="alert"></p>
      <div class="dialog-actions"><button class="ghost-btn" type="button" data-action="close-dialog">Cancelar</button><button class="solid-btn" type="submit">Guardar</button></div>
    </form>`;
  }

  // ------------------------------------------------------------------ personal: resultados

  function resultsToolbar(sessions, filters) {
    const f = filters || {};
    return `<form id="form-results-filter" class="toolbar" role="search">
      <label><span>Convocatoria</span><select name="sessionId"><option value="">Todas</option>${(sessions || []).map(s => `<option value="${s.id}" ${String(f.sessionId) === String(s.id) ? 'selected' : ''}>${esc(s.name)}</option>`).join('')}</select></label>
      <label><span>Estado</span><select name="status">${[['', 'Todas'], ['valid', 'Válidas'], ['invalid', 'Invalidadas']].map(([v, l]) => `<option value="${v}" ${(f.status || '') === v ? 'selected' : ''}>${l}</option>`).join('')}</select></label>
      <label class="toolbar__grow"><span>Buscar</span><input name="q" type="search" maxlength="80" placeholder="Nombre o correo" value="${esc(f.q || '')}"></label>
      <button class="ghost-btn" type="submit">Filtrar</button>
      <button class="solid-btn" type="button" data-action="export-results">Exportar CSV</button>
    </form>`;
  }

  function attemptsTable(rows, totalItems) {
    if (!rows.length) return '<div class="empty-state">No hay pruebas con esos filtros.</div>';
    return `<section class="table-wrap"><table class="data-table"><caption class="sr-only">Resultados filtrados de aspirantes</caption><thead><tr><th scope="col">Aspirante</th><th scope="col">Convocatoria</th><th scope="col">Puntaje</th><th scope="col">Percentil</th><th scope="col">CI</th><th scope="col">Clasificación</th><th scope="col">Incidencias</th><th scope="col">Entregada</th><th scope="col">Estado</th><th scope="col"><span class="sr-only">Acciones</span></th></tr></thead><tbody>${rows.map(item => {
      const state = item.invalidated ? badge('Invalidada', 'err') : item.reviewed ? badge('Revisada', 'ok') : badge('Sin revisar', 'warn');
      const flags = [item.autoSubmitted ? 'cerrada por tiempo' : '', item.extraMinutes ? `+${item.extraMinutes} min` : ''].filter(Boolean).join(' · ');
      return `<tr class="${item.invalidated ? 'is-invalid' : ''}">
        <td><strong>${esc(item.fullName)}</strong><br><span class="muted tiny">${esc(item.email || '')}</span></td>
        <td class="tiny">${esc(item.sessionName || '—')}${flags ? `<br><span class="muted">${esc(flags)}</span>` : ''}</td>
        <td>${item.directScore}/${totalItems}</td><td>${item.percentile}</td><td>${item.ci}</td><td>${esc(item.classification)}</td>
        <td>${eventsHtml(item.securityEvents)}</td><td class="tiny">${esc(formatDateTime(item.submittedAt))}</td>
        <td>${state}${item.invalidated ? `<br><span class="muted tiny">${esc(item.invalidReason || '')}</span>` : ''}</td>
        <td class="row-actions">${item.invalidated
          ? `<button class="ghost-btn" type="button" data-action="reapply-attempt" data-attempt-id="${item.id}">Reaplicar</button>`
          : `${item.reviewed ? '' : `<button class="ghost-btn" type="button" data-action="review-attempt" data-attempt-id="${item.id}">Marcar revisada</button>`}<button class="danger-btn" type="button" data-action="invalidate-attempt" data-attempt-id="${item.id}">Invalidar</button>`}</td></tr>`;
    }).join('')}</tbody></table></section>`;
  }

  function eventsHtml(events = []) {
    if (!events.length) return '0';
    return `<details><summary>${events.length}</summary>${events.map(e => `<p class="tiny"><strong>${esc(e.type)}</strong><br>${esc(e.detail || 'Sin detalle')}<br>${esc(formatDateTime(e.occurredAt))}</p>`).join('')}</details>`;
  }

  function invalidateFormHtml(row) {
    return `<form id="form-invalidate" class="dialog-form" data-attempt-id="${row.id}">
      <p>Vas a invalidar la prueba de <strong>${esc(row.fullName)}</strong>. Queda registrada con tu nombre y la justificación; el aspirante no verá el motivo.</p>
      <label><span>Justificación (mínimo 10 caracteres)</span><textarea name="reason" rows="3" minlength="10" maxlength="500" required></textarea></label>
      <p id="dialog-error" class="form-error" role="alert"></p>
      <div class="dialog-actions"><button class="ghost-btn" type="button" data-action="close-dialog">Volver</button><button class="danger-btn" type="submit">Invalidar prueba</button></div>
    </form>`;
  }

  function reapplyFormHtml(row, sessions) {
    const options = (sessions || []).filter(s => s.state === 'OPEN' || s.state === 'UPCOMING');
    return `<form id="form-reapply" class="dialog-form" data-attempt-id="${row.id}">
      <p>Se habilita una nueva oportunidad para <strong>${esc(row.fullName)}</strong> en la convocatoria que elijas. Recibirá un código nuevo por correo.</p>
      <label><span>Convocatoria</span><select name="sessionId" required>${options.length ? options.map(s => `<option value="${s.id}">${esc(s.name)}</option>`).join('') : '<option value="">No hay convocatorias abiertas ni próximas</option>'}</select></label>
      <label><span>Minutos adicionales (opcional)</span><input name="extraMinutes" type="number" min="0" max="120" value="${esc(row.extraMinutes || 0)}"></label>
      <p id="dialog-error" class="form-error" role="alert"></p>
      <div class="dialog-actions"><button class="ghost-btn" type="button" data-action="close-dialog">Cancelar</button><button class="solid-btn" type="submit" ${options.length ? '' : 'disabled'}>Habilitar reaplicación</button></div>
    </form>`;
  }

  function dialogHtml(title, bodyHtml) {
    return `<div class="submit-dialog" role="dialog" aria-modal="true" aria-labelledby="staff-dialog-title">
      <div class="submit-dialog__backdrop" data-action="close-dialog"></div>
      <div class="submit-dialog__card"><div class="submit-dialog__head"><h2 id="staff-dialog-title">${esc(title)}</h2></div>${bodyHtml}</div></div>`;
  }

  const api = Object.freeze({
    esc, formatDateTime, toLocalInput, fromLocalInput, parseApplicants, aspirantHome, claimForm, noticeHtml, consentView,
    sessionsView, sessionFormHtml, enrollmentsView, adjustFormHtml, resultsToolbar, attemptsTable,
    invalidateFormHtml, reapplyFormHtml, dialogHtml
  });
  if (typeof module !== 'undefined' && module.exports) module.exports = api;
  if (global) global.SAP_UAM_ADMISSION = api;
})(typeof window !== 'undefined' ? window : null);
