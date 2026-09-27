package org.alocacreche.core.comum.geolocalizacao;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import java.util.stream.Stream;
import org.alocacreche.core.comum.Endereco;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

@DisplayName("GeolocalizacaoServiceImpl")
class GeolocalizacaoServiceImplTest {

    private final GeolocalizacaoServiceImpl service = new GeolocalizacaoServiceImpl();

    @Nested
    @DisplayName("quando as duas coordenadas estao completas")
    class QuandoCoordenadasCompletas {

        @Test
        @DisplayName("distancia entre a mesma coordenada e zero")
        void distanciaEntreMesmaCoordenadaEZero() {
            Endereco endereco = enderecoCom(-23.5505, -46.6333);

            assertThat(service.calcularDistancia(endereco, endereco)).isCloseTo(0.0, within(0.01));
        }

        @Test
        @DisplayName("distancia entre Sao Paulo e Rio de Janeiro fica proxima do valor conhecido (~357km)")
        void distanciaEntreSaoPauloERioDeJaneiroFicaProximaDoValorConhecido() {
            Endereco saoPaulo = enderecoCom(-23.5505, -46.6333);
            Endereco rioDeJaneiro = enderecoCom(-22.9068, -43.1729);

            Double distancia = service.calcularDistancia(saoPaulo, rioDeJaneiro);

            assertThat(distancia).isCloseTo(357.0, within(10.0));
        }
    }

    @Nested
    @DisplayName("quando falta coordenada em origem ou destino")
    class QuandoFaltaCoordenada {

        @ParameterizedTest(name = "{0}")
        @MethodSource(
                "org.alocacreche.core.comum.geolocalizacao.GeolocalizacaoServiceImplTest#coordenadasIncompletas")
        @DisplayName("lanca IllegalArgumentException em vez de calcular com dado faltando")
        void lancaExcecaoQuandoFaltaAlgumaCoordenada(String descricao, Endereco origem, Endereco destino) {
            assertThatThrownBy(() -> service.calcularDistancia(origem, destino))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    static Stream<Arguments> coordenadasIncompletas() {
        Endereco completo = enderecoCom(-23.5505, -46.6333);
        return Stream.of(
                Arguments.of("origem sem nenhuma coordenada", new Endereco(), completo),
                Arguments.of("destino sem nenhuma coordenada", completo, new Endereco()),
                Arguments.of(
                        "origem so com latitude preenchida (longitude nula)",
                        enderecoComApenas(-23.5505, null),
                        completo),
                Arguments.of(
                        "origem so com longitude preenchida (latitude nula)",
                        enderecoComApenas(null, -46.6333),
                        completo));
    }

    private static Endereco enderecoCom(double latitude, double longitude) {
        return enderecoComApenas(latitude, longitude);
    }

    private static Endereco enderecoComApenas(Double latitude, Double longitude) {
        Endereco endereco = new Endereco();
        endereco.setLatitude(latitude);
        endereco.setLongitude(longitude);
        return endereco;
    }
}
