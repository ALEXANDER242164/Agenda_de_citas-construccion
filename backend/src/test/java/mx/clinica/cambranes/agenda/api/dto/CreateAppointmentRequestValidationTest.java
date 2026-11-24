package mx.clinica.cambranes.agenda.api.dto;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Reglas de entrada para agendar una cita: la cuota no puede ser negativa.
 */
class CreateAppointmentRequestValidationTest {

    private static ValidatorFactory factory;
    private static Validator validator;

    @BeforeAll
    static void initValidator() {
        factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @AfterAll
    static void closeValidator() {
        factory.close();
    }

    // --- Caso normal ---

    @Test
    void solicitudCompletaYValida_noTieneErrores() {
        CreateAppointmentRequest req = request(60, new BigDecimal("350.00"), "Primera sesion");
        assertThat(validator.validate(req)).isEmpty();
    }

    // --- Caso limite ---

    @Test
    void cuotaCero_valorMinimo_esValida() {
        assertThat(validator.validate(request(60, BigDecimal.ZERO, null))).isEmpty();
    }

    // --- Caso de error ---

    @Test
    void cuotaNegativa_esRechazada() {
        Set<ConstraintViolation<CreateAppointmentRequest>> errores =
                validator.validate(request(60, new BigDecimal("-0.01"), null));

        assertThat(errores).singleElement().satisfies(v -> {
            assertThat(v.getPropertyPath().toString()).isEqualTo("cuota");
            assertThat(v.getMessage()).isEqualTo("La cuota no puede ser negativa.");
        });
    }

    private CreateAppointmentRequest request(Integer duracion, BigDecimal cuota, String comentarios) {
        return new CreateAppointmentRequest(
                1L, 2L, 3L, (short) 1,
                LocalDateTime.of(2026, 5, 13, 10, 0),
                duracion, comentarios, cuota, null);
    }
}
