package org.alocacreche.core.comum.seguranca.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.alocacreche.core.comum.seguranca.exception.TentativasExcedidasException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("LimitadorTentativasLogin")
class LimitadorTentativasLoginTest {

    private static final Long MUNICIPIO = 1L;
    private static final String EMAIL = "gestora@prefeitura.gov.br";

    private RelogioAjustavel relogio;
    private LimitadorTentativasLogin limitador;

    @BeforeEach
    void setUp() {
        relogio = new RelogioAjustavel();
        limitador = new LimitadorTentativasLogin(3, Duration.ofMinutes(15), relogio);
    }

    @Test
    @DisplayName("abaixo do limite de falhas nao bloqueia")
    void abaixoDoLimiteNaoBloqueia() {
        falhar(2);

        assertThatCode(() -> limitador.verificarBloqueio(MUNICIPIO, EMAIL)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("ao atingir o limite bloqueia e informa quanto falta")
    void noLimiteBloqueia() {
        falhar(3);

        assertThatThrownBy(() -> limitador.verificarBloqueio(MUNICIPIO, EMAIL))
                .isInstanceOfSatisfying(
                        TentativasExcedidasException.class,
                        e -> assertThat(e.getSegundosParaLiberar()).isEqualTo(900));
    }

    @Test
    @DisplayName("depois da janela o bloqueio expira")
    void bloqueioExpiraDepoisDaJanela() {
        falhar(3);
        relogio.avancar(Duration.ofMinutes(15));

        assertThatCode(() -> limitador.verificarBloqueio(MUNICIPIO, EMAIL)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("login com sucesso zera as falhas")
    void sucessoZeraAsFalhas() {
        falhar(2);
        limitador.registrarSucesso(MUNICIPIO, EMAIL);
        falhar(2);

        assertThatCode(() -> limitador.verificarBloqueio(MUNICIPIO, EMAIL)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("o bloqueio vale so para o municipio e email que falharam")
    void bloqueioIsoladoPorChave() {
        falhar(3);

        assertThatCode(() -> limitador.verificarBloqueio(2L, EMAIL)).doesNotThrowAnyException();
        assertThatCode(() -> limitador.verificarBloqueio(MUNICIPIO, "outra@prefeitura.gov.br"))
                .doesNotThrowAnyException();
    }

    private void falhar(int vezes) {
        for (int i = 0; i < vezes; i++) {
            limitador.registrarFalha(MUNICIPIO, EMAIL);
        }
    }

    private static final class RelogioAjustavel extends Clock {

        private Instant agora = Instant.parse("2026-10-06T12:00:00Z");

        void avancar(Duration duracao) {
            agora = agora.plus(duracao);
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return agora;
        }
    }
}
