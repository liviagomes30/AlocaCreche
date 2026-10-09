package org.alocacreche.core.comum.seguranca.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.alocacreche.core.comum.seguranca.exception.TentativasExcedidasException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Limita falhas de login por municipio e email numa janela deslizante, contra forca bruta.
 * O contador vive em memoria, entao vale por instancia; com varias instancias do core seria
 * preciso mover para o Redis.
 */
@Component
public class LimitadorTentativasLogin {

    private static final int LIMITE_DE_CHAVES_PARA_LIMPEZA = 10_000;

    private final int maximoDeFalhas;
    private final Duration janela;
    private final Clock relogio;
    private final ConcurrentMap<String, Deque<Instant>> falhasPorChave = new ConcurrentHashMap<>();

    @Autowired
    public LimitadorTentativasLogin(
            @Value("${alocacreche.seguranca.login.max-falhas:5}") int maximoDeFalhas,
            @Value("${alocacreche.seguranca.login.janela-segundos:900}") long janelaEmSegundos) {
        this(maximoDeFalhas, Duration.ofSeconds(janelaEmSegundos), Clock.systemUTC());
    }

    LimitadorTentativasLogin(int maximoDeFalhas, Duration janela, Clock relogio) {
        this.maximoDeFalhas = maximoDeFalhas;
        this.janela = janela;
        this.relogio = relogio;
    }

    /** Lanca TentativasExcedidasException quando a chave ja estourou o limite na janela. */
    public void verificarBloqueio(Long municipioId, String email) {
        Deque<Instant> falhas = falhasPorChave.get(chave(municipioId, email));
        if (falhas == null) {
            return;
        }
        synchronized (falhas) {
            descartarExpiradas(falhas);
            if (falhas.size() >= maximoDeFalhas) {
                Duration restante = Duration.between(relogio.instant(), falhas.peekFirst().plus(janela));
                throw new TentativasExcedidasException(Math.max(1, restante.toSeconds()));
            }
        }
    }

    public void registrarFalha(Long municipioId, String email) {
        if (falhasPorChave.size() > LIMITE_DE_CHAVES_PARA_LIMPEZA) {
            limparChavesExpiradas();
        }
        Deque<Instant> falhas = falhasPorChave.computeIfAbsent(chave(municipioId, email), k -> new ArrayDeque<>());
        synchronized (falhas) {
            descartarExpiradas(falhas);
            falhas.addLast(relogio.instant());
        }
    }

    public void registrarSucesso(Long municipioId, String email) {
        falhasPorChave.remove(chave(municipioId, email));
    }

    private void descartarExpiradas(Deque<Instant> falhas) {
        Instant limite = relogio.instant().minus(janela);
        while (!falhas.isEmpty() && !falhas.peekFirst().isAfter(limite)) {
            falhas.removeFirst();
        }
    }

    private void limparChavesExpiradas() {
        falhasPorChave.entrySet().removeIf(entrada -> {
            synchronized (entrada.getValue()) {
                descartarExpiradas(entrada.getValue());
                return entrada.getValue().isEmpty();
            }
        });
    }

    private static String chave(Long municipioId, String email) {
        return municipioId + ":" + email;
    }
}
