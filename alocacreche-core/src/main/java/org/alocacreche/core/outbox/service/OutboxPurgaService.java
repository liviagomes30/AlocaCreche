package org.alocacreche.core.outbox.service;

import java.time.LocalDateTime;
import lombok.extern.slf4j.Slf4j;
import org.alocacreche.core.outbox.domain.OutboxEvent;
import org.alocacreche.core.outbox.repository.OutboxEventRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
public class OutboxPurgaService {

    private final OutboxEventRepository repository;
    private final int retencaoDias;

    public OutboxPurgaService(
            OutboxEventRepository repository,
            @Value("${alocacreche.outbox.purga.retencao-dias:7}") int retencaoDias) {
        if (retencaoDias < 1) {
            throw new IllegalArgumentException(
                    "alocacreche.outbox.purga.retencao-dias deve ser >= 1, recebido: " + retencaoDias);
        }
        this.repository = repository;
        this.retencaoDias = retencaoDias;
    }

    @Transactional
    public int purgarProcessadosAntigos() {
        LocalDateTime corte = LocalDateTime.now().minusDays(retencaoDias);
        int apagados =
                repository.apagarPorStatusCriadosAntes(OutboxEvent.STATUS_PROCESSADO, corte);
        log.info(
                "Purga da Outbox: {} evento(s) PROCESSADO anteriores a {} removido(s)",
                apagados,
                corte);
        return apagados;
    }
}
