package org.alocacreche.core.outbox.dto;

/**
 * Fixtures do contrato dos tres eventos entre o core e o MSRelatorios. O texto das tres constantes e
 * IDENTICO, caractere a caractere, ao de {@code EventosContratoFixtures} do modulo
 * alocacreche-relatorios (pacote {@code evento}): nao ha modulo compartilhado, entao a copia fixa
 * so muda se os dois lados mudarem juntos. Ao alterar uma constante, altere a outra.
 */
final class EventosContratoFixtures {

    private EventosContratoFixtures() {
    }

    static final String VAGA_LIBERADA = """
            {
              "tipoEvento": "vaga.liberada",
              "municipioId": 1,
              "turmaId": 2,
              "crecheId": 3,
              "vagasDisponiveis": 5,
              "ocorridoEm": "2026-10-05T14:30:15"
            }
            """;

    static final String FILA_ATUALIZADA = """
            {
              "tipoEvento": "fila.atualizada",
              "municipioId": 1,
              "turmaId": 2,
              "crecheId": 3,
              "inscricaoId": 4,
              "dataEntrada": "2026-10-03",
              "dataSaida": null,
              "ocorridoEm": "2026-10-05T14:30:15"
            }
            """;

    static final String MATRICULA_ATUALIZADA = """
            {
              "tipoEvento": "matricula.atualizada",
              "municipioId": 1,
              "turmaId": 2,
              "crecheId": 3,
              "inscricaoId": 4,
              "matriculaId": 6,
              "status": "CONFIRMADA",
              "distanciaKm": 2.75,
              "dataConvocacao": "2026-10-05",
              "dataConfirmacao": "2026-10-07",
              "ocorridoEm": "2026-10-05T14:30:15"
            }
            """;
}
