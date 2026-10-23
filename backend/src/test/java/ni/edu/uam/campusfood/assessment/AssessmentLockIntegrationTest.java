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
                Collections.nCopies(60, 2), 18, "UN");

        assertThat(submitted.status()).isEqualTo(AttemptStatus.SUBMITTED);
        assertThat(submitted.responses()).hasSize(60);
        var staffRow = service.listSubmitted("weil-form-a").get(0);
        assertThat(staffRow.directScore()).isEqualTo(10);
        assertThat(staffRow.pageScores()).containsExactly(2, 2, 2, 2, 2);
        assertThat(staffRow.age()).isEqualTo(18);
        assertThat(staffRow.normGroup()).isEqualTo("UN");
        assertThat(responses.countByAttemptId(opened.id())).isEqualTo(60);
    }

    @Test
    void elPuntajeUsaLaClaveDelServidor() {
        var opened = service.open(userId, "weil-form-a", 1, null);
        int[] key = {2, 8, 7, 4, 5, 6, 8, 4, 4, 7, 3, 2};
        var perfect = java.util.stream.IntStream.range(0, 60).mapToObj(i -> key[i % 12]).toList();

        service.submit(userId, opened.id(), opened.leaseToken(), opened.version(), perfect, 18, "UN");

        var staffRow = service.listSubmitted("weil-form-a").get(0);
        assertThat(staffRow.directScore()).isEqualTo(60);
        assertThat(staffRow.pageScores()).containsExactly(12, 12, 12, 12, 12);
    }

    @Test
    void unIntentoAjenoSeComportaComoSiNoExistiera() {
        var opened = service.open(userId, "weil-form-a", 1, null);
        Long otherUser = users.save(User.manual("Luis Pérez", "luis@example.com", "hash", "002")).getId();

        Throwable foreign = org.assertj.core.api.Assertions.catchThrowable(() -> service.get(otherUser, opened.id()));
        Throwable missing = org.assertj.core.api.Assertions.catchThrowable(() -> service.get(otherUser, 999_999L));

        assertThat(foreign).isInstanceOf(AssessmentConflictException.class);
        assertThat(foreign).hasMessage(missing.getMessage());
    }

    @Test
    void unUsuarioNoPuedeEscribirEnElIntentoDeOtro() {
        var opened = service.open(userId, "weil-form-a", 1, null);
        Long otherUser = users.save(User.manual("Luis Pérez", "luis@example.com", "hash", "002")).getId();

        assertThatThrownBy(() -> service.answer(otherUser, opened.id(), 0, 2, opened.leaseToken(), opened.version()))
                .isInstanceOf(AssessmentConflictException.class)
                .hasMessageContaining("Intento no encontrado");
    }

    @Test
    void laVistaDelAspiranteNoTieneCamposDePuntaje() {
        var names = java.util.Arrays.stream(AssessmentService.AttemptView.class.getRecordComponents())
                .map(java.lang.reflect.RecordComponent::getName).toList();

        assertThat(names).doesNotContain("directScore", "pageScores", "percentile", "ci");
    }

    @Test
    void rechazaUnGrupoDeBaremoInvalidoOUnaEdadFueraDeRango() {
        var opened = service.open(userId, "weil-form-a", 1, null);
        var answers = Collections.nCopies(60, 2);

        assertThatThrownBy(() -> service.submit(userId, opened.id(), opened.leaseToken(), opened.version(), answers, 18, "XX"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.submit(userId, opened.id(), opened.leaseToken(), opened.version(), answers, 30, "7-16"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
