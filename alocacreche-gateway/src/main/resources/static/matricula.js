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
  var opcoesMatricula = matriculasComId().map(function (m) { return { valor: m.id, rotulo: '#' + m.id + ' ' + m.nome + ' (' + m.status + ')' }; });
  preencherSelect($('b_mSel'), opcoesMatricula, '(aloque uma vaga)');
  if (estado.matriculaAtual && !$('b_mSel').getAttribute('data-tocado')) { $('b_mSel').value = String(estado.matriculaAtual); }
  preencherSelect($('b_dSel'), opcoesMatricula, '(aloque uma vaga)');
  if (estado.matriculaAtual && !$('b_dSel').getAttribute('data-tocado')) { $('b_dSel').value = String(estado.matriculaAtual); }
  preencherSelect($('b_pxResp'), [{ valor: '', rotulo: '(digitar a coordenada)' }].concat(estado.responsaveis.map(function (r) { return { valor: r.id, rotulo: r.nome + ' (#' + r.id + ')' }; })));

  preencherTabela($('b_tabela'), ['Turma', 'Creche', 'Faixa', 'Vagas', 'Alocadas', 'Restantes', 'Na fila'],
    estado.turmas.map(function (t) {
      var creche = crecheLocal(t.crecheId);
      return ['#' + t.id, creche ? creche.nome : '#' + t.crecheId, t.faixa, String(t.vagas), String(t.alocadas), String(t.vagas - t.alocadas), String(filaLocalDaTurma(t.id).length)];
    }), 'Nenhuma turma nesta sessão.');
  preencherTabela($('b_tabelaMat'), ['Matrícula', 'Criança', 'Turma', 'Status', 'Distância (estimada)', 'Ação'],
    estado.matriculas.map(function (m) {
      var temId = m.id !== null && m.id !== undefined;
      var botao = '';
      if (temId && (m.status === 'PENDENTE' || m.status === 'CONFIRMADA')) {
        botao = el('button', { class: 'mini sec', texto: 'Desistir', title: 'POST .../matriculas/' + m.id + '/desistir' });
        botao.onclick = function () { $('b_dSel').value = String(m.id); desistirDaMatricula(m.id); };
      }
      return [temId ? '#' + m.id : 'auto', m.nome, '#' + m.turmaId, selo(m.status, classeDeStatus(m.status)), m.distanciaKm === null ? '-' : numero(m.distanciaKm, 2) + ' km', botao];
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

// ---------------------------------------------------------------------------
// Desistência e alocação automática
// ---------------------------------------------------------------------------

function matriculasComId() {
  return estado.matriculas.filter(function (m) { return m.id !== null && m.id !== undefined; });
}

function statusDoProximo(resumo) {
  if (!resumo.proximo) { return 'ninguém na fila desta sessão'; }
  if (resumo.statusPosicao === 404) { return 'saiu da fila (404): foi convocado'; }
  if (resumo.statusPosicao === 200) { return 'continua na fila'; }
  return 'consulta respondeu ' + resumo.statusPosicao;
}

function mostrarDesistencia(resposta) {
  var res = $('b_desRes');
  res.style.color = '';
  res.style.fontWeight = '';
  if (!resposta.ok) {
    preencherTabela($('b_desTabela'), ['Item', 'Antes', 'Depois'], [], 'Sem alteração.');
    res.textContent = '';
    res.appendChild(seloStatus(resposta.status));
    var explica = resposta.status === 409 ? ' 409: a matrícula já não ocupa vaga (já foi cancelada ou expirada).'
      : resposta.status === 404 ? ' 404: matrícula inexistente (ou de outro município).' : ' ' + mensagemDeErro(resposta);
    res.appendChild(document.createTextNode(explica));
    return;
  }
  var r = resposta.resumo;
  var matricula = acharPorId(estado.matriculas, r.matriculaId);
  var fila = filaLocalDaTurma(r.turmaId);
  var linhas = [
    ['Matrícula #' + r.matriculaId, matricula && matricula.statusAntes ? matricula.statusAntes : 'PENDENTE ou CONFIRMADA', selo('CANCELADA', 'erro')],
    ['Vagas disponíveis na turma #' + r.turmaId, 'logo após cancelar: ' + r.vagasAposCancelar, r.vagasFinais === null ? '-' : String(r.vagasFinais) + (r.realocada ? ' (vaga reocupada)' : ' (vaga livre)')],
    ['Próximo da fila' + (r.proximo ? ' (inscrição #' + r.proximo.id + ', ' + r.proximo.nome + ')' : ''), r.proximo ? 'posição ' + r.proximo.posicaoAntes : '-', statusDoProximo(r)],
    ['Nova matrícula PENDENTE', '-', r.realocada ? selo('criada para a inscrição #' + r.proximo.id, 'aviso') : 'não houve alocação'],
    ['Inscrições aguardando (desta sessão)', String(fila.length + (r.realocada ? 1 : 0)), String(fila.length)]
  ];
  preencherTabela($('b_desTabela'), ['Item', 'Antes', 'Depois'], linhas, '');
  res.textContent = '';
  res.appendChild(seloStatus(resposta.status));
  res.appendChild(document.createTextNode(r.realocada
    ? ' A vaga devolvida já foi dada ao próximo da fila pela alocação automática (id da nova matrícula: veja o banco). Os relatórios refletem em instantes.'
    : (r.proximo ? ' A vaga ficou livre: o próximo da fila ainda aparece na fila (a alocação automática pode ter falhado; o job de manutenção reconcilia).' : ' Ninguém na fila desta sessão: a vaga ficou livre.')));
  acompanharRelatoriosDaDesistencia(r);
}

var geracaoDesistencia = 0;

// Espera os relatórios refletirem a desistência e escreve os números observados
async function acompanharRelatoriosDaDesistencia(resumo) {
  var geracao = ++geracaoDesistencia;
  var alvo = $('b_desRel');
  alvo.textContent = 'Aguardando os relatórios (consistência eventual)...';
  var fila = filaLocalDaTurma(resumo.turmaId).length;
  var limite = Date.now() + 25000;
  while (Date.now() < limite && geracao === geracaoDesistencia) {
    await atualizarRelatorios();
    if (snapshot && snapshot.status.ocupacao === 200) {
      var linha = null;
      snapshot.ocupacao.forEach(function (o) { if (o.turmaId === resumo.turmaId) { linha = o; } });
      var filaRel = filaDaTurma(resumo.turmaId);
      var quantidade = filaRel ? filaRel.quantidadeAguardando : 0;
      if (linha && resumo.vagasFinais !== null && linha.vagasDisponiveis === resumo.vagasFinais && quantidade === fila) {
        var total = snapshot.taxa ? snapshot.taxa.totalMatriculas : '?';
        alvo.textContent = 'Relatórios refletem a desistência: turma #' + resumo.turmaId + ' com ' + linha.vagasDisponiveis + ' vaga(s) disponível(is), ' + quantidade + ' na fila; total de matrículas do município no período: ' + total + '.';
        return;
      }
    }
    await esperar(1500);
  }
  if (geracao === geracaoDesistencia) { alvo.textContent = 'Os relatórios ainda não convergiram (RabbitMQ ou MSRelatorios parados?). Veja a aba Eventos e Outbox.'; }
}

async function desistirDaMatricula(matriculaId) {
  var matricula = acharPorId(estado.matriculas, matriculaId);
  if (matricula) { matricula.statusAntes = matricula.status; }
  var resposta = await acaoDesistir(matriculaId);
  mostrarDesistencia(resposta);
  return resposta;
}

async function aoDesistir() {
  var matriculaId = Number($('b_dSel').value);
  if (!matriculaId) { avisar('Aloque uma vaga primeiro.', 'erro'); return; }
  await desistirDaMatricula(matriculaId);
}

async function aoDesistirDuasVezes() {
  var matriculaId = Number($('b_dSel').value);
  if (!matriculaId) { avisar('Aloque uma vaga primeiro.', 'erro'); return; }
  var primeira = await desistirDaMatricula(matriculaId);
  var segunda = await acaoDesistir(matriculaId, 'Desistir de novo (espera 409)');
  var res = $('b_desRes');
  res.textContent = '1ª chamada: ' + primeira.status + ' | 2ª chamada: ' + segunda.status + (segunda.status === 409 ? ' (conflito esperado: a matrícula já foi cancelada).' : ' (esperado 409).');
  res.style.color = segunda.status === 409 ? 'var(--ok)' : 'var(--erro)';
  res.style.fontWeight = '600';
}

async function aoDesistirDeInexistente() {
  var resposta = await acaoDesistir(ID_INEXISTENTE, 'Desistir de matrícula inexistente (espera 404)');
  mostrarDesistencia(resposta);
}

/*
 * Cenário completo: turma de 1 vaga com 3 inscritos (renda alta, média e baixa + vulnerável, nessa
 * ordem de chegada). A alocação convoca a de maior score; ao desistir, o servidor convoca sozinho
 * a seguinte (renda média, score 0,20) e a fila fica só com a renda alta.
 */
async function executarCenarioDesistencia() {
  var res = $('b_desRes');
  res.style.color = '';
  res.textContent = 'Preparando turma de 1 vaga e 3 inscrições...';
  $('b_desRel').textContent = '';
  var turma = await acaoCriarCrecheETurma({ nome: 'Creche Desistencia ' + sufixoAleatorio(), capacidade: 10, rua: 'Rua B', lat: -23.56, lon: -46.64 }, 'MATERNAL', 1);
  if (!turma.ok) { res.textContent = 'Falha ao criar a turma: ' + mensagemDeErro(turma); return; }
  var turmaId = turma.dados.turmaId;
  var perfis = ['alta', 'media', 'baixa'];
  for (var i = 0; i < perfis.length; i++) {
    var ins = await acaoInscreverExemplo(turmaId, perfis[i]);
    if (!ins.ok) { res.textContent = 'Falha na inscrição: ' + mensagemDeErro(ins); return; }
  }
  var aloc = await acaoAlocar(turmaId, 'Alocar a vaga (convoca a maior prioridade)');
  if (!aloc.ok) { res.textContent = 'Falha ao alocar: ' + mensagemDeErro(aloc); return; }
  res.textContent = 'Matrícula #' + aloc.dados.matriculaId + ' alocada; desistindo para disparar a alocação automática...';
  await esperar(600);
  await desistirDaMatricula(aloc.dados.matriculaId);
}

// ---------------------------------------------------------------------------
// Sugestão de creches próximas
// ---------------------------------------------------------------------------

var MENSAGENS_CAMPO = {
  latitude: 'Latitude obrigatória, entre -90 e 90.',
  longitude: 'Longitude obrigatória, entre -180 e 180.',
  faixaEtaria: 'Faixa etária obrigatória (BERCARIO, MATERNAL ou PRE_ESCOLA).',
  limite: 'Limite deve ficar entre 1 e 20.'
};

function textoDeErroDeProximas(resposta) {
  if (resposta.status !== 400) { return mensagemDeErro(resposta); }
  var campos = resposta.dados && resposta.dados.campos ? resposta.dados.campos : [];
  var amigavel = campos.map(function (c) {
    var nome = String(c).split(/[: ]/)[0];
    return MENSAGENS_CAMPO[nome] || String(c);
  });
  return '400: ' + (amigavel.length ? amigavel.join(' ') : 'parâmetros inválidos. Confira latitude, longitude, faixa etária e limite.');
}

function consultaDeProximas(valores) {
  var partes = [];
  Object.keys(valores).forEach(function (chave) {
    if (valores[chave] !== '' && valores[chave] !== null && valores[chave] !== undefined) { partes.push(chave + '=' + encodeURIComponent(valores[chave])); }
  });
  return partes.join('&');
}

async function buscarCrechesProximas(valores) {
  var resposta = await api('GET', '/api/v1/matricula/creches/proximas?' + consultaDeProximas(valores), { rotulo: 'Sugerir creches próximas' });
  var res = $('b_pxRes');
  res.style.color = '';
  res.textContent = '';
  res.appendChild(seloStatus(resposta.status));
  if (!resposta.ok) {
    preencherTabela($('b_pxTabela'), ['Creche'], [], 'Sem resultado.');
    res.appendChild(document.createTextNode(' ' + textoDeErroDeProximas(resposta)));
    return resposta;
  }
  var lista = Array.isArray(resposta.dados) ? resposta.dados : [];
  preencherTabela($('b_pxTabela'), ['#', 'Creche', 'Bairro', 'Turma', 'Vagas', 'Distância', ''],
    lista.map(function (c, i) {
      var botao = el('button', { class: 'mini fantasma', texto: 'Inscrever aqui', title: 'Leva esta turma para o formulário de inscrição' });
      botao.onclick = function () { usarTurmaSugerida(c.turmaId); };
      return [String(i + 1), c.nome + ' (#' + c.crecheId + ')', c.bairro || '-', '#' + c.turmaId,
        c.vagasDisponiveis > 0 ? String(c.vagasDisponiveis) : selo('sem vaga: entra na fila', 'aviso'),
        numero(c.distanciaKm, 2) + ' km', botao];
    }), 'Nenhuma turma da faixa etária com coordenadas cadastradas.');
  $$('#b_pxTabela tbody tr').forEach(function (tr, i) { if (lista[i] && lista[i].vagasDisponiveis === 0) { tr.classList.add('destaque'); } });
  var semVaga = lista.filter(function (c) { return c.vagasDisponiveis === 0; }).length;
  res.appendChild(document.createTextNode(' ' + lista.length + ' turma(s), da mais próxima para a mais distante' + (semVaga ? '; ' + semVaga + ' sem vaga (destacada: a família pode entrar na fila).' : '.')));
  return resposta;
}

function usarTurmaSugerida(turmaId) {
  $('a_iTurmaId').value = String(turmaId);
  irParaAba('inscricao');
  avisar('Turma #' + turmaId + ' preenchida no formulário de inscrição.', 'ok');
}

function lerFormularioDeProximas() {
  return { latitude: $('b_pxLat').value, longitude: $('b_pxLon').value, faixaEtaria: $('b_pxFaixa').value, limite: $('b_pxLim').value };
}

function preencherCoordenada(lat, lon) {
  $('b_pxLat').value = String(lat);
  $('b_pxLon').value = String(lon);
}

function aoEscolherResponsavelParaProximas() {
  var responsavel = acharPorId(estado.responsaveis, Number($('b_pxResp').value));
  if (responsavel) { preencherCoordenada(responsavel.lat.toFixed(4), responsavel.lon.toFixed(4)); }
}

function iniciarMatricula() {
  $('b_btnDesistir').onclick = aoDesistir;
  $('b_btnDesistir2').onclick = aoDesistirDuasVezes;
  $('b_btnDesistir404').onclick = aoDesistirDeInexistente;
  $('b_btnCenarioDes').onclick = executarCenarioDesistencia;
  $('b_dSel').onchange = function () { $('b_dSel').setAttribute('data-tocado', '1'); };
  $('b_btnProximas').onclick = function () { buscarCrechesProximas(lerFormularioDeProximas()); };
  $('b_btnPxSe').onclick = function () { preencherCoordenada(-23.55, -46.63); };
  $('b_btnPxPaulista').onclick = function () { preencherCoordenada(-23.56, -46.65); };
  $('b_btnPxErro').onclick = function () { buscarCrechesProximas({ latitude: '200', longitude: $('b_pxLon').value, faixaEtaria: $('b_pxFaixa').value, limite: '50' }); };
  $('b_pxResp').onchange = aoEscolherResponsavelParaProximas;
  preencherTabela($('b_desTabela'), ['Item', 'Antes', 'Depois'], [], 'Desista de uma matrícula para ver o que mudou.');
  preencherTabela($('b_pxTabela'), ['Creche'], [], 'Clique em Buscar creches próximas.');
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
