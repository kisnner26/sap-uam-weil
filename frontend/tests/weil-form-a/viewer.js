(function initWeilViewer(global) {
  'use strict';

  // Visor de láminas de Weil: cada ítem es una figura con un hueco y ocho piezas.
  // Arrastrar o tocar la pieza N equivale a responder la opción N.
  global.SAP_UAM_TEST_VIEWERS.register('weil-form-a', function createWeilViewer(context) {
    const { test, state, materialUrl, esc, $, $$ } = context;

    function item(itemIndex) {
      const geometry = test.media.puzzleGeometry?.(itemIndex);
      if (!geometry || !state.remoteAttempt?.id) return null;
      return {
        ...geometry,
        base: materialUrl(itemIndex, 'base'),
        pieces: Array.from({ length: test.optionCount }, (_, index) => materialUrl(itemIndex, `p${index + 1}`))
      };
    }

    function renderStage(puzzle, { local, page, selected }) {
      const label = `Ejercicio ${local + 1} de ${test.sections[page].label}`;
      const placed = selected ? puzzle.pieces[selected - 1] : '';
      return `
        <figure class="question-figure puzzle">
          <div class="puzzle-stage">
            <div class="puzzle-board" style="--pw:${puzzle.width};--ph:${puzzle.height}">
              <img class="puzzle-base" src="${puzzle.base}" alt="${esc(label)}: figura incompleta" width="${puzzle.width}" height="${puzzle.height}" decoding="async" fetchpriority="high" draggable="false">
              <div class="puzzle-slot ${selected ? 'is-filled' : ''}" data-slot style="${global.SAP_UAM_PUZZLE_VIEW.slotStyle(puzzle)}" aria-label="Hueco de la figura${selected ? `: pieza ${selected}` : ' vacío'}">
                <img class="puzzle-placed" src="${placed}" alt="" draggable="false" ${selected ? `data-slot-piece="${selected}"` : 'hidden'}>
              </div>
            </div>
          </div>
          <div class="puzzle-tray" role="group" aria-label="Piezas disponibles">${puzzle.pieces.map((src, i) => `
            <button type="button" class="puzzle-piece ${selected === i + 1 ? 'is-used' : ''}" data-piece="${i + 1}" data-option="${i + 1}" aria-keyshortcuts="${i + 1}" aria-label="Pieza ${i + 1}${selected === i + 1 ? ', colocada' : ''}">
              <img src="${src}" alt="" draggable="false" decoding="async">
            </button>`).join('')}
          </div>
        </figure>`;
    }

    function renderResponse(puzzle, { selected }) {
      return {
        title: 'Completá la figura',
        hint: 'Arrastrá la pieza que completa la figura hasta el hueco. También podés tocarla, o usar las teclas del 1 al 8.',
        control: `<button type="button" class="ghost-btn wide-btn puzzle-clear" data-action="clear-answer" ${selected ? '' : 'disabled'}>Quitar pieza</button>`
      };
    }

    function afterAnswer() {
      const slot = $('[data-slot]');
      if (!slot) return;
      const selected = state.answers[state.currentIndex];
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

    function prepareMedia() {
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
      const nextIndex = state.currentIndex + 1;
      if (nextIndex < context.totalItems) {
        const nextPuzzle = item(nextIndex);
        (nextPuzzle ? [nextPuzzle.base, ...nextPuzzle.pieces] : []).forEach(src => {
          const nextImage = new Image();
          nextImage.decoding = 'async';
          nextImage.src = src;
        });
      }
    }

    function attachInput(hooks) {
      global.SAP_UAM_PUZZLE_VIEW.attachDrag(document, hooks);
    }

    return { item, renderStage, renderResponse, afterAnswer, prepareMedia, attachInput };
  });
})(window);
