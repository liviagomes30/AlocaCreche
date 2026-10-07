package org.alocacreche.core.inscricao;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import java.util.ArrayList;
import java.util.List;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.alocacreche.core.comum.Endereco;
import org.alocacreche.core.comum.EntidadeMultiTenant;
import com.fasterxml.jackson.annotation.JsonIgnore;

/**
 * CPF e renda (via {@link Inscricao}) sao dado sensivel LGPD - nunca logar em claro (ver
 * CLAUDE.md). Sem {@code @ToString}/{@code @Data} de proposito, para nao vazar isso num log
 * acidental de debug.
 */
@Entity
@Table(name = "responsavel")
@Getter
@Setter
@NoArgsConstructor
public class Responsavel extends EntidadeMultiTenant {

    @Column(nullable = false, length = 200)
    private String nome;

    @Column(nullable = false, unique = true, length = 14)
    private String cpf;

    @Column(length = 20)
    private String telefone;

    @Column(length = 200)
    private String email;

    @Embedded
    private Endereco endereco;
    
    @JsonIgnore
    @OneToMany(mappedBy = "responsavel", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    private List<Crianca> criancas = new ArrayList<>();

    /**
     * {@code Crianca.responsavel} e o lado dono da relacao (tem o {@code @JoinColumn}) - so
     * adicionar em {@code getCriancas()} nao persiste o FK. Usar sempre este metodo em vez de
     * mexer direto na lista.
     */
    public void adicionarCrianca(Crianca crianca) {
        criancas.add(crianca);
        crianca.setResponsavel(this);
    }
}
