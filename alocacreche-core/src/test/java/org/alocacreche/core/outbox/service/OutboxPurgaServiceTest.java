package org.alocacreche.core.outbox.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import org.alocacreche.core.outbox.repository.OutboxEventRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Cobre a regra de retencao da purga da Outbox: so eventos PROCESSADO antigos saem da tabela.
 * PENDENTE (ainda nao entregue) e ERRO (guardado para investigacao) nunca sao apagados. O DELETE
 * real contra o Postgres e coberto por OutboxPublicadorConsumerIT.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("OutboxPurgaService")
class OutboxPurgaServiceTest {

    @Mock private OutboxEventRepository repository;

    @Test
    @DisplayName("apaga somente PROCESSADO criado antes da data de corte e devolve a quantidade")
    void apagaSomenteProcessadoAntigoEDevolveQuantidade() {
        when(repository.apagarPorStatusCriadosAntes(eq("PROCESSADO"), any(LocalDateTime.class)))
                .thenReturn(12);
        OutboxPurgaService service = new OutboxPurgaService(repository, 7);

        LocalDateTime antes = LocalDateTime.now();
        int apagados = service.purgarProcessadosAntigos();
        LocalDateTime depois = LocalDateTime.now();

        assertThat(apagados).isEqualTo(12);
        ArgumentCaptor<LocalDateTime> corte = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(repository).apagarPorStatusCriadosAntes(eq("PROCESSADO"), corte.capture());
        assertThat(corte.getValue()).isBetween(antes.minusDays(7), depois.minusDays(7));
        verifyNoMoreInteractions(repository);
    }

    @Test
    @DisplayName("respeita a retencao configurada em dias")
    void respeitaRetencaoConfigurada() {
        OutboxPurgaService service = new OutboxPurgaService(repository, 30);

        LocalDateTime antes = LocalDateTime.now();
        service.purgarProcessadosAntigos();
        LocalDateTime depois = LocalDateTime.now();

        ArgumentCaptor<LocalDateTime> corte = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(repository).apagarPorStatusCriadosAntes(eq("PROCESSADO"), corte.capture());
        assertThat(corte.getValue()).isBetween(antes.minusDays(30), depois.minusDays(30));
    }

    @Test
    @DisplayName("retencao menor que 1 dia e rejeitada na construcao")
    void retencaoInvalidaEhRejeitada() {
        assertThatThrownBy(() -> new OutboxPurgaService(repository, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
