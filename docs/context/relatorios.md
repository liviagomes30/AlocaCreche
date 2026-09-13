# Contexto — MSRelatorios

> Leia este arquivo antes de implementar ou revisar código em `alocacreche-relatorios/`. Decisões
> arquiteturais: `docs/adr/0001-fundacao-arquitetural.md`.

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

## Aspecto técnico de destaque

Mensageria + eventual consistency + CQRS + cache (Redis).
