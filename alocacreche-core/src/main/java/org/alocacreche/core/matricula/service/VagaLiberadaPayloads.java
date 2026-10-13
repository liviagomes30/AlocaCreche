package org.alocacreche.core.matricula.service;

import java.time.LocalDateTime;
import org.alocacreche.core.comum.Endereco;
import org.alocacreche.core.matricula.Creche;
import org.alocacreche.core.matricula.Turma;
import org.alocacreche.core.outbox.config.OutboxRabbitConfig;
import org.alocacreche.core.outbox.dto.OutboxPayloads.VagaLiberadaPayload;

/** Monta o evento vaga.liberada num lugar so: quem muda as vagas de uma turma usa esta fabrica. */
final class VagaLiberadaPayloads {

    private VagaLiberadaPayloads() {
    }

    static VagaLiberadaPayload de(Long municipioId, Turma turma, LocalDateTime ocorridoEm) {
        Creche creche = turma.getCreche();
        Endereco endereco = creche.getEndereco();
        return new VagaLiberadaPayload(
                OutboxRabbitConfig.VAGA_LIBERADA_ROUTING_KEY,
                municipioId,
                turma.getId(),
                creche.getId(),
                turma.getVagasDisponiveis(),
                turma.getFaixaEtaria() == null ? null : turma.getFaixaEtaria().name(),
                creche.getNome(),
                endereco == null ? null : endereco.getBairro(),
                ocorridoEm);
    }
}
