# Consumo do evento FilaEsperaAtualizada e relatorioFilaEspera (MSRelatorios) Specification

## Problem Statement

`alocacreche-relatorios` hoje só tem o indicador de ocupação (`OcupacaoTurma`). Dos 5 indicadores
previstos em `docs/arquitetura/visao-geral.md`, falta `relatorioFilaEspera` — sem projeção, sem
consumer, sem controller. Sem ele, gestores municipais não têm visibilidade de quantas crianças
aguardam vaga por turma/creche nem de quanto tempo essa espera está durando, um dos quatro problemas
concretos que o AlocaCreche existe para resolver (ver `docs/arquitetura/visao-geral.md`, introdução do
projeto).

## Goals

- [ ] Consumidor RabbitMQ processa o evento `FilaEsperaAtualizada` (granularidade por inscrição, não
      por turma) e faz upsert da projeção de fila de espera, de forma idempotente e resiliente a
      entrega duplicada/fora de ordem — mesmo mecanismo de upsert-por-sequência já provado em
      `relatorios-ocupacao-vaga-liberada` (AD-001/AD-002), aplicado a uma chave de negócio diferente
      (`inscricaoId` em vez de `turmaId`).
- [ ] `GET /api/v1/relatorios/fila-espera` retorna, por turma, a contagem de aguardando e o tempo
      médio de espera (em dias) — incluindo, no cálculo da média, quem ainda está na fila no momento
      da consulta. Filtrável por município (obrigatório), creche e turma (opcionais) — mesmo padrão
      de `relatorios-leitura-ocupacao`.
- [ ] O contrato de payload definido aqui vira a especificação que o módulo Inscrição & Fila
      (`alocacreche-core`, ainda sem nenhuma entidade implementada) deve seguir quando publicar esse
      evento pela Outbox — mesma relação que `relatorios-ocupacao-vaga-liberada` já tem com o módulo
      Matrícula.

## Out of Scope

| Feature | Reason |
| --- | --- |
| Motivo de saída da fila (matrícula/desistência/expiração) | Não é necessário para calcular contagem de aguardando nem tempo médio de espera — candidato a uma feature futura de relatório de saída/conversão por motivo |
| Implementação do lado produtor (módulo Inscrição & Fila, `alocacreche-core`) | Fora do escopo deste módulo — o contrato de payload definido aqui é a especificação que o produtor deve seguir ao ser implementado (mesma relação de `VagaLiberadaEvent` com o módulo Matrícula) |
| Autenticação/autorização (Sa-Token) no novo endpoint | `alocacreche-relatorios` não tem a dependência `sa-token` no `pom.xml` ainda (confirmado — só `alocacreche-core` tem); `OcupacaoConsultaController` (relatório gerencial já existente) tem o mesmo TODO documentado. Este endpoint segue a mesma decisão já tomada, não uma nova exceção |
| `distanciaMediaAlocacao` e `taxaConfirmacaoMatricula` | Indicadores distintos, cada um com seu próprio evento/projeção — fora do escopo desta feature (ver `docs/arquitetura/visao-geral.md`) |
| Job `gerarSnapshotRelatorios` (`@Scheduled`) | Recálculo agregado a partir de todas as projeções já idempotentes — tarefa transversal separada, não específica de fila de espera |
| Ferramenta de migration (Flyway/Liquibase) para a política de RLS da nova tabela | Mesma decisão já registrada em AD-002: `schema.sql` idempotente até uma ferramenta de migration ser adotada no projeto inteiro |
| Mascaramento LGPD (CPF/renda/vulnerabilidade) | O payload deste evento (`inscricaoId`, `turmaId`, `crecheId`, `municipioId`, `dataEntrada`, `dataSaida`) não carrega nenhum dado de família — não há o que mascarar nesta projeção |

---

## Assumptions & Open Questions

| Assumption / decision | Chosen default | Rationale | Confirmed? |
| --- | --- | --- | --- |
| Granularidade da projeção | Por inscrição individual (`inscricaoId` como chave de negócio), não um total agregado por turma | Decisão de produto confirmada explicitamente com o usuário: o relatório precisa de tempo médio de espera, que só é calculável a partir de `dataEntrada`/`dataSaida` por inscrição | y |
| Contrato JSON do evento `FilaEsperaAtualizada` | `{eventId, tipoEvento, municipioId, turmaId, crecheId, inscricaoId, dataEntrada, dataSaida, ocorridoEm}` — `dataSaida` nulo enquanto a inscrição ainda aguarda | Cada evento carrega o estado completo conhecido da inscrição (nunca um delta), mesmo raciocínio de "upsert por chave de negócio, nunca contador incremental" já documentado em `docs/context/relatorios.md` — resolve fora-de-ordem e duplicata sem depender de dois tipos de evento distintos (entrada/saída) | y |
| Cálculo de `tempoMedioEsperaDias` | Média de `(COALESCE(dataSaida, momento da consulta) - dataEntrada)`, incluindo inscrições ainda aguardando | Decisão de produto confirmada explicitamente com o usuário — reflete a fila "ao vivo"; consequência aceita: o valor pode mudar entre duas chamadas consecutivas do mesmo endpoint mesmo sem nenhum evento novo processado, porque o termo `momento da consulta` avança | y |
| Unidade de `tempoMedioEsperaDias` | Dias (`Double`), não minutos/horas | Espera por vaga em creche é medida em dias/semanas na prática; o domínio já usa dias como unidade administrativa (`Municipio.prazoConfirmacaoDias`) — minutos produziriam números grandes e pouco legíveis para este caso de uso | n |
| Topologia RabbitMQ | Reusa o exchange topic já existente `alocacreche.dominio`; nova fila `relatorios.filaespera`, nova DLQ `relatorios.filaespera.dlq`, routing key `fila.atualizada`, DLQ routing key `fila.atualizada.dlq` | Mesmo exchange de domínio já declarado por `RabbitMqConfig` (topic exchange existe para múltiplos routing keys); fila/DLQ dedicadas seguem o padrão 1 fila por tipo de evento já usado em `relatorios.ocupacao` | y |
| Row-Level Security da nova tabela | Mesmo padrão de `ocupacao_turma`: `ENABLE`+`FORCE ROW LEVEL SECURITY` via `schema.sql` idempotente, política por `municipio_id = current_setting('app.current_tenant')` | Consistência com AD-002 — mesma tabela de decisão, mesma razão (consumidor roda sem contexto de sessão JWT) | y |
| Mecanismo de upsert concorrente | `INSERT ... ON CONFLICT` nativo, condicionado a `ultima_sequencia_aplicada` menor que a recebida | Mesma decisão de Design já provada em `OcupacaoTurmaRepository` — nenhuma razão para um mecanismo diferente aqui, só troca a chave de conflito de `turma_id` para `inscricao_id` | y |
| Agregação do relatório por turma a partir de linhas por inscrição | Query nativa com `GROUP BY turma_id, creche_id, municipio_id`, projeção de interface Spring Data (sem MyBatis) | Evita carregar todas as linhas de inscrição na aplicação para agregar em memória; `GROUP BY` no Postgres é a forma padrão e mais barata | y |

**Open questions:** none - all resolved or logged above.

---

## User Stories

### P1: Consumir FilaEsperaAtualizada e manter a projeção de fila de espera por inscrição ⭐ MVP

**User Story**: Como MSRelatorios, quero consumir o evento `FilaEsperaAtualizada` e manter uma
projeção por inscrição (data de entrada e, quando aplicável, data de saída da fila), para que o
relatório gerencial de fila de espera tenha um dado confiável para calcular contagem e tempo médio de
espera.

**Why P1**: Sem a projeção não existe dado nenhum para o endpoint de leitura agregar — pré-requisito
de tudo o resto desta feature, mesma ordem já usada em `relatorios-ocupacao-vaga-liberada`.

**Acceptance Criteria**:

1. **[FILA-01]** WHEN uma mensagem com routing key `fila.atualizada` chega na fila
   `relatorios.filaespera` THEN o sistema SHALL fazer upsert da linha de projeção identificada por
   `inscricaoId`, gravando `turmaId`, `crecheId`, `municipioId`, `dataEntrada`, `dataSaida` (nulo ou
   preenchido) e a sequência (`eventId`) recebida.
2. **[FILA-02]** WHEN o `eventId` recebido for menor ou igual à última sequência já aplicada para
   aquela `inscricaoId` THEN o sistema SHALL descartar o upsert sem erro e sem alterar a projeção
   (idempotência e ordem).
3. **[FILA-03]** WHEN duas mensagens para a mesma `inscricaoId` forem processadas concorrentemente
   THEN o sistema SHALL garantir que a projeção final reflita só a maior sequência entre as duas,
   independente da ordem de chegada real.
4. **[FILA-04]** The system SHALL aplicar a variável de tenant (`SET LOCAL app.current_tenant`) igual
   ao `municipioId` do payload antes de gravar ou ler a projeção, dentro da mesma transação de
   processamento da mensagem.
5. **[FILA-05]** The system SHALL declarar a fila `relatorios.filaespera` e a dead-letter queue
   `relatorios.filaespera.dlq` na inicialização, vinculadas ao exchange `alocacreche.dominio` já
   existente, de forma idempotente (não falha se já existirem).
6. **[FILA-06]** The system SHALL manter uma política de Row-Level Security na tabela de projeção
   (`municipio_id = current_setting('app.current_tenant')`), de forma que uma leitura sem `SET LOCAL`
   definido, ou com o tenant errado, não retorne linhas de outro município — mesmo que o código da
   aplicação omita um filtro explícito.

**Independent Test**: publicar manualmente uma mensagem JSON válida com routing key `fila.atualizada`
no RabbitMQ local e verificar que a linha aparece/atualiza na tabela de projeção do Postgres do
`alocacreche-relatorios`, com `municipio_id` respeitando RLS.

---

### P2: Rejeitar mensagens malformadas ou inconsistentes para a dead-letter queue

**User Story**: Como MSRelatorios, quero que mensagens malformadas, com campo obrigatório faltando ou
com datas inconsistentes sejam isoladas numa dead-letter queue, para que um payload inválido não fique
reentregue para sempre, nem trave o processamento das mensagens válidas seguintes, nem corrompa o
cálculo de tempo médio de espera com uma duração negativa.

**Why P2**: Importante para resiliência e correção do indicador em produção, mas não bloqueia a
demonstração do caminho feliz (P1) nem do endpoint de leitura (P3).

**Acceptance Criteria**:

1. **[FILA-07]** IF o payload não puder ser desserializado, ou faltar `municipioId`, `turmaId`,
   `crecheId`, `inscricaoId`, `eventId` ou `dataEntrada` THEN o sistema SHALL rejeitar a mensagem
   diretamente para `relatorios.filaespera.dlq`, sem tentativa de reprocessamento.
2. **[FILA-08]** IF `dataSaida` vier preenchida e for anterior a `dataEntrada` THEN o sistema SHALL
   tratar como payload inválido e rejeitar para a dead-letter queue (mesma regra do item 1) — evita
   que uma duração negativa distorça `tempoMedioEsperaDias`.
3. **[FILA-09]** IF o processamento falhar por uma causa transitória (ex.: banco indisponível) THEN o
   sistema SHALL tentar novamente, até 3 tentativas consecutivas, antes de mover a mensagem para
   `relatorios.filaespera.dlq` (mesma política AD-001 já em produção para `relatorios.ocupacao`).

**Independent Test**: publicar uma mensagem com JSON inválido (ou faltando `inscricaoId`, ou com
`dataSaida` anterior a `dataEntrada`) e verificar que ela aparece na fila `relatorios.filaespera.dlq`
imediatamente, sem reentrega.

---

### P3: Consultar o relatório de fila de espera

**User Story**: Como gestor municipal, quero consultar quantas crianças aguardam vaga por turma/creche
e há quanto tempo em média, para priorizar a abertura de novas turmas ou realocação de recursos.

**Why P3**: Depende da projeção existir (P1) — é a ponta de leitura que efetivamente entrega o valor
de negócio (`relatorioFilaEspera` da assinatura em `docs/arquitetura/visao-geral.md`), mas sem dado
nenhum consultável (P1) não há o que agregar.

**Acceptance Criteria**:

1. **[FILA-10]** WHEN `GET /api/v1/relatorios/fila-espera` é chamado com `municipioId` THEN o sistema
   SHALL retornar, por turma, `quantidadeAguardando` (contagem de inscrições daquela turma com
   `dataSaida` nula) e `tempoMedioEsperaDias` (média de `COALESCE(dataSaida, agora) - dataEntrada`,
   em dias, sobre todas as inscrições daquela turma) — opcionalmente filtrado por `crecheId` e/ou
   `turmaId` quando informados.
2. **[FILA-11]** IF `municipioId` não for informado THEN o sistema SHALL responder HTTP 400, sem
   consultar o banco.
3. **[FILA-12]** The system SHALL aplicar `SET LOCAL app.current_tenant` igual ao `municipioId`
   recebido antes de consultar a projeção, mesma mecânica de `OcupacaoConsultaController`.
4. **[FILA-13]** The system SHALL retornar a resposta como uma lista de DTO
   (`RelatorioFilaEsperaDTO`), nunca a entidade JPA `FilaEsperaInscricao` diretamente.
5. **[FILA-14]** The system SHALL considerar o instante da consulta como saída provisória para
   inscrições que ainda não saíram da fila (`dataSaida` nula) no cálculo de `tempoMedioEsperaDias` —
   duas chamadas consecutivas ao mesmo endpoint, sem nenhum evento novo processado entre elas, PODEM
   retornar valores diferentes para `tempoMedioEsperaDias` (comportamento esperado, não uma falha de
   cache ou de idempotência).
6. **[FILA-15]** IF uma turma nunca recebeu nenhum evento `FilaEsperaAtualizada` THEN ela SHALL não
   aparecer na lista retornada (sem linha com contagem zero) — mesmo comportamento de "sem dado ainda"
   já usado no relatório de ocupação.

**Independent Test**: com uma linha de projeção já criada por P1 (uma inscrição aguardando, sem
`dataSaida`), chamar `GET /api/v1/relatorios/fila-espera?municipioId=X` e receber
`quantidadeAguardando=1` e `tempoMedioEsperaDias` maior que zero; chamar sem `municipioId` e receber
HTTP 400; chamar duas vezes seguidas e confirmar que `tempoMedioEsperaDias` não diminui entre as duas
chamadas.

---

## Edge Cases

- IF a fila/DLQ ainda não existirem no broker THEN o sistema SHALL declará-las na inicialização
  (coberto por P1, FILA-05).
- IF `dataSaida` for anterior a `dataEntrada` THEN o sistema SHALL rejeitar para DLQ (coberto por P2,
  FILA-08).
- IF duas inscrições diferentes da mesma turma forem processadas ao mesmo tempo THEN o sistema SHALL
  processá-las de forma independente — cada upsert é isolado por `inscricaoId` (chave primária
  distinta), sem trava entre inscrições da mesma turma.
- IF uma inscrição sair da fila e depois um evento antigo (sequência menor) para a mesma inscrição for
  reentregue pelo broker THEN o sistema SHALL manter `dataSaida` já aplicada (regra geral de
  idempotência por sequência, FILA-02, aplicada a este caso específico).
- IF uma turma tiver só inscrições que já saíram da fila (nenhuma aguardando) THEN
  `quantidadeAguardando` SHALL ser `0` e `tempoMedioEsperaDias` SHALL refletir só as durações já
  concluídas.

---

## Requirement Traceability

| Requirement ID | Story | Phase | Status |
| --- | --- | --- | --- |
| FILA-01 | P1 | Tasks | Implementing |
| FILA-02 | P1 | Tasks | Implementing |
| FILA-03 | P1 | Tasks | Implementing |
| FILA-04 | P1 | Tasks | Implementing |
| FILA-05 | P1 | Tasks | Implementing |
| FILA-06 | P1 | Tasks | Implementing |
| FILA-07 | P2 | Tasks | Implementing |
| FILA-08 | P2 | Tasks | Implementing |
| FILA-09 | P2 | Tasks | Implementing |
| FILA-10 | P3 | Tasks | Pending |
| FILA-11 | P3 | Tasks | Pending |
| FILA-12 | P3 | Tasks | Pending |
| FILA-13 | P3 | Tasks | Pending |
| FILA-14 | P3 | Tasks | Pending |
| FILA-15 | P3 | Tasks | Pending |

**ID format:** `FILA-NN` (Fila de Espera).

**Status values:** Pending → In Design → In Tasks → Implementing → Verified

**Coverage:** 15 total, 15 to be mapped to tasks (ver tasks.md), 0 unmapped

---

## Success Criteria

- [ ] Publicar um evento `FilaEsperaAtualizada` válido resulta numa linha de projeção correta no
      Postgres do `alocacreche-relatorios`, em menos de alguns segundos.
- [ ] Reenviar o mesmo evento (ou um com `eventId` menor) não altera a projeção.
- [ ] Um payload malformado ou com `dataSaida` anterior a `dataEntrada` aparece na DLQ, não na fila
      principal, e não trava mensagens seguintes.
- [ ] `GET /api/v1/relatorios/fila-espera?municipioId=X` retorna `quantidadeAguardando` e
      `tempoMedioEsperaDias` corretos por turma e respeita RLS entre municípios.
- [ ] `mvn -pl alocacreche-relatorios verify` cobre os cenários acima e passa (AD-003 — gate é
      `verify`, nunca `test` sozinho).
