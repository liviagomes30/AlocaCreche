package org.alocacreche.core.matricula.service;

import jakarta.persistence.EntityManager;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.alocacreche.core.comum.Endereco;
import org.alocacreche.core.comum.excecao.ConflitoException;
import org.alocacreche.core.comum.excecao.RecursoNaoEncontradoException;
import org.alocacreche.core.comum.geolocalizacao.GeolocalizacaoService;
import org.alocacreche.core.comum.seguranca.SessaoTenantResolver;
import org.alocacreche.core.comum.tenant.TenantContext;
import org.alocacreche.core.inscricao.FilaEspera;
import org.alocacreche.core.inscricao.FilaEsperaRepository;
import org.alocacreche.core.inscricao.Inscricao;
import org.alocacreche.core.inscricao.RenumeracaoFila;
import org.alocacreche.core.inscricao.StatusInscricao;
import org.alocacreche.core.inscricao.TravaFilaTurma;
import org.alocacreche.core.matricula.Creche;
import org.alocacreche.core.matricula.Matricula;
import org.alocacreche.core.matricula.MatriculaRepository;
import org.alocacreche.core.matricula.StatusMatricula;
import org.alocacreche.core.matricula.Turma;
import org.alocacreche.core.matricula.TurmaRepository;
import org.alocacreche.core.notificacao.Municipio;
import org.alocacreche.core.notificacao.MunicipioRepository;
import org.alocacreche.core.outbox.OutboxEventWriter;
import org.alocacreche.core.outbox.OutboxPayloads.FilaEsperaAtualizadaPayload;
import org.alocacreche.core.outbox.OutboxPayloads.MatriculaAtualizadaPayload;
import org.alocacreche.core.outbox.OutboxPayloads.VagaLiberadaPayload;
import org.alocacreche.core.outbox.OutboxRabbitConfig;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Component
@RequiredArgsConstructor
public class AlocacaoVagaTransacional {

    private final SessaoTenantResolver sessaoTenantResolver;
    private final EntityManager entityManager;
    private final TurmaRepository turmaRepository;
    private final FilaEsperaRepository filaEsperaRepository;
    private final MatriculaRepository matriculaRepository;
    private final MunicipioRepository municipioRepository;
    private final GeolocalizacaoService geolocalizacaoService;
    private final OutboxEventWriter outboxEventWriter;
    private final TravaFilaTurma travaFilaTurma;
    private final RenumeracaoFila renumeracaoFila;

    /**
     * Alocacao pedida por uma requisicao HTTP: o municipio vem do token. REQUIRES_NEW pelo mesmo
     * motivo de {@link #executarNoMunicipio}; sem transacao aberta, o efeito e o de REQUIRED.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public MatriculaAlocadaResponse executar(Long turmaId) {
        return alocar(sessaoTenantResolver.municipioIdAtual(), turmaId);
    }

    /**
     * Alocacao sem token: {@link AlocacaoAutomaticaListener} (depois do commit da desistencia) e o
     * job de manutencao. REQUIRES_NEW porque, depois do commit, a transacao anterior ainda esta
     * vinculada a thread, e um REQUIRED se juntaria a ela sem nunca gravar nada.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public MatriculaAlocadaResponse executarNoMunicipio(Long municipioId, Long turmaId) {
        return alocar(municipioId, turmaId);
    }

    private MatriculaAlocadaResponse alocar(Long municipioId, Long turmaId) {
        TenantContext.aplicar(entityManager, municipioId);
        // Mesmo lock do enfileiramento: sem ele, a leitura do primeiro da fila e a remocao
        // competiriam com uma renumeracao em andamento e poderiam convocar fora da ordem do score.
        travaFilaTurma.travar(municipioId, turmaId);

        Turma turma =
                turmaRepository
                        .findById(turmaId)
                        .orElseThrow(() -> new RecursoNaoEncontradoException("Turma nao encontrada"));
        if (turma.getVagasDisponiveis() <= 0) {
            throw new ConflitoException("Turma sem vaga disponivel");
        }
        FilaEspera primeiroDaFila =
                filaEsperaRepository
                        .findFirstByTurmaIdOrderByPosicaoAsc(turmaId)
                        .orElseThrow(() -> new ConflitoException("Fila de espera da turma esta vazia"));
        Inscricao inscricao = primeiroDaFila.getInscricao();
        Creche creche = turma.getCreche();
        Double distanciaKm = calcularDistancia(inscricao.getCrianca().getResponsavel().getEndereco(), creche.getEndereco());
        Municipio municipio =
                municipioRepository
                        .findById(municipioId)
                        .orElseThrow(() -> new RecursoNaoEncontradoException("Municipio nao encontrado"));

        LocalDateTime agora = LocalDateTime.now();
        turma.setVagasDisponiveis(turma.getVagasDisponiveis() - 1);
        turmaRepository.saveAndFlush(turma);

        Matricula matricula = new Matricula();
        matricula.setMunicipioId(municipioId);
        matricula.setInscricaoId(inscricao.getId());
        matricula.setTurma(turma);
        matricula.setStatus(StatusMatricula.PENDENTE);
        matricula.setDataConvocacao(agora);
        matricula.setPrazoConfirmacao(agora.plusDays(municipio.getPrazoConfirmacaoDias()));
        matricula.setDistanciaKm(distanciaKm);
        Matricula salva = matriculaRepository.saveAndFlush(matricula);

        filaEsperaRepository.delete(primeiroDaFila);
        inscricao.setStatus(StatusInscricao.CONVOCADA);
        // A alocada saiu da fila: quem estava atras sobe uma posicao, no banco e no Redis (o snapshot
        // publicado pela renumeracao so contem quem continua na fila, entao a alocada some do ZSET).
        renumeracaoFila.renumerar(municipioId, turmaId);

        registrarEventos(municipioId, turma, creche, primeiroDaFila, salva, agora);

        log.info("Matricula {} criada para a turma {} do municipio {}", salva.getId(), turmaId, municipioId);
        return new MatriculaAlocadaResponse(salva.getId());
    }

    // TODO: se a geocodificacao externa voltar ao escopo (ADR 0006), envolver a chamada com Circuit
    // Breaker (Resilience4j) e gravar a matricula com distancia nula quando o circuito abrir - a
    // distancia e side-channel e nao pode travar a alocacao. Hoje usa coordenadas cadastradas.
    private Double calcularDistancia(Endereco origem, Endereco destino) {
        if (!temCoordenadas(origem) || !temCoordenadas(destino)) {
            throw new DadoInvalidoException("Responsavel e creche precisam ter latitude e longitude para a alocacao");
        }
        return geolocalizacaoService.calcularDistanciaEmQuilometros(origem, destino);
    }

    private boolean temCoordenadas(Endereco endereco) {
        return endereco != null && endereco.getLatitude() != null && endereco.getLongitude() != null;
    }

    private void registrarEventos(
            Long municipioId,
            Turma turma,
            Creche creche,
            FilaEspera filaRemovida,
            Matricula matricula,
            LocalDateTime agora) {
        outboxEventWriter.registrar(
                OutboxRabbitConfig.VAGA_LIBERADA_ROUTING_KEY,
                new VagaLiberadaPayload(
                        OutboxRabbitConfig.VAGA_LIBERADA_ROUTING_KEY,
                        municipioId,
                        turma.getId(),
                        creche.getId(),
                        turma.getVagasDisponiveis(),
                        agora));
        outboxEventWriter.registrar(
                OutboxRabbitConfig.FILA_ATUALIZADA_ROUTING_KEY,
                new FilaEsperaAtualizadaPayload(
                        OutboxRabbitConfig.FILA_ATUALIZADA_ROUTING_KEY,
                        municipioId,
                        turma.getId(),
                        creche.getId(),
                        matricula.getInscricaoId(),
                        filaRemovida.getDataEntrada().toLocalDate(),
                        agora.toLocalDate(),
                        agora));
        outboxEventWriter.registrar(
                OutboxRabbitConfig.MATRICULA_ATUALIZADA_ROUTING_KEY,
                new MatriculaAtualizadaPayload(
                        OutboxRabbitConfig.MATRICULA_ATUALIZADA_ROUTING_KEY,
                        municipioId,
                        turma.getId(),
                        creche.getId(),
                        matricula.getInscricaoId(),
                        matricula.getId(),
                        matricula.getStatus().name(),
                        matricula.getDistanciaKm(),
                        matricula.getDataConvocacao().toLocalDate(),
                        null,
                        agora));
    }
}
