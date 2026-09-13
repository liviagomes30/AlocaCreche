package org.alocacreche.core.comum;

import jakarta.persistence.Embeddable;
import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Objeto de Valor imutavel na intencao de uso: sem identidade propria nem ciclo de vida
 * independente (nao existe "editar o Endereco X"; existe "trocar o endereco do
 * Responsavel"). Embutido ({@code @Embeddable}) no dono — Responsavel ou Creche — nunca
 * uma entidade com tabela propria. Ver ADR 0001, decisao 9.
 *
 * <p>Usado tanto pelo modulo Inscricao &amp; Fila (Responsavel) quanto pelo modulo
 * Matricula &amp; Vagas/Creches (Creche) — por isso vive no pacote comum, nao no pacote
 * de nenhum dos dois donos.
 *
 * <p>latitude/longitude sao preenchidas pela geocodificacao (feita uma unica vez, no
 * cadastro da entidade dona) e usadas por {@link
 * org.alocacreche.core.comum.geolocalizacao.GeolocalizacaoService#calcularDistancia}.
 */
@Embeddable
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode
public class Endereco {

    private String rua;
    private Integer numero;
    private String bairro;
    private String cep;
    private String cidade;
    private String estado;
    private Double latitude;
    private Double longitude;
}
