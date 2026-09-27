-- Role de aplicacao SEM privilegio de superuser/BYPASSRLS.
--
-- O usuario bootstrap do container Postgres (POSTGRES_USER) e sempre criado como
-- superuser pela imagem oficial - e superusers ignoram Row-Level Security
-- incondicionalmente, mesmo com FORCE ROW LEVEL SECURITY (ver AD-002 / mesmo
-- padrao de docker/postgres-relatorios-init/01-app-role.sql). Por isso a
-- aplicacao NUNCA conecta como o usuario bootstrap quando RLS estiver em uso:
-- conecta como esta role dedicada, que cria as proprias tabelas (dona) mas nao
-- e superuser, entao FORCE ROW LEVEL SECURITY tem efeito real sobre ela.
CREATE ROLE alocacreche_app WITH LOGIN PASSWORD 'alocacreche_app'
    NOSUPERUSER NOCREATEDB NOCREATEROLE NOBYPASSRLS;

GRANT ALL PRIVILEGES ON DATABASE alocacreche TO alocacreche_app;
GRANT ALL ON SCHEMA public TO alocacreche_app;
ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT ALL PRIVILEGES ON TABLES TO alocacreche_app;
ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT ALL PRIVILEGES ON SEQUENCES TO alocacreche_app;
