package org.alocacreche.core.inscricao;

import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import org.alocacreche.core.notificacao.Municipio;
import org.alocacreche.core.notificacao.MunicipioRepository;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class InscricaoService {

    private final InscricaoRepository inscricaoRepository;
    private final CriancaRepository criancaRepository;
    private final MunicipioRepository municipioRepository;
    private final MatriculaConsulta matriculaConsulta;
    private final CalculadoraScorePrioridade calculadoraScorePrioridade;

    @Transactional
    public Inscricao registrarInscricao(InscricaoDTO dto) {

        Municipio municipio = municipioRepository.findById(dto.municipioId())
                .orElseThrow(() -> new IllegalArgumentException(
                        "Município não encontrado: " + dto.municipioId()));

        Crianca crianca = criancaRepository.findById(dto.criancaId())
                .orElseThrow(() -> new IllegalArgumentException(
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

        return inscricaoRepository.save(inscricao);
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