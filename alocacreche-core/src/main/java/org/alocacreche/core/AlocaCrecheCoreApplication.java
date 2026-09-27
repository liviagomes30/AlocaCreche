package org.alocacreche.core;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * {@code @EnableScheduling} habilita o Publicador Outbox ({@code
 * org.alocacreche.core.outbox.OutboxPublicadorScheduler}), primeiro {@code @Scheduled} do modulo.
 */
@SpringBootApplication
@EnableScheduling
public class AlocaCrecheCoreApplication {

    public static void main(String[] args) {
        SpringApplication.run(AlocaCrecheCoreApplication.class, args);
    }
}
