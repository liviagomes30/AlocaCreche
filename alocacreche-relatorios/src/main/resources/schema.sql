-- Row-Level Security da projecao de ocupacao (ver ADR 0002 / .specs/STATE.md AD-002).
-- Idempotente: roda a cada startup (sem ferramenta de migration ainda), depois do
-- ddl-auto=update criar a tabela (spring.jpa.defer-datasource-initialization=true).

ALTER TABLE ocupacao_turma ENABLE ROW LEVEL SECURITY;

-- Sem FORCE, o dono da tabela (a mesma role que a aplicacao usa para conectar,
-- ja que Hibernate cria a tabela com ddl-auto=update) fica ISENTO da policy -
-- a RLS nao teria efeito nenhum nas queries da propria aplicacao sem esta linha.
ALTER TABLE ocupacao_turma FORCE ROW LEVEL SECURITY;

DROP POLICY IF EXISTS ocupacao_turma_tenant_isolation ON ocupacao_turma;

CREATE POLICY ocupacao_turma_tenant_isolation ON ocupacao_turma
    USING (municipio_id = current_setting('app.current_tenant')::bigint);

-- Row-Level Security da projecao de fila de espera (mesmo padrao de ocupacao_turma acima).
ALTER TABLE fila_espera_inscricao ENABLE ROW LEVEL SECURITY;

ALTER TABLE fila_espera_inscricao FORCE ROW LEVEL SECURITY;

DROP POLICY IF EXISTS fila_espera_inscricao_tenant_isolation ON fila_espera_inscricao;

CREATE POLICY fila_espera_inscricao_tenant_isolation ON fila_espera_inscricao
    USING (municipio_id = current_setting('app.current_tenant')::bigint);

-- Row-Level Security da projecao de matricula (mesmo padrao de ocupacao_turma acima).
ALTER TABLE matricula_relatorio ENABLE ROW LEVEL SECURITY;

ALTER TABLE matricula_relatorio FORCE ROW LEVEL SECURITY;

DROP POLICY IF EXISTS matricula_relatorio_tenant_isolation ON matricula_relatorio;

CREATE POLICY matricula_relatorio_tenant_isolation ON matricula_relatorio
    USING (municipio_id = current_setting('app.current_tenant')::bigint);
