# ADR 0001 — Fundação arquitetural do AlocaCreche

> Status: aceito (proposta inicial do Workshop de Práticas — Arquitetura de Software, 2026)
> Este arquivo registra as decisões da fundação do projeto. Decisões novas ou que revisem alguma destas
> devem ganhar seu próprio arquivo `docs/adr/NNNN-titulo-curto.md`, referenciando o número desta ADR
> quando a substituírem ou complementarem.

## Contexto

Município(s) alocam vagas em creches públicas manualmente, criando quatro problemas de arquitetura:
concorrência na alocação de vaga, ausência de reação em tempo real à liberação de vaga, cálculo
aproximado de proximidade residência–creche, e necessidade de isolar dados sensíveis entre diferentes
prefeituras atendidas pela mesma plataforma (multi-tenant).

## Decisões

1. **Monólito modular no núcleo do domínio, não microsserviços por padrão.** Inscrição, Fila, Matrícula
   e Vagas/Creches compartilham processo e banco porque formam um único domínio transacional (evitar
   dupla matrícula na mesma vaga). Decompor isso em serviços de rede criaria um problema de consistência
   distribuída (saga, outbox de compensação) que não existe com banco único — complexidade sem
   benefício real na escala do estudo de caso (15 creches).

2. **Único ponto real de decomposição em microsserviço: MSRelatorios.** Tolera atraso (eventual
   consistency) e tem padrão de leitura/escala diferente do núcleo transacional. É o único lugar do
   sistema onde perder ou atrasar um evento não compromete uma regra de negócio — o único lugar onde
   vale pagar o custo operacional de separar processo e banco.

3. **Evento de aplicação em processo não é durável — mitigado por reconciliação periódica.** A reação a
   `VagaLiberadaInternalEvent` usa `Spring ApplicationEventPublisher` (memória do processo); se a
   aplicação cair entre o commit e a execução do listener, o evento se perde. Mitigação: o mesmo job
   `@Scheduled` que expira matrículas vencidas também varre turmas com vaga livre e fila não vazia,
   forçando a realocação. Limitação assumida: se o monólito rodar em múltiplas instâncias por
   disponibilidade, o reconciliador — não o evento em processo — vira o mecanismo real de garantia.

4. **Transactional Outbox restrito à borda de publicação (RabbitMQ).** A criação da `Matricula` grava o
   evento de domínio na mesma transação local que persiste a outbox; um publicador assíncrono entrega ao
   RabbitMQ com retry.

5. **Geocodificação via Nominatim (~1 req/s de uso justo) — mitigada por geocodificar uma única vez.**
   Cada endereço é geocodificado uma única vez (creche no cadastro, família na inscrição) e a
   latitude/longitude persiste no Value Object `Endereco`; Redis disponível como cache adicional se o
   volume crescer.

6. **Carga entre integrantes dividida por módulo (pacote), não por serviço novo.** Dois integrantes
   dividem o núcleo do domínio por pacote (Inscrição+Fila vs. Matrícula+Vagas/Creches), o terceiro
   assume Notificação e a plataforma compartilhada (Gateway, segurança, observabilidade), e o quarto é o
   único com deploy e banco de fato próprios (MSRelatorios).

7. **RabbitMQ com escopo restrito** — usado só para notificação e para alimentar o MSRelatorios, nunca no
   caminho que decide quem fica com a vaga. Kafka só se justificaria com requisito real de
   replay/auditoria de longo prazo, ausente no escopo atual.

8. **REST parametrizado em vez de GraphQL para relatórios** — o conjunto de relatórios é pequeno e
   conhecido (5 indicadores); um DTO de filtro cobre a flexibilidade necessária sem o custo operacional
   de uma camada de query language. Reavaliar se o número de combinações de filtro crescer
   substancialmente.

9. **`Endereco` como Value Object (`@Embeddable`)** — sem identidade própria nem ciclo de vida
   independente; embutido no dono (`Responsavel`/`Creche`), nunca uma entidade com tabela própria.

10. **Outbox como infraestrutura compartilhada, não propriedade de um módulo** — schema genérico
    (`id`, `tipo_evento`, `payload`, `status`, `criado_em`) gravável por qualquer módulo de domínio; o
    publicador é operado pela plataforma (integrante responsável por Notificação + plataforma), não pelo
    módulo que gera o evento. Única exceção declarada ao invariante "um pacote por integrante".

11. **Outbox fora do escopo de Row-Level Security, por declaração explícita** — o Publicador Outbox só
    repassa payload já decidido dentro de uma transação de negócio já tenant-scoped; seu schema não tem
    `municipio_id` nem política RLS. Não é uma lacuna: dado sensível do payload (CPF, renda) já é
    resolvido pelo mascaramento na camada de apresentação, não por RLS de linha na Outbox.

12. **Idempotência do lado da leitura (MSRelatorios), não só da escrita** — o Outbox garante entrega
    at-least-once, então uma duplicata de evento pode chegar mais de uma vez. Notificação deduplica por
    ID de evento (enviar duas vezes é o erro real ali). MSRelatorios trata com **upsert por chave de
    negócio**, nunca contador incremental — reprocessar o mesmo evento dá o mesmo resultado.

13. **Gateway como infraestrutura compartilhada** — roteia para o monólito e para o MSRelatorios sem
    acoplar a nenhum dos dois no código-fonte; deploy próprio, mantido pelo integrante responsável por
    plataforma/segurança/observabilidade.

14. **Multi-tenancy via discriminador (`municipio_id`) + Row-Level Security, não banco/schema por
    município.** Com um número potencialmente grande de municípios de tamanho muito variável, banco ou
    schema por tenant multiplicaria migração, backup e connection pool por município. Mecânica completa
    (incluindo `SET LOCAL` vs `SET` e o cuidado com jobs agendados).

15. **Motor de priorização e prazo de confirmação configuráveis por município** — cada prefeitura define
    seus próprios pesos de priorização e prazo de confirmação de matrícula, conforme lei municipal local.
    Modelado como `ConfiguracaoPriorizacao` (Value Object embutido em `Municipio`), no mesmo espírito de
    `Endereco`.

16. **`Inscricao` referencia `Municipio` diretamente**, não só transitivamente via `Creche`/`Turma` — a
    família se vincula ao município antes de ser alocada a uma creche específica; sem essa referência
    direta, isolamento multi-tenant e priorização configurável não teriam onde se apoiar entre a
    inscrição e a alocação.

17. **Painel público e relatórios escopados obrigatoriamente por município** — nome de bairro sozinho é
    ambíguo entre municípios diferentes; `municipioId` é campo obrigatório em `FiltroRelatorioDTO` e no
    painel público.

18. **Onboarding de novo município é capacidade de domínio nova** — cadastro do município, configuração
    inicial de `ConfiguracaoPriorizacao` e cadastro em lote das creches daquele município.

19. **SLA da reconciliação declarado explicitamente: a cada 2 minutos** (mesmo job que expira matrículas
    vencidas) — valor assumido, sujeito a revisão com dados reais de carga. Sem um número declarado, a
    alegação de "reação em tempo real" não tem lastro no pior caso (evento em processo perdido por queda
    da aplicação); com o intervalo declarado, o pior caso passa a ser "até 2 minutos".

20. **Critério de desempate declarado: `dataInscricao` (ordem de chegada)** — com o score recalculado
    on-the-fly como `double`, dois candidatos podem empatar; numa fila pública, quem vence o empate
    precisa ser auditável, não um efeito colateral da ordem física das linhas no banco.
    `alocarProximoElegivel` usa `ORDER BY score DESC, dataInscricao ASC`.

21. **`Inscricao.scorePrioridade` persistido é cache de exibição, não fonte de verdade da alocação** —
    `alocarProximoElegivel` sempre recalcula o score on-the-fly a partir dos fatores brutos e da
    `ConfiguracaoPriorizacao` vigente, no momento exato da alocação (evento raro, custo desprezível). O
    campo persistido e o cache Redis (chave versionada `score:municipioId:versaoConfig:inscricaoId`)
    servem só de estimativa de exibição para `consultarPosicaoFila`.

22. **Sa-Token em modo stateless com a extensão `sa-token-jwt`** — uma escolha de modo dentro de um único
    framework de autenticação, não dois mecanismos complementares.

23. **Self-invocation quebra `@Transactional`/`@Retryable`/`@Scheduled` em três pontos críticos do
    domínio** (listener de liberação de vaga, job de reconciliação por município, e a combinação
    `@Transactional`+`@Retryable` no mesmo método) — cada um resolvido colocando a chamada em um bean
    injetado separado, nunca `this`.

## Gatilhos de reavaliação de escala (registrados, não implementados agora)

Três pontos ficam frágeis à medida que o número de creches/municípios cresce — revisar quando/se isso
se tornar realidade, não antecipar agora:

1. Se o monólito passar a rodar em múltiplas instâncias por disponibilidade, o job de reconciliação —
   não mais o evento em processo — vira o mecanismo primário de realocação, e seu intervalo passa a
   definir o SLA real.
2. O limite de uso justo do Nominatim (~1 req/s) deixa de estar obviamente coberto se muitos municípios
   abrirem matrícula digital no mesmo período do ano.
3. Consultas de posição na fila e o scan de reconciliação passam a exigir índice composto explícito
   (`Turma` por município e disponibilidade, `FilaEspera` por turma e score) em vez de depender de
   tabelas pequenas.
