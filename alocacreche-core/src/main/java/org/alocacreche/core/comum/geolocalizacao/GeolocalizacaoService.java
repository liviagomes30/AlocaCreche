package org.alocacreche.core.comum.geolocalizacao;

import org.alocacreche.core.comum.Endereco;

/**
 * Contrato apenas — a implementacao (formula de Haversine sobre as coordenadas ja
 * geocodificadas de {@link Endereco}) fica a cargo do time, seguindo TDD (escreva o
 * teste da formula antes da implementacao). A chamada a API externa de geocodificacao
 * (endereco em texto -&gt; coordenadas) NAO pertence a este contrato: ela ocorre uma
 * unica vez, no cadastro da entidade dona (Creche ou Responsavel), dentro do modulo que
 * possui aquela entidade.
 */
public interface GeolocalizacaoService {

    /**
     * Distancia, em quilometros, entre duas coordenadas ja geocodificadas.
     *
     * @throws IllegalArgumentException se origem ou destino nao tiverem
     *     latitude/longitude preenchidas
     */
    Double calcularDistancia(Endereco origem, Endereco destino);
}
