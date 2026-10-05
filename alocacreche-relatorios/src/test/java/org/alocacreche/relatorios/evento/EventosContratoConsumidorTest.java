package org.alocacreche.relatorios.evento;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import java.time.LocalDate;
import org.alocacreche.relatorios.filaespera.FilaEsperaAtualizadaEvent;
import org.alocacreche.relatorios.filaespera.FilaEsperaAtualizadaListener;
import org.alocacreche.relatorios.filaespera.FilaEsperaInscricaoRepository;
import org.alocacreche.relatorios.matricula.MatriculaAtualizadaEvent;
import org.alocacreche.relatorios.matricula.MatriculaAtualizadaListener;
import org.alocacreche.relatorios.matricula.MatriculaRelatorioRepository;
import org.alocacreche.relatorios.ocupacao.OcupacaoTurmaRepository;
import org.alocacreche.relatorios.ocupacao.PainelPublicoCacheInvalidador;
import org.alocacreche.relatorios.ocupacao.VagaLiberadaEvent;
import org.alocacreche.relatorios.ocupacao.VagaLiberadaListener;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;

/**
 * Prova o lado consumidor do contrato (PROD-19): o mesmo texto de fixture que o core serializa e
 * desserializado para o {@code record} do evento, acrescentando o {@code eventId} que o Publicador
 * injeta, e o listener o aceita e chama o {@code upsert} com os valores certos. O mapper rejeita
 * propriedades desconhecidas para que um campo renomeado de um dos lados derrube o teste, mesmo
 * quando o campo e opcional.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("Fixtures do contrato dos eventos aceitas pelos listeners do MSRelatorios")
class EventosContratoConsumidorTest {

    private static final long EVENT_ID = 99L;

    @Mock private EntityManager entityManager;
    @Mock private Query query;

    private final ObjectMapper objectMapper =
            Jackson2ObjectMapperBuilder.json()
                    .featuresToEnable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                    .build();

    @BeforeEach
    void aplicarTenant() {
        lenient().when(entityManager.createNativeQuery(anyString())).thenReturn(query);
        lenient().when(query.setParameter(1, "1")).thenReturn(query);
    }

    @Test
    @DisplayName("vaga.liberada do fixture e aceito e vira upsert da ocupacao")
    void vagaLiberadaDoFixtureEAceita(@Mock OcupacaoTurmaRepository repository,
            @Mock PainelPublicoCacheInvalidador invalidador) throws Exception {
        VagaLiberadaEvent evento =
                objectMapper.readValue(
                        comEventId(EventosContratoFixtures.VAGA_LIBERADA), VagaLiberadaEvent.class);
        VagaLiberadaListener listener = new VagaLiberadaListener(repository, entityManager, invalidador);

        assertThatCode(() -> listener.receber(evento)).doesNotThrowAnyException();

        verify(repository).upsert(2L, 1L, 3L, 5, EVENT_ID);
        verify(invalidador).invalidarAposCommit(1L);
    }

    @Test
    @DisplayName("fila.atualizada do fixture e aceito e vira upsert da fila de espera")
    void filaAtualizadaDoFixtureEAceita(@Mock FilaEsperaInscricaoRepository repository) throws Exception {
        FilaEsperaAtualizadaEvent evento =
                objectMapper.readValue(
                        comEventId(EventosContratoFixtures.FILA_ATUALIZADA),
                        FilaEsperaAtualizadaEvent.class);
        FilaEsperaAtualizadaListener listener = new FilaEsperaAtualizadaListener(repository, entityManager);

        assertThatCode(() -> listener.receber(evento)).doesNotThrowAnyException();

        verify(repository).upsert(4L, 2L, 3L, 1L, LocalDate.of(2026, 10, 3), null, EVENT_ID);
    }

    @Test
    @DisplayName("matricula.atualizada do fixture e aceito e vira upsert da matricula")
    void matriculaAtualizadaDoFixtureEAceita(@Mock MatriculaRelatorioRepository repository) throws Exception {
        MatriculaAtualizadaEvent evento =
                objectMapper.readValue(
                        comEventId(EventosContratoFixtures.MATRICULA_ATUALIZADA),
                        MatriculaAtualizadaEvent.class);
        MatriculaAtualizadaListener listener = new MatriculaAtualizadaListener(repository, entityManager);

        assertThatCode(() -> listener.receber(evento)).doesNotThrowAnyException();

        verify(repository)
                .upsert(
                        6L,
                        4L,
                        2L,
                        3L,
                        1L,
                        "CONFIRMADA",
                        2.75,
                        LocalDate.of(2026, 10, 5),
                        LocalDate.of(2026, 10, 7),
                        EVENT_ID);
    }

    @Test
    @DisplayName("um campo renomeado no fixture deixa de ser aceito pelo consumidor")
    void campoRenomeadoNaoEAceito() throws Exception {
        String renomeado = comEventId(EventosContratoFixtures.VAGA_LIBERADA).replace("turmaId", "idDaTurma");

        assertThatThrownBy(() -> objectMapper.readValue(renomeado, VagaLiberadaEvent.class))
                .isInstanceOf(UnrecognizedPropertyException.class);
    }

    private String comEventId(String fixture) throws Exception {
        ObjectNode json = (ObjectNode) objectMapper.readTree(fixture);
        json.put("eventId", EVENT_ID);
        return objectMapper.writeValueAsString(json);
    }
}
