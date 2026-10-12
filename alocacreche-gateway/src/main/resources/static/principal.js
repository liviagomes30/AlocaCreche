/*
 * Inicialização da página: sessão, navegação entre abas, modo apresentação/técnico,
 * reinício da sessão local e atalhos da visão geral.
 */

var ABAS = ['visao', 'inscricao', 'matricula', 'relatorios', 'seguranca', 'eventos', 'roteiro'];

function irParaAba(nome) {
  if (ABAS.indexOf(nome) < 0) { nome = 'visao'; }
  ABAS.forEach(function (a) { $('aba-' + a).classList.toggle('ativa', a === nome); });
  $$('.nav-item').forEach(function (b) { b.classList.toggle('ativo', b.getAttribute('data-aba') === nome); });
  sessionStorage.setItem(CHAVES.aba, nome);
  $('conteudo').scrollTop = 0;
  if (nome === 'visao' || nome === 'relatorios') { renderizarVisao(); }
  if (nome === 'eventos') { renderizarTimeline(); }
  if (nome === 'seguranca') { renderizarUsuario(); }
  if (nome === 'roteiro') { verificarAmbiente(); }
}

function aplicarModo(modo) {
  var apresentacao = modo === 'apresentacao';
  document.body.classList.toggle('apresentacao', apresentacao);
  $('btnModo').textContent = 'Modo: ' + (apresentacao ? 'apresentação' : 'técnico');
  sessionStorage.setItem(CHAVES.modo, modo);
}

function renderizarCabecalhoDeUsuario() {
  var perfil = perfilDoToken();
  var chip = $('chipUsuario');
  chip.textContent = '';
  chip.appendChild(document.createTextNode('Perfil '));
  chip.appendChild(el('b', { texto: perfil }));
  chip.appendChild(document.createTextNode(' | município '));
  chip.appendChild(el('b', { texto: String(municipioId) }));
  var lista = $('listaPermissoes');
  lista.textContent = '';
  permissoesDoToken().forEach(function (p) { lista.appendChild(el('li', { texto: p })); });
  if (!permissoesDoToken().length) { lista.appendChild(el('li', { texto: '(nenhuma permissão no token)' })); }
}

function renderizarResumoDaSessao() {
  $('vz_turma').textContent = estado.turmaAtual || '-';
  $('vz_matricula').textContent = estado.matriculaAtual || '-';
  var aguardando = estado.inscricoes.filter(function (i) { return i.status === 'AGUARDANDO'; }).length;
  $('resumoSessao').textContent = estado.creches.length + ' creche(s), ' + estado.turmas.length + ' turma(s), ' + estado.inscricoes.length +
    ' inscrição(ões) (' + aguardando + ' na fila), ' + estado.matriculas.length + ' matrícula(s) e ' + estado.eventos.length + ' evento(s) esperado(s) nesta sessão.';
}

// Chamado depois de qualquer ação que muda o estado local
function aoMudarEstado() {
  renderizarInscricao();
  renderizarMatricula();
  renderizarFiltrosDetalhe();
  renderizarResumoDaSessao();
  renderizarTimeline();
  renderizarJobDeExpiracao();
  renderizarVisao();
  renderizarResiliencia();
}

async function atalhoCrecheETurma() {
  var r = await acaoCriarCrecheETurma({ nome: 'Creche ' + sufixoAleatorio(), capacidade: 30, rua: 'Rua B', lat: -23.56, lon: -46.64 }, 'MATERNAL', 3);
  avisar(r.ok ? 'Creche e turma #' + r.dados.turmaId + ' criadas (3 vagas).' : 'Erro: ' + mensagemDeErro(r), r.ok ? 'ok' : 'erro');
}

async function atalhoInscrever() {
  var turmaId = turmaPadrao();
  if (!turmaId) { avisar('Crie uma creche e uma turma primeiro.', 'erro'); return; }
  var perfis = ['baixa', 'media', 'alta'];
  var r = await acaoInscreverExemplo(turmaId, perfis[estado.inscricoes.length % perfis.length]);
  avisar(r.ok ? 'Inscrição #' + r.dados.inscricaoId + ': posição ' + r.dados.posicao + ', score ' + numero(r.dados.scorePrioridade, 2) : 'Erro: ' + mensagemDeErro(r), r.ok ? 'ok' : 'erro');
}

async function atalhoAlocar() {
  var turmaId = turmaPadrao();
  if (!turmaId) { avisar('Crie uma creche e uma turma primeiro.', 'erro'); return; }
  var r = await acaoAlocar(turmaId);
  avisar(r.ok ? 'Matrícula #' + r.dados.matriculaId + ' alocada (PENDENTE).' : 'Alocação: ' + mensagemDeErro(r), r.ok ? 'ok' : 'erro');
}

async function atalhoConfirmar() {
  if (!estado.matriculaAtual) { avisar('Aloque uma vaga primeiro.', 'erro'); return; }
  var r = await acaoConfirmar(estado.matriculaAtual);
  avisar(r.ok ? 'Matrícula #' + estado.matriculaAtual + ' confirmada.' : 'Confirmação: ' + mensagemDeErro(r), r.ok ? 'ok' : 'erro');
}

// Copia o token da sessão para a área de transferência (nunca o exibe na tela) e abre o Swagger do
// serviço em outra aba; lá basta clicar em Authorize e colar. O navegador isola o sessionStorage por
// porta, por isso o token não pode ser repassado sozinho entre a tela (8090) e o Swagger (8083/8082).
async function abrirSwagger(porta, nome) {
  var url = location.protocol + '//' + location.hostname + ':' + porta + '/swagger-ui.html';
  try {
    await navigator.clipboard.writeText(token);
    avisar('Token copiado. No Swagger do ' + nome + ', clique em Authorize, cole (Ctrl+V) e confirme.', 'ok');
  } catch (e) {
    avisar('Não consegui copiar o token automaticamente. Faça o login pelo próprio Swagger.', 'erro');
  }
  window.open(url, '_blank', 'noopener');
}

function iniciarNavegacao() {
  $$('.nav-item').forEach(function (b) { b.onclick = function () { irParaAba(b.getAttribute('data-aba')); }; });
  $('btnModo').onclick = function () { aplicarModo(document.body.classList.contains('apresentacao') ? 'tecnico' : 'apresentacao'); };
  $('btnSair').onclick = irParaLogin;
  $('btnSwaggerCore').onclick = function () { abrirSwagger(8083, 'core'); };
  $('btnSwaggerRel').onclick = function () { abrirSwagger(8082, 'relatórios'); };
  $('btnReiniciar').onclick = function () {
    reiniciarEstadoLocal();
    snapshot = null;
    snapshotAnterior = null;
    definirIntervaloAuto(estado.intervalo);
    $('narrador').classList.remove('visivel');
    aoMudarEstado();
    atualizarRelatorios();
    avisar('Sessão local reiniciada. O banco não foi alterado.', 'ok');
  };
  $('chipUsuario').onclick = function () { $('painelPermissoes').classList.toggle('aberto'); };
  document.addEventListener('click', function (evento) {
    if (!evento.target.closest('#chipUsuario') && !evento.target.closest('#painelPermissoes')) { $('painelPermissoes').classList.remove('aberto'); }
  });
  $('vz_btnCrecheTurma').onclick = atalhoCrecheETurma;
  $('vz_btnInscrever').onclick = atalhoInscrever;
  $('vz_btnAlocar').onclick = atalhoAlocar;
  $('vz_btnConfirmar').onclick = atalhoConfirmar;
}

// Busca no banco as listagens reais e substitui as listas locais; lista que falhar (ex.: 403 por
// falta de permissão do perfil) mantém o que já existia no estado local.
async function carregarDoBanco() {
  var r = await Promise.all([
    api('GET', '/api/v1/matricula/creches', { automatica: true, rotulo: 'Carregar creches' }),
    api('GET', '/api/v1/matricula/turmas', { automatica: true, rotulo: 'Carregar turmas' }),
    api('GET', '/api/v1/inscricao/responsaveis', { automatica: true, rotulo: 'Carregar responsáveis' }),
    api('GET', '/api/v1/inscricao/criancas', { automatica: true, rotulo: 'Carregar crianças' }),
    api('GET', '/api/v1/inscricao/inscricoes', { automatica: true, rotulo: 'Carregar inscrições' }),
    api('GET', '/api/v1/matricula/matriculas', { automatica: true, rotulo: 'Carregar matrículas' })
  ]);
  var creches = r[0], turmas = r[1], responsaveis = r[2], criancas = r[3], inscricoes = r[4], matriculas = r[5];
  if (creches.ok) {
    estado.creches = creches.dados.map(function (c) { return { id: c.id, nome: c.nome, capacidade: c.capacidadeTotal, lat: c.latitude, lon: c.longitude }; });
  }
  if (turmas.ok) {
    estado.turmas = turmas.dados.map(function (t) {
      return { id: t.id, crecheId: t.crecheId, faixa: t.faixaEtaria, vagas: t.vagasTotais, alocadas: t.vagasTotais - t.vagasDisponiveis };
    });
    if (!acharPorId(estado.turmas, estado.turmaAtual)) { estado.turmaAtual = estado.turmas.length ? estado.turmas[estado.turmas.length - 1].id : null; }
  }
  if (responsaveis.ok) {
    estado.responsaveis = responsaveis.dados.map(function (x) { return { id: x.id, nome: x.nome, lat: x.latitude, lon: x.longitude }; });
  }
  if (criancas.ok) {
    estado.criancas = criancas.dados.map(function (c) { return { id: c.id, responsavelId: c.responsavelId, nome: c.nome }; });
  }
  if (inscricoes.ok) {
    estado.inscricoes = inscricoes.dados.map(function (i) {
      var crianca = acharPorId(estado.criancas, i.criancaId);
      var resp = crianca ? acharPorId(estado.responsaveis, crianca.responsavelId) : null;
      return {
        id: i.id, turmaId: i.turmaId, nome: i.nome, posicao: i.posicao, score: i.scorePrioridade, status: i.status,
        origem: resp ? { lat: resp.lat, lon: resp.lon } : null, modo: 'banco', explicacao: 'carregada do banco'
      };
    });
  }
  if (matriculas.ok) {
    estado.matriculas = matriculas.dados.map(function (m) {
      var inscricao = acharPorId(estado.inscricoes, m.inscricaoId);
      return { id: m.id, turmaId: m.turmaId, inscricaoId: m.inscricaoId, nome: inscricao ? inscricao.nome : '?', status: m.status, distanciaKm: m.distanciaKm };
    });
    if (!acharPorId(estado.matriculas, estado.matriculaAtual)) { estado.matriculaAtual = null; }
  }
  salvarEstado();
}

async function iniciar() {
  if (!token) { irParaLogin(); return; }
  // Página restaurada do cache de navegação (botão Voltar) sem token: volta ao login
  window.addEventListener('pageshow', function (evento) {
    if (evento.persisted && !sessionStorage.getItem(CHAVES.token)) { irParaLogin(); }
  });
  iniciarRegistro();
  iniciarNavegacao();
  iniciarPainel();
  iniciarInscricao();
  iniciarMatricula();
  iniciarSeguranca();
  iniciarEventos();
  iniciarResiliencia();
  iniciarDemoStackC();
  iniciarRoteiro();
  renderizarCabecalhoDeUsuario();
  aplicarModo(sessionStorage.getItem(CHAVES.modo) || 'tecnico');
  await carregarDoBanco();
  aoMudarEstado();
  irParaAba(sessionStorage.getItem(CHAVES.aba) || 'visao');
  atualizarRelatorios();
}

iniciar();
