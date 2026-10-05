package org.alocacreche.core.inscricao.service;

import jakarta.persistence.EntityManager;
import java.time.LocalDate;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.alocacreche.core.comum.seguranca.SessaoTenantResolver;
import org.alocacreche.core.comum.tenant.TenantContext;
import org.alocacreche.core.inscricao.Crianca;
import org.alocacreche.core.inscricao.FilaEspera;
import org.alocacreche.core.inscricao.FilaEsperaRepository;
import org.alocacreche.core.inscricao.Inscricao;
import org.alocacreche.core.inscricao.InscricaoRepository;
import org.alocacreche.core.inscricao.Responsavel;
import org.alocacreche.core.inscricao.ResponsavelRepository;
import org.alocacreche.core.matricula.Turma;
import org.alocacreche.core.matricula.TurmaRepository;
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
public class InscricaoServiceImpl implements InscricaoService {

    private final SessaoTenantResolver sessaoTenantResolver;
    private final EntityManager entityManager;
    private final TurmaRepository turmaRepository;
    private final ResponsavelRepository responsavelRepository;
    private final InscricaoRepository inscricaoRepository;
    private final FilaEsperaRepository filaEsperaRepository;
    private final MunicipioRepository municipioRepository;
    private final OutboxEventWriter outboxEventWriter;

    @Override
    @Transactional
    public InscricaoCriadaResponse registrarInscricao(RegistrarInscricaoRequest request) {
        Long municipioId = sessaoTenantResolver.municipioIdAtual();
        TenantContext.aplicar(entityManager, municipioId);

        Turma turma =
                turmaRepository
                        .findById(request.turmaId())
                        .orElseThrow(() -> new RecursoNaoEncontradoException("Turma nao encontrada"));
        exigirCpfLivre(request.responsavel().cpf());

        Responsavel responsavel = salvarResponsavel(request, municipioId);
        Inscricao inscricao = salvarInscricao(request, municipioId, responsavel.getCriancas().get(0));
        int posicao = salvarNaFila(inscricao, turma.getId(), municipioId);

        outboxEventWriter.registrar(
                OutboxRabbitConfig.FILA_ATUALIZADA_ROUTING_KEY,
                new FilaEsperaAtualizadaPayload(
                        OutboxRabbitConfig.FILA_ATUALIZADA_ROUTING_KEY,
                        municipioId,
                        turma.getId(),
                        turma.getCreche().getId(),
                        inscricao.getId(),
                        LocalDate.now(),
                        null,
                        LocalDateTime.now()));

        log.info(
                "Inscricao {} registrada na turma {} do municipio {} na posicao {}",
                inscricao.getId(),
                turma.getId(),
                municipioId,
                posicao);
        return new InscricaoCriadaResponse(inscricao.getId(), posicao);
    }

    private void exigirCpfLivre(String cpf) {
        if (responsavelRepository.findByCpf(cpf).isPresent()) {
            throw new ConflitoException("CPF ja cadastrado");
        }
    }

    private Responsavel salvarResponsavel(RegistrarInscricaoRequest request, Long municipioId) {
        RegistrarInscricaoRequest.ResponsavelRequest dados = request.responsavel();
        Responsavel responsavel = new Responsavel();
        responsavel.setMunicipioId(municipioId);
        responsavel.setNome(dados.nome());
        responsavel.setCpf(dados.cpf());
        responsavel.setTelefone(dados.telefone());
        responsavel.setEmail(dados.email());
        responsavel.setEndereco(dados.endereco());

        Crianca crianca = new Crianca();
        crianca.setMunicipioId(municipioId);
        crianca.setNome(request.crianca().nome());
        crianca.setDataNascimento(request.crianca().dataNascimento());
        responsavel.adicionarCrianca(crianca);

        try {
            return responsavelRepository.saveAndFlush(responsavel);
        } catch (DataIntegrityViolationException excecao) {
            throw new ConflitoException("CPF ja cadastrado");
        }
    }

    private Inscricao salvarInscricao(
            RegistrarInscricaoRequest request, Long municipioId, Crianca crianca) {
        Inscricao inscricao = new Inscricao();
        inscricao.setMunicipio(municipioRepository.getReferenceById(municipioId));
        inscricao.setCrianca(crianca);
        inscricao.setRendaPerCapita(request.rendaPerCapita());
        inscricao.setCriterioVulnerabilidade(request.criterioVulnerabilidade());
        // TODO: calcular o scorePrioridade; o motor de prioridade e da Stack A (Inscricao e Fila) e fica nulo no minimo
        return inscricaoRepository.saveAndFlush(inscricao);
    }

    private int salvarNaFila(Inscricao inscricao, Long turmaId, Long municipioId) {
        int posicao = filaEsperaRepository.findMaiorPosicaoByTurmaId(turmaId).orElse(0) + 1;
        FilaEspera fila = new FilaEspera();
        fila.setMunicipioId(municipioId);
        fila.setInscricao(inscricao);
        fila.setTurmaId(turmaId);
        fila.setPosicao(posicao);
        filaEsperaRepository.save(fila);
        return posicao;
    }
}
