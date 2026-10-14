# ADR 0002 — Versões da stack e scaffold inicial

> Status: aceito (2026-09-12)
> Complementa a `ADR 0001` (`docs/adr/0001-fundacao-arquitetural.md`) com as versões
> concretas escolhidas para o scaffold Maven multi-módulo e o racional de cada uma.
> Revise este arquivo (não o edite silenciosamente) se alguma versão precisar mudar —
> registre a mudança como uma nova ADR (`0003-...`) referenciando esta.

## Contexto

O time precisa começar a implementação dividida entre 4 integrantes sem que a estrutura
do repositório em si vire fonte de conflito (poms divergentes, versões incompatíveis
entre módulos, cada um inventando um `Endereco`/`Outbox` diferente). Este ADR fixa a
base comum — versões de dependências e o scaffold Maven — deixando a lógica de negócio
de cada módulo livre para os donos implementarem.

## Decisões

1. **Spring Boot 3.5.16 (última release OSS da linha 3.5.x), não 4.1.x.** Verificado em
   setembro/2026: a linha 3.5 chegou ao fim do suporte OSS em 30/06/2026 (3.5.16 foi o
   último patch), e o Spring Boot 4.1 (lançado em 10/06/2026, patch atual 4.1.1) é a
   linha estável recomendada para projetos novos. Mesmo assim, escolhemos 3.5.16 porque
   o **Sa-Token — dependência obrigatória de segurança deste projeto — ainda não publica
   um starter dedicado para Spring Boot 4 no Maven Central** (verificado via busca no
   grupo `cn.dev33`: só existe `sa-token-spring-boot3-starter`, nada com
   `-spring-boot4-`). Arriscar a integração de segurança obrigatória do projeto contra um
   starter não testado/não publicado é pior, para um projeto de disciplina com prazo
   fixo, do que abrir mão temporariamente de patches OSS futuros do Spring Boot 3.5.
   **Gatilho de revisão:** assim que `cn.dev33:sa-token-spring-boot4-starter` (ou
   equivalente) aparecer publicado no Maven Central, reavalie a migração para Spring Boot
   4.1.x nesta ADR.
2. **Java 25 (LTS)**, atualização da decisão original de Java 21, compatível com Spring Boot 3.5.x
   (mínimo Java 17) e com o Resilience4j 2.x usado abaixo. O JDK 25.0.2 foi escolhido por ser o LTS
   mais recente disponível no ambiente do projeto.
3. **Spring Cloud 2025.0.0** como release train do Gateway — confirmado compatível com
   Spring Boot 3.5.x (blog oficial Spring: "Spring Cloud 2025.0.0 is compatible with
   Spring Boot 3.5.0").
4. **Sa-Token 1.44.0** (`sa-token-spring-boot3-starter` + `sa-token-jwt`) — última versão
   publicada no Maven Central para a linha `spring-boot3-starter` no momento da criação
   deste scaffold.
5. **Resilience4j 2.3.0** (`resilience4j-spring-boot3`) — última versão publicada
   compatível com Spring Boot 3.x / Java 17+.
6. **`ddl-auto: update` como padrão em todos os módulos (não `validate`), com TODO
   explícito no `application.yml`.** Sem uma ferramenta de migration (Flyway/Liquibase)
   ainda configurada, `validate` faria a aplicação falhar no primeiro start contra um
   banco vazio. `update` é aceitável só nesta fase inicial — trocar para `validate` +
   Flyway/Liquibase assim que o schema de qualquer módulo estabilizar o suficiente para
   valer a pena versionar migrations.
7. **Scaffold: 3 módulos Maven** (`alocacreche-core`, `alocacreche-gateway`,
   `alocacreche-relatorios`) num único reactor, refletindo exatamente a arquitetura da
   ADR 0001 — não 4 nem 2 módulos. `alocacreche-core` contém os pacotes de dono exclusivo
   (`inscricao`, `matricula`, `notificacao`) mais dois pacotes compartilhados:
   - `comum` (Value Object `Endereco` e o contrato `GeolocalizacaoService`, sem
     implementação — a implementação da fórmula de Haversine fica para quem o time
     designar, seguindo TDD);
   - `outbox` (entidade `OutboxEvent` e `OutboxEventRepository`, schema fixo da ADR 0001).

   Nenhuma entidade de domínio (`Inscricao`, `Matricula`, `Turma`, `Creche`, `Municipio`,
   `Notificacao`, `Responsavel`, `Crianca`, `FilaEspera`) foi criada pelo scaffold — cada
   uma é responsabilidade do dono do módulo correspondente, para não
   antecipar decisões de modelagem que são trabalho de quem vai implementar.

8. **Infra local via `docker-compose.yml` na raiz**: dois containers Postgres separados
   (`postgres-core` porta 5432, `postgres-relatorios` porta 5433 — refletindo o "banco
   realmente separado" da ADR 0001, decisão 2), um Redis compartilhado, um RabbitMQ
   compartilhado (é o transporte que liga os dois lados por design).
9. **Sem Maven Wrapper (`mvnw`) neste scaffold.** Cada integrante deve ter Java 25 e
   Maven instalados (ou rodar `mvn -N io.takari:maven:wrapper -Dmaven=3.9.16` uma vez
   para gerar o wrapper localmente) — optou-se por não gerar os scripts `mvnw`/`mvnw.cmd`
   à mão sem poder testá-los de ponta a ponta neste ambiente.

## Verificação

Este scaffold foi compilado e testado de ponta a ponta antes de ser commitado: `mvn
compile` e `mvn test-compile` passam nos 3 módulos, e o teste de contexto
(`contextLoads`) de cada módulo passou contra Postgres 16 e Redis reais (via
`docker-compose up`) — incluindo a criação automática da tabela `outbox` pelo Hibernate e
a inicialização do Sa-Token. Não é um scaffold "no papel": `docker compose up -d` seguido de `mvn
clean install` e `mvn test` (com a infra local no ar) funcionam como descrito.
