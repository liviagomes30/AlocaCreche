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
- A posição é o número de entrada na fila (não é renumerada quando alguém é alocado), igual no Redis e
  no banco.

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
