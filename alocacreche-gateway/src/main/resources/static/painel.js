/*
 * Painel ao vivo (visão geral) e aba Stack C: consulta dos relatórios do MSRelatorios,
 * atualização manual e automática, destaque de valores que mudam e painel público.
 */

var snapshot = null;
var snapshotAnterior = null;
var emAtualizacao = false;
var temporizadorAuto = null;
var acompanhando = false;
var acompanhamentoAte = 0;

function municipioDosRelatorios() { return estado.municipioRel || municipioId; }
function periodoSelecionado() { return estado.periodo || periodoPadrao(); }

// Consulta os cinco endpoints de relatório em paralelo e devolve um instantâneo
async function consultarRelatorios(opcoes) {
  opcoes = opcoes || {};
  var mun = opcoes.municipio || municipioDosRelatorios();
  var base = '/api/v1/relatorios/';
  var filtros = '';
  if (opcoes.turmaId) { filtros += '&turmaId=' + opcoes.turmaId; }
  if (opcoes.crecheId) { filtros += '&crecheId=' + opcoes.crecheId; }
  var periodo = periodoComoData(periodoSelecionado());
  var auto = !!opcoes.automatica;
  var r = await Promise.all([
    api('GET', base + 'ocupacao?municipioId=' + mun + filtros, { automatica: auto, rotulo: 'Relatório: ocupação', sem401: opcoes.sem401 }),
    api('GET', base + 'fila-espera?municipioId=' + mun + filtros, { automatica: auto, rotulo: 'Relatório: fila de espera', sem401: opcoes.sem401 }),
    api('GET', base + 'matricula/taxa-confirmacao?municipioId=' + mun + '&periodo=' + periodo, { automatica: auto, rotulo: 'Relatório: taxa de confirmação', sem401: opcoes.sem401 }),
    api('GET', base + 'matricula/distancia-media?municipioId=' + mun + '&periodo=' + periodo, { automatica: auto, rotulo: 'Relatório: distância média', sem401: opcoes.sem401 }),
    api('GET', base + 'painel-publico?municipioId=' + mun, { automatica: auto, rotulo: 'Painel público', token: null })
  ]);
  return {
    hora: new Date(), municipio: mun, respostas: r,
    status: { ocupacao: r[0].status, fila: r[1].status, taxa: r[2].status, dist: r[3].status, painel: r[4].status },
    ocupacao: r[0].ok && Array.isArray(r[0].dados) ? r[0].dados : [],
    fila: r[1].ok && Array.isArray(r[1].dados) ? r[1].dados : [],
    taxa: r[2].ok ? r[2].dados : null,
    dist: r[3].ok ? r[3].dados : null,
    painel: r[4].ok ? r[4].dados : null
  };
}

async function atualizarRelatorios() {
  if (emAtualizacao) { return snapshot; }
  emAtualizacao = true;
  try {
    var novo = await consultarRelatorios({ automatica: true });
    snapshotAnterior = snapshot;
    snapshot = novo;
    atualizarIndicadoresDeHora();
    renderizarVisao();
    atualizarAvisosDasBarras();
    if (typeof avaliarEventos === 'function') { avaliarEventos(); }
  } finally {
    emAtualizacao = false;
  }
  return snapshot;
}

async function garantirSnapshot() {
  if (!snapshot) { await atualizarRelatorios(); }
}

function atualizarIndicadoresDeHora() {
  var hora = snapshot.hora.toLocaleTimeString('pt-BR');
  $('topoUltima').textContent = hora;
  $$('.b-ult').forEach(function (n) { n.textContent = hora; });
}

// Depois de uma ação, consulta os relatórios a cada 2 s até os eventos pendentes aparecerem
async function iniciarAcompanhamento() {
  acompanhamentoAte = Date.now() + 30000;
  if (acompanhando) { return; }
  acompanhando = true;
  try {
    while (Date.now() < acompanhamentoAte) {
      await esperar(1500);
      await atualizarRelatorios();
      if (!snapshot || snapshot.status.ocupacao !== 200) { break; }
      if (typeof contarPendentes === 'function' && contarPendentes() === 0) { break; }
    }
  } finally {
    acompanhando = false;
  }
}

function definirIntervaloAuto(ms) {
  estado.intervalo = ms;
  salvarEstado();
  if (temporizadorAuto) { clearInterval(temporizadorAuto); temporizadorAuto = null; }
  if (ms > 0) { temporizadorAuto = setInterval(atualizarRelatorios, ms); }
  $$('.b-auto').forEach(function (s) { s.value = String(ms); });
}

function urlPainelPublico() { return '/api/v1/relatorios/painel-publico?municipioId=' + municipioDosRelatorios(); }

// Barra de controle compartilhada (município, período, atualização e painel público)
function montarBarraRelatorios(destino) {
  destino.innerHTML =
    '<div class="campos" style="grid-template-columns:repeat(auto-fit,minmax(150px,1fr));align-items:end">' +
    '<div><label>Município</label><input type="number" min="1" class="b-mun"></div>' +
    '<div><label>Período (mês)</label><input type="month" class="b-per"></div>' +
    '<div><label>Atualização automática</label><select class="b-auto">' +
    '<option value="0">Manual</option><option value="2000">a cada 2 s</option><option value="3000">a cada 3 s</option><option value="5000">a cada 5 s</option></select></div>' +
    '<div><button class="b-btn">Atualizar agora</button></div>' +
    '<div><a class="btn sec b-link" target="_blank" rel="noopener">Painel público (nova aba)</a></div></div>' +
    '<p class="dica">Última atualização: <b class="b-ult">-</b> <span class="b-aviso"></span></p>' +
    '<p class="dica tecnico">Use outro número em Município para ver o 403: o relatório só atende o município do token.</p>';
  var mun = destino.querySelector('.b-mun');
  var per = destino.querySelector('.b-per');
  var auto = destino.querySelector('.b-auto');
  mun.value = municipioDosRelatorios();
  per.value = periodoSelecionado();
  auto.value = String(estado.intervalo || 0);
  destino.querySelector('.b-link').href = urlPainelPublico();
  mun.onchange = function () {
    estado.municipioRel = Number(mun.value) || municipioId;
    salvarEstado();
    sincronizarBarras();
    atualizarRelatorios();
  };
  per.onchange = function () {
    estado.periodo = per.value || periodoPadrao();
    salvarEstado();
    sincronizarBarras();
    atualizarRelatorios();
  };
  auto.onchange = function () { definirIntervaloAuto(Number(auto.value)); };
  destino.querySelector('.b-btn').onclick = atualizarRelatorios;
}

function sincronizarBarras() {
  $$('.b-mun').forEach(function (n) { n.value = municipioDosRelatorios(); });
  $$('.b-per').forEach(function (n) { n.value = periodoSelecionado(); });
  $$('.b-link').forEach(function (n) { n.href = urlPainelPublico(); });
  var link = $('c_linkPublico');
  if (link) { link.href = urlPainelPublico(); }
}

function atualizarAvisosDasBarras() {
  var texto = '';
  if (snapshot && snapshot.status.ocupacao === 403) {
    texto = snapshot.municipio !== municipioId
      ? 'Resposta 403: o município ' + snapshot.municipio + ' é diferente do município do token (' + municipioId + ').'
      : 'Resposta 403: o perfil ' + perfilDoToken() + ' não tem permissão para ler relatórios.';
  } else if (snapshot && snapshot.status.ocupacao === 401) {
    texto = 'Resposta 401: sessão inválida.';
  } else if (snapshot && snapshot.status.ocupacao === 0) {
    texto = 'Serviço de relatórios indisponível.';
  }
  $$('.b-aviso').forEach(function (n) { n.textContent = texto; n.style.color = texto ? 'var(--erro)' : ''; });
}

// Turmas exibidas na visão geral: só as desta sessão, quando houver e o filtro estiver ligado
function turmasExibidas() {
  if (!snapshot) { return []; }
  var idsLocais = estado.turmas.map(function (t) { return t.id; });
  if ($('soSessao').checked && idsLocais.length) {
    return snapshot.ocupacao.filter(function (o) { return idsLocais.indexOf(o.turmaId) >= 0; });
  }
  return snapshot.ocupacao;
}

function filaDaTurma(turmaId, fonte) {
  var lista = (fonte || snapshot).fila;
  for (var i = 0; i < lista.length; i++) { if (lista[i].turmaId === turmaId) { return lista[i]; } }
  return null;
}

function valorAnterior(turmaId, campo) {
  if (!snapshotAnterior) { return undefined; }
  var lista = campo === 'fila' ? snapshotAnterior.fila : snapshotAnterior.ocupacao;
  for (var i = 0; i < lista.length; i++) {
    if (lista[i].turmaId === turmaId) { return campo === 'fila' ? lista[i].quantidadeAguardando : lista[i].vagasDisponiveis; }
  }
  return undefined;
}

// Preenche uma tabela; cada célula pode ser texto, nó ou {c: conteudo, mudou: bool}
function preencherTabela(tabela, cabecalhos, linhas, vazio) {
  tabela.textContent = '';
  var cab = el('tr');
  cabecalhos.forEach(function (t) { cab.appendChild(el('th', { texto: t })); });
  tabela.appendChild(el('thead', {}, [cab]));
  var corpo = el('tbody');
  if (!linhas.length) {
    corpo.appendChild(el('tr', {}, [el('td', { colspan: cabecalhos.length, class: 'vazio', texto: vazio || 'Sem dados.' })]));
  }
  linhas.forEach(function (linha) {
    var tr = el('tr');
    linha.forEach(function (celula) {
      var conteudo = celula;
      var mudou = false;
      if (celula && typeof celula === 'object' && !(celula instanceof Node)) { conteudo = celula.c; mudou = !!celula.mudou; }
      var td = el('td', mudou ? { class: 'mudou' } : {});
      if (conteudo instanceof Node) { td.appendChild(conteudo); } else { td.textContent = conteudo === null || conteudo === undefined ? '-' : String(conteudo); }
      tr.appendChild(td);
    });
    corpo.appendChild(tr);
  });
  tabela.appendChild(corpo);
}

function barraOcupacao(ocupadas, total) {
  var pct = total > 0 ? Math.max(0, Math.min(100, ocupadas * 100 / total)) : 0;
  var barra = el('div', { class: 'barra', title: ocupadas + ' de ' + total + ' ocupadas' }, [el('i', { style: 'width:' + pct + '%' })]);
  return barra;
}

function textoHora(iso) {
  if (!iso) { return '-'; }
  var d = new Date(iso);
  return isNaN(d) ? String(iso) : d.toLocaleTimeString('pt-BR');
}

function linhasDeOcupacao(turmas, fonteFila) {
  return turmas.map(function (o) {
    var local = turmaLocal(o.turmaId);
    var creche = local ? crecheLocal(local.crecheId) : null;
    var fila = filaDaTurma(o.turmaId, fonteFila);
    var total = local ? local.vagas : null;
    var antesVagas = valorAnterior(o.turmaId, 'vagas');
    var antesFila = valorAnterior(o.turmaId, 'fila');
    var disponiveisTxt = total !== null ? o.vagasDisponiveis + ' de ' + total : String(o.vagasDisponiveis);
    return [
      '#' + o.turmaId,
      creche ? creche.nome + ' (#' + o.crecheId + ')' : '#' + o.crecheId,
      { c: disponiveisTxt, mudou: antesVagas !== undefined && antesVagas !== o.vagasDisponiveis },
      total !== null ? barraOcupacao(total - o.vagasDisponiveis, total) : '-',
      { c: fila ? fila.quantidadeAguardando : 0, mudou: antesFila !== undefined && fila && antesFila !== fila.quantidadeAguardando },
      textoHora(o.atualizadoEm)
    ];
  });
}

function renderizarVisao() {
  if (!snapshot) { return; }
  var s = snapshot;
  var turmas = turmasExibidas();
  var somaVagas = turmas.reduce(function (a, o) { return a + (o.vagasDisponiveis || 0); }, 0);
  var somaFila = turmas.reduce(function (a, o) {
    var f = filaDaTurma(o.turmaId);
    return a + (f ? f.quantidadeAguardando : 0);
  }, 0);
  var ok = s.status.ocupacao === 200;
  var k = function (nome) { return $('cartoesVisao').querySelector('[data-k="' + nome + '"]'); };
  definirValor(k('vagas'), ok ? somaVagas : '-');
  definirValor(k('turmas'), ok ? turmas.length : '-');
  definirValor(k('fila'), s.status.fila === 200 ? somaFila : '-');
  definirValor(k('taxa'), s.taxa && s.taxa.taxaConfirmacao !== null && s.taxa.taxaConfirmacao !== undefined ? numero(s.taxa.taxaConfirmacao * 100, 0) + '%' : '-');
  definirValor(k('dist'), s.dist && s.dist.distanciaMediaKm !== null && s.dist.distanciaMediaKm !== undefined ? numero(s.dist.distanciaMediaKm, 2) : '-');
  definirValor(k('pVagas'), s.painel ? s.painel.totalVagasDisponiveis : '-');
  definirValor(k('pCreches'), s.painel ? s.painel.totalCrechesComVaga : '-');
  definirValor(k('pTurmas'), s.painel ? s.painel.totalTurmas : '-');
  preencherTabela($('tabelaOcupacaoVisao'), ['Turma', 'Creche', 'Vagas disponíveis', 'Ocupação', 'Na fila', 'Atualizado'],
    linhasDeOcupacao(turmas, s), ok ? 'Nenhuma turma ainda. Crie uma creche e uma turma.' : 'Relatórios indisponíveis para esta consulta (veja o aviso acima).');
}

// ---------------------------------------------------------------------------
// Aba Stack C: consulta detalhada de cada relatório
// ---------------------------------------------------------------------------

function mensagemSemAcesso(resposta) {
  return el('div', { class: 'selo erro', texto: mensagemDeErro(resposta) });
}

function jsonBloco(dados) {
  return el('pre', { class: 'json tecnico', texto: mascarar(JSON.stringify(dados, null, 2)) });
}

async function consultarDetalhe(opcoes) {
  opcoes = opcoes || {};
  var turmaId = Number($('c_fTurma').value) || null;
  var crecheId = Number($('c_fCreche').value) || null;
  var s = await consultarRelatorios({ municipio: opcoes.municipio, turmaId: turmaId, crecheId: crecheId });
  var r = s.respostas;

  var blocoOcup = $('c_ocup');
  blocoOcup.textContent = '';
  if (r[0].ok) {
    var tabela = el('table', { class: 'tabela' });
    preencherTabela(tabela, ['Turma', 'Creche', 'Vagas disponíveis', 'Ocupação', 'Na fila', 'Seq. aplicada', 'Atualizado'],
      linhasDeOcupacao(s.ocupacao, s).map(function (linha, i) {
        return linha.slice(0, 5).concat([String(s.ocupacao[i].ultimaSequenciaAplicada), linha[5]]);
      }),
      'Nenhuma turma para o filtro.');
    blocoOcup.appendChild(tabela);
    blocoOcup.appendChild(jsonBloco(r[0].dados));
  } else { blocoOcup.appendChild(mensagemSemAcesso(r[0])); }

  var blocoFila = $('c_fila');
  blocoFila.textContent = '';
  if (r[1].ok) {
    var tabelaFila = el('table', { class: 'tabela' });
    preencherTabela(tabelaFila, ['Turma', 'Creche', 'Aguardando', 'Espera média (dias)'],
      s.fila.map(function (f) { return ['#' + f.turmaId, '#' + f.crecheId, String(f.quantidadeAguardando), numero(f.tempoMedioEsperaDias, 1)]; }), 'Fila vazia para o filtro.');
    blocoFila.appendChild(tabelaFila);
    blocoFila.appendChild(jsonBloco(r[1].dados));
  } else { blocoFila.appendChild(mensagemSemAcesso(r[1])); }

  var blocoTaxa = $('c_taxa');
  blocoTaxa.textContent = '';
  if (r[2].ok) {
    var t = r[2].dados;
    blocoTaxa.appendChild(el('div', { class: 'cartoes', style: 'margin:0' }, [
      el('div', { class: 'cartao' }, [el('b', { texto: String(t.totalMatriculas) }), el('span', { texto: 'matrículas no período' })]),
      el('div', { class: 'cartao' }, [el('b', { texto: String(t.totalConfirmadas) }), el('span', { texto: 'confirmadas' })]),
      el('div', { class: 'cartao' }, [el('b', { texto: t.taxaConfirmacao === null ? '-' : numero(t.taxaConfirmacao * 100, 0) + '%' }), el('span', { texto: 'taxa de confirmação' })])
    ]));
    blocoTaxa.appendChild(jsonBloco(t));
  } else { blocoTaxa.appendChild(mensagemSemAcesso(r[2])); }

  var blocoDist = $('c_dist');
  blocoDist.textContent = '';
  if (r[3].ok) {
    var d = r[3].dados;
    blocoDist.appendChild(el('div', { class: 'cartoes', style: 'margin:0' }, [
      el('div', { class: 'cartao' }, [el('b', { texto: d.distanciaMediaKm === null ? '-' : numero(d.distanciaMediaKm, 2) }), el('span', { texto: 'km (média das alocações do período)' })])
    ]));
    blocoDist.appendChild(jsonBloco(d));
  } else { blocoDist.appendChild(mensagemSemAcesso(r[3])); }

  renderizarPainelPublico(r[4]);
  if (opcoes.municipio) {
    avisar('Município ' + opcoes.municipio + ' no relatório de ocupação: ' + r[0].status + (r[0].status === 403 ? ' (isolamento por município funcionando)' : ''), r[0].status === 403 ? 'ok' : 'erro');
  }
  return s;
}

function renderizarPainelPublico(resposta) {
  var alvo = $('c_painel');
  alvo.textContent = '';
  if (!resposta.ok) { alvo.appendChild(mensagemSemAcesso(resposta)); return; }
  var p = resposta.dados;
  alvo.appendChild(el('div', { class: 'cartoes', style: 'margin:0' }, [
    el('div', { class: 'cartao publico' }, [el('b', { texto: String(p.totalVagasDisponiveis) }), el('span', { texto: 'vagas disponíveis' })]),
    el('div', { class: 'cartao publico' }, [el('b', { texto: String(p.totalCrechesComVaga) }), el('span', { texto: 'creches com vaga' })]),
    el('div', { class: 'cartao publico' }, [el('b', { texto: String(p.totalTurmas) }), el('span', { texto: 'turmas' })]),
    el('div', { class: 'cartao publico' }, [el('b', { texto: textoHora(p.atualizadoEm) }), el('span', { texto: 'atualizado em' })])
  ]));
  alvo.appendChild(el('p', { class: 'dica', texto: 'Resposta ' + resposta.status + ' sem enviar token (' + Math.round(resposta.ms) + ' ms).' }));
}

async function consultarPainelSemToken() {
  var resposta = await api('GET', urlPainelPublico(), { token: null, rotulo: 'Painel público sem token' });
  renderizarPainelPublico(resposta);
}

function renderizarFiltrosDetalhe() {
  preencherSelect($('c_fTurma'), [{ valor: '', rotulo: 'todas' }].concat(estado.turmas.map(function (t) { return { valor: t.id, rotulo: rotuloTurma(t) }; })));
  preencherSelect($('c_fCreche'), [{ valor: '', rotulo: 'todas' }].concat(estado.creches.map(function (c) { return { valor: c.id, rotulo: c.nome + ' (#' + c.id + ')' }; })));
}

function iniciarPainel() {
  $$('[data-barra-relatorios]').forEach(montarBarraRelatorios);
  definirIntervaloAuto(estado.intervalo || 0);
  $('soSessao').onchange = renderizarVisao;
  $('c_btnConsultar').onclick = function () { consultarDetalhe(); };
  $('c_btnOutroMun').onclick = function () { consultarDetalhe({ municipio: municipioId + 1 }); };
  $('c_btnPublico').onclick = consultarPainelSemToken;
  $('c_linkPublico').href = urlPainelPublico();
  // Enquanto houver eventos aguardando (ex.: RabbitMQ parado), confere os relatórios a cada 5 s
  setInterval(function () {
    if (contarPendentes() > 0 && !acompanhando && snapshot && snapshot.status.ocupacao === 200) { atualizarRelatorios(); }
  }, 5000);
}
