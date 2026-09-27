package org.alocacreche.core.notificacao;

import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * E o proprio discriminador de tenant do sistema - por isso, diferente das demais entidades de
 * dominio, nao carrega uma coluna {@code municipio_id} propria.
 */
@Entity
@Table(name = "municipio")
@Getter
@Setter
@NoArgsConstructor
public class Municipio {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 200)
    private String nome;

    @Column(nullable = false, length = 2)
    private String uf;

    @Column(name = "prazo_confirmacao_dias", nullable = false)
    private Integer prazoConfirmacaoDias;

    @Embedded
    private ConfiguracaoPriorizacao configuracaoPriorizacao;

    /**
     * Falha cedo, com mensagem clara, em vez de deixar o NPE (ou um score sem sentido, com pesos
     * negativos ou que nao somam 1.0) estourar bem mais tarde dentro do calculo de score de outra
     * stack (modulo Inscricao), longe da causa real.
     */
    @PrePersist
    @PreUpdate
    private void validarConfiguracaoPriorizacao() {
        if (configuracaoPriorizacao == null) {
            throw new IllegalStateException(
                    "Municipio precisa de ConfiguracaoPriorizacao preenchida antes de persistir");
        }
        configuracaoPriorizacao.validar();
    }
}
