package org.alocacreche.core.inscricao;

import io.swagger.v3.oas.annotations.media.Schema;
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
        @Schema(description = "Nome do responsável (fictício no exemplo).", example = "Responsável Exemplo")
        @NotBlank @Size(max = 200) String nome,
        @Schema(description = "CPF (dado sensível). O exemplo é claramente fictício.", example = "000.000.000-00")
        @NotBlank @Size(max = 14) String cpf,
        @Schema(description = "Telefone de contato (fictício no exemplo).", example = "(00) 00000-0000")
        @Size(max = 20) String telefone,
        @Schema(description = "E-mail de contato.", example = "responsavel@exemplo.invalid")
        @Email @Size(max = 200) String email,
        @Schema(description = "Endereço; a alocação de vaga exige latitude e longitude.")
        Endereco endereco) {

    @Override
    public String toString() {
        return "ResponsavelDTO[]";
    }
}
