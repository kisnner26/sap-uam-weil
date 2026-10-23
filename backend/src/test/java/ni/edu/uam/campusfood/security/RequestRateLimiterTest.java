package ni.edu.uam.campusfood.security;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RequestRateLimiterTest {
    private static final Instant T0 = Instant.parse("2026-10-23T12:00:00Z");

    @Test
    void permiteHastaElMaximoYBloqueaLoSiguiente() {
        var limiter = new RequestRateLimiter(3, Duration.ofMinutes(5));

        assertThat(limiter.tryAcquire("1.1.1.1", T0)).isTrue();
        assertThat(limiter.tryAcquire("1.1.1.1", T0.plusSeconds(1))).isTrue();
        assertThat(limiter.tryAcquire("1.1.1.1", T0.plusSeconds(2))).isTrue();
        assertThat(limiter.tryAcquire("1.1.1.1", T0.plusSeconds(3))).isFalse();
    }

    @Test
    void cadaClaveTieneSuPropioCupo() {
        var limiter = new RequestRateLimiter(1, Duration.ofMinutes(5));

        assertThat(limiter.tryAcquire("1.1.1.1", T0)).isTrue();
        assertThat(limiter.tryAcquire("1.1.1.1", T0)).isFalse();
        assertThat(limiter.tryAcquire("2.2.2.2", T0)).isTrue();
    }

    @Test
    void elCupoSeRestablecePasadaLaVentana() {
        var limiter = new RequestRateLimiter(1, Duration.ofMinutes(5));
        limiter.tryAcquire("1.1.1.1", T0);

        assertThat(limiter.tryAcquire("1.1.1.1", T0.plusSeconds(299))).isFalse();
        assertThat(limiter.tryAcquire("1.1.1.1", T0.plusSeconds(300))).isTrue();
    }

    @Test
    void informaCuantoFaltaParaReintentar() {
        var limiter = new RequestRateLimiter(1, Duration.ofMinutes(5));
        limiter.tryAcquire("1.1.1.1", T0);
        limiter.tryAcquire("1.1.1.1", T0.plusSeconds(60));

        assertThat(limiter.retryAfterSeconds("1.1.1.1", T0.plusSeconds(60))).isEqualTo(240);
    }

    @Test
    void rechazaConfiguracionInvalida() {
        assertThatThrownBy(() -> new RequestRateLimiter(0, Duration.ofMinutes(1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RequestRateLimiter(1, Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
