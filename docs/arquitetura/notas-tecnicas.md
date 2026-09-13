# Notas técnicas — padrões usados neste projeto

> Referenciado por `docs/adr/0001-fundacao-arquitetural.md`, `docs/arquitetura/visao-geral.md` e
> `docs/context/*.md`. Consolida, de forma autocontida no repositório, os três padrões técnicos
> mais citados nesses documentos — para que quem clonar o projeto não dependa de nenhuma
> ferramenta externa para entender o racional.

## Self-invocation quebra `@Transactional`/`@Retryable`/`@Scheduled`

Spring AOP é baseado em proxy: as anotações `@Transactional`, `@Retryable`, etc. só têm efeito
quando o método é chamado **através do proxy do bean**, isto é, por outro bean injetado. Se um
método chama outro método anotado na **mesma instância** (`this.outroMetodo()` — inclusive uma
chamada implícita, sem `this.` explícito), a chamada nunca passa pelo proxy, e a anotação é
**silenciosamente ignorada** — sem exceção, sem aviso.

```java
@Service
public class MatriculaService {

    @Transactional
    public void metodoTransacional() { ... }

    public void chamador() {
        this.metodoTransacional(); // ❌ NAO passa pelo proxy — @Transactional é ignorado
    }
}
```

Correção: mover a chamada para um bean diferente, injetado.

```java
@Component
public class Chamador {
    private final MatriculaService service; // injetado, nunca a propria classe

    public void executar() {
        service.metodoTransacional(); // ✅ passa pelo proxy
    }
}
```

Este projeto tem três pontos onde esse gotcha é crítico (ver `docs/context/matricula-vagas.md` e
`docs/adr/0001-fundacao-arquitetural.md`, decisão 23):

1. O listener de liberação de vaga (`aoLiberarVaga`) chamando `alocarProximoElegivel` — precisam
   estar em beans diferentes.
2. O job de reconciliação por município — a lógica de `SET LOCAL` + varredura por tenant precisa
   estar num bean separado do job `@Scheduled`.
3. `@Transactional` e `@Retryable` nunca no mesmo método — cada um é montado por um advice de AOP
   sem ordem explícita por padrão; separá-los em bean chamador (`@Retryable`) e bean chamado
   (`@Transactional`) garante que cada tentativa de retry abra uma transação nova.

## Transactional Outbox Pattern

Usado quando uma escrita local precisa também notificar algo fora da transação (um broker, outro
serviço) sem risco de perda silenciosa — a garantia é **at-least-once, nunca zero**, nunca
exactly-once (por isso os consumidores precisam ser idempotentes, ver abaixo).

**Mecânica:**

1. O evento é gravado na **mesma transação local** que a escrita de negócio (`OutboxEvent`, ver
   `alocacreche-core/.../outbox/OutboxEvent.java`).
2. Um publicador assíncrono (`@Scheduled`, separado) lê linhas `PENDENTE`, publica no broker, e só
   marca `PROCESSADO` após confirmar o publish. Uma falha de publish deixa a linha `PENDENTE` para
   a próxima tentativa — nunca um drop silencioso.
3. **Consumidores idempotentes** — o outbox entrega at-least-once, então o mesmo evento pode
   chegar mais de uma vez:
   - Efeitos colaterais (enviar notificação): **dedup por ID de evento** — enviar duas vezes é o
     erro a evitar.
   - Projeções de leitura (MSRelatorios): **upsert por chave de negócio, nunca contador
     incremental** — `UPDATE projecao SET vagas_disponiveis = :valor WHERE turma_id = :id` é
     seguro para reprocessar; `vagas_disponiveis = vagas_disponiveis - 1` não é.
   - Ordem/duplicata: usar o `id` auto-incremento da própria Outbox como sequência global no
     payload; só aplicar o upsert se a sequência recebida for maior que a última aplicada para
     aquela chave de negócio.
4. Purgar periodicamente linhas `PROCESSADO` antigas (retenção de alguns dias) para não deixar a
   tabela crescer sem limite.

**Escopo:** só use Outbox na borda que realmente cruza para fora do processo (broker, outro
serviço). Não use para coordenar escritas que já estão na mesma transação/banco local — aí a
própria transação ACID já garante a consistência, sem precisar de Outbox nem saga.

## Multi-Tenancy com Row-Level Security (PostgreSQL)

Duas camadas, não uma:

- **`@TenantId` (Hibernate)** — filtro automático de aplicação (`WHERE municipio_id = :atual` em
  toda query JPA). Conveniente, mas é código Java — uma query nativa ou um job em lote pode
  contorná-lo.
- **Row-Level Security (Postgres)** — política do banco que rejeita qualquer linha fora do
  tenant, independente de qual caminho de código chegou até ali. É a garantia real;
  `@TenantId` é a conveniência.

```sql
ALTER TABLE inscricao ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON inscricao
    USING (municipio_id = current_setting('app.current_tenant')::bigint);
```

**`SET LOCAL`, nunca `SET`.** Com um connection pool (HikariCP), um `SET app.current_tenant = 5`
comum muda a variável na **conexão física**, não só na requisição atual — ao voltar pro pool, o
valor vaza para a próxima requisição que reusar essa conexão, exatamente o que a RLS existe para
evitar. `SET LOCAL` escopa o valor à transação: o Postgres descarta automaticamente no
`COMMIT`/`ROLLBACK`, antes da conexão voltar ao pool.

**Jobs agendados não têm tenant de sessão** (não há usuário logado). Solução segura: listar os
municípios ativos e, para cada um, abrir uma transação própria com `SET LOCAL` antes de processar
— nunca um role com `BYPASSRLS` filtrando manualmente (reabriria o mesmo risco que a RLS existe
para fechar). Isso também é onde o gotcha de self-invocation (acima) se aplica: a chamada
por-município precisa estar num bean injetado separado do job `@Scheduled`, senão o `SET LOCAL`
nunca entra em vigor.
