package org.alocacreche.relatorios.ocupacao.service;

import jakarta.persistence.EntityManager;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.alocacreche.relatorios.filaespera.projection.ResumoFilaProjection;
import org.alocacreche.relatorios.filaespera.repository.FilaEsperaInscricaoRepository;
import org.alocacreche.relatorios.ocupacao.domain.OcupacaoTurma;
import org.alocacreche.relatorios.ocupacao.dto.PainelPublicoDTO;
import org.alocacreche.relatorios.ocupacao.repository.OcupacaoTurmaRepository;
import org.alocacreche.relatorios.tenant.TenantContext;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class PainelPublicoService {

    public static final String CACHE_PAINEL_PUBLICO = "painel-publico";

    /** Abaixo disto, a contagem e a espera media da fila nao sao divulgadas (risco de identificar alguem). */
    static final long MINIMO_PARA_DIVULGAR = 5;

    /** Limite da lista "onde ha vaga agora", para o painel nao crescer sem controle. */
    static final int LIMITE_DE_CRECHES = 30;

    private static final List<String> ORDEM_DAS_FAIXAS = List.of("BERCARIO", "MATERNAL", "PRE_ESCOLA");

    private final OcupacaoTurmaRepository repository;
    private final FilaEsperaInscricaoRepository filaRepository;
    private final EntityManager entityManager;

    @Cacheable(cacheNames = CACHE_PAINEL_PUBLICO, key = "#municipioId")
    @Transactional(readOnly = true)
    public PainelPublicoDTO obterPainel(Long municipioId) {
        TenantContext.aplicar(entityManager, municipioId);

        List<OcupacaoTurma> turmas = repository.buscarPorFiltro(municipioId, null, null);

        long totalVagasDisponiveis = 0;
        Set<Long> crechesComVaga = new HashSet<>();
        LocalDateTime atualizadoEm = null;
        for (OcupacaoTurma turma : turmas) {
            totalVagasDisponiveis += turma.getVagasDisponiveis();
            if (turma.getVagasDisponiveis() > 0) {
                crechesComVaga.add(turma.getCrecheId());
            }
            if (atualizadoEm == null || turma.getAtualizadoEm().isAfter(atualizadoEm)) {
                atualizadoEm = turma.getAtualizadoEm();
            }
        }

        ResumoFilaProjection fila = filaRepository.resumirFila(municipioId);
        long aguardando = fila == null || fila.getAguardando() == null ? 0 : fila.getAguardando();
        long totalNaFila = fila == null || fila.getTotal() == null ? 0 : fila.getTotal();
        boolean abaixoDoMinimo = aguardando > 0 && aguardando < MINIMO_PARA_DIVULGAR;
        Double esperaMedia = fila != null && fila.getEsperaMediaDias() != null && totalNaFila >= MINIMO_PARA_DIVULGAR
                ? Math.round(fila.getEsperaMediaDias() * 10) / 10.0
                : null;

        return new PainelPublicoDTO(
                municipioId,
                totalVagasDisponiveis,
                (long) crechesComVaga.size(),
                (long) turmas.size(),
                atualizadoEm,
                vagasPorFaixa(turmas),
                crechesComVaga(turmas),
                abaixoDoMinimo ? null : aguardando,
                abaixoDoMinimo,
                esperaMedia);
    }

    /** Soma as vagas livres por faixa etaria, na ordem berçario, maternal, pre-escola; turmas sem faixa ficam de fora. */
    private List<PainelPublicoDTO.VagasPorFaixa> vagasPorFaixa(List<OcupacaoTurma> turmas) {
        Map<String, long[]> porFaixa = new LinkedHashMap<>();
        ORDEM_DAS_FAIXAS.forEach(faixa -> porFaixa.put(faixa, new long[] {0, 0}));
        for (OcupacaoTurma turma : turmas) {
            long[] acumulado = turma.getFaixaEtaria() == null ? null : porFaixa.get(turma.getFaixaEtaria());
            if (acumulado != null) {
                acumulado[0] += turma.getVagasDisponiveis();
                acumulado[1] += 1;
            }
        }
        List<PainelPublicoDTO.VagasPorFaixa> resultado = new ArrayList<>();
        porFaixa.forEach((faixa, valores) -> {
            if (valores[1] > 0) {
                resultado.add(new PainelPublicoDTO.VagasPorFaixa(faixa, valores[0], valores[1]));
            }
        });
        return resultado;
    }

    /** Turmas com vaga livre agora: mais vagas primeiro e, no empate, ordem alfabetica da creche. */
    private List<PainelPublicoDTO.CrecheComVaga> crechesComVaga(List<OcupacaoTurma> turmas) {
        return turmas.stream()
                .filter(turma -> turma.getVagasDisponiveis() > 0)
                .map(turma -> new PainelPublicoDTO.CrecheComVaga(
                        turma.getCrecheId(),
                        turma.getCrecheNome() == null ? "Creche #" + turma.getCrecheId() : turma.getCrecheNome(),
                        turma.getBairro(),
                        turma.getFaixaEtaria(),
                        turma.getVagasDisponiveis()))
                .sorted(Comparator.comparing(PainelPublicoDTO.CrecheComVaga::vagasDisponiveis).reversed()
                        .thenComparing(PainelPublicoDTO.CrecheComVaga::nome))
                .limit(LIMITE_DE_CRECHES)
                .toList();
    }
}
