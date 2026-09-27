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

/**
 * Schema generico e estavel (ver ADR 0001, decisao 10) — nao adicione colunas
 * especificas de um tipo de evento aqui; o payload especifico vai dentro de {@code
 * payload} (JSON serializado). O {@code id} auto-incremento tambem serve como sequencia
 * global de entrega para consumidores que precisam detectar entregas fora de ordem
 * (ver {@code sb-outbox-pattern.md}).
 *
 * <p>{@code status} tem 3 valores possiveis: {@code PENDENTE} (aguardando publicacao),
 * {@code PROCESSADO} (publicado com sucesso) e {@code ERRO} (tipoEvento sem routing key
 * reconhecida pelo Publicador — erro permanente, nao tentado de novo, ver
 * OutboxPublicadorService).
 */
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

    /**
     * {@code @JdbcTypeCode(SqlTypes.JSON)} - sem isso, Hibernate faz bind como varchar e o insert
     * falha ("column payload is of type jsonb but expression is of type character varying"), ja
     * que {@code columnDefinition} sozinho so afeta a DDL gerada, nao o tipo usado no bind em
     * tempo de execucao.
     */
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
