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
  renderizarVisao();
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

function iniciarNavegacao() {
  $$('.nav-item').forEach(function (b) { b.onclick = function () { irParaAba(b.getAttribute('data-aba')); }; });
  $('btnModo').onclick = function () { aplicarModo(document.body.classList.contains('apresentacao') ? 'tecnico' : 'apresentacao'); };
  $('btnSair').onclick = irParaLogin;
  $('btnReiniciar').onclick = function () {
    reiniciarEstadoLocal();
    snapshot = null;
    snapshotAnterior = null;
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

function iniciar() {
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
  iniciarRoteiro();
  renderizarCabecalhoDeUsuario();
  aplicarModo(sessionStorage.getItem(CHAVES.modo) || 'tecnico');
  aoMudarEstado();
  irParaAba(sessionStorage.getItem(CHAVES.aba) || 'visao');
  atualizarRelatorios();
}

iniciar();
