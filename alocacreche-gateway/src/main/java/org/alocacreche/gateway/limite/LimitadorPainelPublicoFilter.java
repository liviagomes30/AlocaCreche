package org.alocacreche.gateway.limite;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * Limita as requisicoes por IP ao painel publico, a unica rota sem autenticacao: janela fixa de um
 * minuto, contada na memoria deste gateway. O limite vale por instancia do gateway; para limite
 * compartilhado entre instancias seria preciso um contador externo (ex.: Redis).
 *
 * <p>O IP vem da conexao, nao de {@code X-Forwarded-For}: esse cabecalho e escolhido pelo cliente e
 * permitiria burlar o limite. Atras de um proxy confiavel todos os clientes parecem o mesmo IP.
 */
@Component
public class LimitadorPainelPublicoFilter implements GlobalFilter, Ordered {

    static final String CAMINHO_PAINEL_PUBLICO = "/api/v1/relatorios/painel-publico";

    private static final long JANELA_MS = 60_000;
    private static final int LIMITE_DE_IPS_RASTREADOS = 10_000;

    /** Contagem de uma janela: inicio dela e quantas requisicoes ja passaram. */
    private record Janela(long inicioMs, AtomicInteger requisicoes) {}

    private final int limitePorMinuto;
    private final Clock relogio;
    private final Map<String, Janela> janelasPorIp = new ConcurrentHashMap<>();

    @Autowired
    public LimitadorPainelPublicoFilter(
            @Value("${alocacreche.gateway.painel-publico.limite-por-minuto:120}") int limitePorMinuto) {
        this(limitePorMinuto, Clock.systemUTC());
    }

    LimitadorPainelPublicoFilter(int limitePorMinuto, Clock relogio) {
        this.limitePorMinuto = limitePorMinuto;
        this.relogio = relogio;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        if (!CAMINHO_PAINEL_PUBLICO.equals(exchange.getRequest().getPath().value())) {
            return chain.filter(exchange);
        }
        long segundosAteLiberar = segundosAteLiberarSeExcedido(ipDe(exchange));
        if (segundosAteLiberar == 0) {
            return chain.filter(exchange);
        }
        return responder429(exchange.getResponse(), segundosAteLiberar);
    }

    /** Conta a requisicao; devolve 0 se pode passar, ou quantos segundos faltam para a janela virar. */
    private long segundosAteLiberarSeExcedido(String ip) {
        long agora = relogio.millis();
        if (janelasPorIp.size() > LIMITE_DE_IPS_RASTREADOS) {
            janelasPorIp.values().removeIf(j -> agora - j.inicioMs() >= JANELA_MS);
        }
        Janela janela = janelasPorIp.compute(ip, (chave, atual) ->
                atual == null || agora - atual.inicioMs() >= JANELA_MS
                        ? new Janela(agora, new AtomicInteger(0))
                        : atual);
        if (janela.requisicoes().incrementAndGet() <= limitePorMinuto) {
            return 0;
        }
        long restanteMs = JANELA_MS - (agora - janela.inicioMs());
        return Math.max(1, (restanteMs + 999) / 1000);
    }

    private static String ipDe(ServerWebExchange exchange) {
        InetSocketAddress endereco = exchange.getRequest().getRemoteAddress();
        return endereco == null || endereco.getAddress() == null
                ? "desconhecido"
                : endereco.getAddress().getHostAddress();
    }

    private Mono<Void> responder429(ServerHttpResponse resposta, long segundos) {
        resposta.setStatusCode(HttpStatus.TOO_MANY_REQUESTS);
        resposta.getHeaders().set(HttpHeaders.RETRY_AFTER, String.valueOf(segundos));
        resposta.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        byte[] corpo = "{\"erro\":\"Muitas requisicoes ao painel publico; tente novamente em instantes.\"}"
                .getBytes(StandardCharsets.UTF_8);
        DataBuffer buffer = resposta.bufferFactory().wrap(corpo);
        return resposta.writeWith(Mono.just(buffer));
    }

    /** Antes do roteamento, para recusar sem chegar ao servico de relatorios. */
    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }
}
