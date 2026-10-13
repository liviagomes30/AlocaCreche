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
  // A projeção é aplicada em ordem: se um evento mais novo do grupo foi refletido, os mais antigos também foram,
  // mesmo que um valor intermediário (ex.: fila = 1 entre duas alocações seguidas) nunca tenha sido lido no relatório
  var gruposComEventoRefletido = {};
  estado.eventos.forEach(function (e) {
    var chave = chaveDoGrupo(e);
    if (e.estado === 'refletido') { gruposComEventoRefletido[chave] = true; }
    else if (gruposComEventoRefletido[chave]) { e.estado = 'refletido'; }
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

// Nome da criança de cada inscrição, para contar o que o job fez em linguagem de gente
async function nomesPorInscricao() {
  var r = await api('GET', '/api/v1/inscricao/inscricoes', { automatica: true, rotulo: 'Core: inscrições' });
  var nomes = {};
  if (r.ok) { r.dados.forEach(function (i) { nomes[i.id] = i.nome; }); }
  return nomes;
}

async function aoVencerPrazo() {
  var matriculaId = Number($('e_expSel').value);
  var saida = $('e_jobRes');
  if (!matriculaId) { avisar('Aloque uma vaga primeiro (matrícula PENDENTE).', 'erro'); return; }
  var r = await api('POST', '/api/v1/matricula/matriculas/' + matriculaId + '/vencer-prazo', { rotulo: 'Vencer o prazo da matrícula ' + matriculaId });
  saida.textContent = r.ok
    ? 'Prazo da matrícula #' + matriculaId + ' colocado em ' + String(r.dados.prazoConfirmacao).slice(0, 10) + ' (vencido). Ela ainda está PENDENTE: agora execute o job.'
    : 'Não foi possível vencer o prazo: ' + mensagemDeErro(r);
}

// Roda uma rodada do job na hora e conta o que mudou comparando as matrículas antes e depois
async function aoExecutarJob() {
  var saida = $('e_jobRes');
  var botao = $('e_btnJob');
  botao.disabled = true;
  saida.textContent = 'Executando uma rodada do job...';
  try {
    var antes = await api('GET', '/api/v1/matricula/matriculas', { automatica: true, rotulo: 'Core: matrículas (antes do job)' });
    var rodada = await api('POST', '/api/v1/matricula/manutencao/executar', { rotulo: 'Executar uma rodada do job de manutenção' });
    if (!rodada.ok) { saida.textContent = 'Não foi possível executar o job: ' + mensagemDeErro(rodada); return; }
    var depois = await api('GET', '/api/v1/matricula/matriculas', { automatica: true, rotulo: 'Core: matrículas (depois do job)' });
    var nomes = await nomesPorInscricao();
    var statusAntes = {};
    if (antes.ok) { antes.dados.forEach(function (m) { statusAntes[m.id] = m.status; }); }
    var mudancas = [];
    if (depois.ok) {
      depois.dados.forEach(function (m) {
        if (statusAntes[m.id] === undefined) { mudancas.push('Nova matrícula #' + m.id + ' (' + m.status + ') para ' + (nomes[m.inscricaoId] || 'inscrição #' + m.inscricaoId) + ': convocada pelo job'); }
        else if (statusAntes[m.id] !== m.status) { mudancas.push('Matrícula #' + m.id + ' de ' + (nomes[m.inscricaoId] || 'inscrição #' + m.inscricaoId) + ': ' + statusAntes[m.id] + ' → ' + m.status); }
      });
    }
    saida.textContent = mudancas.length ? 'O job fez: ' + mudancas.join(' · ') + '.' : 'O job rodou e não encontrou nada para fazer (nenhum prazo vencido e nenhuma vaga livre com fila).';
    await carregarDoBanco();
    aoMudarEstado();
    atualizarRelatorios();
  } finally {
    botao.disabled = false;
  }
}

function iniciarEventos() {
  $('e_btnVencer').onclick = aoVencerPrazo;
  $('e_btnJob').onclick = aoExecutarJob;
  $('e_expSel').onchange = renderizarJobDeExpiracao;
  $('e_btnCopiarSql').onclick = function () { copiar($('e_expSql').textContent, 'SQL copiado.'); };
  $('e_btnCopiarSqlMatriculas').onclick = function () { copiar($('e_sqlMatriculas').textContent, 'SQL copiado.'); };
  $('e_btnExpiracao').onclick = aoAcompanharExpiracao;
  renderizarJobDeExpiracao();
  $$('[data-copiar]').forEach(function (b) { b.onclick = function () { copiar(b.getAttribute('data-copiar'), 'Comando copiado.'); }; });
  $('e_btnVerificar').onclick = async function () { await atualizarRelatorios(); avisar('Relatórios consultados.', 'ok'); };
  renderizarTimeline();
}
