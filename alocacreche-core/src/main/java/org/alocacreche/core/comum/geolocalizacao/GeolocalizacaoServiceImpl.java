package org.alocacreche.core.comum.geolocalizacao;

import org.alocacreche.core.comum.Endereco;
import org.springframework.stereotype.Service;

@Service
public class GeolocalizacaoServiceImpl implements GeolocalizacaoService {

    private static final double RAIO_TERRA_KM = 6371.0;

    @Override
    public Double calcularDistanciaEmQuilometros(Endereco origem, Endereco destino) {
        validarCoordenadasPreenchidas(origem, destino);

        double latOrigemRad = Math.toRadians(origem.getLatitude());
        double latDestinoRad = Math.toRadians(destino.getLatitude());
        double deltaLatRad = Math.toRadians(destino.getLatitude() - origem.getLatitude());
        double deltaLonRad = Math.toRadians(destino.getLongitude() - origem.getLongitude());

        double a = Math.sin(deltaLatRad / 2) * Math.sin(deltaLatRad / 2)
                + Math.cos(latOrigemRad) * Math.cos(latDestinoRad)
                        * Math.sin(deltaLonRad / 2) * Math.sin(deltaLonRad / 2);
        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));

        return RAIO_TERRA_KM * c;
    }

    private void validarCoordenadasPreenchidas(Endereco origem, Endereco destino) {
        if (origem == null || destino == null
                || origem.getLatitude() == null || origem.getLongitude() == null
                || destino.getLatitude() == null || destino.getLongitude() == null) {
            throw new IllegalArgumentException(
                    "Origem e destino precisam ter latitude/longitude preenchidas para calcular distancia");
        }
    }
}
