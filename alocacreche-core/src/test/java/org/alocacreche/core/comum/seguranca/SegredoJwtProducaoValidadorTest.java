package org.alocacreche.core.comum.seguranca;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("SegredoJwtProducaoValidador")
class SegredoJwtProducaoValidadorTest {

    @Test
    @DisplayName("rejeita o segredo padrao do repositorio")
    void rejeitaSegredoPadrao() {
        assertThatThrownBy(() -> new SegredoJwtProducaoValidador(SegredoJwtProducaoValidador.SEGREDO_PADRAO).validar())
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("rejeita segredo vazio ou curto")
    void rejeitaVazioOuCurto() {
        assertThatThrownBy(() -> new SegredoJwtProducaoValidador("").validar())
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new SegredoJwtProducaoValidador("a".repeat(31)).validar())
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("aceita segredo proprio com 32 caracteres ou mais")
    void aceitaSegredoForte() {
        assertThatCode(() -> new SegredoJwtProducaoValidador("x".repeat(32)).validar()).doesNotThrowAnyException();
    }
}
