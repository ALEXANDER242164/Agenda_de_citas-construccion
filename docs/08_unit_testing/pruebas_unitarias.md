# Pruebas unitarias — Entrega 2

Pruebas automatizadas con **JUnit 5**, **Mockito** y **AssertJ** (incluidos en
`spring-boot-starter-test`) sobre la lógica principal del backend.

- **Ubicación:** `backend/src/test/java/mx/clinica/cambranes/agenda/`
- **Total:** 8 clases de prueba, 41 pruebas, todas pasan.
- **Requisitos:** JDK 17 o superior (probado con JDK 17 y JDK 25). No hace falta tener Maven instalado.

---

## 1. Cómo ejecutarlas

### Opción A: VS Code (panel *Testing*)

1. Instala la extensión **Extension Pack for Java** (`vscjava.vscode-java-pack`).
   VS Code la sugiere sola al abrir el repo, porque está en `.vscode/extensions.json`.
2. Abre en VS Code la **carpeta raíz del repositorio**.
3. Espera a que termine de cargar el proyecto Maven. En la barra inferior aparece
   `Java: Ready`. La primera vez tarda unos minutos porque descarga las dependencias.
4. Abre el panel **Testing** (el ícono del matraz 🧪 en la barra lateral izquierda).
5. Despliega `agenda-backend`. Ahí aparecen todas las clases de prueba.
6. Usa ▶ **Run Tests** para ejecutarlas todas, o el ▶ de una clase o método para
   ejecutar solo esa parte. Las pruebas que pasan se marcan con ✔ verde.

También puedes abrir cualquier archivo `*Test.java` y dar clic en el ▶ que
aparece junto a cada método o clase.

### Opción B: terminal (igual que en la revisión)

Desde la carpeta `backend/`:

```powershell
# Windows (PowerShell o CMD)
.\mvnw.cmd test

# macOS / Linux / Git Bash
./mvnw test
```

Resultado esperado:

```
[INFO] Tests run: 41, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
```

El *wrapper* (`mvnw`) descarga Maven 3.9.9 la primera vez; después ya no hace falta red.

Si aparece el error `TestEngine with ID 'junit-jupiter' failed to discover tests`,
quedaron clases compiladas viejas en `target/` (por ejemplo, de VS Code). Se arregla con:

```powershell
.\mvnw.cmd clean test
```

Para correr una sola clase:

```powershell
.\mvnw.cmd test -Dtest=AppointmentServiceTest
```

---

## 2. Qué se prueba y tipos de caso

Cada método de la lógica principal tiene un **caso normal**, un **caso límite**
y un **caso de error** cuando aplica.

| Clase de prueba | Qué valida | RF | Normal | Límite | Error |
|---|---|---|---|---|---|
| `AppointmentServiceTest` (7) | Crear, cancelar y reprogramar citas | RF-01, RF-03, RF-08, RF-09 | Crear cita válida; cancelar cita programada; reprogramar a horario libre | — | Paciente inexistente; horario con conflictos al crear y al reprogramar; cancelar una cita ya cancelada |
| `CreateAppointmentRequestValidationTest` (3) | Reglas de entrada para agendar | RF-01 | Solicitud completa y válida | Cuota 0 | Cuota negativa |
| `WorkingHoursValidatorTest` (8) | Horario laboral L-V 09:00–17:30 | RF-03 | Cita en día y hora hábil | Inicio 09:00 exacto; fin 17:30 exacto | Sábado, domingo, antes de las 09:00, después de las 17:30, inicio posterior al fin |
| `OverlapValidatorTest` (5) | Solapes de terapeuta, sala y paciente | RF-03 | Sin solape | — | Solape de terapeuta, de sala, de paciente y de terapeuta + sala a la vez |
| `PatientLimitValidatorTest` (3) | Máximo 3 citas activas por paciente | RF-01, RF-05 | 2 citas: se acepta; sin paciente no valida | 3 citas (justo en el máximo): se rechaza | — |
| `AppointmentStateTest` (8) | Patrón State: transiciones de una cita | RF-08, RF-09 | Programada → cancelada / finalizada / reprogramada | Resolver cada estado conocido | Cancelar cancelada, completar completada, reprogramar reprogramada, estado desconocido |
| `AppointmentFactoryTest` (5) | Construcción de citas (Factory) | RF-01, RF-08 | Calcula fin según duración; comentarios nulos; reprogramación hereda recursos | Duración 15 y 240 minutos | — (la entrada ya se valida en el request) |
| `AppointmentValidationChainTest` (2) | Cadena de validadores | RF-03 | Acumula errores de todos los validadores | Cadena sin validadores | — |

> Los números entre paréntesis indican cuántas pruebas tiene cada clase.

---

## 3. Convenciones

- **Nombres:** `escenario_resultadoEsperado`, por ejemplo
  `cuotaNegativa_esRechazada` o `create_pacienteInexistente_lanzaNotFoundExceptionYNoGuarda`.
- **Independencia:** cada prueba crea sus propios objetos y mocks en `@BeforeEach`.
  No comparten estado ni dependen del orden de ejecución.
- **Sin base de datos:** los repositorios se simulan con Mockito, así que las
  pruebas son rápidas y no necesitan MySQL ni Docker.
- **Implementaciones reales cuando se puede:** el patrón State, la Factory y la
  cadena de validación se usan con sus clases reales. Solo se simula el acceso a datos.
- **Composición:** 26 pruebas existían antes de esta entrega (se corrigieron para que
  compilen y se renombraron a este formato) y 15 se agregaron en esta entrega.
