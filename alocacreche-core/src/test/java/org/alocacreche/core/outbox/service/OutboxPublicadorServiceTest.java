package org.alocacreche.core.outbox.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.alocacreche.core.outbox.config.OutboxRabbitConfig;
import org.alocacreche.core.outbox.domain.OutboxEvent;
import org.alocacreche.core.outbox.repository.OutboxEventRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.core.ReturnedMessage;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.data.domain.PageRequest;

/**
 * Cobre a logica de decisao de OutboxPublicadorService (sucesso, injecao do eventId, payload
 * invalido, tipoEvento desconhecido, falha de broker, confirmacao, retorno de mensagem nao roteada, nack e timeout) - o fluxo fim a fim real (RabbitMQ/Postgres reais, @Scheduled disparando sozinho) e
 * coberto por OutboxPublicadorConsumerIT.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("OutboxPublicadorService")
class OutboxPublicadorServiceTest {

    @Mock private OutboxEventRepository repository;
    @Mock private RabbitTemplate rabbitTemplate;

    private static final long TIMEOUT_CONFIRMACAO_MS = 5_000;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private OutboxPublicadorService service;

    @BeforeEach
    void setUp() {
        service =
                new OutboxPublicadorService(
                        repository, rabbitTemplate, objectMapper, TIMEOUT_CONFIRMACAO_MS);
        lenient()
                .doAnswer(this::confirmarComAck)
                .when(rabbitTemplate)
                .send(anyString(), anyString(), any(Message.class), any(CorrelationData.class));
    }

    private Object confirmarComAck(InvocationOnMock invocacao) {
        CorrelationData correlacao = invocacao.getArgument(3);
        correlacao.getFuture().complete(new CorrelationData.Confirm(true, null));
        return null;
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

        verify(rabbitTemplate, never())
                .send(anyString(), anyString(), any(Message.class), any(CorrelationData.class));
        assertThat(evento.getStatus()).isEqualTo("ERRO");
        verify(repository).save(evento);
    }

    @Test
    @DisplayName("payload JSON que nao e um objeto marca ERRO sem tentar publicar")
    void payloadJsonQueNaoEObjetoMarcaErroSemPublicar() {
        OutboxEvent evento = new OutboxEvent("vaga.liberada", "[1,2,3]");
        evento.setId(9L);

        service.publicarEvento(evento);

        verify(rabbitTemplate, never())
                .send(anyString(), anyString(), any(Message.class), any(CorrelationData.class));
        assertThat(evento.getStatus()).isEqualTo("ERRO");
        verify(repository).save(evento);
    }

    @Test
    @DisplayName("tipoEvento desconhecido marca ERRO sem tentar publicar")
    void tipoEventoDesconhecidoMarcaErroSemPublicar() {
        OutboxEvent evento = new OutboxEvent("evento.inexistente", "{}");

        service.publicarEvento(evento);

        verify(rabbitTemplate, never())
                .send(anyString(), anyString(), any(Message.class), any(CorrelationData.class));
        assertThat(evento.getStatus()).isEqualTo("ERRO");
        verify(repository).save(evento);
    }

    @Test
    @DisplayName("tipoEvento nulo marca ERRO sem lancar NullPointerException")
    void tipoEventoNuloMarcaErroSemLancarNullPointerException() {
        OutboxEvent evento = new OutboxEvent();
        evento.setPayload("{}");

        service.publicarEvento(evento);

        verify(rabbitTemplate, never())
                .send(anyString(), anyString(), any(Message.class), any(CorrelationData.class));
        assertThat(evento.getStatus()).isEqualTo("ERRO");
        verify(repository).save(evento);
    }

    @Test
    @DisplayName("falha de publish mantem PENDENTE e nao propaga a excecao")
    void falhaDePublishMantemPendenteSemPropagarExcecao() {
        OutboxEvent evento = new OutboxEvent("vaga.liberada", "{}");
        doThrow(new AmqpException("broker indisponivel"))
                .when(rabbitTemplate)
                .send(anyString(), anyString(), any(Message.class), any(CorrelationData.class));

        service.publicarEvento(evento);

        assertThat(evento.getStatus()).isEqualTo("PENDENTE");
        verify(repository, never()).save(evento);
    }

    @Test
    @DisplayName("envia com CorrelationData cujo id e o id da Outbox")
    void enviaComCorrelationDataComIdDaOutbox() {
        OutboxEvent evento = new OutboxEvent("vaga.liberada", "{}");
        evento.setId(77L);

        service.publicarEvento(evento);

        ArgumentCaptor<CorrelationData> correlacao = ArgumentCaptor.forClass(CorrelationData.class);
        verify(rabbitTemplate)
                .send(anyString(), anyString(), any(Message.class), correlacao.capture());
        assertThat(correlacao.getValue().getId()).isEqualTo("77");
    }

    @Test
    @DisplayName("mensagem devolvida pelo broker mantem PENDENTE e nao salva")
    void mensagemDevolvidaMantemPendenteSemSalvar() {
        OutboxEvent evento = new OutboxEvent("vaga.liberada", "{}");
        evento.setId(10L);
        brokerResponde(true, true);

        service.publicarEvento(evento);

        assertThat(evento.getStatus()).isEqualTo("PENDENTE");
        verify(repository, never()).save(evento);
    }

    @Test
    @DisplayName("mensagem devolvida emite aviso WARN com o id do evento e sem o payload")
    void mensagemDevolvidaEmiteAvisoSemPayload() {
        OutboxEvent evento = new OutboxEvent("vaga.liberada", "{\"segredo\":\"valor-sensivel-123\"}");
        evento.setId(10L);
        brokerResponde(true, true);
        Logger logger = (Logger) LoggerFactory.getLogger(OutboxPublicadorService.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            service.publicarEvento(evento);
        } finally {
            logger.detachAppender(appender);
        }

        List<ILoggingEvent> avisos =
                appender.list.stream().filter(e -> e.getLevel() == Level.WARN).toList();
        assertThat(avisos).hasSize(1);
        String mensagem = avisos.get(0).getFormattedMessage();
        assertThat(mensagem).contains("id=10").contains("devolvido");
        assertThat(mensagem).doesNotContain("valor-sensivel-123").doesNotContain("segredo");
    }

    @Test
    @DisplayName("nack do broker mantem PENDENTE e nao salva")
    void nackMantemPendenteSemSalvar() {
        OutboxEvent evento = new OutboxEvent("vaga.liberada", "{}");
        evento.setId(11L);
        brokerResponde(false, false);

        service.publicarEvento(evento);

        assertThat(evento.getStatus()).isEqualTo("PENDENTE");
        verify(repository, never()).save(evento);
    }

    @Test
    @DisplayName("sem confirmacao dentro do timeout mantem PENDENTE e nao salva")
    void timeoutDaConfirmacaoMantemPendenteSemSalvar() {
        OutboxEvent evento = new OutboxEvent("vaga.liberada", "{}");
        evento.setId(12L);
        doAnswer(invocacao -> null)
                .when(rabbitTemplate)
                .send(anyString(), anyString(), any(Message.class), any(CorrelationData.class));
        OutboxPublicadorService comTimeoutCurto =
                new OutboxPublicadorService(repository, rabbitTemplate, objectMapper, 50);

        comTimeoutCurto.publicarEvento(evento);

        assertThat(evento.getStatus()).isEqualTo("PENDENTE");
        verify(repository, never()).save(evento);
    }

    @Test
    @DisplayName("confirmacao que falha com excecao mantem PENDENTE e nao salva")
    void confirmacaoComExcecaoMantemPendenteSemSalvar() {
        OutboxEvent evento = new OutboxEvent("vaga.liberada", "{}");
        evento.setId(13L);
        doAnswer(
                        invocacao -> {
                            CorrelationData correlacao = invocacao.getArgument(3);
                            correlacao
                                    .getFuture()
                                    .completeExceptionally(new IllegalStateException("falha"));
                            return null;
                        })
                .when(rabbitTemplate)
                .send(anyString(), anyString(), any(Message.class), any(CorrelationData.class));

        service.publicarEvento(evento);

        assertThat(evento.getStatus()).isEqualTo("PENDENTE");
        verify(repository, never()).save(evento);
    }

    @Test
    @DisplayName("thread interrompida durante a espera mantem PENDENTE e restaura a flag")
    void interrupcaoMantemPendenteERestauraFlag() {
        OutboxEvent evento = new OutboxEvent("vaga.liberada", "{}");
        evento.setId(14L);
        doAnswer(invocacao -> null)
                .when(rabbitTemplate)
                .send(anyString(), anyString(), any(Message.class), any(CorrelationData.class));
        Thread.currentThread().interrupt();

        boolean flagRestaurada;
        try {
            service.publicarEvento(evento);
        } finally {
            flagRestaurada = Thread.interrupted();
        }

        assertThat(flagRestaurada).isTrue();
        assertThat(evento.getStatus()).isEqualTo("PENDENTE");
        verify(repository, never()).save(evento);
    }

    @Test
    @DisplayName("lote inteiro devolvido pelo broker permanece PENDENTE sem lancar excecao")
    void loteInteiroDevolvidoPermanecePendente() {
        brokerResponde(true, true);
        List<OutboxEvent> eventos = new ArrayList<>();
        for (long id = 1; id <= OutboxPublicadorService.TAMANHO_LOTE; id++) {
            OutboxEvent evento = new OutboxEvent("fila.atualizada", "{}");
            evento.setId(id);
            eventos.add(evento);
        }

        eventos.forEach(service::publicarEvento);

        assertThat(eventos).allSatisfy(e -> assertThat(e.getStatus()).isEqualTo("PENDENTE"));
        verify(repository, never()).save(any(OutboxEvent.class));
    }

    private JsonNode payloadPublicado(String routingKey) throws Exception {
        Message mensagem = mensagemPublicada(routingKey);
        return objectMapper.readTree(new String(mensagem.getBody(), StandardCharsets.UTF_8));
    }

    private Message mensagemPublicada(String routingKey) {
        ArgumentCaptor<Message> mensagem = ArgumentCaptor.forClass(Message.class);
        verify(rabbitTemplate)
                .send(
                        eq(OutboxRabbitConfig.DOMINIO_EXCHANGE),
                        eq(routingKey),
                        mensagem.capture(),
                        any(CorrelationData.class));
        return mensagem.getValue();
    }

    private void brokerResponde(boolean ack, boolean devolvida) {
        doAnswer(
                        invocacao -> {
                            Message mensagem = invocacao.getArgument(2);
                            CorrelationData correlacao = invocacao.getArgument(3);
                            if (devolvida) {
                                correlacao.setReturned(
                                        new ReturnedMessage(
                                                mensagem,
                                                312,
                                                "NO_ROUTE",
                                                OutboxRabbitConfig.DOMINIO_EXCHANGE,
                                                invocacao.getArgument(1)));
                            }
                            correlacao.getFuture().complete(new CorrelationData.Confirm(ack, null));
                            return null;
                        })
                .when(rabbitTemplate)
                .send(anyString(), anyString(), any(Message.class), any(CorrelationData.class));
    }
}
