# Desglose de problemas — versión 1

> **Estado:** problemas **identificados** en la primera versión del sistema. **Ninguno ha sido refactorizado todavía.**
> Las técnicas que aparecen aquí son **propuestas**, no cambios implementados.
> Las rutas son relativas a `backend/src/main/java/mx/clinica/cambranes/agenda/`. Las líneas corresponden al commit `f145115` (rama `main`).

## Control de versiones del inventario

| Versión del análisis | Problemas identificados | Solucionados | Pendientes |
|---|---|---|---|
| v1 (este documento) | P-01 a P-05 | — | P-01 a P-05 |

Al actualizar este documento en futuras versiones, **no se borran** los problemas anteriores: se cambia su estado y se agregan nuevos con el siguiente ID (P-06, P-07, …).

---

## 1. Inventario

| ID | Descripción del problema | Clase o módulo afectado | Funciones o métodos involucrados | Code Smell | Técnica de refactorización | Prioridad |
|---|---|---|---|---|---|---|
| P-01 | `create` hace 7 tareas distintas en un solo método de 43 líneas | `service/AppointmentService.java` | `create` (líneas 75–117) | Long Method | Extract Method | Alta |
| P-02 | Se repite la construcción de `ValidationContext` y el bloque "validar y lanzar excepción" | `service/AppointmentService.java` | `create`, `reschedule` (148–157 y 169–182), `buildContext` | Duplicated Code | Extract Method, Introduce Parameter Object (ajuste del existente) | Alta |
| P-03 | La regla "la sala debe ser la del terapeuta" vive en el servicio y no en la cadena de validación, así que `check` puede aprobar una cita que `create` rechaza | `service/AppointmentService.java`, `domain/validation/` | `create` (líneas 85–90), `check` (68–72) | Lógica de negocio mal ubicada (cercano a Divergent Change / Feature Envy) | Move Method, Extract Class (nuevo validador) | Alta |
| P-04 | El método `toJson` está copiado igual en dos servicios | `service/AppointmentService.java`, `service/PatientService.java` | `toJson` (205–214 y 65–72) | Duplicated Code | Extract Class, Move Method | Media |
| P-05 | Los ids de estado `1` y `4` están escritos a mano en las consultas JPQL, aunque ya existen constantes | `domain/repository/AppointmentRepository.java` | `findOverlapping` (línea 19), `countScheduledByPatient` (línea 57) | Magic Numbers | Replace Magic Number with Symbolic Constant | Media |

---

## 2. Detalle de cada problema

### P-01 — Método `create` largo con múltiples responsabilidades

1. **Identificador:** P-01
2. **Nombre:** `AppointmentService.create` concentra todo el caso de uso "agendar cita".
3. **Descripción:** el método hace, en este orden:
   1. Busca 4 entidades: paciente, terapeuta, sala y tipo de sesión, cada una con su `NotFoundException` (líneas 76–83).
   2. Verifica la regla sala ↔ terapeuta (85–90).
   3. Corre la cadena de validación y lanza si hay errores (92–94).
   4. Construye la cita con la Factory y la guarda (96–97).
   5. Hace `flush()` y vuelve a validar para cerrar la ventana TOCTOU (99–108).
   6. Arma el payload de auditoría y publica el evento (110–113).
   7. Escribe el log (114–115).

   Para entender o cambiar una sola de esas tareas hay que leer las 43 líneas completas.
4. **Ubicación:** `service/AppointmentService.java`, clase `AppointmentService`, capa de servicio.
5. **Métodos involucrados:** `create(CreateAppointmentRequest, User)`. Usa `buildContext`, `toJson`, `factory.newScheduled` y `validationChain.run`.
6. **Evidencia:**
   ```java
   // AppointmentService.java:75-117 (resumido)
   public Appointment create(CreateAppointmentRequest req, User by) {
       Patient patient = patientRepository.findById(...).orElseThrow(...);
       Therapist therapist = therapistRepository.findById(...).orElseThrow(...);
       Room room = roomRepository.findById(...).orElseThrow(...);
       SessionType sessionType = sessionTypeRepository.findById(...).orElseThrow(...);
       if (!therapist.getRoom().getId().equals(room.getId())) { throw ... }
       List<ValidationError> errors = validationChain.run(ctx);
       if (!errors.isEmpty()) throw new BusinessRuleException(errors);
       Appointment saved = appointmentRepository.save(factory.newScheduled(...));
       appointmentRepository.flush();
       List<ValidationError> postErrors = validationChain.run(buildContext(req, saved.getId()));
       if (!postErrors.isEmpty()) { log.warn(...); throw ... }
       Map<String, Object> payload = new LinkedHashMap<>(); ...
       events.publishEvent(new AppointmentEvent.Created(...));
       log.info(...);
       return saved;
   }
   ```
7. **Code Smell:** *Composición de métodos* → **Long Method** (método largo con múltiples responsabilidades).
8. **Consecuencias:**
   - **Legibilidad:** el flujo principal ("validar, guardar, avisar") queda escondido entre detalles.
   - **Pruebas:** para probar solo la regla de sala hay que preparar los 4 repositorios. Se ve en `AppointmentServiceTest`, que necesita el helper `catalogosExistentes(...)` en cada prueba de `create`.
   - **Mantenimiento:** agregar otro catálogo o regla hace crecer el mismo método.
9. **Posibles soluciones:** **Extract Method**. Separar en métodos privados con nombre propio, por ejemplo cargar catálogos, verificar sala, validar o lanzar, revalidar tras guardar y publicar el evento creado. `create` quedaría como una secuencia de 5 o 6 llamadas.
10. **Justificación:** el problema es de *tamaño y mezcla de pasos* dentro de un solo método, no de una clase entera. Extract Method es la técnica mínima que lo resuelve sin cambiar la API pública. *Replace Method with Method Object* sería excesivo: las variables locales son pocas y se pasan con facilidad como parámetros.
11. **Prioridad: Alta.** Es el caso de uso principal (RF-01), el método más largo del backend, y además contiene los problemas P-02 y P-03.

---

### P-02 — Código duplicado al construir el contexto de validación y al "validar y lanzar"

1. **Identificador:** P-02
2. **Nombre:** construcción de `ValidationContext` y bloque validar-lanzar repetidos.
3. **Descripción:**
   - En `reschedule` se construye **dos veces** un `ValidationContext` casi idéntico (líneas 148–155 y 169–176). Solo cambia `excludeAppointmentId`.
   - `buildContext` (193–203) hace lo mismo para `create`, pero partiendo de un `CreateAppointmentRequest`.
   - El patrón "correr la cadena → si hay errores, lanzar `BusinessRuleException`" aparece **4 veces**: líneas 93–94, 104–108, 156–157 y 177–182.
4. **Ubicación:** `service/AppointmentService.java`.
5. **Métodos involucrados:** `create`, `reschedule`, `buildContext`.
6. **Evidencia:**
   ```java
   // AppointmentService.java:148-155  (antes de guardar)
   ValidationContext ctx = ValidationContext.builder()
           .patientId(original.getPatient().getId())
           .therapistId(original.getTherapist().getId())
           .roomId(original.getRoom().getId())
           .startAt(req.startAt())
           .endAt(req.startAt().plusMinutes(original.getDurationMinutes()))
           .excludeAppointmentId(original.getId())
           .build();

   // AppointmentService.java:169-176  (después de guardar) — mismo bloque
   ValidationContext postCtx = ValidationContext.builder()
           .patientId(original.getPatient().getId())
           .therapistId(original.getTherapist().getId())
           .roomId(original.getRoom().getId())
           .startAt(req.startAt())
           .endAt(req.startAt().plusMinutes(original.getDurationMinutes()))
           .excludeAppointmentId(saved.getId())
           .build();
   ```
7. **Code Smell:** **Duplicated Code** (código duplicado).
8. **Consecuencias:** si cambia la forma de calcular `endAt` o se agrega un campo a `ValidationContext`, hay que modificar 3 lugares, y si se olvida uno, la validación previa y la posterior dejan de ser iguales. El riesgo es real porque el bloque posterior existe justamente para repetir la validación previa.
9. **Posibles soluciones:**
   - **Extract Method:** `validateOrThrow(ValidationContext ctx)` para el bloque validar-lanzar.
   - **Extract Method:** un método que construya el contexto a partir de una cita existente y del nuevo horario, recibiendo solo el `excludeId`.
   - **Introduce Parameter Object:** `ValidationContext` ya es un objeto parámetro. La propuesta es *completarlo* con fábricas estáticas (por ejemplo, desde una solicitud o desde una cita) para no repetir el builder en el servicio.
10. **Justificación:** el duplicado está dentro de la misma clase, así que basta Extract Method. No se justifica crear una jerarquía (Form Template Method) porque solo hay dos variantes.
11. **Prioridad: Alta.** Afecta la validación de conflictos (RF-03), que es la regla más crítica. Además, resolverla facilita resolver P-01.

---

### P-03 — Regla de negocio fuera de la cadena de validación (`check` ≠ `create`)

1. **Identificador:** P-03
2. **Nombre:** la validación "sala del terapeuta" no forma parte de la cadena.
3. **Descripción:** todas las reglas de una cita viven como `AppointmentValidator` dentro de `AppointmentValidationChain` (horario, solapes, límite de citas), **excepto una**: "la sala debe ser la sala fija del terapeuta". Esa regla está escrita directamente en `create` (líneas 85–90).
   El endpoint `POST /api/appointments/check`, que el frontend usa para validar **antes** de enviar (`frontend/src/services/api.ts:203`), llama a `check` (68–72). `check` **solo** corre la cadena. Resultado: `check` puede responder "sin errores" para una cita con sala equivocada, y después `create` la rechaza con 409.
4. **Ubicación:** `service/AppointmentService.java` (`create`, `check`) y `domain/validation/rules/`.
5. **Métodos involucrados:** `AppointmentService.create`, `AppointmentService.check`, `AppointmentValidationChain.run`.
6. **Evidencia:**
   ```java
   // AppointmentService.java:68-72
   public List<ValidationError> check(CreateAppointmentRequest req, Long excludeId) {
       ValidationContext ctx = buildContext(req, excludeId);
       return validationChain.run(ctx);          // <- no revisa sala vs terapeuta
   }

   // AppointmentService.java:85-90  (solo en create)
   if (!therapist.getRoom().getId().equals(room.getId())) {
       throw new BusinessRuleException(List.of(
               ValidationError.of("room", "La sala no corresponde al terapeuta seleccionado.")));
   }
   ```
7. **Code Smell:** no hay un nombre exacto en el catálogo. Lo más cercano es **lógica de negocio mal ubicada**: el comportamiento pertenece a otro módulo (la cadena de validación), que es la idea detrás de *Feature Envy* y de la regla de *Move Method*. Se aclara para no forzar la clasificación.
8. **Consecuencias:**
   - **Comportamiento inconsistente** entre dos endpoints que deberían validar lo mismo.
   - La documentación de la cadena (`AppointmentValidationChain`, Javadoc) dice que agregar una regla es "solo crear un nuevo `@Component`". Esta regla no sigue esa convención, así que quien lea la cadena no la encuentra.
   - No hay prueba unitaria de esta regla.
9. **Posibles soluciones:**
   - **Extract Class:** crear un validador nuevo (por ejemplo `TherapistRoomValidator`) que implemente `AppointmentValidator`.
   - **Move Method:** mover la comparación sala ↔ terapeuta desde `create` a ese validador, que buscará el terapeuta por `therapistId` del `ValidationContext`.
10. **Justificación:** la cadena ya existe y está pensada para crecer agregando validadores (Strategy + Chain of Responsibility). Mover la regla ahí hace que `check`, `create` y `reschedule` la apliquen sin código extra. La alternativa de llamar la regla también desde `check` duplicaría código (empeora P-02).
11. **Prioridad: Alta.** Es un defecto visible para el usuario (RF-01 / RF-03) y afecta la consistencia de la API.

---

### P-04 — Método `toJson` duplicado entre servicios

1. **Identificador:** P-04
2. **Nombre:** serialización del payload de auditoría copiada en dos servicios.
3. **Descripción:** `AppointmentService.toJson` (205–214) y `PatientService.toJson` (65–72) son el mismo método: reciben un `Map`, lo convierten a JSON con `ObjectMapper` y, si falla, escriben un `warn` y devuelven `"{}"`. Por esta razón ambos servicios dependen de `ObjectMapper`, aunque su responsabilidad no es serializar.
4. **Ubicación:** `service/AppointmentService.java`, `service/PatientService.java`.
5. **Métodos involucrados:** `toJson(Map<String, ?>)` en ambas clases.
6. **Evidencia:**
   ```java
   // AppointmentService.java:205-214           // PatientService.java:65-72
   private String toJson(Map<String, ?> data) {   private String toJson(Map<String, ?> data) {
       try {                                          try {
           return objectMapper.writeValueAsString(        return objectMapper.writeValueAsString(data);
               data);                                 } catch (JsonProcessingException e) {
       } catch (JsonProcessingException e) {              log.warn("No se pudo serializar ...");
           log.warn("No se pudo serializar ...");         return "{}";
           return "{}";                               }
       }                                          }
   }
   ```
7. **Code Smell:** **Duplicated Code** entre clases.
8. **Consecuencias:** un cambio en el formato de la auditoría (por ejemplo, agregar la fecha o cambiar el manejo del error) tiene que repetirse en cada servicio. Además, cada servicio nuevo que publique eventos tendría que copiar el método otra vez.
9. **Posibles soluciones:**
   - **Extract Class:** crear un componente (por ejemplo `AuditPayloadSerializer`) con un único método `toJson`.
   - **Move Method:** mover el `toJson` de los servicios a esa clase e inyectarla. Los servicios dejarían de depender de `ObjectMapper`.
10. **Justificación:** las dos clases no comparten jerarquía, así que *Pull Up Method* no aplica (habría que inventar una superclase artificial). Extract Class con inyección encaja con el estilo del proyecto, que ya usa componentes Spring.
11. **Prioridad: Media.** No afecta la lógica principal ni causa errores hoy, pero es duplicación fácil de eliminar y reduce dependencias.

---

### P-05 — Números mágicos para los estados de cita en JPQL

1. **Identificador:** P-05
2. **Nombre:** ids de estado escritos a mano en las consultas.
3. **Descripción:** las consultas que deciden qué citas **ocupan horario** y cuántas citas **activas** tiene un paciente usan los números `1` y `4` directamente. Sin embargo, `AppointmentStatus` ya define `SCHEDULED = 1` y `COMPLETED = 4`. Quien lee la consulta tiene que saber de memoria qué significa cada número (solo lo explica un comentario en `findOverlapping`). Además, el valor vive en tres sitios: la constante Java, la consulta y el script `V2__seed_catalog_and_demo.sql`.
4. **Ubicación:** `domain/repository/AppointmentRepository.java`.
5. **Métodos involucrados:** `findOverlapping` (línea 19) y `countScheduledByPatient` (línea 57). Indirectamente, `OverlapValidator` y `PatientLimitValidator`, que dependen de esas consultas.
6. **Evidencia:**
   ```java
   // AppointmentRepository.java:19
   WHERE a.status.id IN (1, 4)
   // AppointmentRepository.java:57
     AND a.status.id  = 1

   // AppointmentStatus.java:12-15 (las constantes ya existen)
   public static final short SCHEDULED   = 1;
   public static final short COMPLETED   = 4;
   ```
7. **Code Smell:** *Organización de datos* → **Magic Numbers**.
8. **Consecuencias:** si los ids cambian o se agrega un estado que también ocupe horario, hay que encontrar a mano cada consulta. Un error aquí rompe en silencio las reglas de solape (RF-03) y de máximo de citas (RF-01).
9. **Posibles soluciones:** **Replace Magic Number with Symbolic Constant**. Hay dos opciones:
   - (a) Referenciar la constante Java dentro del JPQL. Hibernate 6 permite usar el nombre completo de la constante en la consulta.
   - (b) Pasar los estados como parámetro (`:activeStatuses`) desde un método `default` del repositorio que use `AppointmentStatus.SCHEDULED` y `COMPLETED`.
10. **Justificación:** es exactamente el caso del catálogo, y el cambio es pequeño y localizado. La opción (b) es más portable que (a), pero cambia la firma interna del repositorio, así que debe elegirse al implementar. Reemplazar el código de tipo por un `enum` (*Replace Type Code with Class*) sería un cambio mayor que afecta la base de datos. Se deja como posible mejora futura.
11. **Prioridad: Media.** Hoy funciona correctamente, pero es frágil y afecta dos reglas de negocio centrales.

---

## 3. Relación entre problemas

- **P-01 contiene a P-02 y P-03.** Resolver primero P-02 (validar-lanzar) y P-03 (sacar la regla de sala) reduce mucho el tamaño de `create`, y entonces P-01 se completa con menos esfuerzo.
- **P-04 y P-05 son independientes** de los demás y se pueden hacer en cualquier momento.
- **Extract Method** contribuye a resolver P-01 y P-02. **Extract Class + Move Method** resuelven P-03 y P-04.
