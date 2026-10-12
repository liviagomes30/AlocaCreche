package org.alocacreche.core.outbox.service;

import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Chave de demonstracao para pausar o envio da Outbox ao RabbitMQ e mostrar, na tela, os eventos
 * acumulando como PENDENTE e depois a recuperacao. So funciona com
 * {@code alocacreche.outbox.controle-demo.habilitado=true} (desligado por padrao): em producao o
 * publicador nunca pode ser pausado por uma chamada HTTP. O estado vive na memoria deste processo.
 */
@Component
public class OutboxPublicadorControle {

    private final boolean controleDemoHabilitado;
    private final AtomicBoolean pausado = new AtomicBoolean(false);

    public OutboxPublicadorControle(
            @Value("${alocacreche.outbox.controle-demo.habilitado:false}") boolean controleDemoHabilitado) {
        this.controleDemoHabilitado = controleDemoHabilitado;
    }

    public boolean controleDemoHabilitado() {
        return controleDemoHabilitado;
    }

    public boolean pausado() {
        return pausado.get();
    }

    /** Pausa ou retoma o envio; devolve false (sem alterar nada) se o controle de demonstracao estiver desligado. */
    public boolean definirPausado(boolean valor) {
        if (!controleDemoHabilitado) {
            return false;
        }
        pausado.set(valor);
        return true;
    }
}
