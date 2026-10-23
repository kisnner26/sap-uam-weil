(function initTestCatalog(global) {
  'use strict';

  const weilBaremos = [
    { p: 90, '7': 35, '8': 41, '9': 42, '10': 46, '11': 46, '12': 46, '13': 47, '14': 47, '15': 48, '16': 50, AN: 30, AD: 47, UN: 57 },
    { p: 80, '7': 31, '8': 36, '9': 39, '10': 42, '11': 43, '12': 43, '13': 43, '14': 43, '15': 43, '16': 46, AN: 23, AD: 43, UN: 56 },
    { p: 75, '7': 30, '8': 34, '9': 36, '10': 40, '11': 42, '12': 42, '13': 42, '14': 42, '15': 42, '16': 44, AN: 22, AD: 42, UN: 55 },
    { p: 70, '7': 29, '8': 33, '9': 37, '10': 39, '11': 41, '12': 41, '13': 41, '14': 41, '15': 41, '16': 42, AN: 21, AD: 40, UN: 54 },
    { p: 60, '7': 27, '8': 31, '9': 35, '10': 37, '11': 38, '12': 38, '13': 38, '14': 39, '15': 39, '16': 41, AN: 19, AD: 37, UN: 53 },
    { p: 50, '7': 25, '8': 28, '9': 33, '10': 33, '11': 33, '12': 34, '13': 34, '14': 35, '15': 36, '16': 36, AN: 17, AD: 35, UN: 51 },
    { p: 40, '7': 23, '8': 26, '9': 31, '10': 32, '11': 32, '12': 33, '13': 33, '14': 34, '15': 35, '16': 35, AN: 16, AD: 33, UN: 49 },
    { p: 30, '7': 21, '8': 23, '9': 30, '10': 31, '11': 31, '12': 32, '13': 32, '14': 33, '15': 34, '16': 34, AN: 13, AD: 30, UN: 46 },
    { p: 25, '7': 29, '8': 22, '9': 28, '10': 29, '11': 30, '12': 31, '13': 31, '14': 32, '15': 32, '16': 32, AN: 12, AD: 28, UN: 44 },
    { p: 20, '7': 17, '8': 20, '9': 26, '10': 28, '11': 29, '12': 30, '13': 30, '14': 31, '15': 31, '16': 31, AN: 8, AD: 26, UN: 42 },
    { p: 10, '7': 11, '8': 15, '9': 21, '10': 25, '11': 25, '12': 27, '13': 26, '14': 27, '15': 26, '16': 25, AN: 7, AD: 21, UN: 40 },
    { p: 5, '7': 8, '8': 12, '9': 16, '10': 19, '11': 21, '12': 22, '13': 22, '14': 22, '15': 23, '16': 23, AN: 5, AD: 16, UN: 35 }
  ];

  const weilCiEquivalences = [
    { p: 99, ci: 135 }, { p: 97, ci: 129 }, { p: 95, ci: 125 }, { p: 90, ci: 119 },
    { p: 80, ci: 113 }, { p: 75, ci: 110 }, { p: 70, ci: 108 }, { p: 60, ci: 104 },
    { p: 50, ci: 100 }, { p: 40, ci: 96 }, { p: 30, ci: 92 }, { p: 25, ci: 90 },
    { p: 20, ci: 87 }, { p: 10, ci: 81 }, { p: 5, ci: 75 }, { p: 3, ci: 72 }, { p: 1, ci: 65 }
  ];

  const weilAnswerPattern = Object.freeze([2, 8, 7, 4, 5, 6, 8, 4, 4, 7, 3, 2]);
  const weilScoring = Object.freeze({
    correctAnswer(itemIndex) {
      return weilAnswerPattern[itemIndex % weilAnswerPattern.length];
    },
    score(answers) {
      const pageScores = Array.from({ length: 5 }, (_, pageIndex) => {
        const start = pageIndex * 12;
        return answers.slice(start, start + 12).reduce((sum, answer, localIndex) => (
          sum + (answer === weilAnswerPattern[localIndex] ? 1 : 0)
        ), 0);
      });
      return { total: pageScores.reduce((sum, value) => sum + value, 0), pageScores };
    },
    resolveNormColumn(profile) {
      const age = Number(profile?.edad || 0);
      if (profile?.grupo === '7-16' && age >= 7 && age <= 16) return String(age);
      if (profile?.grupo === 'AN') return 'AN';
      if (profile?.grupo === 'AD') return 'AD';
      return 'UN';
    },
    percentile(score, column) {
      for (const row of weilBaremos) {
        if (score >= Number(row[column])) return row.p;
      }
      return 1;
    },
    ci(percentile) {
      for (const row of weilCiEquivalences) {
        if (percentile >= row.p) return row.ci;
      }
      return 65;
    },
    classify(ci) {
      if (ci >= 140) return 'Inteligencia muy superior';
      if (ci >= 124) return 'Inteligencia superior';
      if (ci >= 116) return 'Buena inteligencia';
      if (ci >= 85) return 'Inteligencia media';
      if (ci >= 70) return 'Rango bajo';
      return 'Rango muy bajo';
    }
  });

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
      answerPattern: weilAnswerPattern,
      baremos: Object.freeze(weilBaremos),
      ciEquivalences: Object.freeze(weilCiEquivalences),
      scoring: weilScoring,
      media: Object.freeze({
        sectionImage(sectionIndex) {
          return `assets/weill-pages/lamina-${sectionIndex + 1}.png`;
        },
        itemImage(itemIndex) {
          const section = Math.floor(itemIndex / 12) + 1;
          const item = String((itemIndex % 12) + 1).padStart(2, '0');
          return `assets/weill-items/lamina-${section}-item-${item}.png?v=2`;
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
