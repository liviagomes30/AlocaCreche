package org.alocacreche.core.matricula.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import org.alocacreche.core.notificacao.Municipio;
import org.alocacreche.core.notificacao.MunicipioRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

@ExtendWith(MockitoExtension.class)
@DisplayName("ManutencaoMatriculaScheduler")
class ManutencaoMatriculaSchedulerTest {

    @Mock private MunicipioRepository municipioRepository;
    @Mock private ManutencaoMatriculaPorMunicipio manutencaoPorMunicipio;
    @InjectMocks private ManutencaoMatriculaScheduler scheduler;

    @Test
    @DisplayName("roda a manutencao uma vez por municipio, por bean injetado")
    void rodaPorMunicipio() {
        when(municipioRepository.findAll()).thenReturn(List.of(municipio(1L), municipio(2L)));

        scheduler.executar();

        verify(manutencaoPorMunicipio).executar(1L);
        verify(manutencaoPorMunicipio).executar(2L);
    }

    @Test
    @DisplayName("falha num municipio nao impede os demais")
    void falhaNumMunicipioNaoImpedeOsDemais() {
        when(municipioRepository.findAll()).thenReturn(List.of(municipio(1L), municipio(2L)));
        doThrow(new IllegalStateException("banco")).when(manutencaoPorMunicipio).executar(1L);

        assertThatCode(() -> scheduler.executar()).doesNotThrowAnyException();

        verify(manutencaoPorMunicipio).executar(2L);
    }

    @Test
    @DisplayName("alocacreche.matricula.manutencao.habilitado=false nao cria o agendador; ausente cria")
    void propriedadeLigaEDesliga() {
        ApplicationContextRunner contexto =
                new ApplicationContextRunner()
                        .withBean(MunicipioRepository.class, () -> mock(MunicipioRepository.class))
                        .withBean(ManutencaoMatriculaPorMunicipio.class, () -> mock(ManutencaoMatriculaPorMunicipio.class))
                        .withUserConfiguration(ManutencaoMatriculaScheduler.class);

        contexto.withPropertyValues("alocacreche.matricula.manutencao.habilitado=false")
                .run(ctx -> assertThat(ctx).doesNotHaveBean(ManutencaoMatriculaScheduler.class));
        contexto.run(ctx -> assertThat(ctx).hasSingleBean(ManutencaoMatriculaScheduler.class));
    }

    private Municipio municipio(Long id) {
        Municipio municipio = new Municipio();
        municipio.setId(id);
        return municipio;
    }
}
