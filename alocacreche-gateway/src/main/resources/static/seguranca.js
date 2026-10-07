/*
 * Aba Segurança e resiliência: laboratório de testes por botão e matriz de permissões do usuário atual.
 * Cada teste dispara chamadas reais e compara o status obtido com o esperado.
 */

var ID_INEXISTENTE = 99999999;

// Resultado de um passo: ok quando o status obtido está entre os esperados
function passoDeTeste(rotulo, resposta, esperados, nota) {
  var lista = [].concat(esperados);
  return { rotulo: rotulo, status: resposta.status, esperados: lista, ok: lista.indexOf(resposta.status) >= 0, nota: nota || '' };
}

function passoDeVerificacao(rotulo, ok, nota) {
  return { rotulo: rotulo, status: null, esperados: [], ok: ok, nota: nota || '' };
}

function chamadaSemSessao(metodo, caminho, corpo, rotulo) {
  return api(metodo, caminho, { corpo: corpo, token: null, sem401: true, rotulo: rotulo });
}

function chamadaComTokenAdulterado(metodo, caminho, corpo, rotulo) {
  return api(metodo, caminho, { corpo: corpo, token: tokenAdulterado(), sem401: true, rotulo: rotulo });
}

var TESTES_SEGURANCA = [
  {
    id: 'sem-token', titulo: 'Requisição sem token', requer: [],
    descricao: 'Sem o cabeçalho Authorization, qualquer endpoint protegido responde 401, no core e nos relatórios.',
    rodar: async function () {
      return [
        passoDeTeste('alocar sem token', await chamadaSemSessao('POST', '/api/v1/matricula/vagas/alocar', { turmaId: 1 }, 'Sem token: alocar'), 401),
        passoDeTeste('inscrever sem token', await chamadaSemSessao('POST', '/api/v1/inscricao/inscricoes', {}, 'Sem token: inscrever'), 401),
        passoDeTeste('relatório sem token', await chamadaSemSessao('GET', '/api/v1/relatorios/ocupacao?municipioId=' + municipioId, null, 'Sem token: relatório'), 401)
      ];
    }
  },
  {
    id: 'token-adulterado', titulo: 'Token adulterado', requer: [],
    descricao: 'Um caractere da assinatura do token é alterado no navegador (o token não é exibido). A assinatura deixa de conferir: 401.',
    rodar: async function () {
      return [
        passoDeTeste('alocar com token adulterado', await chamadaComTokenAdulterado('POST', '/api/v1/matricula/vagas/alocar', { turmaId: 1 }, 'Token adulterado: alocar'), 401),
        passoDeTeste('relatório com token adulterado', await chamadaComTokenAdulterado('GET', '/api/v1/relatorios/ocupacao?municipioId=' + municipioId, null, 'Token adulterado: relatório'), 401)
      ];
    }
  },
  {
    id: 'municipio', titulo: 'Município diferente do token', requer: ['inscricao:inscricao:add'],
    descricao: 'O município do token manda: informar outro município no corpo da inscrição ou na consulta de relatórios responde 403 (isolamento multi-tenant).',
    rodar: async function () {
      var outro = municipioId + 1;
      var passos = [passoDeTeste('inscrição com municipioId ' + outro,
        await api('POST', '/api/v1/inscricao/inscricoes', { corpo: { municipioId: outro, criancaId: ID_INEXISTENTE, turmaId: ID_INEXISTENTE, rendaPerCapita: 100, criterioVulnerabilidade: false }, rotulo: 'Inscrição com município de outro tenant', sem401: true }), 403)];
      if (temPermissao(permissoesDoToken(), 'relatorios:ocupacao:list')) {
        passos.push(passoDeTeste('ocupação do município ' + outro, await api('GET', '/api/v1/relatorios/ocupacao?municipioId=' + outro, { rotulo: 'Relatório de outro município', sem401: true }), 403));
        passos.push(passoDeTeste('fila do município ' + outro, await api('GET', '/api/v1/relatorios/fila-espera?municipioId=' + outro, { rotulo: 'Fila de outro município', sem401: true }), 403));
      }
      return passos;
    }
  },
  {
    id: 'login-429', titulo: 'Bloqueio de login após senhas erradas', requer: [],
    descricao: 'Seis tentativas com senha errada para um e-mail de teste (não o do admin): as cinco primeiras dão 401 e a sexta é bloqueada com 429 e o cabeçalho Retry-After.',
    rodar: async function () {
      var email = 'bloqueio.' + sufixoAleatorio() + '@demo.local';
      var passos = [];
      for (var i = 1; i <= 6; i++) {
        var r = await api('POST', '/api/v1/auth/login', { corpo: { email: email, senha: 'senha-incorreta-demo', municipioId: municipioId }, token: null, sem401: true, rotulo: 'Login com senha errada (' + i + '/6)' });
        var nota = r.status === 429 ? 'Retry-After: ' + r.retryAfter + ' s' : '';
        passos.push(passoDeTeste('tentativa ' + i, r, i < 6 ? 401 : 429, nota));
      }
      return passos;
    }
  },
  {
    id: 'cpf-duplicado', titulo: 'CPF duplicado', requer: ['inscricao:responsavel:add'],
    descricao: 'O CPF é único. Cadastrar o mesmo CPF duas vezes: 201 e depois 409.',
    rodar: async function () {
      var cpf = cpfAleatorio();
      var dados = { nome: 'Teste CPF duplicado', cpf: cpf, telefone: '11999990000', email: 'cpf.dup@example.com' };
      var primeira = await api('POST', '/api/v1/inscricao/responsaveis', { corpo: dados, rotulo: 'CPF duplicado: 1º cadastro', sem401: true });
      var segunda = await api('POST', '/api/v1/inscricao/responsaveis', { corpo: dados, rotulo: 'CPF duplicado: 2º cadastro', sem401: true });
      return [passoDeTeste('1º cadastro', primeira, 201), passoDeTeste('2º cadastro (mesmo CPF)', segunda, 409)];
    }
  },
  {
    id: 'inexistentes', titulo: 'Criança, turma e matrícula inexistentes', requer: ['inscricao:inscricao:add', 'matricula:vaga:alocar', 'matricula:matricula:confirmar', 'inscricao:crianca:add'],
    descricao: 'Recursos que não existem respondem 404 com mensagem em JSON.',
    rodar: async function () {
      var passos = [];
      passos.push(passoDeTeste('inscrever em turma inexistente',
        await api('POST', '/api/v1/inscricao/inscricoes', { corpo: { criancaId: 1, turmaId: ID_INEXISTENTE, rendaPerCapita: 100, criterioVulnerabilidade: false }, rotulo: 'Inscrição em turma inexistente', sem401: true }), 404));
      var turma = turmaPadrao();
      if (turma) {
        passos.push(passoDeTeste('inscrever criança inexistente (turma real)',
          await api('POST', '/api/v1/inscricao/inscricoes', { corpo: { criancaId: ID_INEXISTENTE, turmaId: turma, rendaPerCapita: 100, criterioVulnerabilidade: false }, rotulo: 'Inscrição de criança inexistente', sem401: true }), 404));
      }
      passos.push(passoDeTeste('cadastrar criança de responsável inexistente',
        await api('POST', '/api/v1/inscricao/criancas', { corpo: { responsavelId: ID_INEXISTENTE, nome: 'Fantasma', dataNascimento: '2024-01-01' }, rotulo: 'Criança de responsável inexistente', sem401: true }), 404));
      passos.push(passoDeTeste('alocar em turma inexistente',
        await api('POST', '/api/v1/matricula/vagas/alocar', { corpo: { turmaId: ID_INEXISTENTE }, rotulo: 'Alocar em turma inexistente', sem401: true }), 404));
      passos.push(passoDeTeste('confirmar matrícula inexistente',
        await api('POST', '/api/v1/matricula/matriculas/' + ID_INEXISTENTE + '/confirmar', { rotulo: 'Confirmar matrícula inexistente', sem401: true }), 404));
      passos.push(passoDeTeste('posição de inscrição inexistente',
        await api('GET', '/api/v1/inscricao/inscricoes/' + ID_INEXISTENTE + '/posicao?turmaId=' + ID_INEXISTENTE, { rotulo: 'Posição de inscrição inexistente', sem401: true }), 404));
      return passos;
    }
  },
  {
    id: 'corpo-invalido', titulo: 'Corpo inválido', requer: ['matricula:vaga:alocar', 'inscricao:inscricao:add', 'inscricao:crianca:add'],
    descricao: 'Campos obrigatórios ausentes ou JSON malformado respondem 400 com a lista de campos inválidos.',
    rodar: async function () {
      return [
        passoDeTeste('alocar com corpo vazio {}', await api('POST', '/api/v1/matricula/vagas/alocar', { corpo: {}, rotulo: 'Corpo vazio', sem401: true }), 400),
        passoDeTeste('alocar com JSON malformado', await api('POST', '/api/v1/matricula/vagas/alocar', { corpo: '{turmaId: nao-e-json', rotulo: 'JSON malformado', sem401: true }), 400),
        passoDeTeste('inscrição com turmaId negativo', await api('POST', '/api/v1/inscricao/inscricoes', { corpo: { criancaId: 1, turmaId: -1, rendaPerCapita: 100, criterioVulnerabilidade: false }, rotulo: 'turmaId negativo', sem401: true }), 400),
        passoDeTeste('inscrição com os dois modos', await api('POST', '/api/v1/inscricao/inscricoes', { corpo: { criancaId: 1, responsavel: { nome: 'A', cpf: '1' }, crianca: { nome: 'B', dataNascimento: '2024-01-01' }, turmaId: 1, rendaPerCapita: 100, criterioVulnerabilidade: false }, rotulo: 'criancaId e inline juntos', sem401: true }), 400),
        passoDeTeste('criança sem nome', await api('POST', '/api/v1/inscricao/criancas', { corpo: { responsavelId: 1 }, rotulo: 'Criança sem campos obrigatórios', sem401: true }), 400)
      ];
    }
  },
  {
    id: 'caminho', titulo: 'Caminho inexistente', requer: [],
    descricao: 'Rotas que não existem respondem 404 em JSON (sem página de erro do servidor).',
    rodar: async function () {
      var a = await api('GET', '/api/v1/nao-existe', { rotulo: 'Caminho inexistente na API', sem401: true });
      var b = await api('GET', '/api/v1/relatorios/nao-existe', { rotulo: 'Caminho inexistente nos relatórios', sem401: true });
      var c = await api('GET', '/nao-existe', { rotulo: 'Caminho inexistente fora da API', sem401: true });
      return [
        passoDeTeste('/api/v1/nao-existe', a, [401, 404], 'resposta ' + (typeof a.dados === 'object' ? 'em JSON' : 'não JSON')),
        passoDeTeste('/api/v1/relatorios/nao-existe', b, [401, 404], 'resposta ' + (typeof b.dados === 'object' ? 'em JSON' : 'não JSON')),
        passoDeTeste('/nao-existe', c, 404, 'resposta ' + (typeof c.dados === 'object' ? 'em JSON' : 'não JSON'))
      ];
    }
  },
  {
    id: 'lgpd', titulo: 'Mascaramento LGPD (CPF e renda)', requer: ['inscricao:inscricao:add'],
    descricao: 'Faz uma inscrição com CPF e renda e confere que nenhuma resposta da API devolve esses dados, e que este registro de chamadas os mascara.',
    rodar: async function () {
      var turma = turmaPadrao();
      if (!turma) {
        var criada = await acaoCriarCrecheETurma({ nome: 'Creche LGPD', capacidade: 10, rua: 'Rua B', lat: -23.56, lon: -46.64 }, 'MATERNAL', 1);
        if (!criada.ok) { return [passoDeTeste('criar turma de apoio', criada, 201)]; }
        turma = criada.dados.turmaId;
      }
      var cpf = cpfAleatorio();
      var digitos = cpf.replace(/\D/g, '');
      var renda = 777.77;
      var inscricao = await acaoInscrever({
        modo: 'inline', turmaId: turma, renda: renda, vulneravel: true,
        responsavel: { nome: 'Resp. LGPD ' + sufixoAleatorio(), cpf: cpf, telefone: '11999990000', email: 'lgpd@example.com', lat: -23.55, lon: -46.63 },
        crianca: { nome: 'Crianca LGPD', nascimento: '2024-03-10' }
      });
      var passos = [passoDeTeste('inscrição com CPF e renda', inscricao, 201)];
      if (!inscricao.ok) { return passos; }
      var posicao = await acaoConsultarPosicao(inscricao.dados.inscricaoId, turma);
      var respostas = inscricao.texto + ' ' + posicao.texto;
      var vazou = respostas.indexOf(cpf) >= 0 || respostas.indexOf(digitos) >= 0;
      var chaves = /"(cpf|rendaPerCapita|renda|criterioVulnerabilidade|vulnerabilidade)"/i.test(respostas);
      var registroLimpo = !logEntradas.slice(0, 6).some(function (e) { return (e.corpoReq + e.corpoResp).indexOf(cpf) >= 0 || (e.corpoReq + e.corpoResp).indexOf(digitos) >= 0; });
      passos.push(passoDeVerificacao('respostas da inscrição e da posição não contêm o CPF', !vazou));
      passos.push(passoDeVerificacao('respostas não trazem campos de renda, vulnerabilidade ou CPF', !chaves));
      passos.push(passoDeVerificacao('registro de chamadas desta tela mascara o CPF enviado', registroLimpo, 'veja a requisição com ***.***.***-' + digitos.slice(-2)));
      return passos;
    }
  }
];

function renderizarUsuario() {
  var alvo = $('s_usuario');
  var perms = permissoesDoToken();
  var expira = sessionStorage.getItem(CHAVES.expira);
  alvo.textContent = '';
  alvo.appendChild(el('div', {}, [
    document.createTextNode('Perfil: '), selo(perfilDoToken(), 'info'),
    document.createTextNode(' município: '), selo(String(municipioId), 'info')
  ]));
  alvo.appendChild(el('p', { class: 'dica', texto: 'Token expira em: ' + (expira ? new Date(expira).toLocaleString('pt-BR') : 'desconhecido') }));
  alvo.appendChild(el('p', { class: 'dica tecnico', texto: 'Permissões: ' + perms.join(', ') }));
}

function renderizarCartoesDeTeste() {
  var alvo = $('s_testes');
  alvo.textContent = '';
  TESTES_SEGURANCA.forEach(function (teste) {
    var botao = el('button', { class: 'mini', texto: 'Executar' });
    var estadoSelo = el('span', { class: 'selo', texto: 'não executado' });
    var resultados = el('div', { class: 'passos-res' });
    var cartao = el('div', { class: 'teste', id: 'teste-' + teste.id }, [
      el('div', { class: 'cab' }, [el('strong', { texto: teste.titulo }), estadoSelo, botao]),
      el('p', { class: 'desc', texto: teste.descricao }),
      resultados
    ]);
    botao.onclick = function () { executarTeste(teste); };
    alvo.appendChild(cartao);
  });
}

// Executa um teste e devolve true quando todos os passos passaram (ou null se foi ignorado)
async function executarTeste(teste) {
  var cartao = $('teste-' + teste.id);
  var botao = cartao.querySelector('button');
  var estadoSelo = cartao.querySelector('.cab .selo');
  var resultados = cartao.querySelector('.passos-res');
  var perms = permissoesDoToken();
  var faltando = teste.requer.filter(function (p) { return !temPermissao(perms, p); });
  resultados.textContent = '';
  if (faltando.length) {
    estadoSelo.textContent = 'ignorado';
    estadoSelo.className = 'selo aviso';
    resultados.appendChild(el('span', { class: 'chip-res', texto: 'O perfil ' + perfilDoToken() + ' não tem as permissões necessárias para este teste (' + faltando[0] + '). Use a matriz de permissões ou entre como ADMIN.' }));
    return null;
  }
  botao.disabled = true;
  estadoSelo.textContent = 'executando...';
  estadoSelo.className = 'selo';
  var passos;
  try {
    passos = await teste.rodar();
  } catch (erro) {
    passos = [passoDeVerificacao('erro inesperado no teste', false, String(erro && erro.message))];
  }
  var todosOk = passos.every(function (p) { return p.ok; });
  passos.forEach(function (p) {
    var texto = p.rotulo + (p.status !== null ? ': ' + (p.status === 0 ? 'falha de rede' : p.status) + (p.ok ? '' : ' (esperado ' + p.esperados.join(' ou ') + ')') : (p.ok ? ': ok' : ': falhou')) + (p.nota ? ' | ' + p.nota : '');
    resultados.appendChild(el('span', { class: 'chip-res ' + (p.ok ? 'ok' : 'erro'), texto: texto }));
  });
  estadoSelo.textContent = todosOk ? 'passou' : 'falhou';
  estadoSelo.className = 'selo ' + (todosOk ? 'ok' : 'erro');
  botao.disabled = false;
  return todosOk;
}

async function executarTodosOsTestes() {
  var botao = $('s_btnTodos');
  botao.disabled = true;
  var passaram = 0;
  var ignorados = 0;
  for (var i = 0; i < TESTES_SEGURANCA.length; i++) {
    var r = await executarTeste(TESTES_SEGURANCA[i]);
    if (r === true) { passaram++; } else if (r === null) { ignorados++; }
  }
  var resumo = $('s_resumo');
  var executados = TESTES_SEGURANCA.length - ignorados;
  resumo.textContent = passaram + ' de ' + executados + ' testes passaram' + (ignorados ? ' (' + ignorados + ' ignorados pelo perfil)' : '');
  resumo.className = 'selo ' + (passaram === executados ? 'ok' : 'erro');
  botao.disabled = false;
}

// ---------------------------------------------------------------------------
// Matriz de permissões do usuário atual
// ---------------------------------------------------------------------------

function endpointsDaMatriz() {
  var m = municipioId;
  return [
    { nome: 'Cadastrar responsável', perm: 'inscricao:responsavel:add', metodo: 'POST', caminho: '/api/v1/inscricao/responsaveis', corpo: function () { return { nome: 'Teste matriz', cpf: cpfAleatorio(), telefone: '11999990000', email: 'matriz@example.com' }; } },
    { nome: 'Cadastrar criança', perm: 'inscricao:crianca:add', metodo: 'POST', caminho: '/api/v1/inscricao/criancas', corpo: function () { return { responsavelId: ID_INEXISTENTE, nome: 'Fantasma', dataNascimento: '2024-01-01' }; } },
    { nome: 'Inscrever', perm: 'inscricao:inscricao:add', metodo: 'POST', caminho: '/api/v1/inscricao/inscricoes', corpo: function () { return { criancaId: ID_INEXISTENTE, turmaId: ID_INEXISTENTE, rendaPerCapita: 100, criterioVulnerabilidade: false }; } },
    { nome: 'Consultar posição na fila', perm: 'inscricao:fila:detail', metodo: 'GET', caminho: '/api/v1/inscricao/inscricoes/' + ID_INEXISTENTE + '/posicao?turmaId=' + ID_INEXISTENTE },
    { nome: 'Criar creche', perm: 'matricula:creche:add', metodo: 'POST', caminho: '/api/v1/matricula/creches', corpo: function () { return {}; } },
    { nome: 'Criar turma', perm: 'matricula:turma:add', metodo: 'POST', caminho: '/api/v1/matricula/turmas', corpo: function () { return {}; } },
    { nome: 'Alocar vaga', perm: 'matricula:vaga:alocar', metodo: 'POST', caminho: '/api/v1/matricula/vagas/alocar', corpo: function () { return { turmaId: ID_INEXISTENTE }; } },
    { nome: 'Confirmar matrícula', perm: 'matricula:matricula:confirmar', metodo: 'POST', caminho: '/api/v1/matricula/matriculas/' + ID_INEXISTENTE + '/confirmar' },
    { nome: 'Relatório de ocupação', perm: 'relatorios:ocupacao:list', metodo: 'GET', caminho: '/api/v1/relatorios/ocupacao?municipioId=' + m },
    { nome: 'Relatório da fila de espera', perm: 'relatorios:fila:list', metodo: 'GET', caminho: '/api/v1/relatorios/fila-espera?municipioId=' + m },
    { nome: 'Relatório de matrículas', perm: 'relatorios:matricula:list', metodo: 'GET', caminho: '/api/v1/relatorios/matricula/taxa-confirmacao?municipioId=' + m + '&periodo=' + periodoComoData(periodoSelecionado()) }
  ];
}

async function executarMatriz() {
  var botao = $('s_btnMatriz');
  botao.disabled = true;
  var perms = permissoesDoToken();
  var linhas = [];
  var divergencias = 0;
  var lista = endpointsDaMatriz();
  for (var i = 0; i < lista.length; i++) {
    var e = lista[i];
    var r = await api(e.metodo, e.caminho, { corpo: e.corpo ? e.corpo() : undefined, rotulo: 'Matriz: ' + e.nome, sem401: true });
    var esperadoPermitido = temPermissao(perms, e.perm);
    var permitido = r.status !== 401 && r.status !== 403 && r.status !== 0;
    var confere = r.status !== 0 && esperadoPermitido === permitido;
    if (!confere) { divergencias++; }
    var nota = permitido ? (r.status >= 200 && r.status < 300 ? 'executou' : 'passou da permissão (dado fictício)') : 'bloqueado';
    linhas.push([
      e.nome, el('code', { texto: e.perm }), selo(esperadoPermitido ? 'permitido' : 'negado', esperadoPermitido ? 'ok' : 'erro'),
      seloStatus(r.status), nota, selo(confere ? 'confere' : 'diverge', confere ? 'ok' : 'erro')
    ]);
    preencherTabela($('s_matriz'), ['Endpoint', 'Permissão exigida', 'Esperado', 'Status', 'Observação', 'Resultado'], linhas, '');
  }
  avisar(divergencias ? divergencias + ' divergência(s) na matriz.' : 'Matriz conferida: o comportamento bate com o perfil ' + perfilDoToken() + '.', divergencias ? 'erro' : 'ok');
  botao.disabled = false;
}

function iniciarSeguranca() {
  renderizarCartoesDeTeste();
  renderizarUsuario();
  $('s_btnTodos').onclick = executarTodosOsTestes;
  $('s_btnMatriz').onclick = executarMatriz;
  $('s_btnTrocar').onclick = irParaLogin;
  preencherTabela($('s_matriz'), ['Endpoint', 'Permissão exigida', 'Esperado', 'Status', 'Observação', 'Resultado'], [], 'Clique em Executar matriz.');
}
