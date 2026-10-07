/*
 * Aba Stack B (Matrícula e vagas): creche, turma, alocação, confirmação e a prova de
 * concorrência (várias alocações simultâneas na mesma turma, protegidas por lock otimista).
 */

function lerCrecheDoFormulario() {
  return { nome: $('b_cNome').value, capacidade: valorNumero('b_cCap'), rua: $('b_cRua').value, lat: valorNumero('b_cLat'), lon: valorNumero('b_cLon') };
}

function renderizarMatricula() {
  preencherSelect($('b_tCreche'), estado.creches.map(function (c) { return { valor: c.id, rotulo: c.nome + ' (#' + c.id + ')' }; }), '(crie uma creche)');
  preencherSelect($('b_aTurma'), estado.turmas.map(function (t) { return { valor: t.id, rotulo: rotuloTurma(t) }; }), '(crie uma turma)');
  if (estado.turmaAtual && !$('b_aTurma').getAttribute('data-tocado')) { $('b_aTurma').value = String(estado.turmaAtual); }
  preencherSelect($('b_mSel'), estado.matriculas.map(function (m) { return { valor: m.id, rotulo: '#' + m.id + ' ' + m.nome + ' (' + m.status + ')' }; }), '(aloque uma vaga)');
  if (estado.matriculaAtual && !$('b_mSel').getAttribute('data-tocado')) { $('b_mSel').value = String(estado.matriculaAtual); }

  preencherTabela($('b_tabela'), ['Turma', 'Creche', 'Faixa', 'Vagas', 'Alocadas', 'Restantes', 'Na fila'],
    estado.turmas.map(function (t) {
      var creche = crecheLocal(t.crecheId);
      return ['#' + t.id, creche ? creche.nome : '#' + t.crecheId, t.faixa, String(t.vagas), String(t.alocadas), String(t.vagas - t.alocadas), String(filaLocalDaTurma(t.id).length)];
    }), 'Nenhuma turma nesta sessão.');
  preencherTabela($('b_tabelaMat'), ['Matrícula', 'Criança', 'Turma', 'Status', 'Distância (estimada)'],
    estado.matriculas.map(function (m) {
      return ['#' + m.id, m.nome, '#' + m.turmaId, selo(m.status, m.status === 'CONFIRMADA' ? 'ok' : 'aviso'), m.distanciaKm === null ? '-' : numero(m.distanciaKm, 2) + ' km'];
    }), 'Nenhuma matrícula nesta sessão.');
}

async function aoCriarCreche() {
  var resposta = await acaoCriarCreche(lerCrecheDoFormulario());
  avisar(resposta.ok ? 'Creche #' + resposta.dados.crecheId + ' criada.' : 'Erro ao criar creche: ' + mensagemDeErro(resposta), resposta.ok ? 'ok' : 'erro');
  if (resposta.ok) { renderizarMatricula(); $('b_tCreche').value = String(resposta.dados.crecheId); }
}

async function aoCriarTurma() {
  var crecheId = Number($('b_tCreche').value);
  if (!crecheId) { avisar('Crie uma creche primeiro.', 'erro'); return; }
  var resposta = await acaoCriarTurma({ crecheId: crecheId, faixa: $('b_tFaixa').value, vagas: valorNumero('b_tVagas') });
  avisar(resposta.ok ? 'Turma #' + resposta.dados.turmaId + ' criada.' : 'Erro ao criar turma: ' + mensagemDeErro(resposta), resposta.ok ? 'ok' : 'erro');
}

async function aoCriarCrecheETurma() {
  var resposta = await acaoCriarCrecheETurma(lerCrecheDoFormulario(), $('b_tFaixa').value, valorNumero('b_tVagas'));
  avisar(resposta.ok ? 'Creche e turma #' + resposta.dados.turmaId + ' criadas.' : 'Erro: ' + mensagemDeErro(resposta), resposta.ok ? 'ok' : 'erro');
}

async function aoAlocar() {
  var turmaId = Number($('b_aTurma').value);
  if (!turmaId) { avisar('Crie uma turma primeiro.', 'erro'); return; }
  var resposta = await acaoAlocar(turmaId);
  var alvo = $('b_aRes');
  alvo.textContent = '';
  alvo.appendChild(seloStatus(resposta.status));
  if (resposta.ok) {
    var m = resposta.matricula;
    var turma = turmaLocal(turmaId);
    var texto = ' Matrícula #' + m.id + ' (PENDENTE)' + (m.inscricaoId ? ' para a inscrição #' + m.inscricaoId + ' (' + m.nome + ')' : '') + '.';
    if (m.distanciaKm !== null) { texto += ' Distância estimada: ' + numero(m.distanciaKm, 2) + ' km (o valor oficial entra na média dos relatórios).'; }
    if (turma) { texto += ' Vagas restantes: ' + (turma.vagas - turma.alocadas) + ' de ' + turma.vagas + '.'; }
    alvo.appendChild(document.createTextNode(texto));
  } else {
    alvo.appendChild(document.createTextNode(' ' + mensagemDeErro(resposta)));
  }
}

async function aoConfirmar() {
  var matriculaId = Number($('b_mSel').value);
  if (!matriculaId) { avisar('Aloque uma vaga primeiro.', 'erro'); return; }
  var resposta = await acaoConfirmar(matriculaId);
  mostrarResultado('b_mRes', resposta, 'Matrícula #' + matriculaId + ' confirmada.');
}

async function aoConfirmarDuasVezes() {
  var matriculaId = Number($('b_mSel').value);
  if (!matriculaId) { avisar('Aloque uma vaga primeiro.', 'erro'); return; }
  var primeira = await acaoConfirmar(matriculaId, 'Confirmar matrícula (1ª vez)');
  var segunda = await acaoConfirmar(matriculaId, 'Confirmar matrícula (2ª vez, espera 409)');
  var alvo = $('b_mRes');
  alvo.textContent = '1ª chamada: ' + primeira.status + ' | 2ª chamada: ' + segunda.status + (segunda.status === 409 ? ' (conflito esperado: a matrícula já foi confirmada).' : ' (esperado 409).');
  alvo.className = 'dica';
}

async function aoDispararEmParalelo() {
  var turmaId = Number($('b_aTurma').value);
  if (!turmaId) { avisar('Crie uma turma primeiro.', 'erro'); return; }
  var n = valorNumero('b_nPar');
  var turma = turmaLocal(turmaId);
  var restantes = turma ? turma.vagas - turma.alocadas : null;
  var naFila = filaLocalDaTurma(turmaId).length;
  await garantirSnapshot();
  $('b_parVeredito').textContent = 'Disparando ' + n + ' requisições ao mesmo tempo...';
  var inicio = performance.now();
  var chamadas = [];
  for (var i = 0; i < n; i++) { chamadas.push(acaoAlocar(turmaId, 'Alocação simultânea ' + (i + 1) + ' de ' + n)); }
  var respostas = await Promise.all(chamadas);
  var total = Math.round(performance.now() - inicio);

  var vitorias = respostas.filter(function (r) { return r.ok; });
  var conflitos = respostas.filter(function (r) { return r.status === 409; });
  var outros = respostas.filter(function (r) { return !r.ok && r.status !== 409; });
  var ids = vitorias.map(function (r) { return r.dados.matriculaId; });
  var distintos = ids.filter(function (id, pos) { return ids.indexOf(id) === pos; }).length === ids.length;
  var maximo = Math.min(n, restantes === null ? n : restantes, naFila || n);

  preencherTabela($('b_parTabela'), ['Requisição', 'Status', 'Resultado', 'Tempo'],
    respostas.map(function (r, pos) {
      return ['#' + (pos + 1), seloStatus(r.status), r.ok ? 'matrícula #' + r.dados.matriculaId : mensagemDeErro(r), Math.round(r.ms) + ' ms'];
    }), '');

  var correto = distintos && vitorias.length <= maximo && !outros.length;
  var texto = vitorias.length + ' vencedora(s) (201), ' + conflitos.length + ' conflito(s) (409)' + (outros.length ? ', ' + outros.length + ' resposta(s) inesperada(s)' : '') +
    ' em ' + total + ' ms. Matrículas ' + (distintos ? 'todas distintas' : 'REPETIDAS') +
    '. Máximo possível agora: ' + maximo + ' (vagas restantes ' + (restantes === null ? '?' : restantes) + ', fila ' + naFila + '). ' +
    (correto ? 'Resultado correto: não houve dupla matrícula.' : 'ATENÇÃO: confira o registro de chamadas.');
  var veredito = $('b_parVeredito');
  veredito.textContent = texto;
  veredito.style.color = correto ? 'var(--ok)' : 'var(--erro)';
  veredito.style.fontWeight = '600';
  avisar(correto ? 'Concorrência OK: ' + vitorias.length + ' venceu, ' + conflitos.length + ' recebeu 409.' : 'Resultado inesperado na concorrência.', correto ? 'ok' : 'erro');
}

// Cenário didático da corrida: 1 vaga e 3 inscritos, para que só uma requisição possa ganhar
async function prepararCorrida() {
  var saida = $('b_parVeredito');
  saida.style.color = '';
  saida.textContent = 'Preparando creche, turma de 1 vaga e 3 inscrições...';
  var turma = await acaoCriarCrecheETurma({ nome: 'Creche Corrida ' + sufixoAleatorio(), capacidade: 10, rua: 'Rua B', lat: -23.56, lon: -46.64 }, 'MATERNAL', 1);
  if (!turma.ok) { saida.textContent = 'Falha ao criar a turma: ' + mensagemDeErro(turma); return; }
  var perfis = ['baixa', 'media', 'alta'];
  for (var i = 0; i < perfis.length; i++) {
    var r = await acaoInscreverExemplo(turma.dados.turmaId, perfis[i]);
    if (!r.ok) { saida.textContent = 'Falha na inscrição: ' + mensagemDeErro(r); return; }
  }
  renderizarMatricula();
  $('b_aTurma').value = String(turma.dados.turmaId);
  $('b_nPar').value = '5';
  $('b_nParVal').textContent = '5';
  saida.textContent = 'Pronto: turma #' + turma.dados.turmaId + ' com 1 vaga e 3 inscritos. Dispare 5 alocações em paralelo: só 1 deve vencer.';
}

async function aoAlocarComFilaVazia() {
  var saida = $('b_erroRes');
  var crecheId = Number($('b_tCreche').value);
  var turma;
  if (crecheId) {
    turma = await acaoCriarTurma({ crecheId: crecheId, faixa: 'BERCARIO', vagas: 1 });
  } else {
    turma = await acaoCriarCrecheETurma(lerCrecheDoFormulario(), 'BERCARIO', 1);
  }
  if (!turma.ok) { saida.textContent = 'Falha ao criar a turma vazia: ' + mensagemDeErro(turma); return; }
  var resposta = await acaoAlocar(turma.dados.turmaId, 'Alocar com fila vazia (espera 409)');
  saida.textContent = 'Turma #' + turma.dados.turmaId + ' sem inscritos: alocação respondeu ' + resposta.status + (resposta.status === 409 ? ' (409 esperado: ' + (resposta.dados && resposta.dados.erro) + ').' : ' (esperado 409).');
}

function iniciarMatricula() {
  $('b_btnCreche').onclick = aoCriarCreche;
  $('b_btnTurma').onclick = aoCriarTurma;
  $('b_btnAmbos').onclick = aoCriarCrecheETurma;
  $('b_btnAlocar').onclick = aoAlocar;
  $('b_btnConfirmar').onclick = aoConfirmar;
  $('b_btnConfirmar2').onclick = aoConfirmarDuasVezes;
  $('b_btnParalelo').onclick = aoDispararEmParalelo;
  $('b_btnPrepara').onclick = prepararCorrida;
  $('b_btnFilaVazia').onclick = aoAlocarComFilaVazia;
  $('b_aTurma').onchange = function () { $('b_aTurma').setAttribute('data-tocado', '1'); };
  $('b_mSel').onchange = function () { $('b_mSel').setAttribute('data-tocado', '1'); };
  $('b_nPar').oninput = function () { $('b_nParVal').textContent = $('b_nPar').value; };
  renderizarMatricula();
}
