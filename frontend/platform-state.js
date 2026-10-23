(function initPlatformState(global) {
  'use strict';

  function create(initialState) {
    const listeners = new Map();

    function subscribe(eventName, listener) {
      const eventListeners = listeners.get(eventName) || new Set();
      eventListeners.add(listener);
      listeners.set(eventName, eventListeners);
      return () => eventListeners.delete(listener);
    }

    function publish(eventName, detail = {}) {
      const event = Object.freeze({ type: eventName, detail, state: initialState });
      for (const listener of listeners.get(eventName) || []) listener(event);
      for (const listener of listeners.get('*') || []) listener(event);
    }

    return Object.freeze({ state: initialState, subscribe, publish });
  }

  global.SAP_UAM_STATE = Object.freeze({ create });
})(window);
