# Contexto — MSRelatorios

> Leia este arquivo antes de implementar ou revisar código em `alocacreche-relatorios/`. Decisões
> arquiteturais: `docs/adr/0001-fundacao-arquitetural.md`.

> **Enquanto a equipe for de 3 integrantes** (`docs/adr/0004-divisao-equipe-tres-integrantes.md`): além
> do que está descrito abaixo, esta integrante também é dona do **Publicador da Outbox**
> (`core/outbox` → RabbitMQ), do **Gateway** e da **autenticação básica** (Sa-Token) — mecânica de cada
> peça em `docs/context/notificacao-plataforma.md` e as garantias implementadas em
> `docs/adr/0005-seguranca-stateless-e-outbox-confiavel.md`. O contrato dos três eventos
> (`VagaLiberadaEvent`, `FilaEsperaAtualizadaEvent`, `MatriculaAtualizadaEvent`) é fixado neste módulo e
> já é gravado de verdade pelas Stacks A e B via `OutboxEventWriter`.
>
> **Escopo reduzido** (`docs/adr/0006-escopo-reduzido-da-proposta.md`): a Stack C entrega o Publicador
> Outbox → RabbitMQ, o painel público de ocupação e os relatórios de fila de espera e confirmação de
> matrícula. `GeolocalizacaoService` e a geocodificação externa estão fora do escopo.

## Este é o único serviço realmente separado do grupo

Deploy e banco próprios — a única fronteira do sistema onde perder ou atrasar um evento não compromete
uma regra de negócio, o que o torna o único lugar do sistema onde vale pagar o custo operacional de
separar processo e banco (ver ADR 0001, decisão 2). Não trate este módulo como precedente para
decompor o núcleo transacional (Inscrição/Matrícula/Notificação) em outros serviços — essa decisão foi
deliberadamente restrita a este caso.

## Responsabilidades

- Painel público de consulta (`consultarPainelPublico`, cacheado).
- Relatórios gerenciais: ocupação, fila de espera, distância média de alocação, taxa de confirmação de
  matrícula.
- Consumo assíncrono dos eventos de domínio publicados pelo monólito (via RabbitMQ, alimentado pela
  Outbox do monólito).

## Regras específicas deste módulo

- **Upsert por chave de negócio, nunca contador incremental.** O Outbox do monólito entrega
  at-least-once, então o mesmo evento pode chegar mais de uma vez. `UPDATE projecao SET
  vagas_disponiveis = :novoValor WHERE turma_id = :id` é seguro para reprocessar. `vagas_disponiveis =
  vagas_disponiveis - 1` não é — uma duplicata infla os números. Ver
  `docs/arquitetura/notas-tecnicas.md#transactional-outbox-pattern`.
- **Ordenação de duplicatas/fora de ordem**: use o id auto-incremento da própria Outbox como sequência
  global no payload do evento; só aplique o upsert se a sequência recebida for maior que a última
  aplicada para aquela chave de negócio. Resolve duplicata e ordem sem uma tabela extra de deduplicação.
- **`municipioId` é obrigatório e explícito em todo payload de evento consumido** — diferente do
  monólito (que resolve `municipioId` do contexto de segurança/JWT), este serviço roda em processo e
  banco separados e não tem esse contexto de sessão.
- **Painel público e relatórios são escopados obrigatoriamente por município** — nome de bairro sozinho
  é ambíguo entre municípios diferentes (ex.: "Centro" existe em milhares de cidades); `municipioId` é
  campo obrigatório em `FiltroRelatorioDTO`.
- **Mascarar CPF, renda e critério de vulnerabilidade social** em qualquer relatório ou painel (LGPD).

## Autenticação e permissões

Os endpoints de `alocacreche-relatorios` validam sozinhos o JWT emitido pelo login do
`alocacreche-core` (`POST /api/v1/auth/login`), com o mesmo segredo (`sa-token.jwt-secret-key`). O
Gateway só roteia, não valida token.

- **Token**: cabeçalho `Authorization: Bearer <jwt>`, validade de 8 horas (`sa-token.timeout`),
  modo stateless (nenhum estado de sessão no servidor).
- **Claims lidas por este módulo**: `municipioId` (número) e `perms` (lista de permissões). A lista pode
  conter o curinga `*`, que libera qualquer endpoint protegido.
- **Nega por padrão**: o `SaInterceptor` do módulo exige login em `/**`, sem nenhum
  `excludePathPatterns` (proibido pelo `CLAUDE.md`). Endpoint sem anotação responde 401 sem token.
  Exceção explícita e única: o `PainelPublicoController` usa `@SaIgnore` e continua público.
  Há ainda uma exceção de leitura, por método do interceptor (não por `excludePathPatterns`): `GET` da
  documentação do Swagger (`/swagger-ui.html`, `/swagger-ui/**`, `/v3/api-docs/**`), só quando
  `alocacreche.swagger.habilitado` está ligado (desligado por padrão em prod). Ver
  `docs/adr/0008-swagger-openapi-para-documentar-e-testar-a-api.md`.
- **Permissões** (`modulo:funcionalidade:operacao`): `relatorios:ocupacao:list` (ocupação),
  `relatorios:fila:list` (fila de espera) e `relatorios:matricula:list` (distância média e taxa de
  confirmação).
- **Isolamento por município**: todo controller protegido chama `MunicipioDoToken.exigir(municipioId)`
  antes de `TenantContext.aplicar` e de qualquer consulta; `municipioId` pedido diferente da claim, ou
  claim ausente, responde 403 sem tocar o banco.
- **Respostas**: 401 (sem token, malformado, assinatura inválida ou expirado) e 403 (sem permissão ou
  município diferente) trazem corpo JSON `{"erro": ...}`.
- **Guarda de regressão**: `ControllersProtegidosTest` falha se um método de controller não tiver
  `@SaCheckPermission` nem `@SaIgnore`, ou se o interceptor declarar caminho de exclusão.
- **Redespacho de erro**: o interceptor ignora o redespacho interno de erro do container
  (`DispatcherType.ERROR`), onde o contexto do Sa-Token não existe; sem isso o corpo JSON de erro do
  Spring (400, 404) virava HTML. Uma chamada direta a `/error` continua exigindo login.

## Pendências adiadas (TODO)

- **Limite de login por instância.** O bloqueio de 5 falhas em 15 minutos (429) vale por instância do
  core, porque o contador fica em memória. Com várias instâncias, mover o contador para o Redis.
- **Segredo JWT.** No perfil `prod` a aplicação não sobe com o valor padrão do `application.yml` nem
  com menos de 32 caracteres (`SA_TOKEN_JWT_SECRET`); fora de `prod` o padrão de desenvolvimento
  continua valendo.
- **TODO — reenvio de eventos devolvidos pelo broker.** Sem backoff nem teto por ciclo; exige coluna
  de tentativas na `outbox` (ver `OutboxPublicadorScheduler`).

## Aspecto técnico de destaque

Mensageria + eventual consistency + CQRS + cache (Redis).
