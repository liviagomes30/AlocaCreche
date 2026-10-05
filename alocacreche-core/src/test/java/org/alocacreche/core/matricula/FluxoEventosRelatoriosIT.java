package org.alocacreche.core.matricula;

import static org.assertj.core.api.Assertions.assertThat;

import cn.dev33.satoken.jwt.StpLogicJwtForStateless;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.alocacreche.core.comum.tenant.TenantContext;
import org.alocacreche.core.inscricao.FilaEsperaRepository;
import org.alocacreche.core.notificacao.ConfiguracaoPriorizacao;
import org.alocacreche.core.notificacao.Municipio;
import org.alocacreche.core.notificacao.MunicipioRepository;
import org.alocacreche.core.outbox.OutboxEvent;
import org.alocacreche.core.outbox.OutboxEventRepository;
import org.alocacreche.core.outbox.OutboxRabbitConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.RabbitMQContainer;

/**
 * Prova o fluxo completo {@code creche -> turma -> inscricao -> alocar -> confirmar} por HTTP com
 * Postgres e RabbitMQ reais (PROD-19, PROD-07, PROD-12): cada passo deixa na Outbox a linha com os
 * campos do contrato do consumidor e o Publicador entrega, nas filas ligadas a exchange
 * {@code alocacreche.dominio}, mensagens cujo {@code eventId} e o id da linha da Outbox. As filas
 * de teste replicam as de {@code alocacreche-relatorios} (nao ha modulo Java compartilhado entre
 * os dois deploys).
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "alocacreche.outbox.publicador.habilitado=true")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("Fluxo completo de eventos ate o RabbitMQ")
class FluxoEventosRelatoriosIT {

    private static final String FILA_VAGA = "teste.fluxo.vaga-liberada";
    private static final String FILA_FILA = "teste.fluxo.fila-atualizada";
    private static final String FILA_MATRICULA = "teste.fluxo.matricula-atualizada";
    private static final long TIMEOUT_ENTREGA_MS = 10_000;
    private static final int VAGAS_TOTAIS = 3;

    static final PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>("postgres:16").withInitScript("db/core-app-role.sql");

    static final RabbitMQContainer rabbitmq = new RabbitMQContainer("rabbitmq:3-management");

    static {
        postgres.start();
        rabbitmq.start();
    }

    @DynamicPropertySource
    static void propriedades(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", () -> "alocacreche_app");
        registry.add("spring.datasource.password", () -> "alocacreche_app");
        registry.add("spring.rabbitmq.host", rabbitmq::getHost);
        registry.add("spring.rabbitmq.port", rabbitmq::getAmqpPort);
        registry.add("spring.rabbitmq.username", rabbitmq::getAdminUsername);
        registry.add("spring.rabbitmq.password", rabbitmq::getAdminPassword);
    }

    @Autowired private TestRestTemplate restTemplate;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private StpLogicJwtForStateless stpLogic;
    @Autowired private MunicipioRepository municipioRepository;
    @Autowired private TurmaRepository turmaRepository;
    @Autowired private FilaEsperaRepository filaEsperaRepository;
    @Autowired private OutboxEventRepository outboxEventRepository;
    @Autowired private RabbitTemplate rabbitTemplate;
    @Autowired private RabbitAdmin rabbitAdmin;
    @Autowired private TopicExchange dominioExchange;
    @Autowired private PlatformTransactionManager transactionManager;
    @PersistenceContext private EntityManager entityManager;

    private final AtomicInteger sequenciaCpf = new AtomicInteger(100);

    private Long municipioId;
    private String token;

    @BeforeAll
    void prepararMunicipio() {
        Municipio municipio = new Municipio();
        municipio.setNome("Municipio de Teste");
        municipio.setUf("SP");
        municipio.setPrazoConfirmacaoDias(5);
        municipio.setConfiguracaoPriorizacao(
                ConfiguracaoPriorizacao.builder()
                        .pesoRenda(0.4)
                        .pesoVulnerabilidade(0.3)
                        .pesoDistancia(0.2)
                        .pesoIrmaoMatriculado(0.1)
                        .build());
        municipioId = municipioRepository.saveAndFlush(municipio).getId();
        token =
                stpLogic.createTokenValue(
                        1L, "default", 3600, Map.of("municipioId", municipioId, "perms", List.of("*")));
    }

    @BeforeEach
    void declararFilasDeTeste() {
        ligar(FILA_VAGA, OutboxRabbitConfig.VAGA_LIBERADA_ROUTING_KEY);
        ligar(FILA_FILA, OutboxRabbitConfig.FILA_ATUALIZADA_ROUTING_KEY);
        ligar(FILA_MATRICULA, OutboxRabbitConfig.MATRICULA_ATUALIZADA_ROUTING_KEY);
        drenar();
        outboxEventRepository.deleteAll();
    }

    @AfterEach
    void limpar() {
        drenar();
        outboxEventRepository.deleteAll();
    }

    @Test
    @DisplayName("creche, turma e inscricao deixam vaga.liberada e fila.atualizada no contrato e entregues no broker")
    void cadastroEInscricaoDeixamVagaEFila() throws Exception {
        Long turmaId = criarCrecheETurma();
        Long inscricaoId = inscrever(turmaId);

        List<OutboxEvent> eventos = outboxEventRepository.findAll();
        assertThat(eventos).extracting(OutboxEvent::getTipoEvento)
                .containsExactlyInAnyOrder("vaga.liberada", "fila.atualizada");
        JsonNode vaga = payloadDe(eventos, "vaga.liberada");
        assertThat(nomesDosCampos(vaga)).containsExactlyInAnyOrderElementsOf(CAMPOS_VAGA);
        assertThat(vaga.get("turmaId").asLong()).isEqualTo(turmaId);
        assertThat(vaga.get("vagasDisponiveis").asInt()).isEqualTo(VAGAS_TOTAIS);
        JsonNode fila = payloadDe(eventos, "fila.atualizada");
        assertThat(nomesDosCampos(fila)).containsExactlyInAnyOrderElementsOf(CAMPOS_FILA);
        assertThat(fila.get("inscricaoId").asLong()).isEqualTo(inscricaoId);
        assertThat(fila.get("dataEntrada").asText()).isEqualTo(LocalDate.now().toString());
        assertThat(fila.get("dataSaida").isNull()).isTrue();

        conferirEntrega(eventos);
    }

    @Test
    @DisplayName("alocar deixa tres eventos no contrato, vagasDisponiveis igual a vagasTotais menos 1 e uma inscricao a menos na fila")
    void alocarDeixaOsTresEventosEReduzVagaEFila() throws Exception {
        Long turmaId = criarCrecheETurma();
        inscrever(turmaId);
        inscrever(turmaId);
        esperarEntregaDe(outboxEventRepository.findAll());
        outboxEventRepository.deleteAll();

        ResponseEntity<String> resposta = postar("/api/v1/matricula/vagas/alocar", Map.of("turmaId", turmaId));

        assertThat(resposta.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        long matriculaId = objectMapper.readTree(resposta.getBody()).get("matriculaId").asLong();
        emTenant(() -> {
            assertThat(turmaRepository.findById(turmaId).orElseThrow().getVagasDisponiveis())
                    .isEqualTo(VAGAS_TOTAIS - 1);
            assertThat(filaEsperaRepository.findByTurmaIdOrderByPosicaoAsc(turmaId)).hasSize(1);
            return null;
        });
        List<OutboxEvent> eventos = outboxEventRepository.findAll();
        assertThat(eventos).extracting(OutboxEvent::getTipoEvento)
                .containsExactlyInAnyOrder("vaga.liberada", "fila.atualizada", "matricula.atualizada");
        assertThat(payloadDe(eventos, "vaga.liberada").get("vagasDisponiveis").asInt())
                .isEqualTo(VAGAS_TOTAIS - 1);
        JsonNode fila = payloadDe(eventos, "fila.atualizada");
        assertThat(nomesDosCampos(fila)).containsExactlyInAnyOrderElementsOf(CAMPOS_FILA);
        assertThat(fila.get("dataSaida").asText()).isEqualTo(LocalDate.now().toString());
        JsonNode matricula = payloadDe(eventos, "matricula.atualizada");
        assertThat(nomesDosCampos(matricula)).containsExactlyInAnyOrderElementsOf(CAMPOS_MATRICULA);
        assertThat(matricula.get("matriculaId").asLong()).isEqualTo(matriculaId);
        assertThat(matricula.get("status").asText()).isEqualTo("PENDENTE");
        assertThat(matricula.get("distanciaKm").asDouble()).isPositive();
        assertThat(matricula.get("dataConfirmacao").isNull()).isTrue();

        conferirEntrega(eventos);
    }

    @Test
    @DisplayName("confirmar deixa matricula.atualizada CONFIRMADA no contrato e entregue no broker")
    void confirmarDeixaMatriculaConfirmada() throws Exception {
        Long turmaId = criarCrecheETurma();
        inscrever(turmaId);
        ResponseEntity<String> alocacao = postar("/api/v1/matricula/vagas/alocar", Map.of("turmaId", turmaId));
        long matriculaId = objectMapper.readTree(alocacao.getBody()).get("matriculaId").asLong();
        esperarEntregaDe(outboxEventRepository.findAll());
        outboxEventRepository.deleteAll();

        ResponseEntity<String> resposta =
                postar("/api/v1/matricula/matriculas/" + matriculaId + "/confirmar", null);

        assertThat(resposta.getStatusCode()).isEqualTo(HttpStatus.OK);
        List<OutboxEvent> eventos = outboxEventRepository.findAll();
        assertThat(eventos).extracting(OutboxEvent::getTipoEvento).containsExactly("matricula.atualizada");
        JsonNode matricula = payloadDe(eventos, "matricula.atualizada");
        assertThat(nomesDosCampos(matricula)).containsExactlyInAnyOrderElementsOf(CAMPOS_MATRICULA);
        assertThat(matricula.get("status").asText()).isEqualTo("CONFIRMADA");
        assertThat(matricula.get("matriculaId").asLong()).isEqualTo(matriculaId);
        assertThat(matricula.get("distanciaKm").asDouble()).isPositive();
        assertThat(matricula.get("dataConfirmacao").asText()).isEqualTo(LocalDate.now().toString());

        conferirEntrega(eventos);
    }

    private static final List<String> CAMPOS_VAGA =
            List.of("tipoEvento", "municipioId", "turmaId", "crecheId", "vagasDisponiveis", "ocorridoEm");

    private static final List<String> CAMPOS_FILA =
            List.of(
                    "tipoEvento",
                    "municipioId",
                    "turmaId",
                    "crecheId",
                    "inscricaoId",
                    "dataEntrada",
                    "dataSaida",
                    "ocorridoEm");

    private static final List<String> CAMPOS_MATRICULA =
            List.of(
                    "tipoEvento",
                    "municipioId",
                    "turmaId",
                    "crecheId",
                    "inscricaoId",
                    "matriculaId",
                    "status",
                    "distanciaKm",
                    "dataConvocacao",
                    "dataConfirmacao",
                    "ocorridoEm");

    private void conferirEntrega(List<OutboxEvent> eventos) throws Exception {
        Map<String, JsonNode> entregues = esperarEntregaDe(eventos);
        for (OutboxEvent evento : eventos) {
            JsonNode mensagem = entregues.get(evento.getId().toString());
            assertThat(mensagem).as("mensagem do evento %s", evento.getId()).isNotNull();
            ((com.fasterxml.jackson.databind.node.ObjectNode) mensagem).remove("eventId");
            assertThat(mensagem).isEqualTo(objectMapper.readTree(evento.getPayload()));
        }
    }

    private Map<String, JsonNode> esperarEntregaDe(List<OutboxEvent> eventos) throws Exception {
        Map<String, JsonNode> entregues = new HashMap<>();
        for (String fila : List.of(FILA_VAGA, FILA_FILA, FILA_MATRICULA)) {
            long esperadas = eventos.stream().filter(e -> filaDe(e.getTipoEvento()).equals(fila)).count();
            for (long i = 0; i < esperadas; i++) {
                Message mensagem = rabbitTemplate.receive(fila, TIMEOUT_ENTREGA_MS);
                assertThat(mensagem).as("mensagem na fila %s", fila).isNotNull();
                JsonNode json = objectMapper.readTree(new String(mensagem.getBody(), StandardCharsets.UTF_8));
                assertThat(json.get("eventId").isNumber()).isTrue();
                entregues.put(json.get("eventId").asText(), json);
            }
        }
        return entregues;
    }

    private static String filaDe(String tipoEvento) {
        return switch (tipoEvento) {
            case "vaga.liberada" -> FILA_VAGA;
            case "fila.atualizada" -> FILA_FILA;
            default -> FILA_MATRICULA;
        };
    }

    private void ligar(String nomeFila, String routingKey) {
        Queue fila = new Queue(nomeFila, true);
        rabbitAdmin.declareQueue(fila);
        Binding binding = BindingBuilder.bind(fila).to(dominioExchange).with(routingKey);
        rabbitAdmin.declareBinding(binding);
    }

    private void drenar() {
        for (String fila : List.of(FILA_VAGA, FILA_FILA, FILA_MATRICULA)) {
            while (rabbitTemplate.receive(fila, 200) != null) {
                continue;
            }
        }
    }

    private Long criarCrecheETurma() {
        Map<String, Object> creche = new HashMap<>();
        creche.put("nome", "Creche Sol");
        creche.put("capacidade", 50);
        creche.put(
                "endereco",
                Map.of(
                        "rua", "Rua B",
                        "numero", 20,
                        "bairro", "Centro",
                        "cidade", "Sao Paulo",
                        "estado", "SP",
                        "latitude", -23.56,
                        "longitude", -46.64));
        Long crecheId = idDe(postar("/api/v1/matricula/creches", creche), "crecheId");
        Map<String, Object> turma = new HashMap<>();
        turma.put("crecheId", crecheId);
        turma.put("faixaEtaria", "MATERNAL");
        turma.put("vagasTotais", VAGAS_TOTAIS);
        return idDe(postar("/api/v1/matricula/turmas", turma), "turmaId");
    }

    private Long inscrever(Long turmaId) {
        Map<String, Object> responsavel = new HashMap<>();
        responsavel.put("nome", "Maria Responsavel");
        responsavel.put("cpf", String.format("%03d.000.000-00", sequenciaCpf.incrementAndGet()));
        responsavel.put(
                "endereco",
                Map.of("rua", "Rua A", "numero", 10, "bairro", "Centro", "latitude", -23.55, "longitude", -46.63));
        Map<String, Object> crianca = new HashMap<>();
        crianca.put("nome", "Joao Crianca");
        crianca.put("dataNascimento", "2024-03-10");
        Map<String, Object> corpo = new HashMap<>();
        corpo.put("responsavel", responsavel);
        corpo.put("crianca", crianca);
        corpo.put("turmaId", turmaId);
        corpo.put("rendaPerCapita", 1234.56);
        corpo.put("criterioVulnerabilidade", true);
        return idDe(postar("/api/v1/inscricao/inscricoes", corpo), "inscricaoId");
    }

    private Long idDe(ResponseEntity<String> resposta, String campo) {
        try {
            return objectMapper.readTree(resposta.getBody()).get(campo).asLong();
        } catch (Exception excecao) {
            throw new IllegalStateException("Resposta inesperada: " + resposta.getStatusCode(), excecao);
        }
    }

    private ResponseEntity<String> postar(String caminho, Map<String, ?> corpo) {
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set(HttpHeaders.AUTHORIZATION, "Bearer " + token);
            String json = corpo == null ? null : objectMapper.writeValueAsString(corpo);
            return restTemplate.exchange(caminho, HttpMethod.POST, new HttpEntity<>(json, headers), String.class);
        } catch (Exception excecao) {
            throw new IllegalStateException(excecao);
        }
    }

    private JsonNode payloadDe(List<OutboxEvent> eventos, String tipoEvento) throws Exception {
        OutboxEvent evento =
                eventos.stream().filter(e -> e.getTipoEvento().equals(tipoEvento)).findFirst().orElseThrow();
        return objectMapper.readTree(evento.getPayload());
    }

    private static List<String> nomesDosCampos(JsonNode json) {
        List<String> nomes = new ArrayList<>();
        json.fieldNames().forEachRemaining(nomes::add);
        return nomes;
    }

    private <T> T emTenant(Supplier<T> acao) {
        return new TransactionTemplate(transactionManager)
                .execute(
                        status -> {
                            TenantContext.aplicar(entityManager, municipioId);
                            return acao.get();
                        });
    }
}
