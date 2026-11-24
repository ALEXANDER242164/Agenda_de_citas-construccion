package mx.clinica.cambranes.agenda.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import mx.clinica.cambranes.agenda.api.dto.CreateAppointmentRequest;
import mx.clinica.cambranes.agenda.api.dto.RescheduleRequest;
import mx.clinica.cambranes.agenda.domain.event.AppointmentEvent;
import mx.clinica.cambranes.agenda.domain.factory.AppointmentFactory;
import mx.clinica.cambranes.agenda.domain.model.Appointment;
import mx.clinica.cambranes.agenda.domain.model.AppointmentStatus;
import mx.clinica.cambranes.agenda.domain.model.Patient;
import mx.clinica.cambranes.agenda.domain.model.Room;
import mx.clinica.cambranes.agenda.domain.model.SessionType;
import mx.clinica.cambranes.agenda.domain.model.Therapist;
import mx.clinica.cambranes.agenda.domain.model.User;
import mx.clinica.cambranes.agenda.domain.repository.AppointmentRepository;
import mx.clinica.cambranes.agenda.domain.repository.AppointmentStatusRepository;
import mx.clinica.cambranes.agenda.domain.repository.PatientRepository;
import mx.clinica.cambranes.agenda.domain.repository.RoomRepository;
import mx.clinica.cambranes.agenda.domain.repository.SessionTypeRepository;
import mx.clinica.cambranes.agenda.domain.repository.TherapistRepository;
import mx.clinica.cambranes.agenda.domain.state.AppointmentStateResolver;
import mx.clinica.cambranes.agenda.domain.state.CancelledState;
import mx.clinica.cambranes.agenda.domain.state.CompletedState;
import mx.clinica.cambranes.agenda.domain.state.IllegalStateTransitionException;
import mx.clinica.cambranes.agenda.domain.state.RescheduledState;
import mx.clinica.cambranes.agenda.domain.state.ScheduledState;
import mx.clinica.cambranes.agenda.domain.validation.AppointmentValidationChain;
import mx.clinica.cambranes.agenda.domain.validation.AppointmentValidator;
import mx.clinica.cambranes.agenda.domain.validation.ValidationError;
import mx.clinica.cambranes.agenda.service.exception.BusinessRuleException;
import mx.clinica.cambranes.agenda.service.exception.NotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pruebas de la logica principal de la agenda: crear, cancelar y
 * reprogramar citas. Los repositorios se simulan con Mockito; el patron
 * State, la Factory y la cadena de validacion se usan con sus
 * implementaciones reales (la cadena con un validador controlado por la prueba).
 */
class AppointmentServiceTest {

    private static final LocalDateTime INICIO = LocalDateTime.of(2026, 5, 13, 10, 0);

    private AppointmentRepository appointmentRepository;
    private PatientRepository patientRepository;
    private TherapistRepository therapistRepository;
    private RoomRepository roomRepository;
    private SessionTypeRepository sessionTypeRepository;
    private ApplicationEventPublisher events;
    private AppointmentService service;

    /**
     * Validador simulado dentro de la cadena real: devuelve en orden las
     * respuestas encoladas, o una lista vacia si no hay ninguna.
     */
    private final Deque<List<ValidationError>> respuestasDelValidador = new ArrayDeque<>();

    private final User recepcionista = user(99L);

    @BeforeEach
    void setUp() {
        appointmentRepository = mock(AppointmentRepository.class);
        patientRepository = mock(PatientRepository.class);
        therapistRepository = mock(TherapistRepository.class);
        roomRepository = mock(RoomRepository.class);
        sessionTypeRepository = mock(SessionTypeRepository.class);
        events = mock(ApplicationEventPublisher.class);

        AppointmentValidator validadorSimulado = ctx -> {
            List<ValidationError> respuesta = respuestasDelValidador.poll();
            return respuesta == null ? List.of() : respuesta;
        };
        AppointmentValidationChain validationChain =
                new AppointmentValidationChain(List.of(validadorSimulado));

        AppointmentStatusRepository statusRepository = mock(AppointmentStatusRepository.class);
        when(statusRepository.getReferenceById(any())).thenAnswer(inv -> status(inv.getArgument(0)));

        AppointmentStateResolver stateResolver = new AppointmentStateResolver(List.of(
                new ScheduledState(statusRepository), new CancelledState(),
                new RescheduledState(), new CompletedState()));

        service = new AppointmentService(
                appointmentRepository, patientRepository, therapistRepository,
                roomRepository, sessionTypeRepository,
                new AppointmentFactory(statusRepository),
                validationChain, stateResolver, events, new ObjectMapper());

        // Por defecto save() asigna un id a las citas nuevas.
        when(appointmentRepository.save(any())).thenAnswer(inv -> {
            Appointment a = inv.getArgument(0);
            if (a.getId() == null) a.setId(100L);
            return a;
        });
    }

    // ===================== create =====================

    @Test
    void create_conDatosValidos_guardaCitaProgramadaYPublicaEvento() {
        catalogosExistentes(3L);

        Appointment creada = service.create(request(), recepcionista);

        assertThat(creada.getId()).isEqualTo(100L);
        assertThat(creada.getStatus().getId()).isEqualTo(AppointmentStatus.SCHEDULED);
        assertThat(creada.getEndAt()).isEqualTo(INICIO.plusMinutes(60));
        assertThat(creada.getCreatedBy()).isSameAs(recepcionista);
        verify(events).publishEvent(any(AppointmentEvent.Created.class));
    }

    @Test
    void create_pacienteInexistente_lanzaNotFoundExceptionYNoGuarda() {
        when(patientRepository.findById(1L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.create(request(), recepcionista))
                .isInstanceOf(NotFoundException.class)
                .hasMessage("Paciente 1 no existe.");
        verify(appointmentRepository, never()).save(any());
    }

    @Test
    void create_horarioConConflictos_lanzaBusinessRuleExceptionYNoGuarda() {
        catalogosExistentes(3L);
        List<ValidationError> conflictos = List.of(
                ValidationError.of("therapist", "El terapeuta ya tiene una cita de 10:00 a 11:00."));
        respuestasDelValidador.add(conflictos);

        assertThatThrownBy(() -> service.create(request(), recepcionista))
                .isInstanceOf(BusinessRuleException.class)
                .satisfies(e -> assertThat(((BusinessRuleException) e).getErrors()).isEqualTo(conflictos));
        verify(appointmentRepository, never()).save(any());
        verify(events, never()).publishEvent(any(Object.class));
    }

    // ===================== cancel =====================

    @Test
    void cancel_citaProgramada_cambiaAEstadoCanceladaYGuardaMotivo() {
        Appointment cita = citaConEstado(5L, AppointmentStatus.SCHEDULED);
        when(appointmentRepository.findById(5L)).thenReturn(Optional.of(cita));

        service.cancel(5L, "El paciente no puede asistir", recepcionista);

        assertThat(cita.getStatus().getId()).isEqualTo(AppointmentStatus.CANCELLED);
        assertThat(cita.getCancelledReason()).isEqualTo("El paciente no puede asistir");
        assertThat(cita.getCancelledAt()).isNotNull();
        verify(appointmentRepository).save(cita);
        verify(events).publishEvent(any(AppointmentEvent.Cancelled.class));
    }

    @Test
    void cancel_citaYaCancelada_lanzaIllegalStateTransitionYNoGuarda() {
        Appointment cita = citaConEstado(5L, AppointmentStatus.CANCELLED);
        when(appointmentRepository.findById(5L)).thenReturn(Optional.of(cita));

        assertThatThrownBy(() -> service.cancel(5L, "otra vez", recepcionista))
                .isInstanceOf(IllegalStateTransitionException.class);
        verify(appointmentRepository, never()).save(any());
        verify(events, never()).publishEvent(any(Object.class));
    }

    // ===================== reschedule =====================

    @Test
    void reschedule_horarioLibre_archivaOriginalYCreaNuevaCita() {
        Appointment original = citaConEstado(7L, AppointmentStatus.SCHEDULED);
        when(appointmentRepository.findById(7L)).thenReturn(Optional.of(original));
        LocalDateTime nuevoInicio = LocalDateTime.of(2026, 5, 14, 12, 0);

        Appointment nueva = service.reschedule(7L, new RescheduleRequest(nuevoInicio), recepcionista);

        assertThat(original.getStatus().getId()).isEqualTo(AppointmentStatus.RESCHEDULED);
        assertThat(nueva.getStatus().getId()).isEqualTo(AppointmentStatus.SCHEDULED);
        assertThat(nueva.getStartAt()).isEqualTo(nuevoInicio);
        assertThat(nueva.getEndAt()).isEqualTo(nuevoInicio.plusMinutes(60));
        assertThat(nueva.getRescheduledFrom()).isSameAs(original);
        verify(events).publishEvent(any(AppointmentEvent.Rescheduled.class));
    }

    @Test
    void reschedule_horarioOcupado_lanzaBusinessRuleExceptionYNoArchivaOriginal() {
        Appointment original = citaConEstado(7L, AppointmentStatus.SCHEDULED);
        when(appointmentRepository.findById(7L)).thenReturn(Optional.of(original));
        respuestasDelValidador.add(
                List.of(ValidationError.of("therapist", "El terapeuta ya tiene una cita.")));

        assertThatThrownBy(() -> service.reschedule(7L,
                new RescheduleRequest(LocalDateTime.of(2026, 5, 14, 12, 0)), recepcionista))
                .isInstanceOf(BusinessRuleException.class);
        assertThat(original.getStatus().getId()).isEqualTo(AppointmentStatus.SCHEDULED);
        verify(appointmentRepository, never()).save(any());
    }

    // ===================== helpers =====================

    /** Registra paciente 1, terapeuta 2 (con la sala indicada), sala 3 y tipo de sesion 1. */
    private void catalogosExistentes(Long salaDelTerapeuta) {
        Therapist terapeuta = new Therapist();
        terapeuta.setId(2L);
        terapeuta.setRoom(room(salaDelTerapeuta));
        SessionType tipo = new SessionType();
        tipo.setId((short) 1);

        when(patientRepository.findById(1L)).thenReturn(Optional.of(patient(1L)));
        when(therapistRepository.findById(2L)).thenReturn(Optional.of(terapeuta));
        when(roomRepository.findById(3L)).thenReturn(Optional.of(room(3L)));
        when(sessionTypeRepository.findById((short) 1)).thenReturn(Optional.of(tipo));
    }

    private CreateAppointmentRequest request() {
        return new CreateAppointmentRequest(1L, 2L, 3L, (short) 1, INICIO, 60,
                "Primera sesion", new BigDecimal("350.00"), null);
    }

    private Appointment citaConEstado(Long id, short statusId) {
        Therapist terapeuta = new Therapist();
        terapeuta.setId(2L);
        Appointment a = Appointment.builder()
                .patient(patient(1L)).therapist(terapeuta).room(room(3L))
                .startAt(INICIO).endAt(INICIO.plusMinutes(60)).durationMinutes(60)
                .status(status(statusId))
                .build();
        a.setId(id);
        return a;
    }

    private static AppointmentStatus status(short id) {
        return AppointmentStatus.builder().id(id).code("S" + id).build();
    }

    private static Patient patient(Long id) { Patient p = new Patient(); p.setId(id); return p; }
    private static Room room(Long id)       { Room r = new Room(); r.setId(id); return r; }
    private static User user(Long id)       { User u = new User(); u.setId(id); return u; }
}
