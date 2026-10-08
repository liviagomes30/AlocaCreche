package org.alocacreche.core.matricula.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.alocacreche.core.notificacao.Municipio;
import org.alocacreche.core.notificacao.MunicipioRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Job de manutencao da matricula (ADR 0001, decisoes 3 e 19): expira matriculas com prazo vencido
 * e e a rede de seguranca da alocacao automatica - o evento em processo
 * ({@link VagaLiberadaInternalEvent}) nao e duravel, este job e. Roda a cada
 * {@code alocacreche.matricula.manutencao.intervalo-ms} (2 minutos por padrao, o SLA declarado),
 * um municipio por vez; a tabela municipio nao tem RLS, entao listar os municipios nao precisa de
 * tenant.
 */
@Slf4j
@Component
@ConditionalOnProperty(
        name = "alocacreche.matricula.manutencao.habilitado",
        havingValue = "true",
        matchIfMissing = true)
@RequiredArgsConstructor
public class ManutencaoMatriculaScheduler {

    private final MunicipioRepository municipioRepository;
    private final ManutencaoMatriculaPorMunicipio manutencaoPorMunicipio;

    @Scheduled(
            fixedDelayString = "${alocacreche.matricula.manutencao.intervalo-ms:120000}",
            initialDelayString = "${alocacreche.matricula.manutencao.intervalo-ms:120000}")
    public void executar() {
        for (Municipio municipio : municipioRepository.findAll()) {
            try {
                manutencaoPorMunicipio.executar(municipio.getId());
            } catch (RuntimeException excecao) {
                log.error("Falha na manutencao das matriculas do municipio {}", municipio.getId(), excecao);
            }
        }
    }
}
