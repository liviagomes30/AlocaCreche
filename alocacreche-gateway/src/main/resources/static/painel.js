/*
 * Painel ao vivo (visão geral) e aba Stack C: consulta dos relatórios do MSRelatorios,
 * atualização manual e automática, destaque de valores que mudam e painel público.
 */

var snapshot = null;
// A aba Stack C carrega os relatórios sozinha na primeira vez que é aberta
var detalheCarregado = false;
// Soma das vagas no relatório e no banco a cada atualização (só em memória), para o gráfico ao vivo
var historicoVagas = [];
var LIMITE_HISTORICO = 40;
var snapshotAnterior = null;
var emAtualizacao = false;
var temporizadorAuto = null;
var acompanhando = false;
var acompanhamentoAte = 0;

function municipioDosRelatorios() { return estado.municipioRel || municipioId; }
function periodoSelecionado() { return estado.periodo || periodoPadrao(); }

function coreDaTurma(s, turmaId) {
  if (!s || !s.turmasCore) { return null; }
  for (var i = 0; i < s.turmasCore.length; i++) { if (s.turmasCore[i].id === turmaId) { return s.turmasCore[i]; } }
  return null;
}

// Linhas de ocupação do relatório mais as turmas que já existem no core mas ainda não chegaram ao relatório
function ocupacaoComAusentes(s) {
  var lista = s.ocupacao.slice();
  if (s.turmasCore && !s.temFiltro) {
    var presentes = {};
    s.ocupacao.forEach(function (o) { presentes[o.turmaId] = true; });
    s.turmasCore.forEach(function (c) {
      if (!presentes[c.id]) {
        lista.push({ turmaId: c.id, crecheId: c.crecheId, vagasDisponiveis: null, ultimaSequenciaAplicada: null, atualizadoEm: null, ausente: true });
      }
    });
  }
  return lista;
}

// Quantas turmas têm vagas diferentes no core e no relatório (ou ainda nem aparecem nele)
function contarDivergencias(ocupacao, turmasCore, temFiltro) {
  if (!turmasCore) { return 0; }
  var porId = {};
  turmasCore.forEach(function (c) { porId[c.id] = c; });
  var presentes = {};
  var divergentes = 0;
  ocupacao.forEach(function (o) {
    presentes[o.turmaId] = true;
    var c = porId[o.turmaId];
    if (c && c.vagasDisponiveis !== o.vagasDisponiveis) { divergentes++; }
  });
  if (!temFiltro) {
    turmasCore.forEach(function (c) { if (!presentes[c.id]) { divergentes++; } });
  }
  return divergentes;
}

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
    api('GET', base + 'painel-publico?municipioId=' + mun, { automatica: auto, rotulo: 'Painel público', token: null }),
    api('GET', '/api/v1/matricula/turmas', { automatica: auto, rotulo: 'Banco (core): turmas' }),
    api('GET', '/api/v1/notificacao/outbox', { automatica: auto, rotulo: 'Outbox: resumo' })
  ]);
  var ocupacao = r[0].ok && Array.isArray(r[0].dados) ? r[0].dados : [];
  // O core só responde pelo município do token: com outro município na consulta não há o que comparar
  var turmasCore = r[5].ok && Array.isArray(r[5].dados) && Number(mun) === municipioId ? r[5].dados : null;
  var temFiltro = !!(opcoes.turmaId || opcoes.crecheId);
  return {
    hora: new Date(), municipio: mun, respostas: r,
    status: { ocupacao: r[0].status, fila: r[1].status, taxa: r[2].status, dist: r[3].status, painel: r[4].status },
    ocupacao: ocupacao,
    turmasCore: turmasCore,
    temFiltro: temFiltro,
    divergentes: contarDivergencias(ocupacao, turmasCore, temFiltro),
    outbox: r[6].ok ? r[6].dados : null,
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
    registrarHistorico(snapshot);
    atualizarIndicadoresDeHora();
    renderizarVisao();
    atualizarAvisosDasBarras();
    // O relatório atrasa em relação ao banco: ao surgir uma diferença, confere a cada 1,5 s até igualar
    if (snapshot.divergentes > 0 && !(snapshotAnterior && snapshotAnterior.divergentes > 0) && !acompanhando) { iniciarAcompanhamento(); }
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
      if (typeof contarPendentes === 'function' && contarPendentes() === 0 && !snapshot.divergentes) { break; }
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

// Página pública e estilizada do painel (o link abre esta página; a consulta continua usando a URL da API acima).
function urlPaginaPublica() { return '/painel.html?municipioId=' + municipioDosRelatorios(); }

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
  destino.querySelector('.b-link').href = urlPaginaPublica();
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
  $$('.b-link').forEach(function (n) { n.href = urlPaginaPublica(); });
  var link = $('c_linkPublico');
  if (link) { link.href = urlPaginaPublica(); }
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

// Turmas exibidas na visão geral: as do relatório e as do core que o relatório ainda não recebeu
function turmasExibidas() {
  return snapshot ? ocupacaoComAusentes(snapshot) : [];
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

// Compara o que o relatório (projeção) mostra com o que o core (banco) tem agora
function seloSincronia(o, core) {
  if (!core) { return '-'; }
  if (o.ausente) { return selo('aguardando evento', 'aviso'); }
  return core.vagasDisponiveis === o.vagasDisponiveis ? selo('em dia', 'ok') : selo('sincronizando...', 'aviso');
}

function linhasDeOcupacao(turmas, fonteFila) {
  return turmas.map(function (o) {
    var local = turmaLocal(o.turmaId);
    var creche = local ? crecheLocal(local.crecheId) : null;
    var fila = filaDaTurma(o.turmaId, fonteFila);
    var core = coreDaTurma(fonteFila || snapshot, o.turmaId);
    var total = core ? core.vagasTotais : (local ? local.vagas : null);
    var antesVagas = valorAnterior(o.turmaId, 'vagas');
    var antesFila = valorAnterior(o.turmaId, 'fila');
    var disponiveisTxt = o.ausente ? '-' : (total !== null ? o.vagasDisponiveis + ' de ' + total : String(o.vagasDisponiveis));
    return [
      '#' + o.turmaId,
      creche ? creche.nome + ' (#' + o.crecheId + ')' : '#' + o.crecheId,
      { c: disponiveisTxt, mudou: antesVagas !== undefined && antesVagas !== o.vagasDisponiveis },
      total !== null && !o.ausente ? barraOcupacao(total - o.vagasDisponiveis, total) : '-',
      { c: fila ? fila.quantidadeAguardando : 0, mudou: antesFila !== undefined && fila && antesFila !== fila.quantidadeAguardando },
      textoHora(o.atualizadoEm),
      core ? String(core.vagasDisponiveis) : '-',
      seloSincronia(o, core)
    ];
  });
}

function registrarHistorico(s) {
  if (!s.turmasCore || s.status.ocupacao !== 200) { return; }
  var soma = function (lista, campo) { return lista.reduce(function (a, o) { return a + (o[campo] || 0); }, 0); };
  historicoVagas.push({ rel: soma(s.ocupacao, 'vagasDisponiveis'), core: soma(s.turmasCore, 'vagasDisponiveis') });
  if (historicoVagas.length > LIMITE_HISTORICO) { historicoVagas.shift(); }
}

// Gráfico de linhas em SVG: vagas no relatório (linha cheia) x vagas no banco (tracejada)
function renderizarGrafico() {
  var area = $('gr_area');
  var selo = $('gr_selo');
  if (!area) { return; }
  var h = historicoVagas;
  if (h.length < 2) {
    area.innerHTML = '<span class="vazio">Aguardando atualizações: o gráfico começa com duas leituras.</span>';
    selo.textContent = '';
    return;
  }
  var W = 600, H = 190, E = 38, D = 84, T = 14, B = 24;
  var maximo = Math.max.apply(null, h.map(function (p) { return Math.max(p.rel, p.core); }));
  var topo = Math.max(4, Math.ceil(maximo * 1.15 / 2) * 2);
  var x = function (i) { return E + (W - E - D) * i / (h.length - 1); };
  var y = function (v) { return T + (H - T - B) * (1 - v / topo); };
  var pontos = function (campo) { return h.map(function (p, i) { return x(i).toFixed(1) + ',' + y(p[campo]).toFixed(1); }).join(' '); };
  var ultimo = h[h.length - 1];
  var grade = [0, topo / 2, topo].map(function (v) {
    return '<line class="grade-y" x1="' + E + '" x2="' + (W - D) + '" y1="' + y(v).toFixed(1) + '" y2="' + y(v).toFixed(1) + '"/>' +
      '<text x="' + (E - 6) + '" y="' + (y(v) + 4).toFixed(1) + '" text-anchor="end">' + Math.round(v) + '</text>';
  }).join('');
  var diferencas = h.map(function (p, i) {
    return p.rel !== p.core ? '<circle class="ponto-dif" cx="' + x(i).toFixed(1) + '" cy="' + y(p.rel).toFixed(1) + '" r="4.5"/>' : '';
  }).join('');
  var xf = x(h.length - 1) + 8;
  area.innerHTML =
    '<svg viewBox="0 0 ' + W + ' ' + H + '" role="img" aria-label="Vagas disponíveis: relatório ' + ultimo.rel + ', banco ' + ultimo.core + ', nas últimas ' + h.length + ' atualizações">' +
    grade +
    '<polyline class="linha-core" points="' + pontos('core') + '"/>' +
    '<polyline class="linha-rel" points="' + pontos('rel') + '"/>' +
    diferencas +
    '<text x="' + xf + '" y="' + (y(ultimo.rel) - 6).toFixed(1) + '">relatório ' + ultimo.rel + '</text>' +
    '<text x="' + xf + '" y="' + (y(ultimo.core) + 12).toFixed(1) + '">banco ' + ultimo.core + '</text>' +
    '<text x="' + E + '" y="' + (H - 6) + '">mais antigo</text>' +
    '<text x="' + (W - D) + '" y="' + (H - 6) + '" text-anchor="end">agora</text>' +
    '</svg>';
  var dif = Math.abs(ultimo.rel - ultimo.core);
  selo.textContent = dif === 0 ? 'em dia' : 'defasagem de ' + dif + ' vaga(s)';
  selo.className = 'selo ' + (dif === 0 ? 'ok' : 'aviso');
}

function renderizarVisao() {
  if (!snapshot) { return; }
  var s = snapshot;
  var turmas = turmasExibidas();
  var projetadas = turmas.filter(function (o) { return !o.ausente; });
  var somaVagas = turmas.reduce(function (a, o) { return a + (o.vagasDisponiveis || 0); }, 0);
  var somaFila = turmas.reduce(function (a, o) {
    var f = filaDaTurma(o.turmaId);
    return a + (f ? f.quantidadeAguardando : 0);
  }, 0);
  var ok = s.status.ocupacao === 200;
  var k = function (nome) { return $('cartoesVisao').querySelector('[data-k="' + nome + '"]'); };
  definirValor(k('vagas'), ok ? somaVagas : '-');
  definirValor(k('turmas'), ok ? projetadas.length : '-');
  definirValor(k('fila'), s.status.fila === 200 ? somaFila : '-');
  definirValor(k('taxa'), s.taxa && s.taxa.taxaConfirmacao !== null && s.taxa.taxaConfirmacao !== undefined ? numero(s.taxa.taxaConfirmacao * 100, 0) + '%' : '-');
  definirValor(k('dist'), s.dist && s.dist.distanciaMediaKm !== null && s.dist.distanciaMediaKm !== undefined ? numero(s.dist.distanciaMediaKm, 2) : '-');
  definirValor(k('oPend'), s.outbox ? s.outbox.pendentes : '-');
  definirValor(k('oErro'), s.outbox ? s.outbox.erros : '-');
  definirValor(k('pVagas'), s.painel ? s.painel.totalVagasDisponiveis : '-');
  definirValor(k('pCreches'), s.painel ? s.painel.totalCrechesComVaga : '-');
  definirValor(k('pTurmas'), s.painel ? s.painel.totalTurmas : '-');
  preencherTabela($('tabelaOcupacaoVisao'), ['Turma', 'Creche', 'Vagas no relatório', 'Ocupação', 'Na fila', 'Atualizado', 'Vagas no banco (core)', 'Sincronia'],
    linhasDeOcupacao(turmas, s), ok ? 'Nenhuma turma ainda. Crie uma creche e uma turma.' : 'Relatórios indisponíveis para esta consulta (veja o aviso acima).');
  renderizarGrafico();
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

// Caixa "Como se chegou neste número": explica a origem do dado e a conta, com os números atuais
function legendaDoRelatorio(texto) {
  return el('p', { class: 'comochegou' }, [el('b', { texto: 'Como se chegou neste número: ' }), document.createTextNode(texto)]);
}

function mesAno(periodo) {
  var partes = String(periodo).split('-');
  return partes[1] + '/' + partes[0];
}

function rotuloDaTurmaDoCore(turma) {
  var creche = crecheLocal(turma.crecheId);
  return '#' + turma.id + ' ' + (creche ? creche.nome + ', ' : '') + turma.faixaEtaria;
}

// Crianças na fila vêm do core (inscrição + fila de espera), não do relatório: o relatório só guarda a contagem
async function renderizarCriancasNaFila(s, turmaId, crecheId) {
  var alvo = $('c_criancas');
  alvo.textContent = '';
  var r = await api('GET', '/api/v1/inscricao/inscricoes', { rotulo: 'Core: crianças na fila' });
  if (!r.ok) { alvo.appendChild(mensagemSemAcesso(r)); return; }
  var turmas = {};
  (s.turmasCore || []).forEach(function (t) { turmas[t.id] = t; });
  var fila = r.dados.filter(function (i) {
    if (i.status !== 'AGUARDANDO' || !i.turmaId) { return false; }
    if (turmaId && i.turmaId !== turmaId) { return false; }
    return !crecheId || (turmas[i.turmaId] && turmas[i.turmaId].crecheId === crecheId);
  }).sort(function (a, b) { return a.turmaId - b.turmaId || a.posicao - b.posicao; });

  var tabela = el('table', { class: 'tabela' });
  var turmaAnterior = null;
  preencherTabela(tabela, ['Turma', 'Posição', 'Criança', 'Score', 'Inscrita em'],
    fila.map(function (i) {
      // O nome da turma aparece só na primeira linha de cada grupo, para a lista ficar legível
      var rotuloTurma = i.turmaId === turmaAnterior ? '' : (turmas[i.turmaId] ? rotuloDaTurmaDoCore(turmas[i.turmaId]) : '#' + i.turmaId);
      turmaAnterior = i.turmaId;
      return [
        rotuloTurma,
        String(i.posicao),
        i.nome,
        numero(i.scorePrioridade, 2),
        new Date(i.dataInscricao).toLocaleString('pt-BR', { day: '2-digit', month: '2-digit', hour: '2-digit', minute: '2-digit' })
      ];
    }), 'Nenhuma criança aguardando na fila para este filtro.');
  alvo.appendChild(tabela);

  // Confere, turma a turma, a contagem do core com a do relatório da fila de espera
  var porTurma = {};
  fila.forEach(function (i) { porTurma[i.turmaId] = (porTurma[i.turmaId] || 0) + 1; });
  var contagens = el('p', { class: 'dica' });
  Object.keys(porTurma).forEach(function (id) {
    var noRelatorio = filaDaTurma(Number(id), s);
    var qtdRelatorio = noRelatorio ? noRelatorio.quantidadeAguardando : 0;
    var igual = qtdRelatorio === porTurma[id];
    contagens.appendChild(el('span', { style: 'margin-right:12px' }, [
      document.createTextNode('Turma #' + id + ': ' + porTurma[id] + ' no core, ' + qtdRelatorio + ' no relatório '),
      selo(igual ? 'igual' : 'sincronizando...', igual ? 'ok' : 'aviso')
    ]));
  });
  alvo.appendChild(contagens);
  alvo.appendChild(legendaDoRelatorio(
    'esta lista vem do banco do core (inscrição com status AGUARDANDO e a posição na fila de espera), e não do relatório. O relatório só guarda a contagem por turma, de propósito, para não expor dados pessoais. ' +
    'A posição é a ordem por score (do maior para o menor) e, no empate, quem se inscreveu antes. Total listado: ' + fila.length + ' criança(s).'));
}

async function consultarDetalhe(opcoes) {
  detalheCarregado = true;
  opcoes = opcoes || {};
  var turmaId = Number($('c_fTurma').value) || null;
  var crecheId = Number($('c_fCreche').value) || null;
  var s = await consultarRelatorios({ municipio: opcoes.municipio, turmaId: turmaId, crecheId: crecheId });
  var r = s.respostas;

  var blocoOcup = $('c_ocup');
  blocoOcup.textContent = '';
  if (r[0].ok) {
    var tabela = el('table', { class: 'tabela' });
    var turmasDetalhe = ocupacaoComAusentes(s);
    preencherTabela(tabela, ['Turma', 'Creche', 'Vagas no relatório', 'Ocupação', 'Na fila', 'Seq. aplicada', 'Atualizado', 'Vagas no banco (core)', 'Sincronia'],
      linhasDeOcupacao(turmasDetalhe, s).map(function (linha, i) {
        return linha.slice(0, 5).concat([String(turmasDetalhe[i].ultimaSequenciaAplicada), linha[5], linha[6], linha[7]]);
      }),
      'Nenhuma turma para o filtro.');
    blocoOcup.appendChild(tabela);
    var somaVagasDetalhe = s.ocupacao.reduce(function (a, o) { return a + (o.vagasDisponiveis || 0); }, 0);
    blocoOcup.appendChild(legendaDoRelatorio(
      'cada linha é o último evento vaga.liberada aplicado à turma na projeção do MSRelatórios (a sequência é o id do evento na Outbox e só avança). ' +
      '"Vagas no banco" vem direto do core, para comparar; "Na fila" vem do relatório da fila de espera. Agora: ' + somaVagasDetalhe + ' vaga(s) disponível(is) em ' + s.ocupacao.length + ' turma(s).'));
    blocoOcup.appendChild(jsonBloco(r[0].dados));
  } else { blocoOcup.appendChild(mensagemSemAcesso(r[0])); }

  var blocoFila = $('c_fila');
  blocoFila.textContent = '';
  if (r[1].ok) {
    var tabelaFila = el('table', { class: 'tabela' });
    preencherTabela(tabelaFila, ['Turma', 'Creche', 'Aguardando', 'Espera média (dias)'],
      s.fila.map(function (f) { return ['#' + f.turmaId, '#' + f.crecheId, String(f.quantidadeAguardando), numero(f.tempoMedioEsperaDias, 1)]; }), 'Fila vazia para o filtro.');
    blocoFila.appendChild(tabelaFila);
    var totalAguardando = s.fila.reduce(function (a, f) { return a + f.quantidadeAguardando; }, 0);
    blocoFila.appendChild(legendaDoRelatorio(
      'Aguardando = inscrições da turma sem data de saída: entram pelo evento fila.atualizada e saem quando a vaga é alocada. ' +
      'Espera média = média de dias entre a entrada na fila e a saída (ou até hoje, para quem ainda aguarda), de todas as inscrições da turma, inclusive as já convocadas. ' +
      'Agora: ' + totalAguardando + ' aguardando em ' + s.fila.length + ' turma(s).'));
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
    blocoTaxa.appendChild(legendaDoRelatorio(
      'conta as matrículas convocadas no mês ' + mesAno(periodoSelecionado()) + ' (pela data de convocação). Confirmadas são as de status CONFIRMADA; pendentes, canceladas e expiradas entram no total, mas não como confirmadas. ' +
      'Conta: ' + t.totalConfirmadas + ' confirmada(s) ÷ ' + t.totalMatriculas + ' matrícula(s) = ' + (t.taxaConfirmacao === null ? 'sem matrículas no período' : numero(t.taxaConfirmacao * 100, 0) + '%') + '.'));
    blocoTaxa.appendChild(jsonBloco(t));
  } else { blocoTaxa.appendChild(mensagemSemAcesso(r[2])); }

  var blocoDist = $('c_dist');
  blocoDist.textContent = '';
  if (r[3].ok) {
    var d = r[3].dados;
    blocoDist.appendChild(el('div', { class: 'cartoes', style: 'margin:0' }, [
      el('div', { class: 'cartao' }, [el('b', { texto: d.distanciaMediaKm === null ? '-' : numero(d.distanciaMediaKm, 2) }), el('span', { texto: 'km (média das alocações do período)' })])
    ]));
    blocoDist.appendChild(legendaDoRelatorio(
      'média, em km, da distância gravada em cada matrícula convocada no mês ' + mesAno(periodoSelecionado()) + '. A distância é calculada na alocação (Haversine entre a casa do responsável e a creche) e vale para matrículas de qualquer status. ' +
      'Resultado: ' + (d.distanciaMediaKm === null ? 'sem matrículas no período' : numero(d.distanciaMediaKm, 2) + ' km') + '.'));
    blocoDist.appendChild(jsonBloco(d));
  } else { blocoDist.appendChild(mensagemSemAcesso(r[3])); }

  renderizarPainelPublico(r[4]);
  await renderizarCriancasNaFila(s, turmaId, crecheId);
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
  alvo.appendChild(legendaDoRelatorio(
    'vagas disponíveis = soma das vagas livres de todas as turmas do município (' + p.totalVagasDisponiveis + '); creches com vaga = creches com pelo menos uma turma com vaga livre (' + p.totalCrechesComVaga + '); ' +
    'turmas = turmas com projeção (' + p.totalTurmas + '); atualizado em = última vez que um evento mudou a ocupação. Só totais, sem dados pessoais, e o resultado fica em cache até um novo evento ser aplicado.'));
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
  $('c_btnConsultar').onclick = function () { consultarDetalhe(); };
  $('c_btnOutroMun').onclick = function () { consultarDetalhe({ municipio: municipioId + 1 }); };
  $('c_btnPublico').onclick = consultarPainelSemToken;
  $('c_linkPublico').href = urlPaginaPublica();
  // Enquanto houver eventos aguardando (ex.: RabbitMQ parado), confere os relatórios a cada 5 s
  setInterval(function () {
    if (contarPendentes() > 0 && !acompanhando && snapshot && snapshot.status.ocupacao === 200) { atualizarRelatorios(); }
  }, 5000);
}
