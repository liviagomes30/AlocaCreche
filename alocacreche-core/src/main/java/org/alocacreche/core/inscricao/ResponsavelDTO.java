package org.alocacreche.core.inscricao;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.alocacreche.core.comum.Endereco;

/**
 * Dados de cadastro do responsavel, usados tanto em {@code POST .../responsaveis} quanto no
 * cadastro inline de {@code POST .../inscricoes}. O endereco e opcional no cadastro, mas a
 * alocacao de vaga exige latitude e longitude. CPF e dado sensivel (LGPD): o toString nao o expoe.
 */
public record ResponsavelDTO(
        @NotBlank @Size(max = 200) String nome,
        @NotBlank @Size(max = 14) String cpf,
        @Size(max = 20) String telefone,
        @Email @Size(max = 200) String email,
        Endereco endereco) {

    @Override
    public String toString() {
        return "ResponsavelDTO[]";
    }
}
