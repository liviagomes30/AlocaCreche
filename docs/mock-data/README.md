# Mock data — AlocaCreche

Dados fictícios para desenvolvimento/demo, gerados a partir do modelo de domínio descrito em
`docs/arquitetura/visao-geral.md`. Nenhuma das entidades abaixo (exceto `ocupacao_turma`) existe como
tabela real ainda — os módulos `inscricao`, `matricula` e `notificacao` são scaffold vazio. Estes
arquivos servem de referência para quando cada entidade for implementada, e de dado de teste/demo para
o que já existe hoje (MSRelatorios).

Gerado com seed fixo (`random.seed(42)`) — regenerar o script produz os mesmos dados.

## Arquivos e relações

| Arquivo | Entidade | Chave estrangeira | Registros |
|---|---|---|---|
| `municipios.json` | `Municipio` (+ `ConfiguracaoPriorizacao` embutido) | — | 3 |
| `creches.json` | `Creche` (+ `Endereco` embutido) | `municipioId` → `municipios` | 50 |
| `turmas.json` | `Turma` | `crecheId` → `creches`, `municipioId` → `municipios` | 150 (3 por creche, uma por `FaixaEtaria`) |
| `responsaveis.json` | `Responsavel` (+ `Endereco` embutido) | `municipioId` → `municipios` | 80 |
| `criancas.json` | `Crianca` | `responsavelId` → `responsaveis` | ~113 (1-2 por responsável) |
| `inscricoes.json` | `Inscricao` | `criancaId` → `criancas`, `municipioId` → `municipios` | 1 por criança |
| `fila-espera.json` | `FilaEspera` | `inscricaoId` → `inscricoes`, `turmaId` → `turmas` | inscrições com status `AGUARDANDO`/`CONVOCADA` |
| `matriculas.json` | `Matricula` | `inscricaoId` → `inscricoes` | inscrições com status `MATRICULADA` |
| `notificacoes.json` | `Notificacao` | `inscricaoId` → `inscricoes` | 0-2 por inscrição |
| `seed-ocupacao-turma.sql` | `ocupacao_turma` (tabela **real**, MSRelatorios) | derivado 1:1 de `turmas.json` | 150 |

Campos que não constam no diagrama de classes de `visao-geral.md` mas foram adicionados para manter as
referências consistentes entre arquivos: todo `*Id` de relacionamento, e `faixaEtariaDesejada` em
`Inscricao` (inferido a partir da idade da criança, necessário para ligar a `FilaEspera`/`Turma` correta).

## Notas de negócio já aplicadas nos dados

- `cpf` é formatado (`000.000.000-00`) mas **não é um CPF matematicamente válido** — não usar para
  testar validação de dígito verificador.
- `mensagem` em `notificacoes.json` nunca contém CPF, renda ou critério de vulnerabilidade (regra de
  mascaramento LGPD do projeto).
- `scorePrioridade` em `inscricoes.json` é calculado com os pesos de `ConfiguracaoPriorizacao` do
  município correspondente, mas os fatores de distância/irmão são simulados (não há geolocalização real
  nem vínculo Responsável↔Creche implementados ainda) — não tratar como fórmula final de negócio.

## `seed-ocupacao-turma.sql` — a única tabela que existe de verdade

```bash
docker exec -i <container-postgres-relatorios> psql -U alocacreche_app -d alocacreche_relatorios \
  < docs/mock-data/seed-ocupacao-turma.sql
```

Agrupado em uma transação por município com `SET LOCAL app.current_tenant`, então funciona tanto
conectando como a role dedicada `alocacreche_app` (sujeita a RLS) quanto como o usuário bootstrap
(superuser, ignora RLS). Testado contra o container real deste repositório: 150 linhas inseridas,
isolamento por tenant confirmado via `SELECT` escopado por município.
