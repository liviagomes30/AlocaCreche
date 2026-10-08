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

## `usuarios-demo.sql` — usuários GESTOR e ATENDENTE para a demo (core)

Cria no município 1 (ou no de `-v municipio_id=N`, que já precisa existir) um usuário GESTOR e um
ATENDENTE, e opcionalmente um ADMIN extra (`-v email_admin=...`). Não há senha nem hash no arquivo: a senha
vem da variável `senha` do psql e o hash BCrypt (`$2a$`) é gerado no banco com `pgcrypto`
(`crypt(senha, gen_salt('bf', 10))`), formato aceito pelo `BCrypt.checkpw` do Sa-Token. Idempotente
(`ON CONFLICT (municipio_id, email) DO UPDATE`: reexecutar troca a senha e reativa o usuário). A tabela
`usuario` tem RLS, então o script define o tenant com `set_config('app.current_tenant', ..., true)` dentro da
transação. Use uma senha só da demo (ela aparece na linha de comando do `psql`).

```bash
docker compose exec -T postgres-core psql -U alocacreche -d alocacreche -v ON_ERROR_STOP=1   -v senha='<senha-da-demo>'   -v email_gestor=gestor@demo.local -v email_atendente=atendente@demo.local   -f - < docs/mock-data/usuarios-demo.sql
```

O script liga `ON_ERROR_STOP` sozinho e **falha de verdade** (exit code 3 do `psql` e mensagem clara) quando falta
`senha`, `email_gestor` ou `email_atendente`, ou quando o município não existe; em automação, o exit code
diferente de zero é o sinal. Não há mais saída 0 com `INSERT 0 0`.

Validado por `AuthControllerIT` (script de usuários da demo): executa o script num Postgres real (Testcontainers)
e faz `POST /api/v1/auth/login` com cada usuário, conferindo as permissões do perfil; cobre também município
inexistente (falha), variável ausente (falha), 2ª execução com outra senha (a nova entra e a antiga dá 401),
reativação de usuário inativo e execução com a role sujeita a RLS (`alocacreche_app`).

Para a demonstração completa, o `scripts/demo-subir.sh` já executa este script (com o ADMIN extra) e valida
o login dos três perfis; ver "Subir tudo com um comando" em `docs/arquitetura/roteiro-demo-relatorios.md`.
