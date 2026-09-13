package org.alocacreche.core.outbox;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Schema generico e estavel (ver ADR 0001, decisao 10) — nao adicione colunas
 * especificas de um tipo de evento aqui; o payload especifico vai dentro de {@code
 * payload} (JSON serializado). O {@code id} auto-incremento tambem serve como sequencia
 * global de entrega para consumidores que precisam detectar entregas fora de ordem
 * (ver {@code sb-outbox-pattern.md}).
 */
@Entity
@Table(name = "outbox")
@Getter
@Setter
@NoArgsConstructor
public class OutboxEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tipo_evento", nullable = false, length = 100)
    private String tipoEvento;

    @Column(name = "payload", nullable = false, columnDefinition = "jsonb")
    private String payload;

    @Column(name = "status", nullable = false, length = 20)
    private String status = "PENDENTE";

    @Column(name = "criado_em", nullable = false)
    private LocalDateTime criadoEm = LocalDateTime.now();

    public OutboxEvent(String tipoEvento, String payload) {
        this.tipoEvento = tipoEvento;
        this.payload = payload;
    }

    public void marcarProcessado() {
        this.status = "PROCESSADO";
    }
}
