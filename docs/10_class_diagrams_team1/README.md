# Model class diagram

| Requisito | ¿Cómo lo respalda el diagrama? |
|---|---|
| RF-01 – Registrar una cita | `Appointment` contiene paciente, terapeuta, sala, tipo de sesión, fecha/hora, duración, comentarios, cuota y estado. |
| RF-02 – Mostrar resumen y confirmar cita | `Appointment` contiene prácticamente todos los datos que deben mostrarse en el resumen: paciente, tipo, terapeuta, sala, fechas, duración, comentarios y estado. |
| RF-03 – Validar disponibilidad | `Appointment` tiene relaciones con `Patient`, `Therapist` y `Room`, además de `startAt`, `endAt` y `duration`. Esto proporciona la estructura necesaria para comprobar conflictos de horario. |
| RF-04 – Generar folio de paciente | `Patient` tiene el atributo `folio`, y las citas tienen una relación con `Patient`, permitiendo asociar las citas con el paciente. |
| RF-05 – Dar de alta pacientes y mantener historial | `Patient` almacena sus datos y `Appointment` mantiene la relación con el paciente. Por eso se puede recuperar el conjunto de citas asociado a cada paciente. |
| RF-06 – Mostrar detalle de una cita | `Appointment` concentra los datos necesarios para consultar el detalle de una cita. |
| RF-07 – Detectar citas atrasadas | `Appointment` tiene `startAt`, `status` y `endAt`, mientras que `AppointmentStatus` define estados como `SCHEDULED` y `COMPLETED`. Con estos datos puede determinarse si una cita programada ya pasó. |
| RF-08 – Reprogramar una cita | `Appointment` tiene `rescheduledFrom : Appointment`, lo que permite relacionar una nueva cita con la cita original. Además, `AppointmentStatus` contiene `RESCHEDULED`. |
| RF-09 – Cancelar una cita | `Appointment` tiene `cancelledReason`, `cancelledAt` y `status`, mientras que `AppointmentStatus` contempla `CANCELLED`. |
| RF-10 – Finalizar una cita | `AppointmentStatus` contempla `COMPLETED`, que corresponde al estado Finalizada del requisito. |
| RF-11 – Mostrar calendario semanal | `Appointment` contiene fecha/hora de inicio, fin, duración, paciente, terapeuta, sala y estado, que son los datos necesarios para representar las citas en el calendario. |
| RF-12 – Autenticar usuarios | La clase `User` contiene `email`, `passwordHash` y `role`, elementos relacionados directamente con autenticación y control de acceso. |
| RF-13 – Buscar pacientes | `Patient` contiene `folio`, `fullName` y `phone`, que corresponden a información utilizada para localizar pacientes. |
| RF-14 – Catálogos | El diagrama contiene `Therapist`, `Room`, `SessionType` y `AppointmentStatus`, que son precisamente los principales catálogos indicados en el requisito. |

# Service class diagram

El diagrama muestra los servicios del backend y sus dependencias con validadores, fábricas, repositorios, estados y eventos. La relación indica cómo la arquitectura puede apoyar los requisitos; el diagrama por sí solo no demuestra que estén implementados o validados.

| Requisito | ¿Cómo lo respalda el diagrama? |
|---|---|
| RF-01 – Registrar una cita | `AppointmentService` contempla las operaciones `check` y `create`, y utiliza `AppointmentValidationChain` y `AppointmentFactory` para validar y construir una cita. |
| RF-02 – Mostrar resumen y confirmar cita | `AppointmentService` puede validar y preparar los datos de la cita antes de crearla. El diagrama no muestra explícitamente la presentación del resumen ni la confirmación del usuario. |
| RF-03 – Validar disponibilidad | `AppointmentService` utiliza `AppointmentValidationChain` mediante la operación `check`, que devuelve una lista de errores de validación si hay problemas. |
| RF-04 – Generar folio de paciente | `PatientService` contempla crear pacientes y utiliza `PatientRepository`. El diagrama no especifica cómo se genera automáticamente el folio. |
| RF-05 – Dar de alta pacientes y mantener historial | `PatientService` contempla crear y consultar pacientes, mientras `AppointmentService` permite buscar citas por paciente mediante `findByPatient`. |
| RF-06 – Mostrar detalle de una cita | `AppointmentService` cuenta con la operación `get` para consultar una cita por su identificador. |
| RF-07 – Detectar citas atrasadas | `AppointmentService` permite consultar citas por fecha y rango, y el diagrama incluye estados de cita. Estos elementos aportan datos para la detección, pero no se muestra la regla que determina el atraso ni su alerta visual. |
| RF-08 – Reprogramar una cita | `AppointmentService` contempla `reschedule` y puede validar la disponibilidad antes del cambio. `AppointmentStateResolver` apoya la gestión del estado de la cita original. |
| RF-09 – Cancelar una cita | `AppointmentService` contempla `cancel`; el manejo de estados y los eventos de aplicación apoyan la actualización y trazabilidad de la operación. |
| RF-10 – Finalizar una cita | `AppointmentService` contempla `complete`, y `AppointmentStateResolver` apoya la gestión del estado de la cita. |
| RF-11 – Mostrar calendario semanal | Las operaciones `findByDate` y `findByRange` de `AppointmentService` permiten recuperar las citas correspondientes a una fecha o periodo. |
| RF-12 – Autenticar usuarios | `AuthService` contempla las operaciones `authenticate` y `getById`, y depende de `UserRepository` para consultar usuarios. |
| RF-13 – Buscar pacientes | `PatientService` contempla las operaciones `search`, `listAll` y `get` para localizar y consultar pacientes. |
| RF-14 – Catálogos | `CatalogService` contempla consultas de terapeutas, salas, tipos de sesión y estados de cita. |
| RNF-01 – Consistencia e integridad transaccional | La separación de validaciones, construcción de citas, estados y persistencia en servicios y repositorios favorece la consistencia. El diagrama no muestra límites transaccionales ni pruebas que demuestren atomicidad. |
| RNF-02 – Control de acceso básico y privacidad en UI | `AuthService` respalda la autenticación. El diagrama no detalla autorización por rol ni restricciones de información sensible en la interfaz. |
| RNF-04 – Observabilidad, logging y recuperación del sistema | Los eventos de aplicación y `AuditLogRepository` apoyan la trazabilidad de operaciones. El diagrama no muestra monitoreo de disponibilidad, respaldos ni recuperación. |
| RNF-09 – Mensajes y retroalimentación al usuario | `AppointmentService.check` contempla devolver errores de validación que podrían comunicarse al usuario. La presentación de esos mensajes en la interfaz no aparece en el diagrama. |

# Validation class diagram

| Requisito | ¿Cómo lo respalda el diagrama? |
|---|---|
| RF-01 – Registrar una cita | `AppointmentValidationChain` ejecuta varios validadores y `AppointmentFactory.newScheduled` construye la cita. `PatientLimitValidator` contempla un máximo de citas activas por paciente, y `OverlapValidator` permite comprobar conflictos de horario. |
| RF-03 – Validar disponibilidad | `ValidationContext` reúne paciente, terapeuta, sala y horario. `OverlapValidator` consulta citas existentes y `AppointmentValidationChain` coordina las reglas y devuelve los errores encontrados. |
| RF-08 – Reprogramar una cita | `ValidationContext` incluye `excludeAppointmentId` para excluir la cita original de la validación de traslapes. `WorkingHoursValidator` comprueba el horario laboral y `AppointmentFactory.fromReschedule` crea la cita reprogramada. |
| RNF-01 – Consistencia e integridad transaccional | La cadena aplica las validaciones antes de construir una cita y la fábrica centraliza su creación, lo que ayuda a evitar citas inválidas. El diagrama no muestra transacciones ni garantiza que no queden cambios parciales ante un fallo. |
| RNF-09 – Mensajes y retroalimentación al usuario | `ValidationError` contiene un tipo y un mensaje para describir los problemas encontrados. El diagrama no muestra cómo esos errores se presentan en la interfaz. |

# State class diagram

| Requisito | ¿Cómo lo respalda el diagrama? |
|---|---|
| RF-01 – Registrar una cita | `ScheduledState` representa el estado programado desde el que se gestionan las citas. El diagrama no muestra la operación de creación ni cómo se asigna ese estado inicialmente. |
| RF-08 – Reprogramar una cita | `AppointmentState` define la operación `reschedule`, y `RescheduledState` representa el estado resultante. `AppointmentStateResolver` permite resolver el comportamiento correspondiente al estado de la cita. |
| RF-09 – Cancelar una cita | `AppointmentState` define la operación `cancel`, y `CancelledState` representa el estado de una cita cancelada. |
| RF-10 – Finalizar una cita | `AppointmentState` define la operación `complete`, y `CompletedState` representa el estado de una cita finalizada. |
| RNF-01 – Consistencia e integridad transaccional | `ScheduledState` y `TerminalState` separan los comportamientos por estado, mientras `IllegalStateTransitionException` permite rechazar transiciones no válidas. Esto apoya la consistencia del ciclo de vida, pero no demuestra atomicidad transaccional. |

# Event class diagram

| Requisito | ¿Cómo lo respalda el diagrama? |
|---|---|
| RF-01 – Registrar una cita | `AppointmentCreatedEvent` representa la creación e incluye la cita, el usuario que realizó la acción y un payload asociado. |
| RF-08 – Reprogramar una cita | `AppointmentRescheduledEvent` representa el evento de reprogramación y conserva la cita, el usuario responsable y el payload. |
| RF-09 – Cancelar una cita | `AppointmentCancelledEvent` representa la cancelación e incluye la cita, el usuario responsable y el payload. |
| RF-10 – Finalizar una cita | `AppointmentCompletedEvent` representa la finalización e incluye la cita, el usuario responsable y el payload. |
| RNF-04 – Observabilidad, logging y recuperación del sistema | `AppointmentEvent` define datos comunes de la operación, como `appointment`, `performedBy`, `action` y `payload`. Estos eventos pueden apoyar la trazabilidad; el diagrama no muestra su almacenamiento, retención ni mecanismos de monitoreo o recuperación. |

