package org.alocacreche.gateway.limite;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.InetSocketAddress;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;

@DisplayName("LimitadorPainelPublicoFilter")
class LimitadorPainelPublicoFilterTest {

    private static final String PAINEL = "/api/v1/relatorios/painel-publico";

    private final AtomicInteger repassadas = new AtomicInteger();
    private final GatewayFilterChain cadeia = exchange -> {
        repassadas.incrementAndGet();
        return Mono.empty();
    };

    private static class RelogioMutavel extends Clock {
        private long agoraMs = 1_000_000;

        void avancarSegundos(long segundos) {
            agoraMs += segundos * 1000;
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zona) {
            return this;
        }

        @Override
        public Instant instant() {
            return Instant.ofEpochMilli(agoraMs);
        }
    }

    private MockServerWebExchange requisicao(String caminho, String ip) {
        return MockServerWebExchange.from(
                MockServerHttpRequest.get(caminho)
                        .remoteAddress(new InetSocketAddress(ip, 5000))
                        .build());
    }

    @Test
    @DisplayName("deixa passar ate o limite e responde 429 com Retry-After acima dele")
    void respondeTooManyRequestsAcimaDoLimite() {
        LimitadorPainelPublicoFilter filtro = new LimitadorPainelPublicoFilter(3, new RelogioMutavel());

        for (int i = 0; i < 3; i++) {
            filtro.filter(requisicao(PAINEL, "10.0.0.1"), cadeia).block();
        }
        MockServerWebExchange quarta = requisicao(PAINEL, "10.0.0.1");
        filtro.filter(quarta, cadeia).block();

        assertThat(repassadas).hasValue(3);
        assertThat(quarta.getResponse().getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(quarta.getResponse().getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isEqualTo("60");
    }

    @Test
    @DisplayName("o limite e por IP: outro IP continua passando")
    void limiteEPorIp() {
        LimitadorPainelPublicoFilter filtro = new LimitadorPainelPublicoFilter(1, new RelogioMutavel());

        filtro.filter(requisicao(PAINEL, "10.0.0.1"), cadeia).block();
        MockServerWebExchange bloqueada = requisicao(PAINEL, "10.0.0.1");
        filtro.filter(bloqueada, cadeia).block();
        filtro.filter(requisicao(PAINEL, "10.0.0.2"), cadeia).block();

        assertThat(bloqueada.getResponse().getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(repassadas).hasValue(2);
    }

    @Test
    @DisplayName("passado um minuto a janela reinicia e o IP volta a passar")
    void janelaReiniciaDepoisDeUmMinuto() {
        RelogioMutavel relogio = new RelogioMutavel();
        LimitadorPainelPublicoFilter filtro = new LimitadorPainelPublicoFilter(1, relogio);

        filtro.filter(requisicao(PAINEL, "10.0.0.1"), cadeia).block();
        filtro.filter(requisicao(PAINEL, "10.0.0.1"), cadeia).block();
        relogio.avancarSegundos(61);
        filtro.filter(requisicao(PAINEL, "10.0.0.1"), cadeia).block();

        assertThat(repassadas).hasValue(2);
    }

    @Test
    @DisplayName("nao limita as demais rotas (so o painel publico e protegido)")
    void naoLimitaOutrasRotas() {
        LimitadorPainelPublicoFilter filtro = new LimitadorPainelPublicoFilter(1, new RelogioMutavel());

        for (int i = 0; i < 5; i++) {
            filtro.filter(requisicao("/api/v1/relatorios/ocupacao", "10.0.0.1"), cadeia).block();
        }

        assertThat(repassadas).hasValue(5);
    }
}
