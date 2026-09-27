package org.alocacreche.core.notificacao;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.alocacreche.core.comum.EntidadeMultiTenant;

@Entity
@Table(name = "notificacao")
@Getter
@Setter
@NoArgsConstructor
public class Notificacao extends EntidadeMultiTenant {

    /**
     * ID cru, nao @ManyToOne - Inscricao vive no pacote inscricao, dono de outro integrante.
     * Referencia por objeto criaria dependencia de pacote em ambas as direcoes (notificacao ->
     * inscricao e inscricao -> notificacao via Inscricao.municipio), o que junta os dois pacotes
     * num ciclo. Ver docs/adr/0004-divisao-equipe-tres-integrantes.md (revisao de arquitetura).
     */
    @Column(name = "inscricao_id", nullable = false)
    private Long inscricaoId;

    @Column(nullable = false, length = 500)
    private String mensagem;

    @Column(name = "data_envio")
    private LocalDateTime dataEnvio;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private TipoNotificacao tipo;
}
