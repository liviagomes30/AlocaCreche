-- Mesma role de aplicacao usada em docker/postgres-core-init/01-app-role.sql, para o container
-- efemero do Testcontainers (que tambem bootstrapa com um usuario superuser por padrao - ver
-- AD-002 / o mesmo padrao de alocacreche-relatorios/src/test/resources/db/relatorios-app-role.sql).
CREATE ROLE alocacreche_app WITH LOGIN PASSWORD 'alocacreche_app'
    NOSUPERUSER NOCREATEDB NOCREATEROLE NOBYPASSRLS;

GRANT ALL PRIVILEGES ON DATABASE test TO alocacreche_app;
GRANT ALL ON SCHEMA public TO alocacreche_app;
ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT ALL PRIVILEGES ON TABLES TO alocacreche_app;
ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT ALL PRIVILEGES ON SEQUENCES TO alocacreche_app;
