# Contexto — Módulo Inscrição & Fila

> Leia este arquivo antes de implementar ou revisar código em `alocacreche-core/.../inscricao/`.
> Modelo de domínio completo e diagrama de classes: `docs/arquitetura/visao-geral.md`. Decisões
> arquiteturais: `docs/adr/0001-fundacao-arquitetural.md`.

> **Enquanto a equipe for de 3 integrantes** (`docs/adr/0004-divisao-equipe-tres-integrantes.md`): esta
> integrante também acumula o domínio `Notificacao`/`Municipio` (`docs/context/notificacao-plataforma.md`,
> só a parte de domínio — Gateway/Outbox Publisher/segurança ficam com quem faz MSRelatorios).

## Dono exclusivo do pacote

`Inscricao`, `FilaEspera`, `Responsavel`, `Crianca`. Nenhum outro módulo edita estas entidades
diretamente.

## Responsabilidades

- Cadastro de inscrição (`registrarInscricao`).
- Motor de score de prioridade configurável por município (`calcularScorePrioridade`).
- Consulta de posição na fila em tempo real (`consultarPosicaoFila`).

## Endpoints (prefixo `/api/v1/inscricao`)

| Rota | Permissão | Resposta |
| --- | --- | --- |
| `POST /responsaveis` | `inscricao:responsavel:add` | 201 `{"responsavelId":N}` |
| `POST /criancas` | `inscricao:crianca:add` | 201 `{"criancaId":N}` |
| `POST /inscricoes` | `inscricao:inscricao:add` | 201 `{inscricaoId, posicao, scorePrioridade, status}` |
| `GET /inscricoes/{inscricaoId}/posicao?turmaId=` | `inscricao:fila:detail` | 200 `{inscricaoId, turmaId, posicao}` |

- Nenhuma resposta devolve a entidade JPA nem dado sensível (CPF, renda, vulnerabilidade).
- Erros: 400 (JSON com a lista de campos), 401 sem token, 403 sem permissão, 404 (responsável, criança,
  inscrição ou turma inexistente, **inclusive de outro município, via RLS**) e 409 (CPF já cadastrado
  em qualquer município, pois `responsavel.cpf` é único no banco inteiro).
- O município vem sempre do token; o tenant é aplicado (`TenantContext.aplicar`) antes de qualquer
  leitura. O cadastro inline de `POST /inscricoes` delega a `ResponsavelService`/`CriancaService`
  (`cadastrarNoMunicipio`), então a regra de CPF único existe em um único lugar. `ResponsavelDTO`
  aceita `endereco` opcional, mas a alocação exige latitude e longitude do responsável.

## Fila de espera no Redis

- Chave: ZSET `fila:{municipioId}:turma:{turmaId}`, membro = `inscricaoId`, score = posição. Os ids são
  globais, mas o município é a fronteira de isolamento, por isso entra na chave.
- **Redis é cache de leitura; a fonte da verdade é `fila_espera`.** A escrita (`adicionarAposCommit`) e a
  remoção na alocação (`removerAposCommit`, chamada por `AlocacaoVagaTransacional`) só acontecem
  **depois do commit**: rollback não deixa entrada fantasma. São fail-open: falha do Redis vira log de
  aviso (sem dados sensíveis) e nunca derruba a inscrição nem a alocação.
- Consulta de posição: lê do Redis; sem a chave (cache frio, expirado ou Redis fora) cai para
  `FilaEspera.posicao` no banco. Inscrição que já saiu da fila (alocada) responde 404 com mensagem clara.
- A posição é a ordem de prioridade na fila, igual no Redis e no banco (ver "Ordenação da fila").

## Ordenação da fila e lock por turma

- **Ordem:** a cada inscrição (`InscricaoService.enfileirar`) **e a cada alocação**
  (`AlocacaoVagaTransacional`, depois de deletar a inscrição convocada), a fila inteira da turma é
  renumerada `1..N` por `score` decrescente, `dataInscricao` crescente e `id` crescente. A lógica vive
  num único componente, `RenumeracaoFila.renumerar(municipioId, turmaId)` (exige transação ativa,
  `MANDATORY`, e o `TravaFilaTurma` já tomado pelo chamador): ordena, grava em lote e agenda a
  atualização das posições no ZSET do Redis para depois do commit. A alocação remove a convocada do
  ZSET (`removerAposCommit`) e renumera, então quem estava atrás sobe de posição em tempo real, no banco,
  no Redis e em `GET /posicao`. A alocação convoca a menor posição
  (`findFirstByTurmaIdOrderByPosicaoAsc`), então a posição errada significa convocar a pessoa errada.
- **Outros pontos que retiram alguém da fila:** nenhum. Desistência e expiração só mudam a
  `Inscricao` (`CANCELADA`/`EXPIRADA`) cuja entrada na fila já foi removida na alocação; a vaga
  devolvida dispara a próxima alocação (listener ou job), que renumera pelo caminho acima. Não há
  reconciliação que mexa em `fila_espera`.
- **Lock por turma (`TravaFilaTurma.travar`):** enfileirar e alocar tomam o mesmo
  `pg_advisory_xact_lock(namespace_fila, turmaId)` dentro da transação, depois do `TenantContext.aplicar`
  e antes de ler ou gravar a fila. Sem ele, duas inscrições simultâneas (READ COMMITTED) não enxergam a
  linha uma da outra e renumeram por conta própria, gerando posições duplicadas ou fora de ordem. O
  lock é solto no commit/rollback, exige transação ativa (`MANDATORY`) e só serializa a mesma turma:
  turmas diferentes não se bloqueiam.
- **Por que advisory lock e não `PESSIMISTIC_WRITE` na `Turma`:** o lock de linha disputaria a mesma
  linha usada pelo `@Version` da alocação e da confirmação de matrícula (módulo Matrícula). O advisory
  lock não toca linha nenhuma.
- **Limitação conhecida:** a renumeração é O(n) por inscrição e por alocação (lê e regrava a fila inteira da turma) e
  o lock serializa as inscrições da mesma turma; aceitável para o tamanho esperado de fila por turma.
  Se uma fila crescer a ponto de pesar, a evolução é inserir só o deslocamento afetado (posição da
  nova inscrição em diante) em vez de renumerar tudo.
- **Redis fora do lock:** a escrita no ZSET ocorre depois do commit, já sem o lock. Duas transações
  consecutivas na mesma turma podem, em janela de microssegundos, aplicar seus snapshots fora de
  ordem; o cache se corrige na próxima inscrição da turma e, na leitura, o banco continua sendo a
  fonte da verdade.
- Testes: `TravaFilaTurmaTest`, `RenumeracaoFilaTest`, `InscricaoServiceTest` (lock antes da fila),
  `AlocacaoVagaTransacionalTest` (renumera depois de deletar a convocada), `InscricaoFilaRedisIT`
  (alocar e desistir com posições 1..N no banco, no Redis e na API) e `InscricaoFilaConcorrenciaIT`
  (inscrições e alocações simultâneas com Postgres e Redis reais). Decisão registrada na ADR 0007.

## Regras específicas deste módulo

- **O score é configurável por município** (`ConfiguracaoPriorizacao`, Value Object embutido em
  `Municipio`): pesos de renda, vulnerabilidade, distância e irmão já matriculado. Implemente o cálculo
  como Strategy — não hardcode os pesos.
- **`Inscricao.scorePrioridade` persistido é cache de exibição, não fonte de verdade da alocação.** A
  alocação real (`alocarProximoElegivel`, no módulo Matrícula) sempre recalcula o score on-the-fly a
  partir dos fatores brutos e da `ConfiguracaoPriorizacao` vigente. Este módulo só atualiza o campo
  persistido e o cache Redis como efeito colateral de uma alocação já processada — não crie um job de
  recompute em lote dedicado para isso.
- **Cache do score no Redis é versionado**: chave `score:municipioId:versaoConfig:inscricaoId`. Quando
  `ConfiguracaoPriorizacao` muda, não invalide chaves explicitamente — incremente a versão da
  configuração; as chaves antigas saem de uso e expiram por TTL. Um pico pontual de recomputo por
  cache-miss após a mudança é esperado e aceitável (a configuração muda raramente).
- **`Inscricao` referencia `Municipio` diretamente**, não só transitivamente via `Creche`/`Turma` — a
  família se vincula ao município no momento da inscrição, antes de ser alocada a uma creche específica.
  Não remova essa referência direta ao refatorar.
- Multi-tenancy: toda query nesta camada precisa respeitar RLS + `@TenantId` — ver
  `docs/arquitetura/notas-tecnicas.md#multi-tenancy-com-row-level-security-postgresql` antes de
  escrever uma query nativa.

## Aspecto técnico de destaque

Regras de negócio configuráveis (padrão Strategy) + cache do score calculado (Redis, versionado).

## Fora de escopo deste módulo

Decrementar `vagasDisponiveis` e criar `Matricula` pertencem ao módulo Matrícula & Vagas/Creches (ver
`docs/context/matricula-vagas.md`) — este módulo só alimenta a fila e o score, nunca aloca a vaga.
