package org.alocacreche.core.matricula;

import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.alocacreche.core.comum.Endereco;
import org.alocacreche.core.comum.EntidadeMultiTenant;

@Entity
@Table(name = "creche")
@Getter
@Setter
@NoArgsConstructor
public class Creche extends EntidadeMultiTenant {

    @Column(nullable = false, length = 200)
    private String nome;

    @Column(name = "capacidade_total", nullable = false)
    private Integer capacidadeTotal;

    @Embedded
    private Endereco endereco;
}
