package org.alocacreche.core.inscricao;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import java.time.LocalDate;
import java.util.Optional;
import org.alocacreche.core.comum.excecao.RecursoNaoEncontradoException;
import org.alocacreche.core.comum.seguranca.SessaoTenantResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** Regras do cadastro de Crianca: tenant do token, responsavel inexistente (404) e vinculo. */
@ExtendWith(MockitoExtension.class)
@DisplayName("CriancaService")
class CriancaServiceTest {

    private static final Long MUNICIPIO_ID = 7L;
    private static final Long RESPONSAVEL_ID = 5L;

    @Mock private CriancaRepository criancaRepository;
    @Mock private ResponsavelRepository responsavelRepository;
    @Mock private SessaoTenantResolver sessaoTenantResolver;
    @Mock private EntityManager entityManager;
    @Mock private Query query;

    private CriancaService service;
    private final CriancaDTO dados = new CriancaDTO("Joao Crianca", LocalDate.of(2024, 3, 10));

    @BeforeEach
    void setUp() {
        service = new CriancaService(criancaRepository, responsavelRepository, sessaoTenantResolver, entityManager);
        lenient().when(sessaoTenantResolver.municipioIdAtual()).thenReturn(MUNICIPIO_ID);
        lenient().when(entityManager.createNativeQuery(anyString())).thenReturn(query);
        lenient().when(query.setParameter(eq(1), any())).thenReturn(query);
        lenient()
                .when(criancaRepository.saveAndFlush(any(Crianca.class)))
                .thenAnswer(invocacao -> invocacao.getArgument(0));
    }

    @Test
    @DisplayName("cadastrar aplica o tenant antes de ler o responsavel e vincula a crianca ao municipio do token")
    void cadastraComTenantEVinculo() {
        Responsavel responsavel = new Responsavel();
        when(responsavelRepository.findById(RESPONSAVEL_ID)).thenReturn(Optional.of(responsavel));

        Crianca salva = service.cadastrar(RESPONSAVEL_ID, dados);

        InOrder ordem = inOrder(query, responsavelRepository, criancaRepository);
        ordem.verify(query).getSingleResult();
        ordem.verify(responsavelRepository).findById(RESPONSAVEL_ID);
        ordem.verify(criancaRepository).saveAndFlush(salva);
        assertThat(salva.getMunicipioId()).isEqualTo(MUNICIPIO_ID);
        assertThat(salva.getNome()).isEqualTo("Joao Crianca");
        assertThat(salva.getDataNascimento()).isEqualTo(LocalDate.of(2024, 3, 10));
        assertThat(salva.getResponsavel()).isSameAs(responsavel);
        assertThat(responsavel.getCriancas()).containsExactly(salva);
    }

    @Test
    @DisplayName("responsavel inexistente ou de outro municipio (RLS) lanca RecursoNaoEncontradoException")
    void responsavelInexistenteLanca404() {
        when(responsavelRepository.findById(RESPONSAVEL_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.cadastrar(RESPONSAVEL_ID, dados))
                .isInstanceOf(RecursoNaoEncontradoException.class);

        verify(criancaRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("cadastrarNoMunicipio usa o municipio informado e o responsavel recebido")
    void cadastrarNoMunicipioUsaOMunicipioInformado() {
        Responsavel responsavel = new Responsavel();

        Crianca salva = service.cadastrarNoMunicipio(99L, responsavel, dados);

        assertThat(salva.getMunicipioId()).isEqualTo(99L);
        assertThat(salva.getResponsavel()).isSameAs(responsavel);
        verify(sessaoTenantResolver, never()).municipioIdAtual();
    }
}
