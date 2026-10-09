package org.alocacreche.relatorios.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.alocacreche.relatorios.evento.exception.EventoDominioInvalidoException;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.support.ListenerExecutionFailedException;
import org.springframework.retry.RetryContext;
import org.springframework.retry.policy.SimpleRetryPolicy;

/**
 * Testa a classificacao de retry isoladamente (sem broker/Testcontainers - ver AD-001). O
 * mecanismo de classificacao ja causou dois bugs reais nesta feature (rodadas 1 e 2 do
 * Verificador), por isso esta prova e feita com a policy de producao de verdade
 * (RabbitMqConfig.excecoesSemRetryRejeitarParaDlq()), nao com uma reconstrucao manual do mapa.
 *
 * <p>Prova especificamente o ganho da refatoracao que trocou EventoInvalidoException.class por
 * EventoDominioInvalidoException.class (classe-base) no mapa: uma subclasse que RabbitMqConfig
 * nunca viu (nao e EventoInvalidoException) tambem e classificada como nao-retryable, so por
 * heranca - confirmado via bytecode que SubclassClassifier resolve por caminhada na hierarquia de
 * superclasses, nao por igualdade exata de classe.
 */
class RabbitMqConfigRetryClassificationTest {

    private static class ExcecaoDominioDeOutroConsumidor extends EventoDominioInvalidoException {
        ExcecaoDominioDeOutroConsumidor(String message) {
            super(message);
        }
    }

    @Test
    void subclasseNaoRegistradaExplicitamenteEClassificadaNaoRetryablePelaHierarquia() {
        SimpleRetryPolicy retryPolicy =
                new SimpleRetryPolicy(3, RabbitMqConfig.excecoesSemRetryRejeitarParaDlq(), true, true);

        RetryContext context =
                registrarFalha(
                        retryPolicy,
                        new ListenerExecutionFailedException(
                                "wrapper", new ExcecaoDominioDeOutroConsumidor("invalido")));

        assertThat(retryPolicy.canRetry(context)).isFalse();
    }

    @Test
    void excecaoNaoMapeadaContinuaRetryablePorPadrao() {
        SimpleRetryPolicy retryPolicy =
                new SimpleRetryPolicy(3, RabbitMqConfig.excecoesSemRetryRejeitarParaDlq(), true, true);

        RetryContext context =
                registrarFalha(
                        retryPolicy,
                        new ListenerExecutionFailedException(
                                "wrapper", new RuntimeException("falha transitoria simulada")));

        assertThat(retryPolicy.canRetry(context)).isTrue();
    }

    private RetryContext registrarFalha(SimpleRetryPolicy retryPolicy, Throwable falha) {
        RetryContext context = retryPolicy.open(null);
        retryPolicy.registerThrowable(context, falha);
        return context;
    }
}
