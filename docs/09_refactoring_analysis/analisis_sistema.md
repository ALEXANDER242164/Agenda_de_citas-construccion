# Análisis del sistema actual — versión 1

> **Estado:** análisis de la primera versión del sistema, **antes de aplicar cualquier refactorización**.
> Todo lo que se describe aquí es comportamiento **implementado hoy** en el repositorio.
> Las mejoras propuestas se documentan aparte en [`desglose_problemas.md`](desglose_problemas.md).

---

## 1. Qué es el sistema

Módulo de **agenda de citas** de la Clínica Psicológica Cambranes. Permite:

- Agendar, consultar, reprogramar, cancelar y finalizar citas (RF-01 a RF-09).
- Registrar y buscar pacientes.
- Consultar catálogos: terapeutas, salas, tipos de sesión y estados de cita.
- Iniciar sesión con usuario y contraseña (token JWT).
- Guardar una bitácora de auditoría de cada operación.

El repositorio tiene dos aplicaciones:

| Carpeta | Qué es |
|---|---|
| `backend/` | API REST en Java (Spring Boot). Contiene la lógica de negocio y es el objeto principal de este análisis. |
| `frontend/` | SPA en React + TypeScript (Vite) que consume la API (`frontend/src/services/api.ts`). |

---

## 2. Tecnologías utilizadas (backend)

Tomadas de `backend/pom.xml` y `backend/src/main/resources/application.yml`.

| Tecnología | Versión | Uso |
|---|---|---|
| Java | 17 (`java.version`) | Lenguaje |
| Spring Boot | 3.3.4 | Web, JPA, validación |
| Spring Data JPA / Hibernate | (gestionado por Spring Boot) | Persistencia |
| MySQL + Flyway | — | Base de datos y migraciones (`db/migration/V1__init_schema.sql`, `V2__seed_catalog_and_demo.sql`) |
| Jakarta Bean Validation | (Hibernate Validator) | Validación de los DTO de entrada |
| JJWT | 0.12.6 | Emisión y verificación de tokens JWT |
| spring-security-crypto | — | Hash BCrypt de contraseñas (no se usa Spring Security completo) |
| MapStruct | — | Conversión entidad → DTO (`DtoMapper`) |
| Lombok | 1.18.42 | Getters, setters, builders y constructores |
| springdoc-openapi | 2.6.0 | Swagger UI en `/swagger-ui.html` |
| JUnit 5 + Mockito + AssertJ | 5.10.3 / 5.11.0 / 3.25.3 (vía `spring-boot-starter-test`) | Pruebas unitarias |

Reglas de negocio configurables (`application.yml`, sección `app.business`):

- Horario laboral: `09:00` a `17:30`.
- Máximo de citas activas por paciente: `3`.

---

## 3. Arquitectura y estructura

El backend sigue una **arquitectura en capas** dentro del paquete `mx.clinica.cambranes.agenda`:

```text
backend/src/main/java/mx/clinica/cambranes/agenda/
├── api/                 Capa de presentación (REST)
│   ├── *Controller      AppointmentController, PatientController, CatalogController, AuthController
│   ├── dto/             Records de entrada/salida (CreateAppointmentRequest, AppointmentDto, ...)
│   ├── mapper/          DtoMapper (MapStruct)
│   ├── error/           GlobalExceptionHandler + ApiError
│   └── security/        JwtService, JwtAuthFilter, @CurrentUser y su resolver
├── service/             Capa de aplicación (casos de uso)
│   ├── AppointmentService, PatientService, CatalogService, AuthService
│   └── exception/       BusinessRuleException, NotFoundException
├── domain/              Capa de dominio
│   ├── model/           Entidades JPA: Appointment, Patient, Therapist, Room, SessionType,
│   │                    AppointmentStatus, User, AuditLog
│   ├── repository/      Interfaces Spring Data JPA
│   ├── validation/      Cadena de validación + reglas (rules/)
│   ├── state/           Patrón State para el ciclo de vida de la cita
│   ├── factory/         AppointmentFactory
│   └── event/           Eventos de dominio y AuditLogListener
└── config/              WebConfig (CORS, filtro JWT), OpenApiConfig, DemoUserSeeder
```

Flujo típico de una petición:

```text
HTTP → JwtAuthFilter → Controller → Service → (Validation chain, State, Factory) → Repository → MySQL
                                       └─> ApplicationEventPublisher → AuditLogListener → audit_log
```

Patrones de diseño ya aplicados (documentados en `docs/PATRONES_DE_DISEÑO.md`): Strategy, Chain of Responsibility, State, Factory, Observer, Repository, DTO, Builder, Argument Resolver y Global Exception Handler.

---

## 4. Responsabilidades de los módulos principales

### 4.1 `api` (controladores)

| Clase | Endpoints | Responsabilidad |
|---|---|---|
| `AppointmentController` | `GET /api/appointments`, `GET /{id}`, `POST /check`, `POST /`, `POST /{id}/cancel`, `POST /{id}/complete`, `POST /{id}/reschedule` | Recibe la petición, valida el DTO con `@Valid`, delega en `AppointmentService` y convierte a DTO. Si no recibe fechas, lista de hoy −7 a hoy +30 días. |
| `PatientController` | `GET /api/patients`, `/search`, `/{id}`, `/{id}/appointments`, `POST /` | Pacientes y su historial de citas. |
| `CatalogController` | `/api/therapists`, `/rooms`, `/session-types`, `/appointment-statuses` | Catálogos de solo lectura. |
| `AuthController` | `POST /api/auth/login` | Autentica y devuelve el token JWT. |
| `GlobalExceptionHandler` | — | Traduce excepciones a respuestas HTTP: `NotFoundException` → 404, `BusinessRuleException` e `IllegalStateTransitionException` → 409, errores de `@Valid` → 400, cualquier otra → 500. |

### 4.2 `service`

**`AppointmentService`** (215 líneas, la clase más grande del backend) concentra la lógica principal:

| Método | Qué hace hoy |
|---|---|
| `findByDate`, `findByRange`, `get`, `findByPatient` | Consultas. |
| `check(req, excludeId)` | Corre **solo la cadena de validación** y devuelve los errores sin guardar. El frontend lo usa para validar antes de enviar (`frontend/src/services/api.ts:203`). |
| `create(req, by)` | Busca paciente, terapeuta, sala y tipo de sesión (lanza `NotFoundException` si falta alguno). Comprueba que la sala sea la del terapeuta. Corre la cadena de validación. Construye la cita con la Factory, la guarda, hace `flush()` y **vuelve a validar** (protección TOCTOU contra citas concurrentes). Publica el evento `Created`. |
| `cancel(id, reason, by)` | Pide al State la transición a CANCELLED, guarda el motivo y la fecha, publica `Cancelled`. |
| `complete(id, by)` | Transición a COMPLETED y publica `Completed`. |
| `reschedule(id, req, by)` | Valida el nuevo horario excluyendo la cita original, archiva la original como RESCHEDULED, crea una cita nueva con `factory.fromReschedule`, vuelve a validar tras `flush()` y publica `Rescheduled`. |

**`PatientService`**: lista, busca y crea pacientes. Al crear, guarda dos veces para generar el folio `PAC-0001` a partir del id.
**`CatalogService`**: lectura de catálogos.
**`AuthService`**: verifica email + BCrypt.

### 4.3 `domain`

- **Validación (Chain of Responsibility + Strategy).** `AppointmentValidationChain.run(ctx)` ejecuta todos los `AppointmentValidator` en orden `@Order` y **acumula** todos los errores:
  - `WorkingHoursValidator` (`@Order(10)`): lunes a viernes, inicio ≥ 09:00, fin ≤ 17:30 e inicio < fin.
  - `OverlapValidator` (`@Order(20)`): solape de terapeuta, sala o paciente con citas SCHEDULED o COMPLETED.
  - `PatientLimitValidator` (`@Order(30)`): máximo 3 citas SCHEDULED por paciente.
- **State.** `AppointmentStateResolver` elige el estado según `status.id`. `ScheduledState` permite cancelar, completar y reprogramar. `CancelledState`, `RescheduledState` y `CompletedState` heredan de `TerminalState`, que lanza `IllegalStateTransitionException` ante cualquier transición.
- **Factory.** `AppointmentFactory.newScheduled` y `fromReschedule` calculan `endAt = startAt + duración` y fijan el estado SCHEDULED.
- **Eventos (Observer).** `AppointmentEvent` (sealed interface con 4 records) y `PatientEvent`. `AuditLogListener` los persiste en `audit_log`.
- **Modelo.** Entidades JPA con Lombok (`@Getter @Setter @Builder`). Los estados de cita son filas de `appointment_statuses` con ids fijos: 1 SCHEDULED, 2 CANCELLED, 3 RESCHEDULED, 4 COMPLETED (constantes en `AppointmentStatus` y datos en `V2__seed_catalog_and_demo.sql`).

### 4.4 Seguridad

`JwtAuthFilter` lee `Authorization: Bearer …` y guarda el id del usuario como atributo de la petición. `CurrentUserArgumentResolver` inyecta el `User` en los parámetros `@CurrentUser`. Como modo de transición, también acepta el header `X-User-Id`.

---

## 5. Pruebas automatizadas existentes

- 8 clases de prueba y **41 métodos `@Test`** en `backend/src/test/java`.
- Todas pasan: `.\mvnw.cmd clean test` → `Tests run: 41, Failures: 0, Errors: 0`.
- No usan base de datos: los repositorios se simulan con Mockito.
- El detalle está en `docs/08_unit_testing/pruebas_unitarias.md`.

Las pruebas cubren las reglas de validación, el patrón State, la Factory, la validación de la cuota y los casos `create`, `cancel` y `reschedule` del servicio. **No hay pruebas** de controladores, `PatientService`, `AuthService` ni seguridad.

---

## 6. Identificación general de problemas

Al revisar el código se detectaron estos problemas. Los cinco primeros se eligieron para la siguiente versión por su impacto en la lógica principal. El detalle de cada uno está en [`desglose_problemas.md`](desglose_problemas.md).

| ID | Problema (resumen) | Dónde |
|---|---|---|
| P-01 | Método `create` largo, con muchas responsabilidades | `AppointmentService.create` |
| P-02 | Código duplicado al validar (construcción de `ValidationContext` y "validar y lanzar") | `AppointmentService.create`, `reschedule`, `buildContext` |
| P-03 | La regla "la sala debe ser la del terapeuta" está fuera de la cadena de validación, así que `check` y `create` no validan lo mismo | `AppointmentService.create` vs `check` |
| P-04 | Método `toJson` duplicado en dos servicios | `AppointmentService`, `PatientService` |
| P-05 | Números mágicos para los estados de cita en las consultas JPQL | `AppointmentRepository` |

**Otros hallazgos (no priorizados en esta versión, se registran para futuros análisis):**

- `AuthService.authenticate` y `CurrentUserArgumentResolver` lanzan `NotFoundException` cuando las credenciales o el token son inválidos. Por eso la API responde **404** donde lo esperado sería 401.
- `Appointment` expone `setStatus(...)` público. El servicio cambia el estado directamente (`AppointmentService` líneas 123, 136 y 161), así que nada impide saltarse el patrón State desde otro punto del código.
- `AppointmentController.list` usa los valores fijos `7` y `30` días para el rango por defecto (línea 41).
- Los tipos de error (`"therapist"`, `"room"`, `"patient"`, `"working_hours"`) y las acciones de auditoría (`"CREATE"`, `"CANCEL"`, …) son cadenas sueltas. El evento `Completed` registra la acción `"UPDATE"`.

> Esta lista **no es definitiva**. Después de aplicar las primeras refactorizaciones se hará un nuevo análisis, y podrán aparecer problemas que hoy no son evidentes.
