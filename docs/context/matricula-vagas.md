# Contexto — Módulo Matrícula & Vagas/Creches

> Leia este arquivo antes de implementar ou revisar código em `alocacreche-core/.../matricula/`.
> Fluxo completo "Alocar vaga automaticamente" (passo a passo): `docs/arquitetura/visao-geral.md`.
> Decisões arquiteturais: `docs/adr/0001-fundacao-arquitetural.md`.

## Dono exclusivo do pacote

`Matricula`, `Turma`, `Creche`, `vagasDisponiveis`. Este é o único módulo autorizado a decrementar
`vagasDisponiveis` ou criar/atualizar `Matricula`.

## Responsabilidades

- Alocação de vaga com lock otimista em transação local (`alocarProximoElegivel`).
- Sugestão de creches por proximidade (`sugerirCrechesProximas`, via pacote comum de geolocalização).
- Criação, confirmação e expiração da matrícula (`confirmarMatricula`, `verificarPrazosVencidos`).
- Reconciliação de vagas disponíveis (`reconciliarVagasDisponiveis`).

## A garantia central deste módulo (não simplifique)

"Confirmar disponibilidade da turma" e "criar a matrícula" são **um único método `@Transactional`**
protegido por lock otimista (`@Version` em `Turma`). Não existe janela em que a vaga fique decrementada
sem uma matrícula correspondente, porque as duas escritas são a mesma transação de banco — isto
substitui a necessidade de saga, outbox de compensação ou consumidor idempotente só para essa garantia.

## Regras específicas deste módulo

- **`alocarProximoElegivel` sempre recalcula o score on-the-fly** a partir dos fatores brutos da
  `Inscricao` (renda, vulnerabilidade, distância, irmão matriculado) e da `ConfiguracaoPriorizacao`
  vigente do município — nunca confia no campo `scorePrioridade` persistido nem no cache Redis (esses
  são só estimativa de exibição, mantidos pelo módulo Inscrição & Fila).
- **Desempate declarado:** `ORDER BY score DESC, dataInscricao ASC` (FIFO em caso de empate). Numa fila
  pública, quem vence o empate precisa ser auditável — nunca deixe isso como efeito colateral da ordem
  física das linhas do banco.
- **`aoLiberarVaga` (listener) e `alocarProximoElegivel` (serviço de domínio) precisam estar em beans
  diferentes.** Se o listener chamar `alocarProximoElegivel` via `this`, a chamada não atravessa o proxy
  do Spring e tanto `@Transactional` quanto `@Retryable` deixam de valer — silenciosamente, sem exceção.
  Injete o serviço de domínio no bean do listener e chame por injeção, nunca `this`. Ver o gotcha de
  self-invocation em `docs/arquitetura/notas-tecnicas.md#self-invocation-quebra-transactionalretryablescheduled`.
- **`@Retryable` fica no listener (não-`@Transactional`), nunca no mesmo método que `@Transactional`.**
  Os dois são advice de AOP sem ordem explícita por padrão; se `@Transactional` acabar "por fora", cada
  tentativa de retry reaproveita a mesma transação e o mesmo contexto de persistência já abertos —
  contradizendo a premissa de que cada tentativa relê o estado atual da `Turma`. `alocarProximoElegivel`
  no bean de domínio mantém só `@Transactional` + `@Version`; `@Retryable` vive no método do listener que
  o chama por injeção.
- **`reconciliarVagasDisponiveis` e `verificarPrazosVencidos` compartilham o mesmo job `@Scheduled`** e
  precisam iterar por município abrindo uma transação com `SET LOCAL app.current_tenant` por vez — nunca
  um role com `BYPASSRLS`. Mesma regra de self-invocation acima: a lógica por município fica num bean
  separado, injetado no job, nunca chamada via `this`. Ver
  `docs/arquitetura/notas-tecnicas.md#multi-tenancy-com-row-level-security-postgresql`.
- **Circuit Breaker (Resilience4j) na chamada de geocodificação** — não deixe uma instabilidade externa
  travar o processo de alocação de vaga; a chamada de geocodificação é side-channel, não bloqueante para
  a garantia central acima.
- **Evento de aplicação (`VagaLiberadaInternalEvent`) não é durável** — só memória do processo. É por
  isso que `reconciliarVagasDisponiveis` existe como rede de segurança independente: mesmo que o evento
  se perca (queda da aplicação entre commit e execução do listener), a próxima varredura do job encontra
  a turma com vaga livre e fila não vazia.
- **Grava na Outbox na mesma transação que cria a `Matricula`** (evento `MatriculaCriada`) e na mesma
  transação que libera a vaga (evento `VagaLiberada`) — ver
  `docs/arquitetura/notas-tecnicas.md#transactional-outbox-pattern`. Este módulo é o produtor da
  Outbox; o módulo Notificação + plataforma é quem opera o publicador.

## Aspecto técnico de destaque

Lock otimista (`@Version`) + retry automático (Spring Retry) + Circuit Breaker (Resilience4j) na
chamada de geocodificação.

## Fora de escopo deste módulo

Calcular o score de prioridade em si é do módulo Inscrição & Fila (ver
`docs/context/inscricao-fila.md`) — este módulo só consome o score recalculado no momento da alocação.
Publicar no RabbitMQ é do módulo Notificação + plataforma (ver
`docs/context/notificacao-plataforma.md`) — este módulo só grava na Outbox.

## Estado da implementação (escopo da ADR 0006)

| Endpoint | Permissão | O que faz |
|---|---|---|
| `POST /api/v1/matricula/creches` e `/turmas` | `matricula:creche:add`, `matricula:turma:add` | Cadastro; a turma grava `vaga.liberada` com as vagas iniciais |
| `POST /api/v1/matricula/vagas/alocar` | `matricula:vaga:alocar` | Aloca a vaga ao primeiro da fila (lock otimista + retry) |
| `POST /api/v1/matricula/matriculas/{id}/confirmar` | `matricula:matricula:confirmar` | `PENDENTE` → `CONFIRMADA` dentro do prazo |
| `POST /api/v1/matricula/matriculas/{id}/desistir` | `matricula:matricula:status` | `PENDENTE`/`CONFIRMADA` → `CANCELADA`, devolve a vaga e realoca automaticamente |
| `GET /api/v1/matricula/creches/proximas?latitude=&longitude=&faixaEtaria=&limite=` | `matricula:creche:list` | Turmas da faixa, da creche mais próxima para a mais distante (Haversine) |

**Sugestão de creches inclui turma sem vaga (decisão de produto).** `creches/proximas` devolve também
turmas com `vagasDisponiveis = 0`, de propósito: a família ainda pode entrar na fila daquela turma. O
campo `vagasDisponiveis` vai na resposta para o cliente distinguir. Creche sem latitude/longitude fica de
fora. O contrato está fixado em `MatriculaControllerIT`.

**Alocação automática ao liberar vaga.** A desistência (`DesistenciaMatriculaTransacional`) cancela a
matrícula, devolve a vaga com o mesmo `@Version` da alocação e grava `vaga.liberada` e
`matricula.atualizada` (status `CANCELADA`) na Outbox, tudo numa transação. Ela publica
`VagaLiberadaInternalEvent`; `AlocacaoAutomaticaListener` (`@TransactionalEventListener(AFTER_COMMIT)`,
bean próprio) chama `AlocacaoVagaService.alocarProximoElegivel` por injeção. Por rodar depois do
commit, `AlocacaoVagaTransacional.executar` usa `REQUIRES_NEW`: com `REQUIRED` a alocação se juntaria à
transação já confirmada e nada seria gravado. Uma falha da realocação é só registrada em log e não
desfaz a desistência.

**Job de manutenção (expiração + reconciliação).** `ManutencaoMatriculaScheduler` roda a cada
`alocacreche.matricula.manutencao.intervalo-ms` (padrão 2 minutos, o SLA da ADR 0001, decisão 19) e
percorre os municípios um por vez. Para cada um, `ManutencaoMatriculaPorMunicipio` executa dois passos:
1. `verificarPrazosVencidos`: cada matrícula `PENDENTE` com prazo vencido vira `EXPIRADA`, numa
   transação própria (`ExpiracaoMatriculaTransacional`), com a vaga devolvida e os eventos gravados.
2. `reconciliarVagasDisponiveis`: toda turma com vaga livre e fila não vazia é preenchida até acabar a
   vaga ou a fila. Isso cobre as vagas que a expiração acabou de devolver e as que a realocação
   automática perdeu.

O job não tem token. Cada transação aplica o `SET LOCAL` do município que recebe, sem `BYPASSRLS`, e a
alocação usa `AlocacaoVagaService.alocarProximoElegivelNoMunicipio`. Expiração e confirmação travam a
mesma linha da matrícula, então nunca acontecem juntas. Desliga-se com
`alocacreche.matricula.manutencao.habilitado=false`, como fazem os testes.

**TODO (ADR 0004/0006):** Circuit Breaker na geocodificação. Hoje a distância usa coordenadas já
cadastradas, sem chamada externa.

**Contrato com MSRelatorios:** `StatusMatricula.CANCELADA` existe nos dois módulos e o `schema.sql` do relatórios recria o CHECK da coluna
`status` (o `ddl-auto=update` não o altera num banco já criado); uma matrícula
cancelada continua contando no total da taxa de confirmação, mas não como confirmada.

## Ajustes na entrada da fila (pacote inscrição)

- **Posição na fila:** `registrarInscricao` serializa a fila da turma com `TravaFilaTurma` (advisory lock
  de transação, o mesmo da alocação) e renumera por prioridade (score desc, data, id). Não se usa
  `PESSIMISTIC_WRITE` na `Turma` (conflitaria com o `@Version` da alocação) nem unicidade
  `(turma_id, posicao)` em `FilaEspera` (a renumeração passa por posições duplicadas dentro da
  transação). Ver `docs/context/inscricao-fila.md`.
- **Dupla inscrição:** uma criança só pode ter uma inscrição em `AGUARDANDO`, `CONVOCADA` ou
  `MATRICULADA`. A checagem no serviço responde 409; o índice parcial `uk_inscricao_crianca_ativa`
  (em `schema.sql`) barra a requisição simultânea que passou pela checagem. Depois de `CANCELADA` ou
  `EXPIRADA`, a criança pode se inscrever de novo. O índice vive em `schema.sql` (idempotente, roda
  depois do `ddl-auto=update`, tanto no banco vazio quanto nas subidas seguintes); num banco antigo com
  duas inscrições ativas da mesma criança a criação do índice falha na subida e os duplicados precisam
  ser resolvidos antes.
