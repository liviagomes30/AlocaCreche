package org.alocacreche.core.matricula.service;

import jakarta.persistence.EntityManager;
import java.util.Comparator;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.alocacreche.core.comum.Endereco;
import org.alocacreche.core.comum.geolocalizacao.GeolocalizacaoService;
import org.alocacreche.core.comum.seguranca.SessaoTenantResolver;
import org.alocacreche.core.comum.tenant.TenantContext;
import org.alocacreche.core.matricula.Creche;
import org.alocacreche.core.matricula.Turma;
import org.alocacreche.core.matricula.TurmaRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Lista as turmas da faixa etaria pedida, da creche mais proxima para a mais distante (Haversine
 * sobre coordenadas ja cadastradas - ADR 0006). Turma sem vaga tambem aparece, porque a familia
 * ainda pode entrar na fila dela. Creche sem latitude/longitude fica de fora: nao ha como medir.
 */
@Service
@RequiredArgsConstructor
public class SugestaoCrecheServiceImpl implements SugestaoCrecheService {

    private static final Comparator<CrecheProximaResponse> MAIS_PROXIMA_PRIMEIRO =
            Comparator.comparing(CrecheProximaResponse::distanciaKm)
                    .thenComparing(CrecheProximaResponse::turmaId);

    private final SessaoTenantResolver sessaoTenantResolver;
    private final EntityManager entityManager;
    private final TurmaRepository turmaRepository;
    private final GeolocalizacaoService geolocalizacaoService;

    @Override
    @Transactional(readOnly = true)
    public List<CrecheProximaResponse> sugerirCrechesProximas(SugerirCrechesRequest request) {
        Long municipioId = sessaoTenantResolver.municipioIdAtual();
        TenantContext.aplicar(entityManager, municipioId);

        Endereco origem = new Endereco();
        origem.setLatitude(request.latitude());
        origem.setLongitude(request.longitude());

        return turmaRepository.buscarPorFaixaEtariaComCreche(request.faixaEtaria()).stream()
                .filter(turma -> temCoordenadas(turma.getCreche().getEndereco()))
                .map(turma -> paraResposta(turma, origem))
                .sorted(MAIS_PROXIMA_PRIMEIRO)
                .limit(request.limiteEfetivo())
                .toList();
    }

    private CrecheProximaResponse paraResposta(Turma turma, Endereco origem) {
        Creche creche = turma.getCreche();
        return new CrecheProximaResponse(
                creche.getId(),
                creche.getNome(),
                creche.getEndereco().getBairro(),
                turma.getId(),
                turma.getVagasDisponiveis(),
                geolocalizacaoService.calcularDistanciaEmQuilometros(origem, creche.getEndereco()));
    }

    private boolean temCoordenadas(Endereco endereco) {
        return endereco != null && endereco.getLatitude() != null && endereco.getLongitude() != null;
    }
}
