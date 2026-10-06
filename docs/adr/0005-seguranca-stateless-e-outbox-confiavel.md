# ADR 0005 — Segurança stateless, Outbox confiável e invalidação de cache do painel

> Status: aceito
> Complementa `docs/adr/0001-fundacao-arquitetural.md` (Outbox e Gateway) e
> `docs/adr/0004-divisao-equipe-tres-integrantes.md` (Stack C opera Outbox, Gateway e autenticação).
> Não muda nenhuma decisão de fronteira de serviço: registra o contrato e as garantias implementadas.

## Contexto

A Stack C entregou o login, o Publicador da Outbox, o consumo em MSRelatorios e o cache do painel
público. Cada uma dessas peças é usada pelas outras duas stacks (A grava eventos de fila, B grava
eventos de vaga e matrícula, ambas protegem endpoints com o token), então o contrato precisa estar
registrado fora do código.

## Decisões

### 1. Contrato do token (Sa-Token stateless + JWT)

- Um único mecanismo de autenticação: Sa-Token em modo stateless (`StpLogicJwtForStateless`). O core
  emite o token em `POST /api/v1/auth/login`; core e MSRelatorios o validam sozinhos, com o mesmo
  segredo (`SA_TOKEN_JWT_SECRET`). O Gateway só roteia, não valida token.
- Transporte: `Authorization: Bearer <jwt>`, validade de 8 horas, sem cookie e sem estado de sessão.
- Claims: `municipioId` (número) e `perms` (lista no formato `modulo:funcionalidade:operacao`; o
  curinga `*` libera tudo e fica restrito ao perfil ADMIN). O perfil do usuário define as permissões;
  perfis e permissões são fixos no código (`PerfilUsuario`).
- O município vem sempre do token, nunca do corpo ou da URL: quando a requisição informa um
  `municipioId`, ele precisa ser igual à claim, senão 403 antes de qualquer leitura. O tenant é
  aplicado com `set_config('app.current_tenant', ?, true)` na transação (equivalente a `SET LOCAL`).
- Login: BCrypt, falha genérica idêntica para email inexistente, senha errada ou usuário inativo, com
  hash fictício para manter o tempo de resposta. Limite de 5 falhas por município e email em 15
  minutos, com 429 e `Retry-After` (contador em memória, por instância).
- No perfil `prod` a aplicação não sobe com o segredo padrão do repositório ou com menos de 32
  caracteres.

### 2. Nega por padrão

- O `SaInterceptor` exige login em `/**`, sem nenhum `excludePathPatterns` (proibido pelo CLAUDE.md).
  Endpoint sem anotação responde 401 sem token.
- Exceções explícitas, com `@SaIgnore`: o login e o painel público.
- O interceptor ignora o redespacho interno de erro do container (`DispatcherType.ERROR`), onde o
  contexto do Sa-Token não existe; sem isso o corpo JSON de erro virava HTML.
- Um teste de regressão falha se algum método de controller não tiver `@SaCheckPermission` nem
  `@SaIgnore`.

### 3. Outbox: escrita e publicação confiáveis

- **Escrita**: todo evento de domínio é gravado por `OutboxEventWriter`, com propagação `MANDATORY`,
  na mesma transação da mudança de negócio. Sem transação aberta a gravação falha, então não existe
  evento sem a mudança correspondente nem mudança sem evento. A serialização do payload fica em
  `OutboxPayloads`, no contrato consumido por MSRelatorios.
- **Publicação**: `OutboxPublicadorService` lê eventos pendentes em lotes de 100 e publica no
  exchange `alocacreche.dominio` em JSON, com o `eventId` injetado no payload. Um evento só é marcado
  como processado depois da confirmação do broker (publisher confirm, por `CorrelationData`). Mensagem
  devolvida por falta de rota (`mandatory`) ou confirmação negativa/em atraso mantém o evento
  PENDENTE para a próxima rodada. A entrega é at-least-once.
- **Consumo**: MSRelatorios faz upsert por chave de negócio e ignora evento com sequência menor ou
  igual à já aplicada, então duplicata e reordenação não alteram os números.
- **Operação**: o agendador pode ser desligado por `alocacreche.outbox.publicador.habilitado` (os
  testes o desligam); a purga diária apaga eventos processados após `retencao-dias` (7 por padrão).
- **Limite conhecido**: evento devolvido pelo broker é reenviado a cada ciclo, sem backoff nem teto de
  tentativas. Resolver exige uma coluna de tentativas na tabela `outbox` (esquema compartilhado) e
  fica como TODO em `OutboxPublicadorScheduler`.

### 4. Invalidação do cache do painel público

- O painel público é consultado em Redis e é eventualmente consistente: a projeção de ocupação em
  MSRelatorios é atualizada quando o consumidor processa o evento de vaga.
- O cache do painel do município é invalidado (`PainelPublicoCacheInvalidador`) **depois do commit**
  da projeção, nunca antes: invalidar antes permite que uma leitura concorrente repovoe o cache com o
  valor antigo. Se a invalidação falhar, o painel fica defasado até o TTL expirar; a falha é logada
  e não desfaz a projeção.
- Painel e relatórios são sempre escopados por município (`municipioId` obrigatório).

## Consequências

- Quem criar um novo endpoint precisa declarar `@SaCheckPermission` (ou `@SaIgnore` com justificativa
  em PR); o teste de regressão falha caso contrário.
- Quem emitir um novo evento deve usar `OutboxEventWriter` dentro de uma transação e registrar a
  routing key em `OutboxRabbitConfig.ROUTING_KEYS_CONHECIDAS`, e o consumo em MSRelatorios precisa
  de upsert idempotente.
- Com mais de uma instância do core, o limite de login passa a valer por instância; mover o contador
  para o Redis resolve, se isso virar requisito.
- Fora do escopo, registrado como limitação: rotação de segredo, refresh token, revogação de token
  antes do vencimento (consequência do modo stateless) e backoff do Outbox.
