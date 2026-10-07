package org.alocacreche.core.inscricao;

import cn.dev33.satoken.exception.NotPermissionException;
import jakarta.persistence.EntityManager;
import java.time.LocalDateTime;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.alocacreche.core.comum.excecao.ConflitoException;
import org.alocacreche.core.comum.excecao.RecursoNaoEncontradoException;
import org.alocacreche.core.comum.seguranca.SessaoTenantResolver;
import org.alocacreche.core.comum.tenant.TenantContext;
import org.alocacreche.core.matricula.Turma;
import org.alocacreche.core.matricula.TurmaRepository;
import org.alocacreche.core.notificacao.Municipio;
import org.alocacreche.core.notificacao.MunicipioRepository;
import org.alocacreche.core.outbox.OutboxEventWriter;
import org.alocacreche.core.outbox.OutboxPayloads.FilaEsperaAtualizadaPayload;
import org.alocacreche.core.outbox.OutboxRabbitConfig;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class InscricaoService {

    /**
     * Status em que a crianca ainda ocupa a fila ou a vaga. Mesma lista do indice parcial
     * uk_inscricao_crianca_ativa em schema.sql - mudar um exige mudar o outro.
     */
    static final Set<StatusInscricao> STATUS_ATIVOS =
            Set.of(StatusInscricao.AGUARDANDO, StatusInscricao.CONVOCADA, StatusInscricao.MATRICULADA);

    private static final String MENSAGEM_INSCRICAO_ATIVA = "Crianca ja possui inscricao ativa";

    private final SessaoTenantResolver sessaoTenantResolver;
    private final EntityManager entityManager;
    private final InscricaoRepository inscricaoRepository;
    private final CriancaRepository criancaRepository;
    private final ResponsavelService responsavelService;
    private final CriancaService criancaService;
    private final MunicipioRepository municipioRepository;
    private final TurmaRepository turmaRepository;
    private final FilaEsperaRepository filaEsperaRepository;
    private final MatriculaConsulta matriculaConsulta;
    private final CalculadoraScorePrioridade calculadoraScorePrioridade;
    private final OutboxEventWriter outboxEventWriter;
    private final FilaRedisService filaRedisService;

    @Transactional
    public InscricaoRegistradaResponse registrarInscricao(InscricaoDTO dto) {
        Long municipioId = sessaoTenantResolver.municipioIdAtual();
        if (dto.municipioId() != null && !municipioId.equals(dto.municipioId())) {
            throw new NotPermissionException("municipioId");
        }
        TenantContext.aplicar(entityManager, municipioId);

        Municipio municipio = municipioRepository.findById(municipioId)
                .orElseThrow(() -> new RecursoNaoEncontradoException(
                        "Município não encontrado: " + municipioId));
        Turma turma = turmaRepository.buscarParaEnfileirar(dto.turmaId())
                .orElseThrow(() -> new RecursoNaoEncontradoException("Turma nao encontrada"));
        Crianca crianca = resolverCrianca(dto, municipioId);

        boolean criterioVulnerabilidade = Boolean.TRUE.equals(dto.criterioVulnerabilidade());
        double score = calculadoraScorePrioridade.calcular(
                dto.rendaPerCapita(),
                criterioVulnerabilidade,
                verificarIrmaoMatriculado(crianca),
                0.0,
                municipio.getConfiguracaoPriorizacao());

        Inscricao inscricao = new Inscricao();
        inscricao.setMunicipio(municipio);
        inscricao.setCrianca(crianca);
        inscricao.setRendaPerCapita(dto.rendaPerCapita());
        inscricao.setCriterioVulnerabilidade(criterioVulnerabilidade);
        inscricao.setScorePrioridade(score);
        Inscricao salva = salvarInscricao(inscricao);

        int posicao = enfileirar(salva, turma.getId(), municipioId);

        filaRedisService.adicionarAposCommit(municipioId, turma.getId(), salva.getId(), posicao);

        emitirEventoDeFila(salva, turma, municipioId);

        log.info(
                "Inscricao {} registrada na turma {} do municipio {} na posicao {}",
                salva.getId(),
                turma.getId(),
                municipioId,
                posicao);
        return new InscricaoRegistradaResponse(
                salva.getId(), posicao, salva.getScorePrioridade(), salva.getStatus());
    }

    private Crianca resolverCrianca(InscricaoDTO dto, Long municipioId) {
        if (dto.criancaId() != null) {
            Crianca crianca = criancaRepository.findById(dto.criancaId())
                    .orElseThrow(() -> new RecursoNaoEncontradoException(
                            "Criança não encontrada: " + dto.criancaId()));
            if (inscricaoRepository.existsByCriancaIdAndStatusIn(crianca.getId(), STATUS_ATIVOS)) {
                throw new ConflitoException(MENSAGEM_INSCRICAO_ATIVA);
            }
            return crianca;
        }
        Responsavel responsavel = responsavelService.cadastrarNoMunicipio(municipioId, dto.responsavel());
        return criancaService.cadastrarNoMunicipio(municipioId, responsavel, dto.crianca());
    }

    /**
     * A consulta em resolverCrianca recusa o caso comum; duas requisicoes simultaneas para a mesma
     * crianca passam pelas duas consultas, e quem barra a segunda e o indice parcial unico.
     */
    private Inscricao salvarInscricao(Inscricao inscricao) {
        try {
            return inscricaoRepository.saveAndFlush(inscricao);
        } catch (DataIntegrityViolationException excecao) {
            throw new ConflitoException(MENSAGEM_INSCRICAO_ATIVA, excecao);
        }
    }

    private boolean verificarIrmaoMatriculado(Crianca crianca) {

        Long responsavelId = crianca.getResponsavel().getId();

        return criancaRepository.findByResponsavelId(responsavelId)
                .stream()
                .filter(outraCrianca ->
                        !outraCrianca.getId().equals(crianca.getId()))
                .anyMatch(outraCrianca ->
                        inscricaoRepository.findByCriancaId(outraCrianca.getId())
                                .stream()
                                .anyMatch(inscricao ->
                                        matriculaConsulta
                                                .existeMatriculaParaInscricao(inscricao.getId())));
    }

    /**
     * A turma ja esta travada (buscarParaEnfileirar), entao inscricoes simultaneas na mesma turma
     * leem a maior posicao uma de cada vez. O unico (turma_id, posicao) em FilaEspera e a ultima
     * barreira caso algum caminho enfileire sem o lock.
     */
    private int enfileirar(Inscricao inscricao, Long turmaId, Long municipioId) {
        int posicao = filaEsperaRepository.findMaiorPosicaoByTurmaId(turmaId).orElse(0) + 1;
        FilaEspera fila = new FilaEspera();
        fila.setMunicipioId(municipioId);
        fila.setInscricao(inscricao);
        fila.setTurmaId(turmaId);
        fila.setPosicao(posicao);
        try {
            filaEsperaRepository.saveAndFlush(fila);
        } catch (DataIntegrityViolationException excecao) {
            throw new ConflitoException("Posicao na fila disputada por outra inscricao, tente novamente", excecao);
        }
        return posicao;
    }

    private void emitirEventoDeFila(Inscricao inscricao, Turma turma, Long municipioId) {
        LocalDateTime agora = LocalDateTime.now();
        outboxEventWriter.registrar(
                OutboxRabbitConfig.FILA_ATUALIZADA_ROUTING_KEY,
                new FilaEsperaAtualizadaPayload(
                        OutboxRabbitConfig.FILA_ATUALIZADA_ROUTING_KEY,
                        municipioId,
                        turma.getId(),
                        turma.getCreche().getId(),
                        inscricao.getId(),
                        agora.toLocalDate(),
                        null,
                        agora));
    }

    /**
     * Posicao da inscricao na fila da turma. Le do Redis (cache) e, na falta da chave (cache frio,
     * expirado ou Redis fora), cai para a posicao persistida em {@code FilaEspera}, que e a fonte
     * da verdade. Inscricao de outro municipio e invisivel pela RLS (404); inscricao que ja saiu da
     * fila (alocada) tambem responde 404.
     */
    @Transactional(readOnly = true)
    public PosicaoFilaResponse consultarPosicaoNaFila(Long turmaId, Long inscricaoId) {
        Long municipioId = sessaoTenantResolver.municipioIdAtual();
        TenantContext.aplicar(entityManager, municipioId);

        Inscricao inscricao = inscricaoRepository.findById(inscricaoId)
                .orElseThrow(() -> new RecursoNaoEncontradoException(
                        "Inscrição não encontrada: " + inscricaoId));
        if (inscricao.getStatus() != StatusInscricao.AGUARDANDO) {
            throw naoEstaNaFila(inscricaoId, turmaId);
        }
        Long posicao = filaRedisService
                .consultarPosicao(municipioId, turmaId, inscricaoId)
                .orElseGet(() -> posicaoPersistida(turmaId, inscricaoId));
        return new PosicaoFilaResponse(inscricaoId, turmaId, posicao);
    }

    private Long posicaoPersistida(Long turmaId, Long inscricaoId) {
        return filaEsperaRepository
                .findByInscricaoId(inscricaoId)
                .filter(fila -> turmaId.equals(fila.getTurmaId()))
                .map(fila -> fila.getPosicao().longValue())
                .orElseThrow(() -> naoEstaNaFila(inscricaoId, turmaId));
    }

    private RecursoNaoEncontradoException naoEstaNaFila(Long inscricaoId, Long turmaId) {
        return new RecursoNaoEncontradoException(
                "Inscrição " + inscricaoId + " não está na fila da turma " + turmaId);
    }
}
