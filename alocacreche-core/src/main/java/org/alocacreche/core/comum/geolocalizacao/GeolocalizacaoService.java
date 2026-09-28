package org.alocacreche.core.comum.geolocalizacao;

import org.alocacreche.core.comum.Endereco;

public interface GeolocalizacaoService {

    Double calcularDistanciaEmQuilometros(Endereco origem, Endereco destino);
}
