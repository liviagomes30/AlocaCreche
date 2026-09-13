package org.alocacreche.relatorios.config;

import java.util.HashMap;
import java.util.Map;
import org.alocacreche.relatorios.ocupacao.EventoInvalidoException;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.config.RetryInterceptorBuilder;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.retry.RejectAndDontRequeueRecoverer;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConversionException;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.retry.interceptor.RetryOperationsInterceptor;
import org.springframework.retry.policy.SimpleRetryPolicy;
import org.springframework.retry.support.RetryTemplate;

/**
 * Topologia RabbitMQ do consumidor VagaLiberada (ver design.md e ADR 0001, decisao 10). Retry +
 * dead-lettering seguem o padrao AD-001 (.specs/STATE.md): 3 tentativas via Spring Retry,
 * EventoInvalidoException excluida da politica (rejeicao imediata), DLX nativo do RabbitMQ na
 * fila principal.
 */
@Configuration
public class RabbitMqConfig {

    public static final String DOMINIO_EXCHANGE = "alocacreche.dominio";
    public static final String DOMINIO_DLX = "alocacreche.dominio.dlx";
    public static final String OCUPACAO_QUEUE = "relatorios.ocupacao";
    public static final String OCUPACAO_DLQ = "relatorios.ocupacao.dlq";
    public static final String VAGA_LIBERADA_ROUTING_KEY = "vaga.liberada";
    public static final String OCUPACAO_DLQ_ROUTING_KEY = "vaga.liberada.dlq";

    @Bean
    public TopicExchange dominioExchange() {
        return new TopicExchange(DOMINIO_EXCHANGE);
    }

    @Bean
    public DirectExchange dominioDlx() {
        return new DirectExchange(DOMINIO_DLX);
    }

    @Bean
    public Queue ocupacaoQueue() {
        return QueueBuilder.durable(OCUPACAO_QUEUE)
                .withArgument("x-dead-letter-exchange", DOMINIO_DLX)
                .withArgument("x-dead-letter-routing-key", OCUPACAO_DLQ_ROUTING_KEY)
                .build();
    }

    @Bean
    public Queue ocupacaoDlq() {
        return QueueBuilder.durable(OCUPACAO_DLQ).build();
    }

    @Bean
    public Binding ocupacaoBinding() {
        return BindingBuilder.bind(ocupacaoQueue())
                .to(dominioExchange())
                .with(VAGA_LIBERADA_ROUTING_KEY);
    }

    @Bean
    public Binding ocupacaoDlqBinding() {
        return BindingBuilder.bind(ocupacaoDlq())
                .to(dominioDlx())
                .with(OCUPACAO_DLQ_ROUTING_KEY);
    }

    @Bean
    public MessageConverter messageConverter() {
        return new Jackson2JsonMessageConverter();
    }

    @Bean
    public SimpleRabbitListenerContainerFactory rabbitListenerContainerFactory(
            ConnectionFactory connectionFactory, MessageConverter messageConverter) {
        SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
        factory.setConnectionFactory(connectionFactory);
        factory.setMessageConverter(messageConverter);
        factory.setAdviceChain(retryInterceptor());
        return factory;
    }

    private RetryOperationsInterceptor retryInterceptor() {
        Map<Class<? extends Throwable>, Boolean> retryableExceptions = new HashMap<>();
        // EventoInvalidoException nunca tenta de novo - rejeicao imediata para a DLQ (OCUP-06/07).
        retryableExceptions.put(EventoInvalidoException.class, false);
        // JSON malformado falha na conversao, antes do listener/validar() rodar - tambem precisa
        // ser rejeicao imediata (OCUP-06 cobre "nao puder ser desserializado", nao so campo
        // faltando), senao cairia na politica de retry generica abaixo.
        retryableExceptions.put(MessageConversionException.class, false);

        // traverseCauses=true: o Spring AMQP envolve a excecao original numa
        // ListenerExecutionFailedException antes de chegar aqui. defaultValue=true: qualquer
        // excecao NAO listada acima (ex.: falha transitoria de banco) continua retryable por
        // padrao. Ver .specs/STATE.md AD-001 - NAO adicionar uma entrada catch-all
        // "Exception.class -> true" aqui: o classifier casa o wrapper externo
        // (ListenerExecutionFailedException, que É uma Exception) com essa entrada antes de
        // sequer olhar a causa, fazendo EventoInvalidoException/MessageConversionException
        // serem retentadas mesmo estando mapeadas como false - descoberto por um teste fim a
        // fim que contava invocacoes do listener, nao so o resultado final (DLQ + repositorio
        // nunca chamado e verdade tanto com 1 tentativa quanto com 3).
        SimpleRetryPolicy retryPolicy = new SimpleRetryPolicy(3, retryableExceptions, true, true);
        RetryTemplate retryTemplate = new RetryTemplate();
        retryTemplate.setRetryPolicy(retryPolicy);

        return RetryInterceptorBuilder.stateless()
                .retryOperations(retryTemplate)
                .recoverer(new RejectAndDontRequeueRecoverer())
                .build();
    }
}
