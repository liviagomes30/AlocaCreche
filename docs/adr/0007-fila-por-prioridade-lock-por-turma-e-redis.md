# ADR 0007 — Fila por prioridade, lock por turma e Redis como cache de posição

> Status: aceito
> Detalha, para a fila de espera, decisões da ADR 0001 (monólito modular, lock otimista na alocação,
> Redis como cache), da ADR 0005 (token, permissão por endpoint, RLS) e da ADR 0006 (escopo reduzido:
> score com pesos fixos e fator de distância 0). Não cria fronteira de rede nem mecanismo distribuído novo.

## Contexto

A fila de espera de cada turma precisa responder duas perguntas com custo baixo: quem é o próximo a
receber a vaga e em que posição cada inscrição está agora. Três riscos concretos apareceram na
implementação:

- duas inscrições (ou uma inscrição e uma alocação) simultâneas na mesma turma, sob `READ COMMITTED`, não
  enxergam a linha uma da outra e podem gravar posições duplicadas ou convocar fora da ordem do score;
- a posição lida pelo cidadão não pode ficar defasada depois que a vaga é alocada: a segunda da fila precisa
  virar primeira na hora, e não só na próxima inscrição;
- o Redis acelera a leitura, mas não pode ser a fonte da verdade nem derrubar a operação de negócio quando
  estiver fora do ar.

## Decisões

### 1. Fila ordenada por score, renumerada a cada inscrição e a cada alocação

- A ordem é `score` decrescente, `dataInscricao` crescente e `id` crescente (consulta
  `FilaEsperaRepository.findByTurmaIdOrdenadaPorPrioridade`). O score é calculado na inscrição com os pesos
  do município; pela ADR 0006 o fator de distância é 0 enquanto não houver cálculo de distância na inscrição.
- `RenumeracaoFila.renumerar(municipioId, turmaId)` é o único ponto que atribui as posições `1..N`. Ele é
  chamado pelo enfileiramento (`InscricaoService`) e pela alocação (`AlocacaoVagaTransacional`, depois de
  deletar a inscrição convocada, na mesma transação). Exige transação ativa (`MANDATORY`) e o lock da turma
  já tomado pelo chamador.
- A alocação convoca a menor posição (`findFirstByTurmaIdOrderByPosicaoAsc`). Desistência e expiração só
  mudam o status da `Inscricao`: a entrada na fila já havia saído na alocação. A vaga devolvida dispara uma
  nova alocação, que renumera pelo mesmo caminho.

### 2. Lock consultivo por turma (`pg_advisory_xact_lock`)

- `TravaFilaTurma.travar(municipioId, turmaId)` toma `pg_advisory_xact_lock(namespace_fila, hash(turmaId))`
  no início de toda escrita na fila (inscrição e alocação), depois do `TenantContext.aplicar`. É solto no
  commit ou rollback e só serializa a mesma turma; turmas diferentes não se bloqueiam. Exige transação ativa.
- **Por que não `PESSIMISTIC_WRITE` na `Turma`:** o lock de linha disputaria a mesma linha que o `@Version`
  (lock otimista) usa na alocação e na confirmação de matrícula, e toda inscrição passaria a competir com o
  fluxo de vagas. O advisory lock não toca linha alguma. O `@Version` da `Turma` continua sendo a garantia
  contra dupla matrícula na mesma vaga (ADR 0001); o lock da fila só ordena quem lê e renumera a fila.
- **Por que não unique `(turma, posição)`:** a renumeração em lote troca posições entre linhas dentro de uma
  mesma transação, e a restrição unique (verificada por linha, não adiada) falharia no meio do `UPDATE` em
  lote. A unicidade das posições é garantida pelo lock somado à renumeração completa, e provada por teste de
  concorrência.

### 3. Redis como cache de leitura da posição

- Um ZSET por município e turma, `fila:{municipioId}:turma:{turmaId}`, membro = `inscricaoId`, score =
  posição. A chave carrega o município para não misturar tenants.
- Cada transação que muda a fila publica **um único snapshot** com a fila inteira da turma (só quem continua
  nela: a alocada não entra, então some do ZSET). `RenumeracaoFila` o gera e `FilaRedisService.publicarSnapshotAposCommit`
  o registra para depois do commit (`TransactionSynchronization.afterCommit`): rollback não deixa entrada
  fantasma. É fail-open: falha do Redis vira log de aviso, sem dados sensíveis, e a operação de negócio segue.
- **Escrita versionada (descarte de snapshot atrasado).** O lock da turma é solto no commit e a escrita no Redis
  acontece depois, fora dele; dois snapshots consecutivos da mesma turma poderiam chegar ao Redis fora de ordem
  e deixar o ZSET diferente do banco (o teste de concorrência com Redis real falhava em cerca de metade das
  execuções). Por isso cada snapshot carrega uma versão obtida **dentro da transação, sob o lock da turma**
  (`VersaoFila.proxima()`, `select nextval('fila_versao_seq')`, sequence criada de forma idempotente em
  `schema.sql`): para a mesma turma, a ordem das versões é a ordem dos commits. O snapshot é aplicado por um
  script Lua atômico que só age se `versao > versaoAtual`, guardada na chave irmã
  `fila:{municipioId}:turma:{turmaId}:versao`, e então apaga e recria o ZSET inteiro. Um snapshot que chega
  atrasado é descartado.
- **TTL nas duas chaves** (`alocacreche.fila.redis.ttl`, padrão 5 minutos), renovado a cada snapshot, em linha
  com a invalidação por TTL da ADR 0001. A chave expirada cai para o banco (fallback abaixo).
- Timeouts curtos de conexão e de comando (`spring.data.redis.connect-timeout` e `timeout`, 500 ms por
  padrão), para que Redis fora do ar não prenda a requisição.
- **Fallback ao banco:** a consulta lê do Redis e, na falta da chave ou do membro (cache frio, expirado, Redis
  fora na leitura), usa `FilaEspera.posicao`, que é a fonte da verdade.

### 4. Consulta de posição com permissão e tenant

- `GET /api/v1/inscricao/inscricoes/{inscricaoId}/posicao?turmaId=` exige `inscricao:fila:detail`. O município
  vem do token, a transação aplica o tenant (RLS) e uma inscrição de outro município é invisível (404).
  Inscrição que já saiu da fila (alocada, cancelada ou expirada) também responde 404.

### 5. Alocação automática ao liberar vaga e job de manutenção (resumo)

- Ao devolver uma vaga (desistência ou expiração), um evento interno pós-commit aciona a alocação do próximo
  da fila, sem token, com o município do evento. Esse evento não é durável: o job `@Scheduled` de manutenção
  (habilitável por configuração, intervalo padrão de 2 minutos) expira matrículas com prazo vencido e aloca
  turmas com vaga livre e fila não vazia, abrindo uma transação com `SET LOCAL` por município. Detalhes,
  retry da alocação e cuidados de demonstração em `docs/context/matricula-vagas.md`.

## Consequências

- A posição é exata no banco (mesma transação da alocação) e, com o Redis disponível, no Redis logo após o
  commit.
- **Limitação conhecida — custo O(n):** cada inscrição e cada alocação lê e regrava a fila inteira da turma,
  e o lock serializa as escritas da mesma turma. É aceitável para o tamanho esperado de fila por turma; se uma
  fila crescer a ponto de pesar, a evolução é deslocar só as posições afetadas.
- **Limitação conhecida — Redis fora do ar durante uma escrita:** se o Redis estiver indisponível quando o
  snapshot é publicado, a escrita é perdida (fail-open) e o ZSET guarda a posição anterior; quando o Redis
  volta, a API devolve essa posição velha até a próxima escrita na turma ou até o TTL das chaves expirar,
  quando a leitura passa a cair para o banco. **A defasagem máxima nesse caso é o TTL** (5 minutos por
  padrão); o banco é a fonte da verdade e nunca fica defasado. Reduzir o TTL reduz a janela ao custo de mais
  leituras no banco.
- **Limitação conhecida — distância:** o fator de distância do score é 0 (ADR 0006), então a priorização hoje
  não considera proximidade; a distância é calculada só na alocação e gravada na matrícula.
- As chaves do Redis expiram por TTL e são reescritas a cada snapshot; a chave ausente cai para o banco.
- Testes: `RenumeracaoFilaTest`, `TravaFilaTurmaTest`, `VersaoFilaTest`, `FilaRedisServiceTest`,
  `InscricaoServiceTest`, `AlocacaoVagaTransacionalTest`, `InscricaoFilaRedisIT` (alocar e desistir mantêm as
  posições `1..N` no banco, no Redis e na API; snapshot atrasado é descartado, o mais novo substitui e o vazio
  limpa; TTL nas chaves; desempate por data e id), `FilaRedisIndisponivelIT` (Redis pausado: a alocação segue e,
  expirado o TTL, a posição volta correta do banco) e `InscricaoFilaConcorrenciaIT` (inscrições e alocações
  simultâneas com o ZSET igual ao banco).
