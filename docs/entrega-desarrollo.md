# entrega del módulo weil a desarrollo

## alcance entregable

el módulo aporta una referencia funcional para `weil-form-a`:

- contrato de calificación por `testId` mediante `ScoringStrategy` y `ScoringRegistry`.
- definición versionada, intento transaccional, control de concurrencia y resultados por escalas.
- visor de ítems separado del shell en `frontend/tests/weil-form-a/`.
- 49 pruebas de frontend y 62 pruebas de backend ejecutadas localmente.
- prueba de carga de postgresql que cubre registro, convocatoria, habilitación, consentimiento, intento y entrega.

## condiciones de integración

1. usar el repositorio común y su estructura aprobada; no copiar este proyecto completo como una segunda plataforma.
2. adaptar el módulo a java 17, spring boot 3.5, react y la nomenclatura definida por integración.
3. preservar el contrato de `testId`, estrategia, versión de calificación y escalas de resultado.
4. validar primero unitarias, después api + postgresql y finalmente regresión del sistema común.
5. registrar cada cambio mediante rama y pull request; no integrar directamente a `main`.

## límite del material incluido

- el material, las claves y los baremos incluidos son de prueba para desarrollo e integración.
- no se usan para evaluación institucional ni como base clínica.
- un instrumento autorizado para aplicación real requiere revisión institucional independiente.
- el aviso de privacidad requiere revisión legal institucional antes de producción.

## estado de aceptación

| condición | estado |
| --- | --- |
| pruebas locales | verde |
| ci de postgresql | corregido localmente; requiere ejecución remota verde |
| contrato multi-test | base disponible; faltan respuesta genérica y batería por convocatoria |
| adaptación al repositorio común | pendiente del equipo de desarrollo e integración |
| material de prueba | disponible para desarrollo; no apto para aplicación institucional |
