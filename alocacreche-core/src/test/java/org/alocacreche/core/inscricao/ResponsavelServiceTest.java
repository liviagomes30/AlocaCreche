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
import java.lang.reflect.Method;
import java.util.Optional;
import org.alocacreche.core.comum.Endereco;
import org.alocacreche.core.comum.excecao.ConflitoException;
import org.alocacreche.core.comum.seguranca.SessaoTenantResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Regras do cadastro de Responsavel: tenant do token, CPF unico (409) e persistencia dos campos. */
@ExtendWith(MockitoExtension.class)
@DisplayName("ResponsavelService")
class ResponsavelServiceTest {

    private static final Long MUNICIPIO_ID = 7L;
    private static final String CPF = "123.456.789-09";

    @Mock private ResponsavelRepository responsavelRepository;
    @Mock private SessaoTenantResolver sessaoTenantResolver;
    @Mock private EntityManager entityManager;
    @Mock private Query query;

    private ResponsavelService service;

    @BeforeEach
    void setUp() {
        service = new ResponsavelService(responsavelRepository, sessaoTenantResolver, entityManager);
        lenient().when(sessaoTenantResolver.municipioIdAtual()).thenReturn(MUNICIPIO_ID);
        lenient().when(entityManager.createNativeQuery(anyString())).thenReturn(query);
        lenient().when(query.setParameter(eq(1), any())).thenReturn(query);
        lenient().when(responsavelRepository.findByCpf(CPF)).thenReturn(Optional.empty());
        lenient()
                .when(responsavelRepository.saveAndFlush(any(Responsavel.class)))
                .thenAnswer(invocacao -> invocacao.getArgument(0));
    }

    @Test
    @DisplayName("cadastrar aplica o tenant do token antes de ler o CPF e grava todos os campos")
    void cadastraComTenantDoToken() {
        Endereco endereco = new Endereco("Rua A", 10, "Centro", "01000-000", "Sao Paulo", "SP", -23.55, -46.63);

        Responsavel salvo = service.cadastrar(dto(endereco));

        InOrder ordem = inOrder(query, responsavelRepository);
        ordem.verify(query).setParameter(1, MUNICIPIO_ID.toString());
        ordem.verify(query).getSingleResult();
        ordem.verify(responsavelRepository).findByCpf(CPF);
        ordem.verify(responsavelRepository).saveAndFlush(any(Responsavel.class));
        assertThat(salvo.getMunicipioId()).isEqualTo(MUNICIPIO_ID);
        assertThat(salvo.getCpf()).isEqualTo(CPF);
        assertThat(salvo.getNome()).isEqualTo("Maria Responsavel");
        assertThat(salvo.getTelefone()).isEqualTo("11999990000");
        assertThat(salvo.getEmail()).isEqualTo("maria@example.com");
        assertThat(salvo.getEndereco()).isSameAs(endereco);
    }

    @Test
    @DisplayName("CPF ja cadastrado no municipio lanca ConflitoException sem salvar")
    void cpfRepetidoLancaConflito() {
        when(responsavelRepository.findByCpf(CPF)).thenReturn(Optional.of(new Responsavel()));

        assertThatThrownBy(() -> service.cadastrar(dto(null))).isInstanceOf(ConflitoException.class);

        verify(responsavelRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("CPF de outro municipio (violacao de unicidade no flush) vira ConflitoException")
    void cpfDeOutroMunicipioLancaConflito() {
        when(responsavelRepository.saveAndFlush(any(Responsavel.class)))
                .thenThrow(new DataIntegrityViolationException("responsavel_cpf_key"));

        assertThatThrownBy(() -> service.cadastrar(dto(null))).isInstanceOf(ConflitoException.class);
    }

    @Test
    @DisplayName("cadastrarNoMunicipio usa o municipio informado, sem ler o token nem aplicar o tenant")
    void cadastrarNoMunicipioUsaOMunicipioInformado() {
        ArgumentCaptor<Responsavel> capturado = ArgumentCaptor.forClass(Responsavel.class);

        service.cadastrarNoMunicipio(99L, dto(null));

        verify(responsavelRepository).saveAndFlush(capturado.capture());
        assertThat(capturado.getValue().getMunicipioId()).isEqualTo(99L);
        verify(sessaoTenantResolver, never()).municipioIdAtual();
        verify(entityManager, never()).createNativeQuery(anyString());
    }

    @Test
    @DisplayName("cadastrar e transacional e cadastrarNoMunicipio exige transacao aberta")
    void propagacaoTransacional() throws NoSuchMethodException {
        Method cadastrar = ResponsavelService.class.getMethod("cadastrar", ResponsavelDTO.class);
        Method noMunicipio =
                ResponsavelService.class.getMethod("cadastrarNoMunicipio", Long.class, ResponsavelDTO.class);

        assertThat(cadastrar.getAnnotation(Transactional.class)).isNotNull();
        assertThat(noMunicipio.getAnnotation(Transactional.class).propagation()).isEqualTo(Propagation.MANDATORY);
    }

    private ResponsavelDTO dto(Endereco endereco) {
        return new ResponsavelDTO("Maria Responsavel", CPF, "11999990000", "maria@example.com", endereco);
    }
}
