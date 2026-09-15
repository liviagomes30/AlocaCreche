package org.alocacreche.relatorios.ocupacao;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * Resumo agregado de ocupação por município. Também é o valor armazenado no cache Redis
 * (`painel-publico`) - precisa implementar {@link Serializable} porque o RedisCacheManager padrão do
 * Spring Boot serializa valores de cache via JDK serialization.
 */
public record PainelPublicoDTO(
        Long municipioId,
        Long totalVagasDisponiveis,
        Long totalCrechesComVaga,
        Long totalTurmas,
        LocalDateTime atualizadoEm)
        implements Serializable {
}
