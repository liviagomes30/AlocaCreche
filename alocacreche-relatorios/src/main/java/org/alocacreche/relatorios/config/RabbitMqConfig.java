package org.alocacreche.relatorios.config;

import java.util.Map;
import org.alocacreche.relatorios.evento.exception.EventoDominioInvalidoException;
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
import org.springframework.retry.backoff.ExponentialBackOffPolicy;
import org.springframework.retry.policy.SimpleRetryPolicy;
import org.springframework.retry.support.RetryTemplate;

@Configuration
public class RabbitMqConfig {

    public static final String DOMINIO_EXCHANGE = "alocacreche.dominio";
    public static final String DOMINIO_DLX = "alocacreche.dominio.dlx";
    public static final String OCUPACAO_QUEUE = "relatorios.ocupacao";
    public static final String OCUPACAO_DLQ = "relatorios.ocupacao.dlq";
    public static final String VAGA_LIBERADA_ROUTING_KEY = "vaga.liberada";
    public static final String OCUPACAO_DLQ_ROUTING_KEY = "vaga.liberada.dlq";
    public static final String FILA_ESPERA_QUEUE = "relatorios.filaespera";
    public static final String FILA_ESPERA_DLQ = "relatorios.filaespera.dlq";
    public static final String FILA_ATUALIZADA_ROUTING_KEY = "fila.atualizada";
    public static final String FILA_ESPERA_DLQ_ROUTING_KEY = "fila.atualizada.dlq";
    public static final String MATRICULA_QUEUE = "relatorios.matricula";
    public static final String MATRICULA_DLQ = "relatorios.matricula.dlq";
    public static final String MATRICULA_ATUALIZADA_ROUTING_KEY = "matricula.atualizada";
    public static final String MATRICULA_DLQ_ROUTING_KEY = "matricula.atualizada.dlq";

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
    public Queue filaEsperaQueue() {
        return QueueBuilder.durable(FILA_ESPERA_QUEUE)
                .withArgument("x-dead-letter-exchange", DOMINIO_DLX)
                .withArgument("x-dead-letter-routing-key", FILA_ESPERA_DLQ_ROUTING_KEY)
                .build();
    }

    @Bean
    public Queue filaEsperaDlq() {
        return QueueBuilder.durable(FILA_ESPERA_DLQ).build();
    }

    @Bean
    public Binding filaEsperaBinding() {
        return BindingBuilder.bind(filaEsperaQueue())
                .to(dominioExchange())
                .with(FILA_ATUALIZADA_ROUTING_KEY);
    }

    @Bean
    public Binding filaEsperaDlqBinding() {
        return BindingBuilder.bind(filaEsperaDlq())
                .to(dominioDlx())
                .with(FILA_ESPERA_DLQ_ROUTING_KEY);
    }

    @Bean
    public Queue matriculaQueue() {
        return QueueBuilder.durable(MATRICULA_QUEUE)
                .withArgument("x-dead-letter-exchange", DOMINIO_DLX)
                .withArgument("x-dead-letter-routing-key", MATRICULA_DLQ_ROUTING_KEY)
                .build();
    }

    @Bean
    public Queue matriculaDlq() {
        return QueueBuilder.durable(MATRICULA_DLQ).build();
    }

    @Bean
    public Binding matriculaBinding() {
        return BindingBuilder.bind(matriculaQueue())
                .to(dominioExchange())
                .with(MATRICULA_ATUALIZADA_ROUTING_KEY);
    }

    @Bean
    public Binding matriculaDlqBinding() {
        return BindingBuilder.bind(matriculaDlq())
                .to(dominioDlx())
                .with(MATRICULA_DLQ_ROUTING_KEY);
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

    static Map<Class<? extends Throwable>, Boolean> excecoesSemRetryRejeitarParaDlq() {
        return Map.of(
                EventoDominioInvalidoException.class, false,
                MessageConversionException.class, false);
    }

    /**
     * Espera entre as tentativas (0,5 s, depois 1 s): sem ela as 3 tentativas gastam-se em
     * milissegundos e uma queda curta do banco manda mensagens boas direto para a DLQ.
     */
    static ExponentialBackOffPolicy politicaDeEspera() {
        ExponentialBackOffPolicy espera = new ExponentialBackOffPolicy();
        espera.setInitialInterval(500);
        espera.setMultiplier(2.0);
        espera.setMaxInterval(4_000);
        return espera;
    }

    private RetryOperationsInterceptor retryInterceptor() {
        boolean classificarPelaCausaRealDaExcecao = true;
        boolean excecoesNaoMapeadasSaoRetryablesPorPadrao = true;
        SimpleRetryPolicy retryPolicy =
                new SimpleRetryPolicy(
                        3,
                        excecoesSemRetryRejeitarParaDlq(),
                        classificarPelaCausaRealDaExcecao,
                        excecoesNaoMapeadasSaoRetryablesPorPadrao);
        RetryTemplate retryTemplate = new RetryTemplate();
        retryTemplate.setRetryPolicy(retryPolicy);
        retryTemplate.setBackOffPolicy(politicaDeEspera());

        return RetryInterceptorBuilder.stateless()
                .retryOperations(retryTemplate)
                .recoverer(new RejectAndDontRequeueRecoverer())
                .build();
    }
}
