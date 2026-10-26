(function initTestViewers(global) {
  'use strict';

  // Cada test aporta su visor de ítems; la plataforma solo conoce esta interfaz y no pregunta por el nombre del test.
  //
  //   register(testId, create)   create(context) -> viewer
  //   viewer.item(index)                 -> material del ítem, o null si no está disponible
  //   viewer.renderStage(item, view)     -> html del ejercicio (view: index, local, page, selected)
  //   viewer.renderResponse(item, view)  -> { title, hint, control } del panel de respuesta
  //   viewer.afterAnswer()               -> sincroniza el visor sin redibujar la pantalla
  //   viewer.prepareMedia()              -> estados de carga y precarga del siguiente ítem
  //   viewer.attachInput(hooks)          -> engancha la interacción propia del test
  const factories = {};

  function register(testId, create) {
    if (factories[testId]) throw new Error(`Visor ya registrado: ${testId}`);
    factories[testId] = create;
  }

  function create(testId, context) {
    const factory = factories[testId];
    if (!factory) throw new Error(`Test sin visor: ${testId}`);
    return factory(context);
  }

  global.SAP_UAM_TEST_VIEWERS = Object.freeze({ register, create });
})(window);
