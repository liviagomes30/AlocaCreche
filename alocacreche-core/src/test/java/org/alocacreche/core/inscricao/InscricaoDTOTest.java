package org.alocacreche.core.inscricao;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import org.alocacreche.core.inscricao.InscricaoDTO.CriancaDTO;
import org.alocacreche.core.inscricao.InscricaoDTO.ResponsavelDTO;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Regra do modo unico de identificacao da crianca e mascaramento de dados sensiveis no toString. */
@DisplayName("InscricaoDTO")
class InscricaoDTOTest {

    private static final String CPF = "12345678901";
    private static final String NOME = "Maria Responsavel";
    private static final String RENDA = "987.65";

    private final ResponsavelDTO responsavel = new ResponsavelDTO(NOME, CPF, null, null, null);
    private final CriancaDTO crianca = new CriancaDTO("Joana", LocalDate.of(2023, 1, 10));

    @Test
    @DisplayName("aceita so criancaId ou o inline completo")
    void aceitaUmModoCompleto() {
        assertThat(dto(10L, null, null).isModoDeCriancaValido()).isTrue();
        assertThat(dto(null, responsavel, crianca).isModoDeCriancaValido()).isTrue();
    }

    @Test
    @DisplayName("rejeita nenhum modo, os dois modos e inline incompleto")
    void rejeitaModosInvalidos() {
        assertThat(dto(null, null, null).isModoDeCriancaValido()).isFalse();
        assertThat(dto(10L, responsavel, crianca).isModoDeCriancaValido()).isFalse();
        assertThat(dto(10L, responsavel, null).isModoDeCriancaValido()).isFalse();
        assertThat(dto(10L, null, crianca).isModoDeCriancaValido()).isFalse();
        assertThat(dto(null, responsavel, null).isModoDeCriancaValido()).isFalse();
        assertThat(dto(null, null, crianca).isModoDeCriancaValido()).isFalse();
    }

    @Test
    @DisplayName("toString nao expoe CPF, nome, renda nem dados da crianca")
    void toStringNaoVazaDadosSensiveis() {
        InscricaoDTO dto = dto(null, responsavel, crianca);

        assertThat(dto.toString()).doesNotContain(CPF, NOME, RENDA, "Joana");
        assertThat(responsavel.toString()).doesNotContain(CPF, NOME);
        assertThat(crianca.toString()).doesNotContain("Joana");
    }

    private InscricaoDTO dto(Long criancaId, ResponsavelDTO responsavelDto, CriancaDTO criancaDto) {
        return new InscricaoDTO(null, criancaId, responsavelDto, criancaDto, 1L, new BigDecimal(RENDA), true);
    }
}
