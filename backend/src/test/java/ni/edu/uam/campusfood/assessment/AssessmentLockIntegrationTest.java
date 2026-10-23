package ni.edu.uam.campusfood.assessment;

import ni.edu.uam.campusfood.user.User;
import ni.edu.uam.campusfood.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.Collections;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
class AssessmentLockIntegrationTest {
    @Autowired AssessmentService service;
    @Autowired AssessmentResponseRepository responses;
    @Autowired AssessmentAttemptRepository attempts;
    @Autowired UserRepository users;

    private Long userId;

    @BeforeEach
    void prepare() {
        responses.deleteAll();
        attempts.deleteAll();
        users.deleteAll();
        userId = users.save(User.manual("Ana López", "ana@example.com", "hash", "001")).getId();
    }

    @Test
    void unSegundoClienteNoPuedeAbrirElIntentoMientrasElLeaseEsteActivo() {
        var first = service.open(userId, "weil-form-a", 1, null);

        assertThatThrownBy(() -> service.open(userId, "weil-form-a", 1, null))
                .isInstanceOf(AssessmentConflictException.class)
                .hasMessageContaining("otra ventana o dispositivo");

        var resumed = service.open(userId, "weil-form-a", 1, first.leaseToken());
        assertThat(resumed.id()).isEqualTo(first.id());
        assertThat(resumed.leaseToken()).isNotEqualTo(first.leaseToken());
    }

    @Test
    void unaVersionViejaNoPuedeSobrescribirElIntento() {
        var opened = service.open(userId, "weil-form-a", 1, null);
        var renewed = service.renew(userId, opened.id(), opened.leaseToken(), opened.version());

        assertThatThrownBy(() -> service.answer(userId, opened.id(), 0, 2,
                renewed.leaseToken(), opened.version()))
                .isInstanceOf(AssessmentConflictException.class)
                .hasMessageContaining("cambió en otra sesión");
    }

    @Test
    void laEntregaGuardaTodasLasRespuestasEnUnaTransaccion() {
        var opened = service.open(userId, "weil-form-a", 1, null);

        var submitted = service.submit(userId, opened.id(), opened.leaseToken(), opened.version(),
                Collections.nCopies(60, 2));

        assertThat(submitted.status()).isEqualTo(AttemptStatus.SUBMITTED);
        assertThat(submitted.responses()).hasSize(60);
        assertThat(responses.countByAttemptId(opened.id())).isEqualTo(60);
    }
}
