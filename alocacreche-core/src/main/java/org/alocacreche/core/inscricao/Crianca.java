package org.alocacreche.core.inscricao;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.LocalDate;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.alocacreche.core.comum.EntidadeMultiTenant;

@Entity
@Table(name = "crianca")
@Getter
@Setter
@NoArgsConstructor
public class Crianca extends EntidadeMultiTenant {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "responsavel_id", nullable = false)
    private Responsavel responsavel;

    @Column(nullable = false, length = 200)
    private String nome;

    @Column(name = "data_nascimento", nullable = false)
    private LocalDate dataNascimento;
}
