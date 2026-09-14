# ADR 0003 — Prefixo de rota REST do projeto

> Status: aceito (2026-09-14)
> Complementa a `ADR 0001` (`docs/adr/0001-fundacao-arquitetural.md`). `CLAUDE.md` deixava o
> prefixo de rota "a definir no primeiro PR que criar o Gateway/controllers" — este é esse PR:
> o primeiro `@RestController` de todo o projeto (`OcupacaoConsultaController`, MSRelatorios).

## Contexto

Até `alocacreche-relatorios` ganhar `OcupacaoConsultaController` (feature
`relatorios-leitura-ocupacao`), nenhum módulo do projeto expunha um endpoint HTTP — nem o
Gateway, nem o monólito. Não havia prefixo de rota decidido em lugar nenhum. Sem uma decisão
explícita, o primeiro Controller a existir decidiria o padrão implicitamente, e um segundo
módulo poderia escolher outro prefixo sem perceber que já havia um precedente.

## Decisão

**Todo endpoint REST deste projeto usa o prefixo `/api/v1/<modulo>/<funcionalidade>`.**

Exemplo já implementado: `GET /api/v1/relatorios/ocupacao`.

- `api` sinaliza que é uma API HTTP, distinta de qualquer rota estática/documentação que um
  módulo venha a servir.
- `v1` versiona a partir do dia 1 — trocar para `v2` no futuro não exige renomear rotas
  existentes nem quebrar clientes já integrados.
- `<modulo>` é o nome do módulo de domínio (`relatorios`, `inscricao`, `matricula`,
  `notificacao`), consistente com a convenção de pacote já usada em `alocacreche-core` e
  `alocacreche-relatorios`.

## Escopo

Vale para qualquer Controller futuro em qualquer módulo (`alocacreche-core`,
`alocacreche-gateway`, `alocacreche-relatorios`). O Gateway roteia por esse mesmo prefixo para
os dois backends (monólito e MSRelatorios) — não decide um prefixo próprio diferente.

## Trade-off

Nenhum custo real identificado — é a convenção de mercado mais comum para APIs REST versionadas,
e não havia nenhum prefixo pré-existente para conflitar.

## Verificação

`OcupacaoConsultaController` (feature `relatorios-leitura-ocupacao`, tasks T1-T3) implementa
`GET /api/v1/relatorios/ocupacao` seguindo este padrão, testado via `OcupacaoConsultaControllerIT`
(HTTP real, Testcontainers).
