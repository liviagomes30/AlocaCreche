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

- **Feature**: relatorios-matricula (distancia media de alocacao + taxa de confirmacao de matricula)
- **Status**: **CONCLUIDA E COMMITADA** (2026-09-23). Blocker de Docker Desktop resolvido -
  Docker Desktop foi iniciado neste ambiente, `mvn -pl alocacreche-relatorios verify` (Testcontainers
  Postgres/RabbitMQ/Redis) rodou de verdade: **BUILD SUCCESS, 74 testes de integracao, 0 falhas**
  (~3min37s). Revisao independente (author != verifier, skill `/code-review --level high` sobre
  `d095c30..HEAD`, 3 passes convergentes) **nao encontrou nenhum achado** - ver secao de commits
  abaixo.
- **Commits** (ordem): `1ff8f80` contrato do evento MatriculaAtualizada, `c5a6a6e` entidade JPA + RLS
  de `MatriculaRelatorio`, `80fdeb4` repositorio (upsert + agregacoes) + IT, `1b2fe1e` topologia
  RabbitMQ da fila de matricula, `a11ba82` listener + teste unitario, `6f78321` endpoints
  `GET /api/v1/relatorios/matricula/distancia-media` e `GET
  /api/v1/relatorios/matricula/taxa-confirmacao` + IT.
- **Phase / Task**: Execute, implementado diretamente sem passar pelas 4 fases do skill
  `tlc-spec-driven` (escopo pedido pelo usuario como "basico, sem exagerar" - ver justificativa
  abaixo).
- **Decisao de escopo tomada com o usuario (AskUserQuestion) antes de implementar**: (1) implementar
  os dois relatorios que ainda faltam da lista de responsabilidades de `docs/context/relatorios.md`
  (distancia media de alocacao, taxa de confirmacao de matricula); (2) definir agora um contrato de
  evento provisorio, antecipando o que o dono do modulo Matricula (`alocacreche-core`, ainda so
  `package-info.java`, nenhuma entidade implementada) vai publicar depois - mesmo padrao ja usado
  para `VagaLiberada`/`FilaEsperaAtualizada`.
- **Decisao de design**: um unico evento `MatriculaAtualizada` (granularidade por matricula, estado
  completo, nao delta - mesmo raciocinio de `FilaEsperaAtualizada`) alimenta uma unica projecao
  `MatriculaRelatorio`, da qual os dois relatorios leem via 2 queries agregadas diferentes -
  deliberadamente mais enxuto que duplicar o par evento/projecao/fila do zero para cada relatorio,
  para nao "fazer muito" (pedido explicito do usuario, lembrando que outras integrantes tambem
  precisam trabalhar no mesmo modulo).
- **Contrato provisorio, a alinhar com o dono de Matricula antes de considerar definitivo**: o campo
  `distanciaKm` do evento nao tem correspondencia no diagrama de classes atual (`Matricula` so tem
  id/dataConvocacao/prazoConfirmacao/status) - e a distancia calculada em `alocarProximoElegivel`
  (usada no score via `pesoDistancia`), que precisa estar no payload para este relatorio existir sem
  recalcular geocodificacao aqui. Ver javadoc de `MatriculaAtualizadaEvent`.
- **Completed (implementado, testado e commitado)**: pacote
  `org.alocacreche.relatorios.matricula` completo - `StatusMatricula`, `MatriculaAtualizadaEvent`,
  `MatriculaEventoInvalidoException` (subclasse de `EventoDominioInvalidoException`, herda a politica
  de retry do AD-001 sem editar `RabbitMqConfig`), `MatriculaRelatorio` (entidade/projecao),
  `MatriculaRelatorioRepository` (upsert idempotente + `calcularDistanciaMedia` +
  `calcularTaxaConfirmacao`), `MatriculaAtualizadaListener`, `RelatorioConfirmacaoProjection`,
  `DistanciaMediaAlocacaoDTO`, `RelatorioConfirmacaoMatriculaDTO`, `RelatorioMatriculaController`
  (`GET /api/v1/relatorios/matricula/distancia-media`, `GET
  /api/v1/relatorios/matricula/taxa-confirmacao`, ambos sem `@SaCheckPermission` - mesmo TODO
  documentado de `OcupacaoConsultaController`/`RelatorioFilaEsperaController`). `RabbitMqConfig`
  ganhou a fila/DLQ/binding de `MATRICULA_QUEUE` (mesma topologia das outras duas filas). `schema.sql`
  ganhou RLS de `matricula_relatorio` (mesmo padrao AD-002). `MatriculaAtualizadaListenerTest` (10
  testes), `MatriculaRelatorioRepositoryIT` e `RelatorioMatriculaControllerIT` todos verdes no gate
  completo (Testcontainers, mesmo padrao de
  `FilaEsperaInscricaoRepositoryIT`/`RelatorioFilaEsperaControllerIT`/`OcupacaoConsultaControllerIT`).
- **Deliberadamente fora do escopo desta rodada (TODO, nao esquecido)**: nenhum teste ponta a ponta
  via RabbitMQ real (`*ConsumerIT`, ao contrario de `FilaEsperaAtualizadaConsumerIT`) foi escrito para
  `MatriculaAtualizadaListener` - a topologia reusa o mesmo padrao ja provado por
  `VagaLiberadaConsumerIT`/`FilaEsperaAtualizadaConsumerIT` (fila+DLQ+binding via `RabbitMqConfig`,
  classificacao de retry pela classe-base `EventoDominioInvalidoException`), e o pedido explicito do
  usuario foi nao fazer mais do que o basico. Se este consumidor virar caminho critico (nao so
  leitura), escrever esse teste antes.
- **Next step (fora do escopo desta feature, registrado para retomar depois)**: alinhar o contrato
  provisorio de `MatriculaAtualizadaEvent` com quem for implementar o modulo Matricula antes de o
  considerar definitivo (nao e so um detalhe de implementacao - o dono real do evento pode querer
  campos diferentes); escrever o `*ConsumerIT` ponta a ponta deliberadamente adiado (ver acima) se
  este consumidor virar caminho critico; adicionar `sa-token-spring-boot3-starter`/`sa-token-jwt` ao
  `pom.xml` de `alocacreche-relatorios` (hoje so estao no `pom.xml` de `alocacreche-core`) antes de
  qualquer `@SaCheckPermission` real poder ser adicionado aos controllers deste modulo.
- **Blocker (RESOLVIDO em 2026-09-23)**: Docker Desktop nao estava rodando neste ambiente
  (`docker ps` falhava com `failed to connect to the docker API at
  npipe:////./pipe/dockerDesktopLinuxEngine`). Iniciado via `Start-Process 'Docker Desktop.exe'` e
  aguardado ficar pronto (~20s) - depois disso `mvn -pl alocacreche-relatorios verify` rodou
  normalmente, confirmando que o codigo em si nunca teve problema, so a infra local estava parada.
- **Branch**: main

---

- **Feature**: relatorios-fila-espera
- **Status**: **CONCLUIDA E COMMITADA** (2026-09-23) — Specify/Design/Tasks completos, 9 tarefas
  T1-T9 todas implementadas, testadas e commitadas.
- **Completed**: T1 `FilaEsperaAtualizadaEvent` (`7c117ab`), T2
  `FilaEsperaEventoInvalidoException` (`9e9ce14`), T3 `FilaEsperaInscricao` (`f76ce09`), T4 RLS via
  `schema.sql` (`5c18586`), T5 `FilaEsperaInscricaoRepository` upsert+agregação + IT (`f9cd783`), T6
  `RabbitMqConfig` topologia (`71d3bd7`), T7 `FilaEsperaAtualizadaListener` + teste unitário
  (`e1c4d30`), T8 teste ponta a ponta do consumidor (`f0cf879`), T9 endpoint
  `GET /api/v1/relatorios/fila-espera` (`007014d`) — bloqueada desde 15/09 por sandbox, destravada
  em 2026-09-23 (ver Blocker abaixo).
- **Blocker (RESOLVIDO em 2026-09-23)**: o sintoma original registrado aqui (`TestRestTemplate`
  falhando com `Unable to establish loopback connection` num sandbox Windows anterior) não se
  repetiu nesta sessão/ambiente — `RelatorioFilaEsperaControllerIT` (`@SpringBootTest
  webEnvironment=RANDOM_PORT` + `TestRestTemplate`) rodou normalmente como parte do gate completo
  (`mvn -pl alocacreche-relatorios verify`: BUILD SUCCESS, 74 testes de integração, 0 falhas). O
  bloqueio real que impedia rodar qualquer coisa nesta sessão era o Docker Desktop parado (ver
  blocker equivalente na feature `relatorios-matricula` acima) — depois de iniciado, o gate passou
  sem precisar de nenhuma mudança de código. Fica registrado que o sintoma específico de loopback
  socket documentado em 15/09 pode ter sido específico daquele sandbox pontual, não uma limitação
  permanente deste ambiente.
- **Verificação independente**: `/code-review --level high` sobre `d095c30..HEAD` (T9 + toda a
  feature relatorios-matricula), 3 passes convergentes, nenhum achado.
- **Branch**: main
