# ADR 0008 — Swagger/OpenAPI para documentar e testar a API (core e MSRelatórios)

> Status: aceito
> Complementa a ADR 0005 (segurança stateless, nega por padrão) e a ADR 0002 (versões pinadas).
> Não cria fronteira de rede nem muda o modelo de autenticação: a documentação só descreve a API que já
> existe, e executar qualquer operação continua exigindo o mesmo token.

## Contexto

A API do core e a do MSRelatórios não tinham contrato legível nem forma simples de ser exercitada fora da
interface web de demonstração. A usuária pediu documentação e teste de todos os endpoints por Swagger UI,
com um comportamento exato: a página e o contrato OpenAPI abrem **sem token**; ao executar ("Try it out")
uma operação protegida sem token a resposta é **401**; depois de obter o token em `POST /api/v1/auth/login`
(pelo próprio Swagger), clicar em "Authorize" e colar o token, as operações passam a valer conforme as
permissões do perfil.

A restrição é a ADR 0005 e o `CLAUDE.md`: o `SaInterceptor` exige login em `/**`, **sem** nenhum
`excludePathPatterns`, e há um teste de regressão que garante isso. Liberar a documentação exige uma
exceção que não quebre essa regra.

## Decisões

### 1. springdoc-openapi, uma dependência por módulo

- `org.springdoc:springdoc-openapi-starter-webmvc-ui` **2.8.17** (a última da linha 2.8.x, que é a
  compatível com Spring Boot 3.5.x), com a versão na propriedade `springdoc.version` do `pom.xml` raiz e a
  dependência nos `pom.xml` do `alocacreche-core` e do `alocacreche-relatorios`. Não vai no Gateway.
- Cada módulo serve o seu próprio documento (`/v3/api-docs`) e a sua UI (`/swagger-ui.html`). Agregar os
  dois no Gateway fica fora de escopo: o Gateway só roteia (ADR 0005) e a UI de demonstração dele está em
  outra frente.

### 2. Contrato: Bearer JWT global, públicas sem cadeado, permissão na descrição

- `OpenApiConfig` (um por módulo) declara o esquema HTTP Bearer JWT `bearerAuth` como requisito global.
- `PermissaoOperationCustomizer` (um `OperationCustomizer` genérico, sem anotar controller por controller)
  lê o método do handler: com `@SaIgnore` a operação fica com requisito de segurança vazio (sem cadeado:
  login e painel público); com `@SaCheckPermission` acrescenta "Permissão exigida: `x:y:z`" à descrição e as
  respostas 401 e 403. Assim o documento reflete a segurança que o código realmente aplica e não envelhece.
- Tags por controller com nomes legíveis e `@Schema`/`@Parameter` com exemplos **fictícios** (CPF
  `000.000.000-00`, senha e renda inventadas). Nunca valores reais.

### 3. Exceção de leitura no interceptor, por método e condicional

- A subclasse do interceptor (`SaInterceptorSemRedespachoDeErro`) já abre uma exceção por **método** para
  o redespacho `DispatcherType.ERROR`. Ela ganha uma segunda: `GET` de `/swagger-ui.html`,
  `/swagger-ui/**`, `/v3/api-docs`, `/v3/api-docs/**` e `/v3/api-docs.yaml`, **somente** quando
  `alocacreche.swagger.habilitado` é verdadeiro.
- Isso **não** é `excludePathPatterns`: o registro do interceptor continua `addPathPatterns("/**")` sem
  nenhuma exclusão, e o teste de regressão que afirma isso continua valendo. A diferença é o que a regra
  protege: `excludePathPatterns` retira caminhos do alcance do interceptor para qualquer método e ficaria
  fixo no código; a exceção por método é estreita (só `GET`), condicional (só habilitada por propriedade) e
  testada com a mesma suíte de segurança. Qualquer outro método nesses caminhos (POST, DELETE, OPTIONS,
  HEAD) cai no `checkLogin` (ou no 405 do Spring, que responde antes do interceptor).
- Defesa contra traversal: o caminho precisa casar tanto no URI bruto quanto no `servletPath` normalizado
  pelo container, e caminhos com `..` ou `;` nunca casam. Além disso, o firewall do Sa-Token já barra `..`
  antes do interceptor.
- Nega por padrão preservado: rota de negócio sem token continua 401 mesmo com o Swagger ligado. O
  Swagger UI só **exibe** a API; ao executar, o navegador envia (ou não) o cabeçalho `Authorization`, e é o
  interceptor quem decide.

### 4. Uma propriedade liga tudo; desligado por padrão em prod

- `alocacreche.swagger.habilitado` (variável `ALOCACRECHE_SWAGGER_HABILITADO`) é a **única** chave: o
  `application.yml` aponta `springdoc.api-docs.enabled` e `springdoc.swagger-ui.enabled` para ela, de modo
  que documento, UI e exceção do interceptor nunca ficam desalinhados. Padrão `true` em desenvolvimento.
- `application-prod.yml` (nos dois módulos) define o padrão `false`: no perfil `prod` o Swagger só liga se
  `ALOCACRECHE_SWAGGER_HABILITADO=true` for definido de forma explícita. Com o Swagger desligado, o
  springdoc não registra os documentos e o interceptor não abre exceção: `/v3/api-docs` e `/swagger-ui.html`
  respondem **401** (o caminho cai no tratador de recursos estáticos, que também passa pelo interceptor).
- `scripts/demo-subir.sh` liga o Swagger nos dois processos e imprime as URLs e o passo a passo do Authorize.

## Consequências e riscos

- **Documentação aberta ajuda reconhecimento**: quem alcança a porta vê todas as rotas, os formatos de corpo
  e as permissões exigidas. Por isso o recurso é só para desenvolvimento e demonstração, desligado por
  padrão em prod, e a ligação em prod é um ato explícito e consciente.
- A exceção do interceptor é código de segurança novo: qualquer alteração em `ehLeituraDaDocumentacao` deve
  passar pelos testes `SwaggerLiberacaoTest`, `OpenApiIT` e `OpenApiDesligadoIT` (core e relatórios).
  Mutações verificadas: remover o customizer, remover a checagem de habilitado ou liberar todo `GET` faz
  esses testes falharem.
- O código de `OpenApiConfig`, `PermissaoOperationCustomizer` e da exceção do interceptor é duplicado entre
  os dois módulos, como já é o `SegurancaConfig`: não há biblioteca compartilhada entre eles e criar uma
  seria uma nova fronteira de build sem ADR própria.
- O token colado no "Authorize" fica só na memória da página (sem persistência), então recarregar a página
  exige colar de novo.
- Fora de escopo: agregação dos dois documentos no Gateway, geração de cliente a partir do contrato e
  documentação das respostas de erro de negócio (404/409/400) além do que o framework já conhece.
