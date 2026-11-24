# 09 · Análisis para refactorización

Esta carpeta documenta el **estado actual de la primera versión del sistema**, **antes de aplicar cualquier refactorización**. Sirve como punto de partida para las mejoras de la siguiente versión del proyecto.

## Documentos

| Documento | Contenido |
|---|---|
| [Análisis del sistema](analisis_sistema.md) | Cómo funciona hoy el backend: tecnologías, arquitectura en capas, responsabilidades de cada módulo, pruebas existentes y una identificación general de problemas. |
| [Desglose de problemas](desglose_problemas.md) | Inventario de los cinco problemas priorizados (P-01 a P-05), con su evidencia en el código, el Code Smell, la técnica de refactorización propuesta y su prioridad. |

## Cómo leerlos

1. Empieza por el [análisis del sistema](analisis_sistema.md) para entender cómo está construido.
2. Después revisa el [desglose de problemas](desglose_problemas.md).

## Importante

- Los documentos describen el código **tal como está** en `main` (commit `f145115`). Las técnicas que aparecen son **propuestas**: todavía no se implementan.
- El inventario de problemas **no es definitivo**. Después de cada refactorización se hará un nuevo análisis. Los problemas anteriores se conservan con su ID y se marca su estado (identificado, solucionado o pendiente).
