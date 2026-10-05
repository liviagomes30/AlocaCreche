package org.alocacreche.core.matricula.service;

import jakarta.persistence.EntityManager;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.alocacreche.core.comum.seguranca.SessaoTenantResolver;
import org.alocacreche.core.comum.tenant.TenantContext;
import org.alocacreche.core.matricula.Creche;
import org.alocacreche.core.matricula.CrecheRepository;
import org.alocacreche.core.matricula.Turma;
import org.alocacreche.core.matricula.TurmaRepository;
import org.alocacreche.core.outbox.OutboxEventWriter;
import org.alocacreche.core.outbox.OutboxPayloads.VagaLiberadaPayload;
import org.alocacreche.core.outbox.OutboxRabbitConfig;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class CrecheTurmaServiceImpl implements CrecheTurmaService {

    private final SessaoTenantResolver sessaoTenantResolver;
    private final EntityManager entityManager;
    private final CrecheRepository crecheRepository;
    private final TurmaRepository turmaRepository;
    private final OutboxEventWriter outboxEventWriter;

    @Override
    @Transactional
    public CrecheCriadaResponse criarCreche(CriarCrecheRequest request) {
        Long municipioId = sessaoTenantResolver.municipioIdAtual();
        TenantContext.aplicar(entityManager, municipioId);

        Creche creche = new Creche();
        creche.setMunicipioId(municipioId);
        creche.setNome(request.nome());
        creche.setCapacidadeTotal(request.capacidade());
        creche.setEndereco(request.endereco());
        Creche salva = crecheRepository.saveAndFlush(creche);

        log.info("Creche {} criada no municipio {}", salva.getId(), municipioId);
        return new CrecheCriadaResponse(salva.getId());
    }

    @Override
    @Transactional
    public TurmaCriadaResponse criarTurma(CriarTurmaRequest request) {
        Long municipioId = sessaoTenantResolver.municipioIdAtual();
        TenantContext.aplicar(entityManager, municipioId);

        if (request.vagasTotais() == null || request.vagasTotais() <= 0) {
            throw new DadoInvalidoException("vagasTotais deve ser maior que zero");
        }

        Creche creche =
                crecheRepository
                        .findById(request.crecheId())
                        .orElseThrow(() -> new RecursoNaoEncontradoException("Creche nao encontrada"));

        Turma turma = new Turma();
        turma.setMunicipioId(municipioId);
        turma.setCreche(creche);
        turma.setFaixaEtaria(request.faixaEtaria());
        turma.setVagasTotais(request.vagasTotais());
        turma.setVagasDisponiveis(request.vagasTotais());
        Turma salva = turmaRepository.saveAndFlush(turma);

        outboxEventWriter.registrar(
                OutboxRabbitConfig.VAGA_LIBERADA_ROUTING_KEY,
                new VagaLiberadaPayload(
                        OutboxRabbitConfig.VAGA_LIBERADA_ROUTING_KEY,
                        municipioId,
                        salva.getId(),
                        creche.getId(),
                        salva.getVagasDisponiveis(),
                        LocalDateTime.now()));

        log.info("Turma {} criada na creche {} do municipio {}", salva.getId(), creche.getId(), municipioId);
        return new TurmaCriadaResponse(salva.getId());
    }
}
