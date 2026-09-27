-- Row-Level Security do nucleo transacional (ADR 0001, decisao 14; mecanica completa em
-- docs/arquitetura/notas-tecnicas.md#multi-tenancy-com-row-level-security-postgresql).
-- Idempotente: roda a cada startup (sem ferramenta de migration ainda), depois do
-- ddl-auto=update criar as tabelas (spring.jpa.defer-datasource-initialization=true).
--
-- Fora deste script, por decisao explicita (ADR 0001, decisao 11): "outbox" (Publicador Outbox
-- ja opera dentro de uma transacao de negocio ja tenant-scoped) e "municipio" (e o proprio
-- discriminador de tenant, nao uma tabela isolada por tenant).
--
-- Limitacao conhecida e aceita (ver comentario junto de spring.jpa.hibernate.ddl-auto em
-- application.yml): depois que FORCE ROW LEVEL SECURITY esta ativo, reiniciar a app contra a MESMA
-- base local persistente (nao os testes de integracao, que comecam vazios) pode falhar durante a
-- checagem de FK existente do ddl-auto=update. Nao "corrigir" trocando para
-- current_setting(..., true) aqui - isso mudaria a policy inteira de fail-closed (lanca excecao)
-- para fail-open-silencioso (retorna vazio), decisao tomada e testada em alocacreche-relatorios.

-- responsavel
ALTER TABLE responsavel ENABLE ROW LEVEL SECURITY;
ALTER TABLE responsavel FORCE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS responsavel_tenant_isolation ON responsavel;
CREATE POLICY responsavel_tenant_isolation ON responsavel
    USING (municipio_id = current_setting('app.current_tenant')::bigint);

-- crianca
ALTER TABLE crianca ENABLE ROW LEVEL SECURITY;
ALTER TABLE crianca FORCE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS crianca_tenant_isolation ON crianca;
CREATE POLICY crianca_tenant_isolation ON crianca
    USING (municipio_id = current_setting('app.current_tenant')::bigint);

-- inscricao (carrega municipio_id via relacao direta com Municipio, ADR 0001 decisao 16 - nao
-- extende EntidadeMultiTenant, mas a coluna fisica existe e a RLS nao depende do mapeamento JPA)
ALTER TABLE inscricao ENABLE ROW LEVEL SECURITY;
ALTER TABLE inscricao FORCE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS inscricao_tenant_isolation ON inscricao;
CREATE POLICY inscricao_tenant_isolation ON inscricao
    USING (municipio_id = current_setting('app.current_tenant')::bigint);

-- fila_espera
ALTER TABLE fila_espera ENABLE ROW LEVEL SECURITY;
ALTER TABLE fila_espera FORCE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS fila_espera_tenant_isolation ON fila_espera;
CREATE POLICY fila_espera_tenant_isolation ON fila_espera
    USING (municipio_id = current_setting('app.current_tenant')::bigint);

-- creche
ALTER TABLE creche ENABLE ROW LEVEL SECURITY;
ALTER TABLE creche FORCE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS creche_tenant_isolation ON creche;
CREATE POLICY creche_tenant_isolation ON creche
    USING (municipio_id = current_setting('app.current_tenant')::bigint);

-- turma
ALTER TABLE turma ENABLE ROW LEVEL SECURITY;
ALTER TABLE turma FORCE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS turma_tenant_isolation ON turma;
CREATE POLICY turma_tenant_isolation ON turma
    USING (municipio_id = current_setting('app.current_tenant')::bigint);

-- matricula
ALTER TABLE matricula ENABLE ROW LEVEL SECURITY;
ALTER TABLE matricula FORCE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS matricula_tenant_isolation ON matricula;
CREATE POLICY matricula_tenant_isolation ON matricula
    USING (municipio_id = current_setting('app.current_tenant')::bigint);

-- notificacao
ALTER TABLE notificacao ENABLE ROW LEVEL SECURITY;
ALTER TABLE notificacao FORCE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS notificacao_tenant_isolation ON notificacao;
CREATE POLICY notificacao_tenant_isolation ON notificacao
    USING (municipio_id = current_setting('app.current_tenant')::bigint);
