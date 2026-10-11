/*
 * Aba Eventos e Outbox: linha do tempo de eventos esperados (gerada localmente),
 * conferência contra os relatórios e animação do fluxo Core -> Outbox -> RabbitMQ -> MSRelatorios.
 */

var temporizadorFluxo = null;
var animandoFluxo = false;

function eventosPendentes() {
  return estado.eventos.filter(function (e) { return e.estado === 'esperado'; });
}

function contarPendentes() { return eventosPendentes().length; }

// Valor que o relatório mostrava para o alvo no último instantâneo (undefined quando a linha não existe)
function valorAtualDoAlvo(alvo) {
  if (!snapshot || snapshot.status.ocupacao !== 200) { return undefined; }
  if (alvo.tipo === 'vaga') {
    var valor;
    snapshot.ocupacao.forEach(function (o) { if (o.turmaId === alvo.turmaId) { valor = o.vagasDisponiveis; } });
    return valor;
  }
  if (alvo.tipo === 'fila') {
    var f = filaDaTurma(alvo.turmaId);
    return f ? f.quantidadeAguardando : 0;
  }
  return undefined;
}

/*
 * Registra o evento que a ação acabou de gravar na Outbox (conforme as regras do núcleo).
 * Eventos de uma mesma ação compartilham o acaoId. Um evento é "observável" quando o número que
 * ele altera difere do que os relatórios mostravam; os demais (ex.: a fila volta ao mesmo valor
 * depois de uma inscrição seguida de alocação) só são dados como refletidos junto de um irmão.
 */
function registrarEvento(acao, tipoEvento, alvo, acaoId) {
  var alvoFinal = Object.assign({}, alvo);
  var baseTaxa = snapshot && snapshot.taxa ? snapshot.taxa : null;
  var pendentesDoTipo = eventosPendentes().filter(function (e) { return e.alvo.tipo === alvo.tipo; }).length;
  if (alvo.tipo === 'matriculas') { alvoFinal.minimo = (baseTaxa ? baseTaxa.totalMatriculas : 0) + pendentesDoTipo + 1; }
  if (alvo.tipo === 'confirmadas') { alvoFinal.minimo = (baseTaxa ? baseTaxa.totalConfirmadas : 0) + pendentesDoTipo + 1; }
  var base = valorAtualDoAlvo(alvoFinal);
  estado.seqEvento = (estado.seqEvento || 0) + 1;
  estado.eventos.unshift({
    id: estado.seqEvento, acaoId: acaoId || ('e' + estado.seqEvento), hora: new Date().toLocaleTimeString('pt-BR'), acao: acao,
    evento: tipoEvento, alvo: alvoFinal, estado: 'esperado',
    observavel: base === undefined || base !== alvoFinal.valor
  });
  if (estado.eventos.length > 80) { estado.eventos.length = 80; }
  pulsarFluxo();
}

function alvoAtingido(alvo) {
  if (!snapshot || snapshot.status.ocupacao !== 200) { return false; }
  if (alvo.tipo === 'vaga') {
    var atual = valorAtualDoAlvo(alvo);
    if (atual === undefined) { return false; }
    return alvo.valor === null || alvo.valor === undefined || atual === alvo.valor;
  }
  if (alvo.tipo === 'fila') { return valorAtualDoAlvo(alvo) === alvo.valor; }
  if (alvo.tipo === 'matriculas') { return !!snapshot.taxa && snapshot.taxa.totalMatriculas >= alvo.minimo; }
  if (alvo.tipo === 'confirmadas') { return !!snapshot.taxa && snapshot.taxa.totalConfirmadas >= alvo.minimo; }
  return false;
}

function chaveDoGrupo(e) { return e.alvo.tipo + ':' + (e.alvo.turmaId || '*'); }

/*
 * Marca como refletidos os eventos cujo número já apareceu nos relatórios.
 * Do mais novo para o mais antigo: se o evento novo foi refletido, os mais antigos do
 * mesmo grupo também foram (a projeção é aplicada em ordem). Eventos não observáveis
 * acompanham os irmãos da mesma ação.
 */
function avaliarEventos() {
  var gruposRefletidos = {};
  var acoesRefletidas = {};
  var acoesComObservavel = {};
  estado.eventos.forEach(function (e) { if (e.observavel !== false) { acoesComObservavel[e.acaoId] = true; } });
  estado.eventos.forEach(function (e) {
    var autonomo = e.observavel !== false || !acoesComObservavel[e.acaoId];
    if (!autonomo) { return; }
    var chave = chaveDoGrupo(e);
    if (gruposRefletidos[chave]) { e.estado = 'refletido'; }
    else if (e.estado === 'refletido') { gruposRefletidos[chave] = true; }
    else if (alvoAtingido(e.alvo)) { e.estado = 'refletido'; gruposRefletidos[chave] = true; }
    if (e.estado === 'refletido') { acoesRefletidas[e.acaoId] = true; }
  });
  estado.eventos.forEach(function (e) {
    if (e.observavel === false && acoesComObservavel[e.acaoId] && acoesRefletidas[e.acaoId]) { e.estado = 'refletido'; }
  });
  salvarEstado();
  renderizarTimeline();
  atualizarEstadoFinalDoFluxo();
}

function renderizarTimeline() {
  var lista = $('e_timeline');
  if (!lista) { return; }
  lista.textContent = '';
  estado.eventos.forEach(function (e) {
    var refletido = e.estado === 'refletido';
    lista.appendChild(el('li', {}, [
      el('span', { class: 'dica', texto: e.hora }),
      el('span', {}, [el('span', { class: 'evento', texto: e.evento }), el('div', { class: 'dica', style: 'margin:0', texto: e.acao })]),
      selo(refletido ? 'refletido nos relatórios' : 'aguardando relatórios', refletido ? 'ok' : 'aviso')
    ]));
  });
  if (!estado.eventos.length) { lista.appendChild(el('li', {}, [el('span'), el('span', { class: 'vazio', texto: 'Nenhum evento ainda. Faça uma ação nas abas Stack A ou B.' }), el('span')])); }
  var pendentes = contarPendentes();
  var seloPend = $('e_pendentes');
  seloPend.textContent = pendentes ? pendentes + ' evento(s) aguardando' : 'tudo refletido';
  seloPend.className = 'selo ' + (pendentes ? 'aviso' : 'ok');
}

function limparFluxo() {
  $$('#fluxo .no').forEach(function (n) { n.classList.remove('ativo', 'fim'); });
  $$('#fluxo .seta').forEach(function (n) { n.classList.remove('ativa'); });
}

// Anima o caminho do evento pelos componentes, um por vez
function pulsarFluxo() {
  if (temporizadorFluxo) { clearTimeout(temporizadorFluxo); }
  var nos = $$('#fluxo .no');
  var setas = $$('#fluxo .seta');
  if (!nos.length) { return; }
  var i = 0;
  animandoFluxo = true;
  $('e_estadoFluxo').textContent = 'Evento gravado na Outbox e a caminho do MSRelatorios...';
  function passo() {
    limparFluxo();
    if (i < 5) {
      nos[i].classList.add('ativo');
      setas[i].classList.add('ativa');
      i++;
      temporizadorFluxo = setTimeout(passo, 650);
    } else {
      animandoFluxo = false;
      nos[4].classList.add('ativo');
      atualizarEstadoFinalDoFluxo();
    }
  }
  passo();
}

// Quando não há mais eventos pendentes, o último trecho (Painel) fica verde
function atualizarEstadoFinalDoFluxo() {
  var nos = $$('#fluxo .no');
  if (!nos.length || animandoFluxo) { return; }
  var pendentes = contarPendentes();
  if (estado.eventos.length && !pendentes && !$$('#fluxo .seta.ativa').length) {
    nos.forEach(function (n) { n.classList.remove('ativo'); });
    nos[5].classList.add('fim');
    $('e_estadoFluxo').textContent = 'Todos os eventos foram refletidos nos relatórios.';
  } else if (pendentes) {
    $('e_estadoFluxo').textContent = pendentes + ' evento(s) ainda não refletidos nos relatórios (consistência eventual ou RabbitMQ parado).';
  }
}

// SQL (para o DBeaver) que vence o prazo da matrícula escolhida (a tabela tem RLS: o município vai no set_config da sessão).
// Usa uma data fixa no passado porque o prazo é gravado no fuso da JVM e o now() do Postgres pode estar em outro fuso.
function comandoDeExpiracao(matriculaId) {
  return "SELECT set_config('app.current_tenant', '" + municipioId + "', false);\n"
    + "UPDATE matricula SET prazo_confirmacao = TIMESTAMP '2000-01-01 00:00:00' WHERE id = " + (matriculaId || 0) + " AND status = 'PENDENTE';";
}

// Consulta das matrículas para o DBeaver (a tabela tem RLS: o município vai no set_config da sessão).
function comandoDeMatriculas() {
  return "SELECT set_config('app.current_tenant', '" + municipioId + "', false);\n"
    + "SELECT id, status, prazo_confirmacao FROM matricula ORDER BY id DESC LIMIT 10;";
}

function renderizarJobDeExpiracao() {
  var pendentes = matriculasComId().filter(function (m) { return m.status === 'PENDENTE'; });
  preencherSelect($('e_expSel'), pendentes.map(function (m) { return { valor: m.id, rotulo: '#' + m.id + ' ' + m.nome + ' (turma #' + m.turmaId + ', ' + filaLocalDaTurma(m.turmaId).length + ' na fila)' }; }), '(nenhuma matrícula PENDENTE nesta sessão)');
  $('e_expSql').textContent = comandoDeExpiracao(Number($('e_expSel').value));
  $('e_sqlMatriculas').textContent = comandoDeMatriculas();
}

function aoAcompanharExpiracao() {
  var matriculaId = Number($('e_expSel').value);
  if (!matriculaId) { avisar('Aloque uma vaga primeiro (matrícula PENDENTE).', 'erro'); return; }
  acaoAcompanharExpiracao(matriculaId);
  avisar('Expiração da matrícula #' + matriculaId + ' marcada: rode o SQL e espere o job. Veja a linha do tempo.', 'ok');
}

function iniciarEventos() {
  $('e_expSel').onchange = renderizarJobDeExpiracao;
  $('e_btnCopiarSql').onclick = function () { copiar($('e_expSql').textContent, 'SQL copiado.'); };
  $('e_btnCopiarSqlMatriculas').onclick = function () { copiar($('e_sqlMatriculas').textContent, 'SQL copiado.'); };
  $('e_btnExpiracao').onclick = aoAcompanharExpiracao;
  renderizarJobDeExpiracao();
  $$('[data-copiar]').forEach(function (b) { b.onclick = function () { copiar(b.getAttribute('data-copiar'), 'Comando copiado.'); }; });
  $('e_btnVerificar').onclick = async function () { await atualizarRelatorios(); avisar('Relatórios consultados.', 'ok'); };
  renderizarTimeline();
}
