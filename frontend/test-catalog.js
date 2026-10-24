(function initTestCatalog(global) {
  'use strict';

  const definitions = Object.freeze({
    'weil-form-a': Object.freeze({
      id: 'weil-form-a',
      version: 1,
      code: 'WEIL-A',
      name: 'Test de Inteligencia No Verbal de Weil',
      shortName: 'Weil · Forma A',
      category: 'Evaluación cognitiva',
      description: 'Evaluación no verbal organizada en cinco láminas.',
      instructions: 'Observá la figura, identificá el patrón y seleccioná una opción del 1 al 8.',
      estimatedMinutes: 20,
      optionCount: 8,
      itemsPerSection: 12,
      sections: Object.freeze([
        { id: 'lamina-1', label: 'Lámina 1' },
        { id: 'lamina-2', label: 'Lámina 2' },
        { id: 'lamina-3', label: 'Lámina 3' },
        { id: 'lamina-4', label: 'Lámina 4' },
        { id: 'lamina-5', label: 'Lámina 5' }
      ]),
      media: Object.freeze({
        puzzleGeometry(itemIndex) {
          const data = global.SAP_UAM_PUZZLE?.['weil-form-a']?.[itemIndex];
          return data ? { width: data.w, height: data.h, patch: data.patch } : null;
        }
      })
    })
  });

  function get(testId) {
    const definition = definitions[testId];
    if (!definition) throw new Error(`Test no registrado: ${testId}`);
    return definition;
  }

  function list() {
    return Object.values(definitions);
  }

  global.SAP_UAM_TESTS = Object.freeze({ get, list, defaultTestId: 'weil-form-a' });
})(window);
