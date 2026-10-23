(function initPuzzleView(global) {
  'use strict';

  // Vista de "rompecabezas" de cada item: la figura base tiene un hueco y las
  // 8 opciones son piezas que se arrastran hasta el. Arrastrar la pieza N
  // equivale a responder la opcion N. Se usan eventos de puntero (no el
  // arrastre nativo del navegador) porque la prueba bloqueada registra como
  // incidencia los eventos `dragstart` y `drop`, y asi tambien funciona con el dedo.

  const DRAG_THRESHOLD = 6;
  const DROP_TOLERANCE = 18;

  function pct(value, total) {
    return `${Math.round((value / total) * 10000) / 100}%`;
  }

  /** Posicion del hueco sobre la figura, en porcentajes (la figura se escala libremente). */
  function slotStyle(entry) {
    const [x, y, w, h] = entry.patch;
    return `left:${pct(x, entry.width)};top:${pct(y, entry.height)};width:${pct(w, entry.width)};height:${pct(h, entry.height)}`;
  }

  /** ¿El punto cae sobre el hueco (con un margen para soltar sin apuntar al pixel)? */
  function hitTest(rect, point, tolerance = DROP_TOLERANCE) {
    return point.x >= rect.left - tolerance && point.x <= rect.right + tolerance
      && point.y >= rect.top - tolerance && point.y <= rect.bottom + tolerance;
  }

  function dragDistance(from, to) {
    return Math.hypot(to.x - from.x, to.y - from.y);
  }

  /**
   * Engancha el arrastre a todo el documento (delegacion de eventos), de modo
   * que sigue funcionando aunque la pantalla se vuelva a dibujar.
   *  - piezas:        [data-piece="N"]       -> sueltas sobre [data-slot] responden N
   *  - pieza colocada: [data-slot-piece="N"] -> sacada del hueco quita la respuesta
   */
  function attachDrag(doc, hooks) {
    let drag = null;
    let suppressClick = false;

    function slotElement() {
      return doc.querySelector('[data-slot]');
    }

    function overSlot(point) {
      const slot = slotElement();
      return !!slot && hitTest(slot.getBoundingClientRect(), point);
    }

    function cleanup() {
      if (!drag) return;
      drag.ghost?.remove();
      drag.source.classList.remove('is-dragging');
      slotElement()?.classList.remove('is-over');
      doc.body.classList.remove('is-puzzle-dragging');
      try { drag.source.releasePointerCapture?.(drag.pointerId); } catch { /* el puntero ya se libero */ }
      drag = null;
    }

    doc.addEventListener('pointerdown', event => {
      if (event.pointerType === 'mouse' && event.button !== 0) return;
      const source = event.target.closest('[data-piece], [data-slot-piece]');
      if (!source || !hooks.canInteract()) return;
      const fromSlot = source.hasAttribute('data-slot-piece');
      drag = {
        source,
        fromSlot,
        option: Number(fromSlot ? source.dataset.slotPiece : source.dataset.piece),
        pointerId: event.pointerId,
        start: { x: event.clientX, y: event.clientY },
        moved: false,
        ghost: null
      };
      try { source.setPointerCapture?.(event.pointerId); } catch { /* sin captura el arrastre sigue por el documento */ }
    });

    doc.addEventListener('pointermove', event => {
      if (!drag || event.pointerId !== drag.pointerId) return;
      const point = { x: event.clientX, y: event.clientY };
      if (!drag.moved) {
        if (dragDistance(drag.start, point) < DRAG_THRESHOLD) return;
        drag.moved = true;
        const picture = drag.source.querySelector('img') || drag.source;
        const box = picture.getBoundingClientRect();
        drag.ghost = doc.createElement('img');
        drag.ghost.className = 'puzzle-ghost';
        drag.ghost.alt = '';
        drag.ghost.draggable = false;
        drag.ghost.src = picture.currentSrc || picture.src;
        drag.ghost.style.width = `${box.width}px`;
        drag.ghost.style.height = `${box.height}px`;
        doc.body.appendChild(drag.ghost);
        drag.source.classList.add('is-dragging');
        doc.body.classList.add('is-puzzle-dragging');
      }
      drag.ghost.style.left = `${point.x}px`;
      drag.ghost.style.top = `${point.y}px`;
      slotElement()?.classList.toggle('is-over', overSlot(point));
    });

    function finish(event) {
      if (!drag || event.pointerId !== drag.pointerId) return;
      const { moved, fromSlot, option } = drag;
      const dropped = moved && overSlot({ x: event.clientX, y: event.clientY });
      cleanup();
      if (!moved) return;
      suppressClick = true;
      setTimeout(() => { suppressClick = false; }, 0);
      if (dropped) hooks.onDrop(option);
      else if (fromSlot) hooks.onClear();
    }

    doc.addEventListener('pointerup', finish);
    doc.addEventListener('pointercancel', event => {
      if (drag && event.pointerId === drag.pointerId) cleanup();
    });
    // Tras arrastrar, el navegador igual emite un clic sobre la pieza: se descarta.
    doc.addEventListener('click', event => {
      if (!suppressClick) return;
      event.preventDefault();
      event.stopPropagation();
      suppressClick = false;
    }, true);
  }

  global.SAP_UAM_PUZZLE_VIEW = Object.freeze({ slotStyle, hitTest, dragDistance, attachDrag, DRAG_THRESHOLD, DROP_TOLERANCE });
})(typeof window !== 'undefined' ? window : globalThis);
