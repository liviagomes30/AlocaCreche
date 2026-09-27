# ADR 0004 — Divisão de trabalho com equipe de 3 integrantes

> Status: aceito
> Revisa a decisão 6 e complementa a decisão 10 de `docs/adr/0001-fundacao-arquitetural.md`. Não muda
> nenhuma decisão de arquitetura de sistema (monólito modular, único microsserviço em MSRelatorios,
> Outbox restrita à borda de publicação) — muda só quem, entre as pessoas do time, é dono de cada parte.

## Contexto

A decisão 6 da ADR 0001 previa 4 papéis: dois integrantes dividindo o núcleo transacional por pacote
(Inscrição+Fila / Matrícula+Vagas/Creches), um terceiro em Notificação + plataforma (Gateway, segurança,
Outbox Publisher, geolocalização compartilhada), e um quarto dedicado a MSRelatorios. A equipe real é de
3 integrantes, não 4 — os quatro papéis precisam ser redistribuídos em três.

Ao mesmo tempo, o estado real do repositório (verificado antes desta ADR) mostra uma assimetria
importante: `alocacreche-relatorios` já está com o lado consumidor praticamente pronto (três
consumidores RabbitMQ com retry/DLQ, projeções, painel público, controllers de relatório, suíte de
testes de integração), enquanto `alocacreche-core` ainda não tem nenhuma entidade de domínio — só o
scaffold compartilhado (`Endereco`, `OutboxEvent`/`OutboxEventRepository`, contrato de
`GeolocalizacaoService` sem implementação). Isso significa que MSRelatorios está **bloqueado por uma
peça de infraestrutura que ainda não existe** (o Publicador da Outbox que lê `OutboxEvent` e publica no
RabbitMQ), não pelas entidades de domínio em si — os contratos de payload dos três eventos já estão
fixados do lado consumidor (`VagaLiberadaEvent`, `FilaEsperaAtualizadaEvent`,
`MatriculaAtualizadaEvent`, em `alocacreche-relatorios`).

## Decisão

1. **Três stacks, uma por integrante, sem papel de "plataforma" isolado:**
   - **Stack A — Inscrição & Fila + Notificação (domínio).** Acumula o pacote `notificacao/` (fino:
     `enviarNotificacao` + consumo idempotente) além do próprio núcleo de Inscrição & Fila, para
     equilibrar carga com a Stack C.
   - **Stack B — Matrícula & Vagas/Creches.** Sem mudança em relação à ADR 0001 — é o pacote com a
     lógica de concorrência mais crítica (lock otimista em `alocarProximoElegivel`), fica sozinho.
   - **Stack C — MSRelatorios + infraestrutura compartilhada.** Acumula, além de MSRelatorios: o
     Publicador da Outbox (antes atribuído a "Notificação + plataforma" pela decisão 10 da ADR 0001),
     o Gateway, autenticação básica (Sa-Token) e a implementação de `GeolocalizacaoService`.

2. **O Publicador da Outbox muda de dono**: não é mais operado por quem faz Notificação (decisão 10 da
   ADR 0001), e sim por quem faz MSRelatorios. Motivo: MSRelatorios é o consumidor final da fila
   alimentada por esse publicador — juntar as duas pontas do pipeline na mesma pessoa elimina uma
   dependência de build entre integrantes. Quem é dono de ambas as pontas pode inserir eventos
   sintéticos diretamente na tabela `outbox` (no formato já fixado pelos `record`s de evento existentes
   em `alocacreche-relatorios`) e validar publicador → RabbitMQ → consumidor → projeção → endpoint de
   relatório de ponta a ponta, sem esperar Inscrição/Matrícula terminarem o domínio real. Quando essas
   stacks começarem a gravar eventos de verdade na Outbox, o trabalho restante da Stack C é conferência
   de conformidade de contrato, não mais construção.

3. **`GeolocalizacaoService` também migra para a Stack C**, por ser bloqueador direto de Inscrição
   (score por distância) e Matrícula (`sugerirCrechesProximas`) — implementá-lo cedo desbloqueia as
   outras duas stacks em vez de deixá-lo como a última tarefa de quem fizesse "plataforma".

4. **Simplificações aceitas por prazo curto** (registrar como TODO explícito no código ao adiar, não
   simplesmente omitir silenciosamente):
   - Stack A: cache Redis do score sem versionamento por `versaoConfig` (TTL simples substitui por
     enquanto — é estimativa de exibição, não fonte de verdade da alocação).
   - Stack B: `reconciliarVagasDisponiveis`/`verificarPrazosVencidos` (job `@Scheduled`) e Circuit
     Breaker (Resilience4j) na geocodificação ficam como TODO — sem eles, falha vira intervenção manual
     em vez de autocorreção, aceitável para o prazo atual, não para produção real.
   - Stack C: observabilidade (Micrometer/OpenTelemetry), notificação multicanal completa (implementar
     1 canal, TODO os demais) e purga diária da Outbox (rodar ad-hoc por enquanto) ficam como TODO.
   - **Não simplificar**: lock otimista em `alocarProximoElegivel` (Stack B) e isolamento multi-tenant
     básico (RLS/`SET LOCAL` por request, Stack C) — o primeiro é a garantia central do projeto, o
     segundo é isolamento de dado sensível (CPF, renda) entre prefeituras, cortar isso é risco de LGPD,
     não só débito técnico.

## Consequências

- `docs/context/notificacao-plataforma.md` descreve a mecânica do Publicador Outbox, Gateway e
  multi-tenancy corretamente, mas o campo "dono" nesse arquivo passa a ser lido como "operado pela
  integrante de MSRelatorios" enquanto esta ADR estiver em vigor, não "quem faz Notificação".
- Se a equipe voltar a 4 integrantes, a decisão 6 original da ADR 0001 volta a ser a divisão padrão —
  esta ADR fica registrada como o arranjo válido apenas enquanto a equipe for de 3.
