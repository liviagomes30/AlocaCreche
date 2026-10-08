-- Usuarios de demonstracao (GESTOR, ATENDENTE e, opcionalmente, um ADMIN extra) para o core.
-- Nenhuma senha nem hash fica neste arquivo: a senha vem da variavel do psql "senha" e o hash
-- BCrypt ($2a$, aceito pelo BCrypt.checkpw do Sa-Token em AutenticacaoServiceImpl) e gerado no
-- proprio banco com pgcrypto.
--
-- Variaveis do psql (-v nome=valor):
--   senha            obrigatoria: senha de todos os usuarios criados (so para a demo)
--   email_gestor     obrigatoria
--   email_atendente  obrigatoria
--   email_admin      opcional: se informada, cria tambem um ADMIN extra
--   municipio_id     opcional (padrao 1): o municipio ja precisa existir (roteiro, secao 5)
--
-- Uso (a partir da raiz do repositorio):
--   docker compose exec -T postgres-core psql -U alocacreche -d alocacreche -v ON_ERROR_STOP=1 \
--     -v senha='<senha-da-demo>' -v email_gestor=gestor@demo.local -v email_atendente=atendente@demo.local \
--     -f - < docs/mock-data/usuarios-demo.sql
--
-- Falha de verdade (exit code != 0 e mensagem clara) quando falta variavel obrigatoria ou o municipio
-- nao existe: o script liga ON_ERROR_STOP sozinho, entao nao depende de quem chama passar a flag.
--
-- Idempotente: reexecutar atualiza hash, nome, perfil e reativa o usuario (UNIQUE municipio+email).
-- A tabela usuario tem RLS: o tenant e definido com set_config(..., true), que equivale a SET LOCAL
-- e vale so dentro da transacao.

\set ON_ERROR_STOP on

\if :{?senha}
\else
  DO $$ BEGIN RAISE EXCEPTION 'Informe a senha com -v senha=...'; END $$;
\endif
\if :{?email_gestor}
\else
  DO $$ BEGIN RAISE EXCEPTION 'Informe o email com -v email_gestor=...'; END $$;
\endif
\if :{?email_atendente}
\else
  DO $$ BEGIN RAISE EXCEPTION 'Informe o email com -v email_atendente=...'; END $$;
\endif
\if :{?municipio_id}
\else
  \set municipio_id 1
\endif

CREATE EXTENSION IF NOT EXISTS pgcrypto;

BEGIN;
SELECT set_config('app.current_tenant', :'municipio_id', true);

-- Municipio inexistente interrompe o script com erro (exit code 3 do psql), em vez de inserir 0 linhas.
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM municipio WHERE id = current_setting('app.current_tenant')::bigint) THEN
        RAISE EXCEPTION 'Municipio % nao existe: crie o municipio antes de rodar este script (municipio_id)',
            current_setting('app.current_tenant');
    END IF;
END $$;

INSERT INTO usuario (municipio_id, email, senha_hash, nome, perfil, ativo)
SELECT m.id, :'email_gestor', crypt(:'senha', gen_salt('bf', 10)), 'Gestor Demo', 'GESTOR', true
FROM municipio m WHERE m.id = :'municipio_id'::bigint
ON CONFLICT (municipio_id, email) DO UPDATE
    SET senha_hash = EXCLUDED.senha_hash, nome = EXCLUDED.nome, perfil = EXCLUDED.perfil, ativo = true;

INSERT INTO usuario (municipio_id, email, senha_hash, nome, perfil, ativo)
SELECT m.id, :'email_atendente', crypt(:'senha', gen_salt('bf', 10)), 'Atendente Demo', 'ATENDENTE', true
FROM municipio m WHERE m.id = :'municipio_id'::bigint
ON CONFLICT (municipio_id, email) DO UPDATE
    SET senha_hash = EXCLUDED.senha_hash, nome = EXCLUDED.nome, perfil = EXCLUDED.perfil, ativo = true;

\if :{?email_admin}
INSERT INTO usuario (municipio_id, email, senha_hash, nome, perfil, ativo)
SELECT m.id, :'email_admin', crypt(:'senha', gen_salt('bf', 10)), 'Admin Demo', 'ADMIN', true
FROM municipio m WHERE m.id = :'municipio_id'::bigint
ON CONFLICT (municipio_id, email) DO UPDATE
    SET senha_hash = EXCLUDED.senha_hash, nome = EXCLUDED.nome, perfil = EXCLUDED.perfil, ativo = true;
\endif

COMMIT;
