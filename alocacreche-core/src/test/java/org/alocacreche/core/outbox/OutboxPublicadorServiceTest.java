package org.alocacreche.core.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.data.domain.PageRequest;

/**
 * Cobre a logica de decisao de OutboxPublicadorService (sucesso, injecao do eventId, payload
 * invalido, tipoEvento desconhecido, falha de broker) - o fluxo fim a fim real (RabbitMQ/Postgres reais, @Scheduled disparando sozinho) e
 * coberto por OutboxPublicadorConsumerIT.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("OutboxPublicadorService")
class OutboxPublicadorServiceTest {

    @Mock private OutboxEventRepository repository;
    @Mock private RabbitTemplate rabbitTemplate;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private OutboxPublicadorService service;

    @BeforeEach
    void setUp() {
        service = new OutboxPublicadorService(repository, rabbitTemplate, objectMapper);
    }

    @Test
    @DisplayName("buscarPendentes delega ao repositorio, ordenado por id e limitado ao lote")
    void buscarPendentesDelegaAoRepositorio() {
        OutboxEvent evento = new OutboxEvent("vaga.liberada", "{}");
        when(repository.findByStatusOrderByIdAsc(
                        "PENDENTE", PageRequest.of(0, OutboxPublicadorService.TAMANHO_LOTE)))
                .thenReturn(List.of(evento));

        List<OutboxEvent> pendentes = service.buscarPendentes();

        assertThat(pendentes).containsExactly(evento);
    }

    @Test
    @DisplayName("publica com sucesso e marca PROCESSADO")
    void publicaComSucessoEMarcaProcessado() throws Exception {
        OutboxEvent evento = new OutboxEvent("vaga.liberada", "{\"turmaId\":1}");
        evento.setId(7L);

        service.publicarEvento(evento);

        JsonNode publicado = payloadPublicado("vaga.liberada");
        assertThat(publicado.get("turmaId").asLong()).isEqualTo(1L);
        assertThat(evento.getStatus()).isEqualTo("PROCESSADO");
        verify(repository).save(evento);
    }

    @Test
    @DisplayName("injeta o id da Outbox como eventId no payload publicado")
    void injetaIdDaOutboxComoEventId() throws Exception {
        OutboxEvent evento = new OutboxEvent("vaga.liberada", "{\"turmaId\":1}");
        evento.setId(42L);

        service.publicarEvento(evento);

        assertThat(payloadPublicado("vaga.liberada").get("eventId").asLong()).isEqualTo(42L);
    }

    @Test
    @DisplayName("o id da Outbox prevalece sobre um eventId que ja venha no payload")
    void idDaOutboxPrevaleceSobreEventIdDoPayload() throws Exception {
        OutboxEvent evento = new OutboxEvent("vaga.liberada", "{\"eventId\":999,\"turmaId\":1}");
        evento.setId(5L);

        service.publicarEvento(evento);

        assertThat(payloadPublicado("vaga.liberada").get("eventId").asLong()).isEqualTo(5L);
    }

    @Test
    @DisplayName("publica com content-type application/json, que o consumidor exige")
    void publicaComContentTypeJson() {
        OutboxEvent evento = new OutboxEvent("vaga.liberada", "{\"turmaId\":1}");
        evento.setId(3L);

        service.publicarEvento(evento);

        assertThat(mensagemPublicada("vaga.liberada").getMessageProperties().getContentType())
                .isEqualTo(MessageProperties.CONTENT_TYPE_JSON);
    }

    @Test
    @DisplayName("payload que nao e JSON marca ERRO sem tentar publicar")
    void payloadNaoJsonMarcaErroSemPublicar() {
        OutboxEvent evento = new OutboxEvent("vaga.liberada", "isto nao e json");
        evento.setId(8L);

        service.publicarEvento(evento);

        verify(rabbitTemplate, never()).send(anyString(), anyString(), any(Message.class));
        assertThat(evento.getStatus()).isEqualTo("ERRO");
        verify(repository).save(evento);
    }

    @Test
    @DisplayName("payload JSON que nao e um objeto marca ERRO sem tentar publicar")
    void payloadJsonQueNaoEObjetoMarcaErroSemPublicar() {
        OutboxEvent evento = new OutboxEvent("vaga.liberada", "[1,2,3]");
        evento.setId(9L);

        service.publicarEvento(evento);

        verify(rabbitTemplate, never()).send(anyString(), anyString(), any(Message.class));
        assertThat(evento.getStatus()).isEqualTo("ERRO");
        verify(repository).save(evento);
    }

    @Test
    @DisplayName("tipoEvento desconhecido marca ERRO sem tentar publicar")
    void tipoEventoDesconhecidoMarcaErroSemPublicar() {
        OutboxEvent evento = new OutboxEvent("evento.inexistente", "{}");

        service.publicarEvento(evento);

        verify(rabbitTemplate, never()).send(anyString(), anyString(), any(Message.class));
        assertThat(evento.getStatus()).isEqualTo("ERRO");
        verify(repository).save(evento);
    }

    @Test
    @DisplayName("tipoEvento nulo marca ERRO sem lancar NullPointerException")
    void tipoEventoNuloMarcaErroSemLancarNullPointerException() {
        OutboxEvent evento = new OutboxEvent();
        evento.setPayload("{}");

        service.publicarEvento(evento);

        verify(rabbitTemplate, never()).send(anyString(), anyString(), any(Message.class));
        assertThat(evento.getStatus()).isEqualTo("ERRO");
        verify(repository).save(evento);
    }

    @Test
    @DisplayName("falha de publish mantem PENDENTE e nao propaga a excecao")
    void falhaDePublishMantemPendenteSemPropagarExcecao() {
        OutboxEvent evento = new OutboxEvent("vaga.liberada", "{}");
        doThrow(new AmqpException("broker indisponivel"))
                .when(rabbitTemplate)
                .send(anyString(), anyString(), any(Message.class));

        service.publicarEvento(evento);

        assertThat(evento.getStatus()).isEqualTo("PENDENTE");
        verify(repository, never()).save(evento);
    }

    private JsonNode payloadPublicado(String routingKey) throws Exception {
        Message mensagem = mensagemPublicada(routingKey);
        return objectMapper.readTree(new String(mensagem.getBody(), StandardCharsets.UTF_8));
    }

    private Message mensagemPublicada(String routingKey) {
        ArgumentCaptor<Message> mensagem = ArgumentCaptor.forClass(Message.class);
        verify(rabbitTemplate)
                .send(eq(OutboxRabbitConfig.DOMINIO_EXCHANGE), eq(routingKey), mensagem.capture());
        return mensagem.getValue();
    }
}
