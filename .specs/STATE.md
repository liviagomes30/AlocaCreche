# STATE

## Decisions

### AD-001
- **Decision**: Consumidores RabbitMQ neste projeto usam o interceptor de retry do Spring Retry (stateless, 3 tentativas) com `RejectAndDontRequeueRecoverer`, mais uma dead-letter queue nativa do RabbitMQ (DLX declarado na fila principal). Exceções de payload inválido (ex.: `EventoInvalidoException`) **e de conversão de mensagem** (`org.springframework.amqp.support.converter.MessageConversionException`) ficam fora da política de retry — rejeição imediata, sem reentrega.
- **Reason**: Reusa `spring-retry`, já dependência do projeto (para `@Retryable` no módulo Matrícula); é o padrão documentado do Spring AMQP para "N tentativas, depois dead-letter", sem precisar de lógica de contagem manual.
- **Trade-off**: Acopla a política de retry à configuração do listener container (`RabbitMqConfig`), não ao código de negócio — quem adicionar um novo consumidor precisa replicar essa config, não só o `@RabbitListener`. **Atualizado após revisão independente de código**: o mapa agora classifica pela classe-base `EventoDominioInvalidoException` (não mais pela concreta `EventoInvalidoException`) — um novo consumidor só precisa lançar uma subclasse dessa base para herdar "rejeição imediata", sem editar `RabbitMqConfig`. Confirmado via bytecode (`SubclassClassifier.classify` caminha por `getSuperclass()`) e por teste unitário decisivo (`RabbitMqConfigRetryClassificationTest`) que usa a policy de produção real, não uma reconstrução do mapa.
- **Scope**: Qualquer consumidor RabbitMQ futuro em `alocacreche-relatorios` ou `alocacreche-core` (ex.: consumidor de `MatriculaCriada`, consumidor de notificações no módulo Notificação).
- **Date**: 2026-09-12
- **Status**: active
- **Gotcha descoberto na implementação (T6 de `relatorios-ocupacao-vaga-liberada`)**: usar
  `org.springframework.amqp.rabbit.config.RetryInterceptorBuilder` (especifico do Spring AMQP),
  **não** `org.springframework.retry.interceptor.RetryInterceptorBuilder` (generico do Spring
  Retry) — o generico nao aceita `MessageRecoverer` (ex.: `RejectAndDontRequeueRecoverer`), so
  `MethodInvocationRecoverer`, e nao compila. Alem disso, `RabbitAdmin` **não** declara
  exchanges/filas/bindings no `ContextRefreshedEvent` como a documentação sugere — a declaração só
  acontece quando o `ConnectionFactory` cria sua primeira conexão real (via `ConnectionListener`),
  o que só acontece quando algo de fato usa o broker (um `@RabbitListener` ativo, ou uso do
  `RabbitTemplate`). Um `@SpringBootTest` que só sobe o contexto sem nenhum listener **não prova
  nada** sobre a topologia estar declarada no broker — só o teste de integração ponta a ponta (T8,
  com um listener real) verifica isso de fato.
- **Gotcha descoberto na implementação (T8)**: JSON malformado falha na **conversão** da mensagem
  (`Jackson2JsonMessageConverter`), **antes** do método do `@RabbitListener` ser invocado — ou
  seja, antes de qualquer validação de negócio (`EventoInvalidoException`) rodar. Classificar só
  `EventoInvalidoException` como não-retryable não cobre esse caso: JSON malformado cairia na
  política de retry genérica (3 tentativas) antes de ir pra DLQ, violando o requisito de rejeição
  imediata. A correção é classificar `MessageConversionException` como não-retryable também.
  Verificado com um teste que publica bytes de JSON inválido diretamente e confirma que a mensagem
  aparece na DLQ sem nunca aparecer na fila principal.
- **Gotcha crítico descoberto na rodada 2 do Verificador independente**: a classificação
  `retryableExceptions.put(EventoInvalidoException.class, false)` **não tinha efeito nenhum em
  produção** — `EventoInvalidoException` era retentada 3 vezes como qualquer outra falha, apesar
  do mapeamento explícito. Causa raiz: uma entrada catch-all `Exception.class -> true` no mesmo
  mapa fazia o `BinaryExceptionClassifier` (`traverseCauses=true`) casar o wrapper externo
  (`ListenerExecutionFailedException`, que **é** uma `Exception`) com essa entrada **antes** de
  sequer olhar a causa real — a entrada específica nunca era alcançada. Correção: usar o
  construtor de 4 argumentos `SimpleRetryPolicy(maxAttempts, map, traverseCauses, defaultValue)`
  com `defaultValue=true`, e **nunca** incluir uma entrada catch-all `Exception.class` no mapa
  (ela sempre vai vencer contra qualquer classificação mais específica de uma causa aninhada).
  **Como foi descoberto**: um teste fim a fim que checava só "mensagem foi pra DLQ" e "repositório
  nunca foi chamado" passava de qualquer forma, porque essas duas condições são idênticas com 1
  tentativa (correto) ou com 3 tentativas (bug) — só verificar a **contagem de invocações do
  listener** (via `@MockitoSpyBean` no próprio `VagaLiberadaListener`, não só no repositório)
  expôs a diferença. Lição: para provar "0 retries" (não "0 efeitos colaterais"), o sinal certo é
  contagem de invocação do próprio componente que decide reentregar, não o efeito colateral que
  ele produziria de qualquer forma.
- **TODO conhecido, adiado deliberadamente (achado na rodada 3, final, do Verificador independente)**:
  a mesma lacuna corrigida acima para `EventoInvalidoException` **continua aberta** para
  `MessageConversionException` (JSON malformado, sub-caso "não puder ser desserializado" de
  OCUP-06) — `payloadMalformadoVaiDiretoParaDlqSemReentrega` só prova "mensagem acabou na DLQ", que
  é verdade tanto com rejeição imediata quanto com 3 tentativas antes da DLQ. O
  `@MockitoSpyBean` no `VagaLiberadaListener` não detecta isso porque `MessageConversionException`
  acontece na conversão da mensagem, antes do listener ser invocado — mostra 0 chamadas nos dois
  casos. Correção sugerida (não aplicada): `@MockitoSpyBean` no `MessageConverter` +
  `verify(messageConverter, times(1)).fromMessage(...)`. Decisão do usuário: registrar como TODO em
  T8 (`tasks.md`) e não estender o loop de fix→re-verify além das 3 rodadas já usadas. Qualquer
  consumidor futuro que reusar este padrão de retry/DLQ deve tratar cada entrada não-retryable do
  mapa como precisando do seu próprio sinal decisivo — não presumir que uma classificação vizinha
  já corrigida cobre as demais.
- **Nuance de OCUP-08/09 registrada após revisão independente de código**: a spec descreve "nack com
  requeue" — a implementação real usa retry local (Spring Retry) dentro do mesmo processamento de
  mensagem, sem nack-requeue real ao broker entre as tentativas; só ao final das 3 tentativas há um
  único reject de fato (`RejectAndDontRequeueRecoverer`). Equivalente no caminho feliz, mas com uma
  consequência: se o processo cair no meio das 3 tentativas locais, a mensagem nunca foi
  efetivamente "nacked" — fica "unacked" no broker, é redistribuída após reconexão, e a contagem de
  3 tentativas **reinicia do zero no novo processo**. Ou seja, "no máximo 3 tentativas" é garantido
  por processo, não globalmente por mensagem. Aceito como trade-off do padrão (não é um bug), mas
  registrado aqui para não ser lido como uma garantia mais forte do que realmente é.

### AD-003
- **Decision**: Testes de integração neste projeto usam o sufixo `*IT.java` (não `*Test.java`) e todo módulo Maven que os tiver **precisa** declarar `maven-failsafe-plugin` com as execuções padrão (`integration-test` + `verify`) no seu `pom.xml`. O gate "Full"/"Build" de qualquer feature roda `mvn verify`, nunca `mvn test` sozinho.
- **Reason**: Surefire (goal `test`) só casa com `**/Test*.java`, `**/*Test.java`, `**/*Tests.java`, `**/*TestCase.java` — arquivos `*IT.java` são a convenção do Failsafe, não do Surefire. Sem o plugin, `mvn test`/`mvn install` reporta sucesso (exit 0) sem executar nenhuma classe `*IT`, sem warning nenhum.
- **Trade-off**: Mais um plugin para configurar em cada módulo novo que tiver testes de integração; fácil de esquecer ao criar um módulo do zero (foi esquecido neste mesmo módulo — ver gotcha abaixo).
- **Scope**: Qualquer módulo Maven futuro (`alocacreche-core`, `alocacreche-gateway`) que adicionar uma classe `*IT.java`.
- **Date**: 2026-09-12
- **Status**: active
- **Gotcha descoberto pelo Verifier independente (feature `relatorios-ocupacao-vaga-liberada`)**: as 8 tarefas foram implementadas e cada uma verificada individualmente com `-Dtest=NomeDaClasse` — todos os testes passavam. Só o Verifier, rodando o comando de gate exatamente como documentado (`mvn -pl alocacreche-relatorios test`), descobriu que esse comando **nunca executava** `OcupacaoTurmaRepositoryIT` nem `VagaLiberadaConsumerIT` (8 dos 12 testes do módulo, cobrindo a maioria dos critérios de aceitação) — sem nenhum erro ou aviso, `BUILD SUCCESS` com `Tests run: 4`. Corrigido adicionando `maven-failsafe-plugin` e trocando o gate documentado de `test` para `verify`.

### AD-002
- **Decision**: Row-Level Security é provisionada via `schema.sql` idempotente (`ENABLE ROW LEVEL SECURITY` + `FORCE ROW LEVEL SECURITY` + `DROP POLICY IF EXISTS` + `CREATE POLICY`), com **`spring.sql.init.mode=always`** (não só `defer-datasource-initialization=true`), **e a aplicação conecta como uma role dedicada não-superuser** (`alocacreche_app`), nunca como o usuário bootstrap do container Postgres.
- **Reason**: Não há ferramenta de migration (Flyway/Liquibase) adotada ainda (ver ADR 0002) — esta é a menor adição possível para ter RLS funcionando sem introduzir uma ferramenta nova fora do escopo da tarefa atual.
- **Trade-off**: `schema.sql` roda a cada restart da aplicação; overhead pequeno agora, mas não escala bem e precisa ser substituído quando uma ferramenta de migration for adotada. A role dedicada exige um script de init no `docker-compose.yml` (e um `withInitScript` equivalente em qualquer teste com Testcontainers).
- **Scope**: Qualquer entidade multi-tenant futura em `alocacreche-core` ou `alocacreche-relatorios` que precise de RLS, até que Flyway/Liquibase seja adotado (nesse ponto, `AD-002` deve ser superseded).
- **Date**: 2026-09-12
- **Status**: active
- **Gotchas descobertos na implementação (T4/T5 de `relatorios-ocupacao-vaga-liberada`)** — os três juntos, na ordem em que se manifestam:
  1. `spring.jpa.defer-datasource-initialization=true` sozinho **não** é suficiente: `schema.sql` só roda automaticamente por padrão para banco embutido (`spring.sql.init.mode` default = `embedded`). Contra um Postgres real, é preciso `spring.sql.init.mode: always` explícito, ou `schema.sql` é silenciosamente ignorado — sem erro, sem log de aviso.
  2. Sem `FORCE ROW LEVEL SECURITY`, a policy não tem efeito nas queries da própria aplicação, porque por padrão o Postgres isenta o dono da tabela da RLS, e a aplicação conecta com a mesma role que o Hibernate usa para criar a tabela.
  3. **Mesmo com `FORCE ROW LEVEL SECURITY`, a policy continua sem efeito nenhum se a role de conexão for superuser** — superusers ignoram RLS incondicionalmente, e `FORCE` não consegue mudar isso. O usuário bootstrap de um container Postgres oficial (`POSTGRES_USER`) **é sempre criado como superuser** pela imagem, então conectar como ele (mesmo com `FORCE` no lugar) deixa a RLS completamente inerte. A correção real é conectar como uma role dedicada `NOSUPERUSER NOBYPASSRLS` (criada via script de init do banco/Testcontainers) — nunca como o usuário bootstrap. Sem essa role dedicada, os gotchas 1 e 2 podem estar 100% corrigidos e a RLS ainda não protege nada.
  4. Nenhum destes três é detectado pelo teste de contexto padrão (`@SpringBootTest` só verifica que a aplicação sobe) — só um teste que efetivamente tenta ler/escrever como dois tenants diferentes e verifica o resultado prova que a RLS está ativa. Verificação de schema (`pg_policies`, `relforcerowsecurity`) sozinha também não basta — prova que a policy existe, não que ela é aplicada à role de conexão real.

## Handoff

- **Feature**: relatorios-leitura-ocupacao
- **Phase / Task**: Execute concluído — T1-T4 implementadas, commitadas, Verificador independente
  rodou 1 vez e retornou PASS (sem precisar de fix→re-verify).
- **Completed**: T1 (DTO `OcupacaoTurmaDTO`), T2 (`OcupacaoTurmaRepository.buscarPorFiltro`), T3
  (`OcupacaoConsultaController` — `GET /api/v1/relatorios/ocupacao`, primeiro endpoint REST do
  projeto), T4 (`docs/adr/0003-prefixo-rest-api.md`). Commits: `27b0ddd`, `a8e6268`, `a13d4f9`,
  `c1c9681`. Gate final: `mvn -pl alocacreche-relatorios verify` → 32 testes (7 unitários + 25
  integração), `BUILD SUCCESS`. Verificador: PASS, 7/7 requisitos (RELOC-01 a 07) verificados, sensor
  de discriminação 3/3 mutantes mortos, 3 gaps de precisão de spec não-bloqueantes (RELOC-03/04/06 -
  registrados como lições L-007/008/009 candidatas, não corrigidos por não serem bloqueantes).
  Autenticação (`@SaCheckPermission`) deliberadamente fora de escopo - TODO documentado em spec.md
  (não existe modelo de usuário/permissão em nenhum módulo do projeto ainda).
- **In-progress**: nenhum — feature fechada, PASS na primeira rodada de verificação.
- **Next step**: nenhum próximo passo automático desta feature. Pendências do roadmap maior
  (Epic 2 - painel público cacheado; Epic 3 - novos consumidores de evento, bloqueado por
  `alocacreche-core` ainda não ter `Matricula`/`Inscricao`) seguem como próximas features candidatas.
- **Blockers**: none
- **Uncommitted files**: `.specs/LESSONS.md`, `.specs/lessons.json` (lições L-007/008/009 do
  Verificador), `.specs/features/relatorios-leitura-ocupacao/validation.md`, `docs/mock-data/`
  (mocks gerados em conversa anterior, ainda sem commit)
- **Branch**: main
