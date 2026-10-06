package org.alocacreche.core.inscricao;

import cn.dev33.satoken.exception.NotPermissionException;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import org.alocacreche.core.comum.excecao.RecursoNaoEncontradoException;
import org.alocacreche.core.comum.seguranca.SessaoTenantResolver;
import org.alocacreche.core.comum.tenant.TenantContext;
import org.alocacreche.core.notificacao.Municipio;
import org.alocacreche.core.notificacao.MunicipioRepository;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class InscricaoService {

    private final SessaoTenantResolver sessaoTenantResolver;
    private final EntityManager entityManager;
    private final InscricaoRepository inscricaoRepository;
    private final CriancaRepository criancaRepository;
    private final MunicipioRepository municipioRepository;
    private final MatriculaConsulta matriculaConsulta;
    private final CalculadoraScorePrioridade calculadoraScorePrioridade;

    @Transactional
    public InscricaoRegistradaResponse registrarInscricao(InscricaoDTO dto) {

        Long municipioId = sessaoTenantResolver.municipioIdAtual();
        if (!municipioId.equals(dto.municipioId())) {
            throw new NotPermissionException("municipioId");
        }
        TenantContext.aplicar(entityManager, municipioId);

        Municipio municipio = municipioRepository.findById(municipioId)
                .orElseThrow(() -> new RecursoNaoEncontradoException(
                        "Município não encontrado: " + municipioId));

        Crianca crianca = criancaRepository.findById(dto.criancaId())
                .orElseThrow(() -> new RecursoNaoEncontradoException(
                        "Criança não encontrada: " + dto.criancaId()));

        boolean irmaoMatriculado = verificarIrmaoMatriculado(crianca);

        double score = calculadoraScorePrioridade.calcular(
                dto.rendaPerCapita(),
                Boolean.TRUE.equals(dto.criterioVulnerabilidade()),
                irmaoMatriculado,
                0.0,
                municipio.getConfiguracaoPriorizacao());

        Inscricao inscricao = new Inscricao();
        inscricao.setMunicipio(municipio);
        inscricao.setCrianca(crianca);
        inscricao.setRendaPerCapita(dto.rendaPerCapita());
        inscricao.setCriterioVulnerabilidade(
                Boolean.TRUE.equals(dto.criterioVulnerabilidade()));
        inscricao.setScorePrioridade(score);

        // TODO: esta rota registra a inscricao mas nao a coloca na fila de espera nem grava o evento fila.atualizada para os relatorios
        Inscricao salva = inscricaoRepository.save(inscricao);
        return new InscricaoRegistradaResponse(salva.getId(), salva.getScorePrioridade(), salva.getStatus());
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
}
