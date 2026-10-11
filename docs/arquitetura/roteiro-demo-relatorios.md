# Roteiro de demonstração: do cadastro ao relatório

Este roteiro roda o fluxo `creche -> turma -> inscrição -> alocar vaga -> confirmar matrícula` por HTTP no
`alocacreche-core` e confere, no `alocacreche-relatorios`, os números correspondentes em `ocupacao`,
`fila-espera` e `matricula/taxa-confirmacao`. Cada evento de domínio é gravado na Outbox na mesma
transação da mudança de estado, o Publicador o envia ao RabbitMQ e o MSRelatorios o consome.

Os comandos são para **bash** (Git Bash no Windows, Linux ou macOS), com `curl` e `docker compose`.
Valores entre `<...>` são placeholders: escolha os seus e não os versione.

## Subir tudo com um comando

Os scripts `scripts/demo-subir.sh` e `scripts/demo-parar.sh` (bash: Git Bash, Linux ou macOS) fazem as seções
2 a 6 e 13 deste roteiro sozinhos, sem variáveis para lembrar:

```bash
scripts/demo-subir.sh            # zera tudo (docker compose down -v) e sobe a demonstração inteira
scripts/demo-subir.sh --manter   # não zera: reaproveita banco, usuários e .demo/credenciais.txt
scripts/demo-subir.sh --com-job  # liga o job de manutenção de matrículas (rodada a cada 20 s)
scripts/demo-subir.sh --sem-build  # não recompila; usa os jars já gerados em */target
scripts/demo-parar.sh            # para os serviços e derruba a infraestrutura (dados ficam nos volumes)
scripts/demo-parar.sh --zerar    # idem, apagando os volumes (down -v) e as credenciais
```

O que o `demo-subir.sh` faz: confere Docker, Java 25, Maven e as portas (para com mensagem clara, sem
encerrar nada alheio, se uma porta estiver ocupada por outro processo); gera um segredo JWT e uma senha
aleatórios; sobe a infraestrutura; compila uma vez; sobe core (8083), relatórios (8082) e Gateway (8090), cada
um com ambiente isolado; cria o município 1 e os usuários ADMIN, GESTOR e ATENDENTE; valida o login dos
três perfis pelo Gateway; e imprime o quadro final com a URL (`http://localhost:8090/login.html`), os
e-mails e a senha, o RabbitMQ (`http://localhost:15672`, guest/guest), os logs e como parar.
Pode ser executado de novo a qualquer momento: ele para antes os processos da própria demonstração.
Se algo falhar no meio (ou com Ctrl+C), o que subiu é derrubado e o erro é mostrado com o final do log.

- **Credenciais:** o segredo JWT e a senha são gerados a cada execução e ficam só em memória e em
  `.demo/credenciais.txt` (permissão 600, pasta `.demo/` ignorada pelo git, nunca no `.env`). O arquivo é
  lido de novo com `--manter`. Os logs (`core.log`, `relatorios.log`, `gateway.log`, `build.log`) e os PIDs
  ficam na mesma pasta. A senha aparece no quadro final e, durante a criação dos usuários, na linha de
  comando do `psql`: use só em máquina de demonstração.
- **Portas:** `POSTGRES_CORE_PORT` (padrão 5434, o mesmo nome do `docker-compose.yml`),
  `DEMO_PORTA_CORE` (8083), `DEMO_PORTA_RELATORIOS` (8082) e `DEMO_PORTA_GATEWAY` (8090). As portas 5433,
  6379, 5672 e 15672 são fixas no `docker-compose.yml`; o script só confere se estão livres.
- **Plataforma:** no Git Bash os processos são encerrados com `taskkill //F //T`; em Linux/macOS, com `kill`.
  A checagem de porta usa `/dev/tcp` do bash (sem depender de `nc`).

O passo a passo manual abaixo continua valendo como alternativa e para entender cada etapa.

## 1. Pré-requisitos

- Java 25 e Maven (o projeto exige o JDK 25, ver ADR 0002).
- Docker (para Postgres, Redis e RabbitMQ).
- Raiz do repositório como diretório atual em todos os comandos.

## 2. Infraestrutura

O Postgres do core usa a porta 5434 do host (a variável `POSTGRES_CORE_PORT` é lida pelo
`docker-compose.yml`; a 5432 costuma estar ocupada). O Postgres dos relatórios usa a 5433.

```bash
export POSTGRES_CORE_PORT=5434
docker compose up -d postgres-core postgres-relatorios redis rabbitmq
docker compose ps
```

Espere os quatro serviços ficarem `healthy`. O painel do RabbitMQ fica em `http://localhost:15672`
(usuário e senha `guest`).

Se já existirem volumes antigos do Postgres, a reinicialização do core pode falhar por uma limitação
conhecida do `ddl-auto: update` com RLS (veja o comentário em
`alocacreche-core/src/main/resources/application.yml`). Para começar do zero:
`docker compose down -v` e suba a infraestrutura de novo.

## 3. Variáveis de ambiente

O mesmo `SA_TOKEN_JWT_SECRET` precisa estar nos **dois** serviços: o core emite o token e os relatórios o
validam. As variáveis do usuário inicial ficam só no core e não têm valor padrão no repositório.

```bash
export SA_TOKEN_JWT_SECRET='<segredo-longo-e-aleatorio>'

export SPRING_DATASOURCE_URL='jdbc:postgresql://localhost:5434/alocacreche'
export SERVER_PORT=8083

export ALOCACRECHE_SEGURANCA_USUARIOINICIAL_EMAIL='<email-do-admin>'
export ALOCACRECHE_SEGURANCA_USUARIOINICIAL_SENHA='<senha-do-admin>'
export ALOCACRECHE_SEGURANCA_USUARIOINICIAL_MUNICIPIOID=1
```

As três últimas correspondem às propriedades `alocacreche.seguranca.usuario-inicial.email`, `.senha` e
`.municipio-id` (regra do Spring Boot: pontos viram `_` e hifens são removidos). A senha tem no máximo 72
bytes em UTF-8. O usuário criado tem o perfil `ADMIN`, cuja permissão `*` cobre todos os endpoints.

Os relatórios usam os padrões do `application.yml` deles (porta 8082, Postgres em `localhost:5433`,
RabbitMQ em `localhost:5672`) e só precisam do `SA_TOKEN_JWT_SECRET` acima.

**Atenção:** `SERVER_PORT`, `SPRING_DATASOURCE_URL` e `SPRING_JPA_HIBERNATE_DDL_AUTO` valem **só para o
core**. Se os relatórios forem iniciados no mesmo shell em que essas variáveis foram exportadas, eles herdam
`SERVER_PORT=8083` (falha com `Port 8083 was already in use`) e o Postgres do core (falha com
`relation "ocupacao_turma" does not exist`). Abra um terminal separado para os relatórios e exporte nele
apenas `SA_TOKEN_JWT_SECRET`, ou rode `unset SERVER_PORT SPRING_DATASOURCE_URL SPRING_JPA_HIBERNATE_DDL_AUTO`
antes de subi-los.

## 4. Primeira subida do core (cria as tabelas)

O município precisa existir antes do usuário inicial, e a tabela `municipio` só nasce quando o core sobe
pela primeira vez. Por isso o core sobe duas vezes.

```bash
cd alocacreche-core && mvn spring-boot:run
```

Quando aparecer `Started AlocaCrecheCoreApplication`, pare com `Ctrl+C` (o log avisa
`Usuario inicial nao criado: municipio 1 inexistente`, o que é esperado nesta primeira vez).

Se a primeira subida falhar com `Port 8083 was already in use`, o schema e o RLS **já foram criados**
antes do erro e a segunda tentativa cai na limitação do `ddl-auto: update` com RLS
(`unrecognized configuration parameter "app.current_tenant"`). Libere a porta (ou troque `SERVER_PORT`),
recrie o volume e recomece desta seção:
`docker compose rm -sf postgres-core && docker volume rm creche_postgres-core-data && docker compose up -d postgres-core`.

## 5. Criar o município por SQL

A tabela `municipio` não tem endpoint de cadastro. Crie o município 1 com prazo de 5 dias para confirmar
a matrícula e pesos de priorização que somam 1,0:

```bash
docker compose exec -T postgres-core psql -U alocacreche -d alocacreche -c "INSERT INTO municipio (id, nome, uf, prazo_confirmacao_dias, peso_renda, peso_vulnerabilidade, peso_distancia, peso_irmao_matriculado) VALUES (1, 'Municipio Demo', 'SP', 5, 0.4, 0.3, 0.2, 0.1)"
```

Esperado: `INSERT 0 1`.

## 6. Subir os dois serviços

Em um terminal, o core (agora sem recriar o schema, e com o usuário inicial que será criado no startup):

```bash
export SPRING_JPA_HIBERNATE_DDL_AUTO=none
cd alocacreche-core && mvn spring-boot:run
```

O log deve trazer `Usuario ADMIN inicial criado no municipio 1`.

Em outro terminal, com `SA_TOKEN_JWT_SECRET` exportado (seção 3), os relatórios:

```bash
cd alocacreche-relatorios && mvn spring-boot:run
```

Espere o log `Started AlocaCrecheCoreApplication` no core e `Started AlocaCrecheRelatoriosApplication` nos
relatórios. O login da seção seguinte confirma que o core responde.

## 7. Login

Use as mesmas credenciais do usuário inicial (seção 3). O comando guarda o token em `TOKEN`:

```bash
CORE=http://localhost:8083
REL=http://localhost:8082

TOKEN=$(curl -s -X POST "$CORE/api/v1/auth/login" \
  -H 'Content-Type: application/json' \
  -d "{\"email\":\"$ALOCACRECHE_SEGURANCA_USUARIOINICIAL_EMAIL\",\"senha\":\"$ALOCACRECHE_SEGURANCA_USUARIOINICIAL_SENHA\",\"municipioId\":1}" \
  | sed -n 's/.*"token":"\([^"]*\)".*/\1/p')
echo "${TOKEN:0:20}..."
```

Esperado: os 20 primeiros caracteres de um JWT (não vazio). A resposta completa traz `token`, `tipo`
(`Bearer`) e `expiraEm`.

## 8. O fluxo

Todos os comandos usam `-i` quando o status HTTP importa. As respostas abaixo são as esperadas.

### 8.1 Criar a creche (HTTP 201)

A creche precisa de latitude e longitude: a distância da alocação depende delas.

```bash
CRECHE_ID=$(curl -s -X POST "$CORE/api/v1/matricula/creches" \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"nome":"Creche Sol","capacidade":50,"endereco":{"rua":"Rua B","numero":20,"bairro":"Centro","cidade":"Sao Paulo","estado":"SP","latitude":-23.56,"longitude":-46.64}}' \
  | sed -n 's/.*"crecheId":\([0-9]*\).*/\1/p')
echo "CRECHE_ID=$CRECHE_ID"
```

Esperado: `CRECHE_ID` numérico (resposta `{"crecheId":N}`). Não grava evento.

### 8.2 Criar a turma com 3 vagas (HTTP 201, evento `vaga.liberada`)

```bash
TURMA_ID=$(curl -s -X POST "$CORE/api/v1/matricula/turmas" \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d "{\"crecheId\":$CRECHE_ID,\"faixaEtaria\":\"MATERNAL\",\"vagasTotais\":3}" \
  | sed -n 's/.*"turmaId":\([0-9]*\).*/\1/p')
echo "TURMA_ID=$TURMA_ID"
```

Esperado: `TURMA_ID` numérico. A turma nasce com `vagasDisponiveis = 3`.

### 8.3 Inscrever duas crianças (HTTP 201, evento `fila.atualizada` cada)

O CPF é único no banco inteiro; os valores abaixo são fictícios. O responsável precisa de latitude e
longitude para a alocação funcionar (sem elas a alocação responde 422).

```bash
curl -s -X POST "$CORE/api/v1/inscricao/inscricoes" \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d "{\"responsavel\":{\"nome\":\"Maria Responsavel\",\"cpf\":\"111.111.111-11\",\"telefone\":\"11999990001\",\"email\":\"maria@example.com\",\"endereco\":{\"rua\":\"Rua A\",\"numero\":10,\"bairro\":\"Centro\",\"latitude\":-23.55,\"longitude\":-46.63}},\"crianca\":{\"nome\":\"Joao Crianca\",\"dataNascimento\":\"2024-03-10\"},\"turmaId\":$TURMA_ID,\"rendaPerCapita\":1234.56,\"criterioVulnerabilidade\":true}"

curl -s -X POST "$CORE/api/v1/inscricao/inscricoes" \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d "{\"responsavel\":{\"nome\":\"Ana Responsavel\",\"cpf\":\"222.222.222-22\",\"telefone\":\"11999990002\",\"email\":\"ana@example.com\",\"endereco\":{\"rua\":\"Rua C\",\"numero\":30,\"bairro\":\"Centro\",\"latitude\":-23.57,\"longitude\":-46.65}},\"crianca\":{\"nome\":\"Lia Crianca\",\"dataNascimento\":\"2024-05-20\"},\"turmaId\":$TURMA_ID,\"rendaPerCapita\":800.00,\"criterioVulnerabilidade\":false}"
```

Esperado: `{"inscricaoId":N,"posicao":1,"scorePrioridade":...,"status":"AGUARDANDO"}` e depois
`{"inscricaoId":M,"posicao":2,...}`. A mesma rota aceita, no lugar de `responsavel` + `crianca`, um
`criancaId` de criança já cadastrada (nunca os dois modos juntos, senão 400); `municipioId` no corpo
é opcional e, se informado, precisa ser o do token (senão 403).

Para consultar a posição de uma inscrição na fila (lida do Redis, com fallback para o banco), use o
`inscricaoId` devolvido acima:

```bash
curl -s "$CORE/api/v1/inscricao/inscricoes/$INSCRICAO_ID/posicao?turmaId=$TURMA_ID"   -H "Authorization: Bearer $TOKEN"
```

Esperado: `{"inscricaoId":N,"turmaId":T,"posicao":1}`. Depois da alocação (seção 8.4) a inscrição sai
da fila e a mesma consulta responde 404. Responsável e criança também podem ser cadastrados antes, em
`POST /api/v1/inscricao/responsaveis` (`{"responsavelId":N}`) e `POST /api/v1/inscricao/criancas`
(`{"criancaId":N}`), e a inscrição usa o `criancaId`.

### 8.4 Alocar a vaga ao primeiro da fila (HTTP 201, três eventos)

```bash
MATRICULA_ID=$(curl -s -X POST "$CORE/api/v1/matricula/vagas/alocar" \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d "{\"turmaId\":$TURMA_ID}" \
  | sed -n 's/.*"matriculaId":\([0-9]*\).*/\1/p')
echo "MATRICULA_ID=$MATRICULA_ID"
```

Esperado: `MATRICULA_ID` numérico. A matrícula nasce `PENDENTE` para a inscrição de posição 1, a turma
passa a ter `vagasDisponiveis = 2`, a fila perde a inscrição alocada e a inscrição vira `CONVOCADA`.
Na Outbox entram `vaga.liberada`, `fila.atualizada` (com `dataSaida`) e `matricula.atualizada`
(`PENDENTE`, com `distanciaKm`).

### 8.5 Confirmar a matrícula (HTTP 200, evento `matricula.atualizada`)

```bash
curl -s -i -X POST "$CORE/api/v1/matricula/matriculas/$MATRICULA_ID/confirmar" \
  -H "Authorization: Bearer $TOKEN"
```

Esperado: `HTTP/1.1 200` e corpo `{"matriculaId":N}`. A matrícula vira `CONFIRMADA` e a inscrição
`MATRICULADA`.

### 8.6 Confirmar de novo (HTTP 409)

```bash
curl -s -i -X POST "$CORE/api/v1/matricula/matriculas/$MATRICULA_ID/confirmar" \
  -H "Authorization: Bearer $TOKEN"
```

Esperado: `HTTP/1.1 409`, sem novo evento.

### 8.7 Sugerir creches próximas (HTTP 200, só leitura)

Usa as coordenadas já cadastradas (sem geocodificação externa, ADR 0006). Exige `matricula:creche:list`.

```bash
curl -s "$CORE/api/v1/matricula/creches/proximas?latitude=-23.55&longitude=-46.63&faixaEtaria=MATERNAL&limite=5"   -H "Authorization: Bearer $TOKEN"
```

Esperado: lista de `{crecheId, nome, bairro, turmaId, vagasDisponiveis, distanciaKm}` da mais próxima
para a mais distante. Sem `latitude`, `longitude` ou `faixaEtaria` a resposta é `400`.

### 8.8 Desistir da matrícula (HTTP 200, vaga devolvida e realocada)

Exige `matricula:matricula:status`. Cancela a matrícula `PENDENTE` ou `CONFIRMADA`, devolve a vaga e, se
houver fila, convoca o próximo logo após o commit.

```bash
curl -s -i -X POST "$CORE/api/v1/matricula/matriculas/$MATRICULA_ID/desistir"   -H "Authorization: Bearer $TOKEN"
```

Esperado: `HTTP/1.1 200` e corpo `{"matriculaId":N,"turmaId":N,"vagasDisponiveis":N}`; a matrícula vira
`CANCELADA` e a inscrição `CANCELADA`. Na Outbox entram `vaga.liberada` e `matricula.atualizada`
(`CANCELADA`) e, havendo fila, os eventos da nova alocação. Desistir de novo responde `409`; matrícula
de outro município ou inexistente responde `404`. O job de manutenção (a cada 2 minutos) também expira
matrículas `PENDENTE` com prazo vencido (`EXPIRADA`) e realoca vagas livres com fila não vazia.

## 9. Conferir a Outbox e a entrega

O Publicador roda a cada segundo. Todas as linhas devem chegar a `PROCESSADO`:

```bash
docker compose exec -T postgres-core psql -U alocacreche -d alocacreche -c "SELECT id, tipo_evento, status FROM outbox ORDER BY id"
```

Esperado, na ordem: `vaga.liberada` (turma), dois `fila.atualizada` (inscrições), `vaga.liberada`,
`fila.atualizada` e `matricula.atualizada` (alocação) e `matricula.atualizada` (confirmação), todos
`PROCESSADO`. Uma linha `ERRO` indica evento rejeitado pelo Publicador.

## 10. Conferir os relatórios

Espere alguns segundos para o consumo dos eventos (consistência eventual). Os relatórios exigem o mesmo
token e o parâmetro `municipioId` igual ao do token.

```bash
PERIODO=$(date +%Y-%m-01)

curl -s "$REL/api/v1/relatorios/ocupacao?municipioId=1&turmaId=$TURMA_ID" \
  -H "Authorization: Bearer $TOKEN"

curl -s "$REL/api/v1/relatorios/fila-espera?municipioId=1&turmaId=$TURMA_ID" \
  -H "Authorization: Bearer $TOKEN"

curl -s "$REL/api/v1/relatorios/matricula/taxa-confirmacao?municipioId=1&periodo=$PERIODO" \
  -H "Authorization: Bearer $TOKEN"

curl -s "$REL/api/v1/relatorios/matricula/distancia-media?municipioId=1&periodo=$PERIODO" \
  -H "Authorization: Bearer $TOKEN"
```

Esperado (conferido em uma execução real em 2026-10-05):

| Relatório | Resultado |
| --- | --- |
| `ocupacao` (filtrado pela turma) | uma turma com `vagasDisponiveis` igual a `2`; `ultimaSequenciaAplicada` é o id da linha da Outbox do último evento de vaga |
| `fila-espera` (filtrado pela turma) | `quantidadeAguardando` igual a `1` (duas entraram, uma saiu na alocação) |
| `matricula/taxa-confirmacao` | `totalMatriculas` `1`, `totalConfirmadas` `1`, `taxaConfirmacao` `1.0`, **somente com o banco dos relatórios zerado** (ver o aviso abaixo) |
| `matricula/distancia-media` | `distanciaMediaKm` cerca de `1.51` entre os endereços do exemplo, também sujeito ao aviso abaixo |
| `painel-publico` | responde 200 **sem** token, com os totais agregados do município |

`ocupacao` e `fila-espera` são filtrados pela turma, então batem sempre. Já `taxa-confirmacao`,
`distancia-media` e `painel-publico` agregam o **município inteiro**, e por isso incluem qualquer outra
matrícula que já esteja no banco dos relatórios.

**Aviso: eventos de teste no broker local.** Os testes de integração do core não configuram um RabbitMQ
de teste para a maioria dos casos e o Publicador roda sempre; com o RabbitMQ do `docker compose` no ar,
rodar `mvn verify` publica eventos de teste nas filas `relatorios.*`, e o MSRelatorios os consome quando
sobe. Para números exatos, antes da demonstração zere o banco dos relatórios e as filas:
`docker compose rm -sf postgres-relatorios rabbitmq && docker volume rm creche_postgres-relatorios-data && docker compose up -d postgres-relatorios rabbitmq`.

## 11. Segurança em um minuto

Sem `Authorization` qualquer endpoint responde 401, e um token sem a permissão responde 403:

```bash
curl -s -o /dev/null -w "%{http_code}\n" -X POST "$CORE/api/v1/matricula/vagas/alocar" \
  -H 'Content-Type: application/json' -d "{\"turmaId\":$TURMA_ID}"
```

Esperado: `401`.

## 12. Encerrar

`Ctrl+C` nos dois serviços e `docker compose down` (acrescente `-v` para apagar os dados).

## 13. Interface web de demonstração (opcional)

O Gateway serve duas páginas estáticas, na mesma origem das APIs (sem CORS), que fazem o fluxo das
seções 7 a 10 pelo navegador: `login.html` (autenticação) e `index.html` (criar creche e turma, inscrever,
alocar, confirmar e acompanhar os relatórios e o painel público). Depois de subir o core (8083) e os
relatórios (8082) como nas seções 2 a 6, suba o Gateway em outro terminal:

```bash
cd alocacreche-gateway
ALOCACRECHE_CORE_URL=http://localhost:8083 SERVER_PORT=8090 mvn spring-boot:run
```

Abra `http://localhost:8090/login.html` (a porta padrão do Gateway é a 8080; use outra se ela estiver
ocupada). Entre com o usuário inicial da seção 3 (ou com um usuário ATENDENTE ou GESTOR, para mostrar a matriz de permissões; o script `docs/mock-data/usuarios-demo.sql` cria os dois, ver abaixo). O token fica só no `sessionStorage` da aba e some ao
fechá-la; o botão **Sair** o apaga e uma resposta 401 volta ao login. Cinco falhas seguidas de login
mostram o bloqueio de 15 minutos (429). As páginas são só para demonstração e teste: não têm senha nem
token fixos no código.

A página `index.html` é organizada em abas (Visão geral, Stack A, Stack B, Stack C, Segurança e resiliência,
Eventos e Outbox, Roteiro) com um registro de chamadas fixo à direita (CPF e token mascarados, copiável como
`curl` sem token). Os arquivos `estilo.css` e `*.js` ficam na mesma pasta. A aba **Roteiro** tem um passo a
passo para duas pessoas e o botão que executa a demonstração completa automaticamente; a aba **Segurança**
dispara por botão os cenários 401, 403, 404, 409, 400, 429 e a matriz de permissões do usuário logado.

> **Antes de apresentar:** o job de manutenção da Stack B aloca sozinho turmas com vaga livre e fila não
> vazia a cada 2 minutos, o que pode antecipar o seu clique em "Alocar". Suba o core com
> `ALOCACRECHE_MATRICULA_MANUTENCAO_HABILITADO=false` para o fluxo manual e ligue-o com um intervalo
> curto só na hora de mostrar a expiração. O SQL para vencer o prazo de uma matrícula na demo está em
> `docs/context/matricula-vagas.md` (seção "Cuidados ao demonstrar o job").

### Usuários GESTOR e ATENDENTE para a demo

O core só cria o ADMIN inicial. Para os outros perfis, rode o script abaixo a partir da raiz do repositório
(sem senha nem hash no repositório: a senha vem do `-v senha=...` e o hash BCrypt é gerado no banco com
`pgcrypto`). Acrescente `-v email_admin=...` para criar também um ADMIN extra e `-v municipio_id=N` se o
município não for o 1:

```bash
docker compose exec -T postgres-core psql -U alocacreche -d alocacreche -v ON_ERROR_STOP=1   -v senha='<senha-da-demo>'   -v email_gestor=gestor@demo.local -v email_atendente=atendente@demo.local   -f - < docs/mock-data/usuarios-demo.sql
```

Esperado: dois `INSERT 0 1` (um por usuário) e exit code 0. Se o município não existir ou faltar uma variável, o script termina com erro e exit code diferente de zero (nada é inserido). Reexecutar é seguro: troca a senha e reativa o usuário. Depois,
entre em `login.html` (ou no `curl` da seção 7) com o email e a senha informados.

## 14. Swagger: documentar e testar a API pelo navegador

O core e o MSRelatorios publicam a própria documentação (ver `docs/adr/0008-swagger-openapi-para-documentar-e-testar-a-api.md`).
O `scripts/demo-subir.sh` já liga o Swagger e imprime as URLs; sem o script, defina
`ALOCACRECHE_SWAGGER_HABILITADO=true` (já é o padrão fora do perfil `prod`).

- Core: `http://localhost:<porta-core>/swagger-ui.html` (8083 na demo).
- MSRelatorios: `http://localhost:<porta-relatorios>/swagger-ui.html` (8082 na demo).

Passo a passo:

1. Abra a página: ela e o contrato (`/v3/api-docs`) abrem **sem token** e listam todos os endpoints.
2. Sem token, clique em "Try it out" e "Execute" numa operação com cadeado (por exemplo
   `POST /api/v1/matricula/creches`): a resposta é **401**.
3. Em "Autenticação", abra `POST /api/v1/auth/login` (sem cadeado), "Try it out", informe e-mail, senha e
   `municipioId` de um usuário da demo (`.demo/credenciais.txt`) e execute.
4. Copie o campo `token` da resposta, clique em "Authorize" (topo da página), cole **só o token** (sem
   `Bearer`) e confirme.
5. Repita a operação do passo 2: agora responde 201/200, conforme as permissões do perfil. Um perfil sem a
   permissão (por exemplo ATENDENTE em `matricula:creche:add`) responde **403**.
6. No Swagger dos relatórios use o **mesmo token** ("Authorize" de novo) e o `municipioId` do token; o
   painel público (`GET /api/v1/relatorios/painel-publico`) não tem cadeado e funciona sem token.

Cada operação protegida mostra, na descrição, a "Permissão exigida". Em prod o Swagger fica desligado por
padrão (`/v3/api-docs` e `/swagger-ui.html` respondem 401).

**Atalho pela interface web.** Depois de entrar em `http://localhost:8090/login.html`, os botões
**Swagger core** e **Swagger relatórios** do cabeçalho copiam o token da sessão para a área de
transferência (ele nunca aparece na tela) e abrem o Swagger do serviço em outra aba. Lá, clique em
**Authorize**, cole com Ctrl+V e confirme: não é preciso chamar o login de novo pelo Swagger. O
Swagger guarda o token ao recarregar a página (`persist-authorization`). O token não é repassado
sozinho porque o navegador isola o armazenamento por porta (8090, 8083 e 8082 são origens diferentes).
