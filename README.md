# AlocaCreche

Projeto desenvolvido para a disciplina **Arquitetura de Software: Teoria e Prática** — **Turma 2026**,
ministrada pelo **Prof. Dr. Frank J. Affonso**.

O AlocaCreche é um estudo de caso de arquitetura: uma plataforma para que prefeituras gerenciem a
**inscrição, a fila de espera e a matrícula em creches públicas**, substituindo a alocação manual de vagas.

## Problema e desafios arquiteturais

- **Concorrência na alocação de vaga:** evitar dupla matrícula na mesma vaga (lock otimista com `@Version` e Spring Retry; lock por turma na fila).
- **Reação à liberação de vaga:** quando uma vaga abre, o próximo da fila é alocado automaticamente.
- **Proximidade residência–creche:** sugestão de creches próximas pela distância de Haversine sobre coordenadas cadastradas.
- **Multi-tenant:** isolamento de dados entre prefeituras com Row-Level Security (RLS) no PostgreSQL.
- **Confiabilidade na integração:** padrão Transactional Outbox para publicar eventos no RabbitMQ.

## Arquitetura

| Módulo | Porta | Papel |
|---|---|---|
| `alocacreche-core` | 8081 | **Monólito modular** com o domínio transacional: inscrição e fila, matrícula e vagas/creches, notificação, segurança e outbox |
| `alocacreche-relatorios` | 8082 | **Único microsserviço real**, com banco próprio. Consome eventos do RabbitMQ e expõe painel público de ocupação e relatórios |
| `alocacreche-gateway` | 8080 | API Gateway (Spring Cloud Gateway) com limitação de requisições |

Decisões principais (detalhadas nos ADRs):

- Monólito modular no núcleo; microsserviço apenas onde há tolerância a consistência eventual (relatórios).
- Outbox restrita à borda de publicação; RabbitMQ nunca decide quem fica com a vaga.
- Fila por prioridade com score de pesos fixos, mantida em Redis.
- Segurança stateless com Sa-Token (JWT) e perfis ADMIN, GESTOR e ATENDENTE.
- API documentada e testável via Swagger/OpenAPI.

## Stack

Java 25 · Spring Boot 3.5 · Spring Cloud Gateway · Spring Data JPA · PostgreSQL 16 (x2) · Redis 7 · RabbitMQ 3 · Sa-Token · springdoc-openapi · Resilience4j · Maven · Docker Compose

## Como executar

Pré-requisitos: JDK 25, Maven, Docker e um shell Bash (Git Bash no Windows).

Sobe toda a demonstração (infraestrutura, core, relatórios, gateway e usuários de exemplo):

```bash
scripts/demo-subir.sh
```

Opções: `--manter` (preserva dados), `--com-job` (liga o job de manutenção de matrículas), `--sem-build`, `--reiniciar-core`. As credenciais geradas ficam em `.demo/credenciais.txt` (ignorado pelo git).

Para encerrar:

```bash
scripts/demo-parar.sh
```

Apenas a infraestrutura (Postgres, Redis, RabbitMQ):

```bash
docker compose up -d
```

Se a porta 5432 estiver ocupada, copie `.env.example` para `.env` e ajuste `POSTGRES_CORE_PORT`.

Testes:

```bash
mvn verify
```

## Estrutura do repositório

```
alocacreche-core/        monólito modular (inscricao, matricula, notificacao, outbox, comum)
alocacreche-relatorios/  microsserviço de relatórios
alocacreche-gateway/     API Gateway
docker/                  scripts de inicialização dos bancos
docs/adr/                Architecture Decision Records
docs/mock-data/          dados de exemplo
scripts/                 subir e parar a demonstração
```

## Documentação de arquitetura (ADRs)

- [0001 – Fundação arquitetural](docs/adr/0001-fundacao-arquitetural.md)
- [0002 – Versões da stack e scaffold inicial](docs/adr/0002-versoes-stack-e-scaffold-inicial.md)
- [0003 – Prefixo REST da API](docs/adr/0003-prefixo-rest-api.md)
- [0004 – Divisão da equipe em três integrantes](docs/adr/0004-divisao-equipe-tres-integrantes.md)
- [0005 – Segurança stateless e Outbox confiável](docs/adr/0005-seguranca-stateless-e-outbox-confiavel.md)
- [0006 – Escopo reduzido da proposta](docs/adr/0006-escopo-reduzido-da-proposta.md)
- [0007 – Fila por prioridade, lock por turma e Redis](docs/adr/0007-fila-por-prioridade-lock-por-turma-e-redis.md)
- [0008 – Swagger/OpenAPI](docs/adr/0008-swagger-openapi-para-documentar-e-testar-a-api.md)
