package org.alocacreche.core.matricula.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import java.lang.reflect.Method;
import java.util.List;
import org.alocacreche.core.comum.Endereco;
import org.alocacreche.core.comum.geolocalizacao.GeolocalizacaoServiceImpl;
import org.alocacreche.core.comum.seguranca.SessaoTenantResolver;
import org.alocacreche.core.matricula.Creche;
import org.alocacreche.core.matricula.FaixaEtaria;
import org.alocacreche.core.matricula.Turma;
import org.alocacreche.core.matricula.TurmaRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Transactional;

/**
 * Ordena as turmas da faixa pela distancia real (Haversine de verdade, nao mock), respeita o limite,
 * mantem turma sem vaga e descarta creche sem coordenadas.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("SugestaoCrecheServiceImpl")
class SugestaoCrecheServiceImplTest {

    private static final Long MUNICIPIO_ID = 7L;
    // Praca da Se, Sao Paulo.
    private static final double LATITUDE_FAMILIA = -23.5505;
    private static final double LONGITUDE_FAMILIA = -46.6333;

    @Mock private SessaoTenantResolver sessaoTenantResolver;
    @Mock private EntityManager entityManager;
    @Mock private Query consultaTenant;
    @Mock private TurmaRepository turmaRepository;

    private SugestaoCrecheServiceImpl service;

    @BeforeEach
    void montarCenario() {
        service =
                new SugestaoCrecheServiceImpl(
                        sessaoTenantResolver, entityManager, turmaRepository, new GeolocalizacaoServiceImpl());
        lenient().when(sessaoTenantResolver.municipioIdAtual()).thenReturn(MUNICIPIO_ID);
        lenient().when(entityManager.createNativeQuery(anyString())).thenReturn(consultaTenant);
        lenient().when(consultaTenant.setParameter(eq(1), any())).thenReturn(consultaTenant);
    }

    @Test
    @DisplayName("devolve da mais proxima para a mais distante, com distancia, vagas e turma")
    void ordenaPelaDistancia() {
        Turma longe = turma(1L, 10L, "Creche Longe", -23.65, -46.70, 2);
        Turma perto = turma(2L, 11L, "Creche Perto", -23.551, -46.634, 0);
        Turma meio = turma(3L, 12L, "Creche Meio", -23.58, -46.65, 4);
        when(turmaRepository.buscarPorFaixaEtariaComCreche(FaixaEtaria.MATERNAL)).thenReturn(List.of(longe, perto, meio));

        List<CrecheProximaResponse> sugestoes = service.sugerirCrechesProximas(pedido(null));

        assertThat(sugestoes).extracting(CrecheProximaResponse::nome)
                .containsExactly("Creche Perto", "Creche Meio", "Creche Longe");
        CrecheProximaResponse primeira = sugestoes.get(0);
        assertThat(primeira.turmaId()).isEqualTo(2L);
        assertThat(primeira.crecheId()).isEqualTo(11L);
        assertThat(primeira.vagasDisponiveis()).isZero();
        assertThat(primeira.bairro()).isEqualTo("Centro");
        assertThat(primeira.distanciaKm()).isBetween(0.0, 0.5);
        assertThat(sugestoes.get(2).distanciaKm()).isGreaterThan(10.0);
    }

    @Test
    @DisplayName("respeita o limite pedido e usa 5 quando ele nao vem")
    void respeitaOLimite() {
        List<Turma> turmas =
                List.of(
                        turma(1L, 10L, "A", -23.551, -46.634, 1),
                        turma(2L, 11L, "B", -23.552, -46.634, 1),
                        turma(3L, 12L, "C", -23.553, -46.634, 1),
                        turma(4L, 13L, "D", -23.554, -46.634, 1),
                        turma(5L, 14L, "E", -23.555, -46.634, 1),
                        turma(6L, 15L, "F", -23.556, -46.634, 1));
        when(turmaRepository.buscarPorFaixaEtariaComCreche(FaixaEtaria.MATERNAL)).thenReturn(turmas);

        assertThat(service.sugerirCrechesProximas(pedido(2))).extracting(CrecheProximaResponse::nome)
                .containsExactly("A", "B");
        assertThat(service.sugerirCrechesProximas(pedido(null))).hasSize(SugerirCrechesRequest.LIMITE_PADRAO);
    }

    @Test
    @DisplayName("creche sem latitude ou longitude fica de fora")
    void descartaCrecheSemCoordenadas() {
        Turma semCoordenadas = turma(1L, 10L, "Sem Mapa", null, -46.63, 3);
        Turma comCoordenadas = turma(2L, 11L, "Com Mapa", -23.56, -46.64, 3);
        when(turmaRepository.buscarPorFaixaEtariaComCreche(FaixaEtaria.MATERNAL))
                .thenReturn(List.of(semCoordenadas, comCoordenadas));

        assertThat(service.sugerirCrechesProximas(pedido(null))).extracting(CrecheProximaResponse::nome)
                .containsExactly("Com Mapa");
    }

    @Test
    @DisplayName("aplica o tenant do token antes de consultar as turmas")
    void aplicaOTenantAntes() {
        when(turmaRepository.buscarPorFaixaEtariaComCreche(FaixaEtaria.MATERNAL)).thenReturn(List.of());

        service.sugerirCrechesProximas(pedido(null));

        InOrder ordem = inOrder(entityManager, consultaTenant, turmaRepository);
        ordem.verify(entityManager).createNativeQuery(contains("set_config"));
        ordem.verify(consultaTenant).setParameter(1, MUNICIPIO_ID.toString());
        ordem.verify(turmaRepository).buscarPorFaixaEtariaComCreche(FaixaEtaria.MATERNAL);
    }

    @Test
    @DisplayName("a consulta roda numa transacao so de leitura, onde o SET LOCAL do tenant vale")
    void transacaoSoDeLeitura() throws NoSuchMethodException {
        Method sugerir = SugestaoCrecheServiceImpl.class.getMethod("sugerirCrechesProximas", SugerirCrechesRequest.class);

        assertThat(sugerir.getAnnotation(Transactional.class).readOnly()).isTrue();
    }

    private SugerirCrechesRequest pedido(Integer limite) {
        return new SugerirCrechesRequest(LATITUDE_FAMILIA, LONGITUDE_FAMILIA, FaixaEtaria.MATERNAL, limite);
    }

    private Turma turma(Long turmaId, Long crecheId, String nome, Double latitude, Double longitude, int vagas) {
        Creche creche = new Creche();
        ReflectionTestUtils.setField(creche, "id", crecheId);
        creche.setNome(nome);
        creche.setEndereco(new Endereco("Rua", 1, "Centro", "01000-000", "Sao Paulo", "SP", latitude, longitude));
        Turma turma = new Turma();
        ReflectionTestUtils.setField(turma, "id", turmaId);
        turma.setCreche(creche);
        turma.setFaixaEtaria(FaixaEtaria.MATERNAL);
        turma.setVagasDisponiveis(vagas);
        return turma;
    }
}
