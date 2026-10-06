package org.alocacreche.core.inscricao;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import cn.dev33.satoken.exception.NotPermissionException;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import org.alocacreche.core.comum.excecao.RecursoNaoEncontradoException;
import org.alocacreche.core.comum.seguranca.SessaoTenantResolver;
import org.alocacreche.core.notificacao.ConfiguracaoPriorizacao;
import org.alocacreche.core.notificacao.Municipio;
import org.alocacreche.core.notificacao.MunicipioRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Cobre as regras do InscricaoService da Stack A: municipio vindo do token, tenant aplicado antes de
 * qualquer leitura, 404 em vez de excecao generica e o fator de irmao ja matriculado no score. O
 * comportamento com Postgres real e RLS esta em InscricaoPorCriancaControllerIT.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("InscricaoService (Stack A)")
class InscricaoServiceTest {

    private static final Long MUNICIPIO_ID = 7L;
    private static final Long CRIANCA_ID = 30L;
    private static final double TOLERANCIA = 1e-9;

    @Mock private SessaoTenantResolver sessaoTenantResolver;
    @Mock private EntityManager entityManager;
    @Mock private Query query;
    @Mock private InscricaoRepository inscricaoRepository;
    @Mock private CriancaRepository criancaRepository;
    @Mock private MunicipioRepository municipioRepository;
    @Mock private MatriculaConsulta matriculaConsulta;

    private InscricaoService service;
    private Municipio municipio;
    private Crianca crianca;
    private Responsavel responsavel;

    @BeforeEach
    void setUp() {
        service =
                new InscricaoService(
                        sessaoTenantResolver,
                        entityManager,
                        inscricaoRepository,
                        criancaRepository,
                        municipioRepository,
                        matriculaConsulta,
                        new CalculadoraScorePrioridade());
        municipio = new Municipio();
        municipio.setConfiguracaoPriorizacao(
                ConfiguracaoPriorizacao.builder()
                        .pesoRenda(0.4)
                        .pesoVulnerabilidade(0.3)
                        .pesoDistancia(0.2)
                        .pesoIrmaoMatriculado(0.1)
                        .build());
        responsavel = new Responsavel();
        ReflectionTestUtils.setField(responsavel, "id", 5L);
        crianca = novaCrianca(CRIANCA_ID);
        crianca.setResponsavel(responsavel);
    }

    @Test
    @DisplayName("aplica o tenant do token antes de ler municipio e crianca")
    void aplicaOTenantAntesDeLer() {
        prepararTenantEMunicipio();
        when(criancaRepository.findById(CRIANCA_ID)).thenReturn(Optional.of(crianca));
        when(criancaRepository.findByResponsavelId(5L)).thenReturn(List.of(crianca));
        when(inscricaoRepository.save(org.mockito.ArgumentMatchers.any(Inscricao.class)))
                .thenAnswer(invocacao -> invocacao.getArgument(0));

        service.registrarInscricao(new InscricaoDTO(MUNICIPIO_ID, CRIANCA_ID, new BigDecimal("400.00"), true));

        InOrder ordem = inOrder(entityManager, query, municipioRepository, criancaRepository);
        ordem.verify(entityManager).createNativeQuery("SELECT set_config('app.current_tenant', ?1, true)");
        ordem.verify(query).setParameter(1, MUNICIPIO_ID.toString());
        ordem.verify(query).getSingleResult();
        ordem.verify(municipioRepository).findById(MUNICIPIO_ID);
        ordem.verify(criancaRepository).findById(CRIANCA_ID);
    }

    @Test
    @DisplayName("municipioId do corpo diferente do token lanca NotPermissionException sem ler nada")
    void municipioDiferenteDoTokenNaoLeNada() {
        when(sessaoTenantResolver.municipioIdAtual()).thenReturn(MUNICIPIO_ID);

        assertThatThrownBy(
                        () ->
                                service.registrarInscricao(
                                        new InscricaoDTO(99L, CRIANCA_ID, new BigDecimal("400.00"), true)))
                .isInstanceOf(NotPermissionException.class);

        verifyNoInteractions(entityManager, municipioRepository, criancaRepository, inscricaoRepository);
    }

    @Test
    @DisplayName("crianca inexistente lanca RecursoNaoEncontradoException e nao salva")
    void criancaInexistenteLancaNaoEncontrado() {
        prepararTenantEMunicipio();
        when(criancaRepository.findById(CRIANCA_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(
                        () ->
                                service.registrarInscricao(
                                        new InscricaoDTO(MUNICIPIO_ID, CRIANCA_ID, new BigDecimal("400.00"), true)))
                .isInstanceOf(RecursoNaoEncontradoException.class);

        verify(inscricaoRepository, never()).save(org.mockito.ArgumentMatchers.any());
    }

    @Test
    @DisplayName("municipio inexistente lanca RecursoNaoEncontradoException")
    void municipioInexistenteLancaNaoEncontrado() {
        when(sessaoTenantResolver.municipioIdAtual()).thenReturn(MUNICIPIO_ID);
        when(entityManager.createNativeQuery(anyString())).thenReturn(query);
        when(query.setParameter(1, MUNICIPIO_ID.toString())).thenReturn(query);
        when(municipioRepository.findById(MUNICIPIO_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(
                        () ->
                                service.registrarInscricao(
                                        new InscricaoDTO(MUNICIPIO_ID, CRIANCA_ID, new BigDecimal("400.00"), true)))
                .isInstanceOf(RecursoNaoEncontradoException.class);

        verifyNoInteractions(criancaRepository, inscricaoRepository);
    }

    @Test
    @DisplayName("sem irmao matriculado o score soma renda e vulnerabilidade")
    void semIrmaoMatriculadoNaoSomaOFatorDeIrmao() {
        prepararTenantEMunicipio();
        when(criancaRepository.findById(CRIANCA_ID)).thenReturn(Optional.of(crianca));
        when(criancaRepository.findByResponsavelId(5L)).thenReturn(List.of(crianca));
        when(inscricaoRepository.save(org.mockito.ArgumentMatchers.any(Inscricao.class)))
                .thenAnswer(invocacao -> invocacao.getArgument(0));

        InscricaoRegistradaResponse resposta =
                service.registrarInscricao(new InscricaoDTO(MUNICIPIO_ID, CRIANCA_ID, new BigDecimal("400.00"), true));

        assertThat(resposta.scorePrioridade()).isCloseTo(0.7, org.assertj.core.data.Offset.offset(TOLERANCIA));
        assertThat(resposta.status()).isEqualTo(StatusInscricao.AGUARDANDO);
    }

    @Test
    @DisplayName("irmao com matricula soma o peso do irmao matriculado (0,1) ao score")
    void irmaoMatriculadoSomaOPeso() {
        prepararTenantEMunicipio();
        Crianca irma = novaCrianca(31L);
        irma.setResponsavel(responsavel);
        Inscricao inscricaoDaIrma = new Inscricao();
        ReflectionTestUtils.setField(inscricaoDaIrma, "id", 77L);
        when(criancaRepository.findById(CRIANCA_ID)).thenReturn(Optional.of(crianca));
        when(criancaRepository.findByResponsavelId(5L)).thenReturn(List.of(crianca, irma));
        when(inscricaoRepository.findByCriancaId(31L)).thenReturn(List.of(inscricaoDaIrma));
        when(matriculaConsulta.existeMatriculaParaInscricao(77L)).thenReturn(true);
        when(inscricaoRepository.save(org.mockito.ArgumentMatchers.any(Inscricao.class)))
                .thenAnswer(invocacao -> invocacao.getArgument(0));

        InscricaoRegistradaResponse resposta =
                service.registrarInscricao(new InscricaoDTO(MUNICIPIO_ID, CRIANCA_ID, new BigDecimal("400.00"), true));

        assertThat(resposta.scorePrioridade()).isCloseTo(0.8, org.assertj.core.data.Offset.offset(TOLERANCIA));
    }

    @Test
    @DisplayName("irmao sem matricula nao soma o peso do irmao")
    void irmaoSemMatriculaNaoSoma() {
        prepararTenantEMunicipio();
        Crianca irma = novaCrianca(31L);
        irma.setResponsavel(responsavel);
        Inscricao inscricaoDaIrma = new Inscricao();
        ReflectionTestUtils.setField(inscricaoDaIrma, "id", 77L);
        when(criancaRepository.findById(CRIANCA_ID)).thenReturn(Optional.of(crianca));
        when(criancaRepository.findByResponsavelId(5L)).thenReturn(List.of(crianca, irma));
        when(inscricaoRepository.findByCriancaId(31L)).thenReturn(List.of(inscricaoDaIrma));
        when(matriculaConsulta.existeMatriculaParaInscricao(77L)).thenReturn(false);
        when(inscricaoRepository.save(org.mockito.ArgumentMatchers.any(Inscricao.class)))
                .thenAnswer(invocacao -> invocacao.getArgument(0));

        InscricaoRegistradaResponse resposta =
                service.registrarInscricao(new InscricaoDTO(MUNICIPIO_ID, CRIANCA_ID, new BigDecimal("400.00"), true));

        assertThat(resposta.scorePrioridade()).isCloseTo(0.7, org.assertj.core.data.Offset.offset(TOLERANCIA));
    }

    @Test
    @DisplayName("vulnerabilidade nula e tratada como falsa e renda nula soma zero")
    void camposNulosViramZero() {
        prepararTenantEMunicipio();
        when(criancaRepository.findById(CRIANCA_ID)).thenReturn(Optional.of(crianca));
        when(criancaRepository.findByResponsavelId(5L)).thenReturn(List.of(crianca));
        when(inscricaoRepository.save(org.mockito.ArgumentMatchers.any(Inscricao.class)))
                .thenAnswer(invocacao -> invocacao.getArgument(0));

        InscricaoRegistradaResponse resposta =
                service.registrarInscricao(new InscricaoDTO(MUNICIPIO_ID, CRIANCA_ID, null, null));

        assertThat(resposta.scorePrioridade()).isCloseTo(0.0, org.assertj.core.data.Offset.offset(TOLERANCIA));
    }

    private void prepararTenantEMunicipio() {
        when(sessaoTenantResolver.municipioIdAtual()).thenReturn(MUNICIPIO_ID);
        when(entityManager.createNativeQuery(anyString())).thenReturn(query);
        when(query.setParameter(1, MUNICIPIO_ID.toString())).thenReturn(query);
        when(municipioRepository.findById(MUNICIPIO_ID)).thenReturn(Optional.of(municipio));
    }

    private Crianca novaCrianca(Long id) {
        Crianca nova = new Crianca();
        ReflectionTestUtils.setField(nova, "id", id);
        return nova;
    }
}
