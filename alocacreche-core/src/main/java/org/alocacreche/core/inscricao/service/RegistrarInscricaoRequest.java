package org.alocacreche.core.inscricao.service;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Past;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import org.alocacreche.core.comum.Endereco;

public record RegistrarInscricaoRequest(
        @NotNull @Valid ResponsavelRequest responsavel,
        @NotNull @Valid CriancaRequest crianca,
        @NotNull @Positive Long turmaId,
        @NotNull @PositiveOrZero BigDecimal rendaPerCapita,
        @NotNull Boolean criterioVulnerabilidade) {

    @Override
    public String toString() {
        return "RegistrarInscricaoRequest[turmaId=" + turmaId + "]";
    }

    public record ResponsavelRequest(
            @NotBlank @Size(max = 200) String nome,
            @NotBlank @Size(max = 14) String cpf,
            @Size(max = 20) String telefone,
            @Email @Size(max = 200) String email,
            Endereco endereco) {

        @Override
        public String toString() {
            return "ResponsavelRequest[]";
        }
    }

    public record CriancaRequest(
            @NotBlank @Size(max = 200) String nome, @NotNull @Past LocalDate dataNascimento) {

        @Override
        public String toString() {
            return "CriancaRequest[]";
        }
    }
}
