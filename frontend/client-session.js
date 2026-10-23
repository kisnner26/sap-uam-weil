(function initClientSession(global) {
  'use strict';

  function parseJson(value, fallback) {
    try {
      return value ? JSON.parse(value) : fallback;
    } catch {
      return fallback;
    }
  }

  function createDraftRepository(storage, keyFor, legacyKeyFor = null) {
    function load(context) {
      const current = storage.getItem(keyFor(context));
      const legacy = !current && legacyKeyFor ? storage.getItem(legacyKeyFor(context)) : null;
      return parseJson(current || legacy, null);
    }

    function save(context, state) {
      const draft = {
        schemaVersion: 1,
        testId: context.testId,
        testVersion: context.testVersion,
        answers: [...state.answers],
        currentIndex: state.currentIndex,
        startedAt: state.startedAt,
        focusLosses: state.focusLosses,
        securityEvents: [...state.securityEvents]
      };
      storage.setItem(keyFor(context), JSON.stringify(draft));
      return draft;
    }

    function remove(context) {
      storage.removeItem(keyFor(context));
      if (legacyKeyFor) storage.removeItem(legacyKeyFor(context));
    }

    return Object.freeze({ load, save, remove });
  }

  function createResultRepository(storage, indexKey, detailPrefix, legacyKey = null) {
    const legacyKeys = Array.isArray(legacyKey) ? legacyKey : [legacyKey].filter(Boolean);
    function summaryOf(result) {
      return {
        id: result.id,
        testId: result.testId,
        testVersion: result.testVersion,
        testName: result.testName,
        profile: result.profile,
        score: result.score,
        total: result.total,
        percentile: result.percentile,
        ci: result.ci,
        classification: result.classification,
        endedAt: result.endedAt
      };
    }

    function migrateLegacy() {
      if (storage.getItem(indexKey) || !legacyKeys.length) return;
      const legacy = parseJson(legacyKeys.map(key => storage.getItem(key)).find(Boolean), []);
      if (!Array.isArray(legacy) || !legacy.length) return;
      for (const result of legacy) storage.setItem(`${detailPrefix}${result.id}`, JSON.stringify(result));
      storage.setItem(indexKey, JSON.stringify(legacy.map(summaryOf).slice(0, 80)));
    }

    function list() {
      migrateLegacy();
      const summaries = parseJson(storage.getItem(indexKey), []);
      return Array.isArray(summaries) ? summaries : [];
    }

    function get(id) {
      return parseJson(storage.getItem(`${detailPrefix}${id}`), null);
    }

    function save(result) {
      storage.setItem(`${detailPrefix}${result.id}`, JSON.stringify(result));
      const summaries = list().filter(item => item.id !== result.id);
      summaries.unshift(summaryOf(result));
      const retained = summaries.slice(0, 80);
      storage.setItem(indexKey, JSON.stringify(retained));
      const retainedIds = new Set(retained.map(item => item.id));
      for (const item of summaries.slice(80)) {
        if (!retainedIds.has(item.id)) storage.removeItem(`${detailPrefix}${item.id}`);
      }
    }

    function clear() {
      for (const item of list()) storage.removeItem(`${detailPrefix}${item.id}`);
      storage.removeItem(indexKey);
      for (const key of legacyKeys) storage.removeItem(key);
    }

    return Object.freeze({ list, get, save, clear });
  }

  global.SAP_UAM_SESSION = Object.freeze({ createDraftRepository, createResultRepository });
})(window);
