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
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "outbox")
@Getter
@Setter
@NoArgsConstructor
public class OutboxEvent {

    public static final String STATUS_PENDENTE = "PENDENTE";
    public static final String STATUS_PROCESSADO = "PROCESSADO";
    public static final String STATUS_ERRO = "ERRO";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tipo_evento", nullable = false, length = 100)
    private String tipoEvento;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "payload", nullable = false, columnDefinition = "jsonb")
    private String payload;

    @Column(name = "status", nullable = false, length = 20)
    private String status = STATUS_PENDENTE;

    @Column(name = "criado_em", nullable = false)
    private LocalDateTime criadoEm = LocalDateTime.now();

    public OutboxEvent(String tipoEvento, String payload) {
        this.tipoEvento = tipoEvento;
        this.payload = payload;
    }

    public void marcarProcessado() {
        this.status = STATUS_PROCESSADO;
    }

    public void marcarErro() {
        this.status = STATUS_ERRO;
    }
}
