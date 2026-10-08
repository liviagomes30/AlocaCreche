/*
 * Aba Stack A (Inscrição e fila): cadastro de responsável e criança, inscrição por criancaId
 * ou inline, score de prioridade, posição na fila e a fila desta sessão.
 */

var NOMES_EXEMPLO = ['Marina', 'Paulo', 'Helena', 'Rafael', 'Beatriz', 'Lucas', 'Sofia', 'Tiago'];

function valorNumero(id) { return Number($(id).value); }

function lerResponsavelDoFormulario() {
  return {
    nome: $('a_rNome').value, cpf: $('a_rCpf').value.trim(), telefone: $('a_rTel').value, email: $('a_rEmail').value,
    rua: $('a_rRua').value, numero: valorNumero('a_rNum'), lat: valorNumero('a_rLat'), lon: valorNumero('a_rLon')
  };
}

function turmaDoFormularioDeInscricao() {
  var manual = valorNumero('a_iTurmaId');
  if (manual) { return manual; }
  return Number($('a_iTurma').value) || null;
}

// Estimativa local do score com os valores do formulário (a distância sempre entra como 0 na inscrição)
function atualizarEstimativaDeScore() {
  var renda = valorNumero('a_iRenda');
  var vulneravel = $('a_iVuln').value === 'true';
  var e = explicarScore(renda, vulneravel, false);
  $('a_scoreEstimado').textContent = numero(e.total, 2);
  $('a_scoreDetalhe').textContent = e.texto;
}

// Cor do selo conforme o status de inscrição ou matrícula
function classeDeStatus(status) {
  if (status === 'AGUARDANDO') { return 'info'; }
  if (status === 'CANCELADA' || status === 'EXPIRADA') { return 'erro'; }
  if (status === 'PENDENTE' || status === 'CONVOCADA') { return 'aviso'; }
  return 'ok';
}

function mostrarResultado(idElemento, resposta, mensagemOk) {
  var alvo = $(idElemento);
  alvo.textContent = '';
  alvo.appendChild(seloStatus(resposta.status));
  alvo.appendChild(document.createTextNode(' ' + (resposta.ok ? mensagemOk : mensagemDeErro(resposta))));
}

function renderizarInscricao() {
  preencherSelect($('a_cResp'), estado.responsaveis.map(function (r) { return { valor: r.id, rotulo: r.nome + ' (#' + r.id + ')' }; }), '(cadastre um responsável)');
  preencherSelect($('a_iCrianca'), estado.criancas.map(function (c) { return { valor: c.id, rotulo: c.nome + ' (#' + c.id + ')' }; }), '(cadastre uma criança)');
  preencherSelect($('a_iTurma'), estado.turmas.map(function (t) { return { valor: t.id, rotulo: rotuloTurma(t) }; }), '(crie uma turma na aba Stack B)');
  if (estado.turmaAtual && !$('a_iTurma').getAttribute('data-tocado')) { $('a_iTurma').value = String(estado.turmaAtual); }
  preencherSelect($('a_pInscricao'), estado.inscricoes.map(function (i) { return { valor: i.id, rotulo: '#' + i.id + ' ' + i.nome + ' (' + i.status + ')' }; }), '(nenhuma inscrição ainda)');
  $('a_iCriancaBox').style.display = $('a_iModo').value === 'id' ? '' : 'none';

  var ordenadas = estado.inscricoes.slice().sort(function (a, b) {
    return a.turmaId !== b.turmaId ? a.turmaId - b.turmaId : (a.posicao || 999) - (b.posicao || 999);
  });
  preencherTabela($('a_tabela'), ['Inscrição', 'Criança', 'Turma', 'Posição', 'Score', 'Status'],
    ordenadas.map(function (i) {
      return ['#' + i.id, i.nome, '#' + i.turmaId, i.posicao === null || i.posicao === undefined ? '-' : String(i.posicao),
        el('span', { title: i.explicacao, texto: numero(i.score, 2) }), selo(i.status, classeDeStatus(i.status))];
    }), 'Nenhuma inscrição nesta sessão.');
}

async function aoCadastrarResponsavel() {
  var resposta = await acaoCadastrarResponsavel(lerResponsavelDoFormulario());
  mostrarResultado('a_respRes', resposta, 'Responsável #' + (resposta.dados && resposta.dados.responsavelId) + ' cadastrado (CPF gravado só no servidor).');
  if (resposta.ok) {
    $('a_rCpf').value = '';
    renderizarInscricao();
    $('a_cResp').value = String(resposta.dados.responsavelId);
  }
}

async function aoCadastrarCrianca() {
  var responsavelId = Number($('a_cResp').value);
  if (!responsavelId) { avisar('Cadastre um responsável primeiro.', 'erro'); return; }
  var resposta = await acaoCadastrarCrianca({ responsavelId: responsavelId, nome: $('a_cNome').value, nascimento: $('a_cNasc').value });
  mostrarResultado('a_criRes', resposta, 'Criança #' + (resposta.dados && resposta.dados.criancaId) + ' cadastrada.');
  if (resposta.ok) { renderizarInscricao(); $('a_iCrianca').value = String(resposta.dados.criancaId); }
}

async function aoInscrever() {
  var turmaId = turmaDoFormularioDeInscricao();
  if (!turmaId) { avisar('Crie uma turma (aba Stack B) ou informe o id da turma.', 'erro'); return; }
  var modo = $('a_iModo').value;
  var dados = { modo: modo, turmaId: turmaId, renda: valorNumero('a_iRenda'), vulneravel: $('a_iVuln').value === 'true' };
  if (modo === 'id') {
    dados.criancaId = Number($('a_iCrianca').value);
    if (!dados.criancaId) { avisar('Cadastre uma criança primeiro.', 'erro'); return; }
  } else {
    dados.responsavel = lerResponsavelDoFormulario();
    dados.crianca = { nome: $('a_cNome').value, nascimento: $('a_cNasc').value };
  }
  var resposta = await acaoInscrever(dados);
  var d = resposta.dados;
  mostrarResultado('a_insRes', resposta, resposta.ok ? 'Inscrição #' + d.inscricaoId + ': posição ' + d.posicao + ', score ' + numero(d.scorePrioridade, 2) + ', status ' + d.status + '.' : '');
  if (resposta.ok) { $('a_rCpf').value = ''; }
}

function sortearNome() { return NOMES_EXEMPLO[Math.floor(Math.random() * NOMES_EXEMPLO.length)]; }

function gerarOutroNome() {
  $('a_rNome').value = 'Resp. ' + sortearNome() + ' ' + sufixoAleatorio();
  $('a_cNome').value = 'Crianca ' + sortearNome() + ' ' + sufixoAleatorio();
  $('a_rCpf').value = '';
}

function aplicarExemplo(tipo) {
  if (tipo === 'irmao') { executarCenarioIrmao(); return; }
  var p = PERFIS_EXEMPLO[tipo];
  $('a_iRenda').value = String(p.renda);
  $('a_iVuln').value = p.vulneravel ? 'true' : 'false';
  gerarOutroNome();
  atualizarEstimativaDeScore();
  $('a_insRes').textContent = 'Exemplo "' + p.rotulo + '" preenchido: clique em Inscrever.';
}

/*
 * Cenário do irmão: usa uma turma nova (fila vazia) para garantir que o primeiro irmão seja o
 * convocado. Depois de matriculado, o segundo irmão recebe 0,1 a mais no score.
 */
async function executarCenarioIrmao() {
  var saida = $('a_insRes');
  saida.textContent = 'Executando o cenário do irmão...';
  var sufixo = sufixoAleatorio();
  var turma = await acaoCriarCrecheETurma({ nome: 'Creche Irmaos ' + sufixo, capacidade: 20, rua: 'Rua B', lat: -23.56, lon: -46.64 }, 'MATERNAL', 3);
  if (!turma.ok) { mostrarResultado('a_insRes', turma, ''); return; }
  var turmaId = turma.dados.turmaId;
  var resp = await acaoCadastrarResponsavel({ nome: 'Resp. Irmaos ' + sufixo, cpf: '', telefone: '11999990000', email: 'irmaos.' + sufixo + '@example.com', rua: 'Rua A', numero: 10, lat: -23.55, lon: -46.63 });
  if (!resp.ok) { mostrarResultado('a_insRes', resp, ''); return; }
  var respId = resp.dados.responsavelId;
  var criA = await acaoCadastrarCrianca({ responsavelId: respId, nome: 'Irmao A ' + sufixo, nascimento: '2023-05-10' });
  if (!criA.ok) { mostrarResultado('a_insRes', criA, ''); return; }
  var insA = await acaoInscrever({ modo: 'id', turmaId: turmaId, criancaId: criA.dados.criancaId, renda: 1200, vulneravel: false });
  if (!insA.ok) { mostrarResultado('a_insRes', insA, ''); return; }
  var aloc = await acaoAlocar(turmaId, 'Alocar o primeiro irmão');
  if (!aloc.ok) { mostrarResultado('a_insRes', aloc, ''); return; }
  var criB = await acaoCadastrarCrianca({ responsavelId: respId, nome: 'Irmao B ' + sufixo, nascimento: '2024-08-02' });
  if (!criB.ok) { mostrarResultado('a_insRes', criB, ''); return; }
  var insB = await acaoInscrever({ modo: 'id', turmaId: turmaId, criancaId: criB.dados.criancaId, renda: 1200, vulneravel: false, irmao: true });
  if (!insB.ok) { mostrarResultado('a_insRes', insB, ''); return; }
  var diferenca = insB.dados.scorePrioridade - insA.dados.scorePrioridade;
  saida.textContent = '';
  saida.appendChild(seloStatus(201));
  saida.appendChild(document.createTextNode(' Irmão A: score ' + numero(insA.dados.scorePrioridade, 2) + ' (matriculado). Irmão B: score ' + numero(insB.dados.scorePrioridade, 2) +
    ' (diferença de ' + numero(diferenca, 2) + ' = peso do irmão matriculado, 0,1).'));
  avisar('Cenário do irmão concluído: diferença de score ' + numero(diferenca, 2), 'ok');
}

async function aoConsultarPosicao() {
  var inscricaoId = Number($('a_pInscricao').value);
  var ins = acharPorId(estado.inscricoes, inscricaoId);
  if (!ins) { avisar('Inscreva alguém primeiro.', 'erro'); return; }
  var resposta = await acaoConsultarPosicao(inscricaoId, ins.turmaId);
  var alvo = $('a_posRes');
  alvo.textContent = '';
  alvo.appendChild(seloStatus(resposta.status));
  if (resposta.ok) {
    alvo.appendChild(document.createTextNode(' Inscrição #' + inscricaoId + ' está na posição ' + resposta.dados.posicao + ' da turma ' + resposta.dados.turmaId + ' (lida do Redis; sem a chave, do banco).'));
  } else if (resposta.status === 404) {
    alvo.appendChild(document.createTextNode(' 404: a inscrição já saiu da fila (foi alocada) ou não existe na turma.'));
  } else {
    alvo.appendChild(document.createTextNode(' ' + mensagemDeErro(resposta)));
  }
  renderizarInscricao();
}

async function aoConsultarTodasAsPosicoes() {
  var fila = estado.inscricoes.filter(function (i) { return i.status === 'AGUARDANDO'; });
  if (!fila.length) { avisar('Nenhuma inscrição aguardando.', 'erro'); return; }
  for (var i = 0; i < fila.length; i++) { await acaoConsultarPosicao(fila[i].id, fila[i].turmaId); }
  renderizarInscricao();
  avisar('Posições atualizadas a partir da API.', 'ok');
}

// ---------------------------------------------------------------------------
// Cenários: fila por prioridade, inscrições simultâneas e inscrição única da criança
// ---------------------------------------------------------------------------

// Escreve o veredito de um cenário (verde quando correto, vermelho caso contrário)
function escreverVeredito(idElemento, correto, texto) {
  var alvo = $(idElemento);
  alvo.textContent = texto;
  alvo.style.color = correto ? 'var(--ok)' : 'var(--erro)';
  alvo.style.fontWeight = '600';
}

function limparCenario(texto) {
  var alvo = $('a_cenRes');
  alvo.style.color = '';
  alvo.style.fontWeight = '';
  alvo.textContent = texto;
  preencherTabela($('a_cenTabela'), ['Inscrição'], [], 'Executando...');
}

// Cria uma creche e uma turma novas (fila vazia) para o cenário
async function criarTurmaDeCenario(nome, vagas) {
  return acaoCriarCrecheETurma({ nome: nome + ' ' + sufixoAleatorio(), capacidade: 30, rua: 'Rua B', lat: -23.56, lon: -46.64 }, 'MATERNAL', vagas);
}

// Consulta a posição oficial (GET .../posicao) de cada inscrição; devolve {status, posicao} na mesma ordem
async function consultarPosicoes(inscricoes, turmaId) {
  var respostas = await Promise.all(inscricoes.map(function (i) { return acaoConsultarPosicao(i.inscricaoId, turmaId); }));
  return respostas.map(function (r) { return { status: r.status, posicao: r.ok ? r.dados.posicao : null }; });
}

function textoDaPosicao(p) { return p.posicao === null ? 'erro ' + p.status : String(p.posicao); }

async function executarCenarioFurarFila() {
  limparCenario('Criando a turma do cenário...');
  var turma = await criarTurmaDeCenario('Creche Prioridade', 5);
  if (!turma.ok) { escreverVeredito('a_cenRes', false, 'Falha ao criar a turma: ' + mensagemDeErro(turma)); return; }
  var turmaId = turma.dados.turmaId;
  var primeira = await acaoInscreverExemplo(turmaId, 'alta');
  if (!primeira.ok) { escreverVeredito('a_cenRes', false, 'Falha na 1ª inscrição: ' + mensagemDeErro(primeira)); return; }
  var segunda = await acaoInscreverExemplo(turmaId, 'baixa');
  if (!segunda.ok) { escreverVeredito('a_cenRes', false, 'Falha na 2ª inscrição: ' + mensagemDeErro(segunda)); return; }
  var linhas = [
    { rotulo: 'renda alta (chegou primeiro)', r: primeira.dados, esperada: 2 },
    { rotulo: 'renda baixa + vulnerável (chegou depois)', r: segunda.dados, esperada: 1 }
  ];
  var posicoes = await consultarPosicoes(linhas.map(function (l) { return l.r; }), turmaId);
  var correto = true;
  preencherTabela($('a_cenTabela'), ['Inscrição', 'Perfil', 'Score', 'Posição na resposta', 'Posição agora', 'Esperada'],
    linhas.map(function (l, i) {
      var confere = posicoes[i].posicao === l.esperada;
      correto = correto && confere;
      return ['#' + l.r.inscricaoId, l.rotulo, numero(l.r.scorePrioridade, 2), String(l.r.posicao), textoDaPosicao(posicoes[i]), selo(String(l.esperada), confere ? 'ok' : 'erro')];
    }), '');
  escreverVeredito('a_cenRes', correto, correto
    ? 'A inscrição de maior score passou na frente: a renda alta saiu da posição 1 para a 2 quando a baixa + vulnerável chegou (turma #' + turmaId + ').'
    : 'ATENÇÃO: as posições não seguem o score. Confira o registro de chamadas.');
}

// Perfis com scores todos diferentes (0,7 a 0,0), para que a ordem esperada seja inequívoca
var PERFIS_SIMULTANEOS = [
  { renda: 350, vulneravel: true, rotulo: 'renda 350 + vuln.', nome: 'P1' },
  { renda: 800, vulneravel: true, rotulo: 'renda 800 + vuln.', nome: 'P2' },
  { renda: 1200, vulneravel: true, rotulo: 'renda 1200 + vuln.', nome: 'P3' },
  { renda: 350, vulneravel: false, rotulo: 'renda 350', nome: 'P4' },
  { renda: 800, vulneravel: false, rotulo: 'renda 800', nome: 'P5' },
  { renda: 1200, vulneravel: false, rotulo: 'renda 1200', nome: 'P6' },
  { renda: 1700, vulneravel: false, rotulo: 'renda 1700', nome: 'P7' },
  { renda: 2500, vulneravel: false, rotulo: 'renda 2500', nome: 'P8' }
];

async function executarCenarioSimultaneas() {
  limparCenario('Criando a turma do cenário...');
  var turma = await criarTurmaDeCenario('Creche Simultanea', 10);
  if (!turma.ok) { escreverVeredito('a_cenRes', false, 'Falha ao criar a turma: ' + mensagemDeErro(turma)); return; }
  var turmaId = turma.dados.turmaId;
  $('a_cenRes').textContent = 'Disparando ' + PERFIS_SIMULTANEOS.length + ' inscrições ao mesmo tempo...';
  // A ordem de disparo é embaralhada para que a chegada não coincida com o score
  var disparo = PERFIS_SIMULTANEOS.slice().sort(function () { return Math.random() - 0.5; });
  var respostas = await Promise.all(disparo.map(function (p) { return acaoInscreverExemplo(turmaId, p); }));
  var falhas = respostas.filter(function (r) { return !r.ok; });
  if (falhas.length) { escreverVeredito('a_cenRes', false, falhas.length + ' inscrição(ões) falharam: ' + mensagemDeErro(falhas[0])); return; }
  var itens = respostas.map(function (r, i) { return { perfil: disparo[i], r: r.dados }; });
  itens.sort(function (a, b) { return b.r.scorePrioridade - a.r.scorePrioridade; });
  var posicoes = await consultarPosicoes(itens.map(function (i) { return i.r; }), turmaId);
  var obtidas = posicoes.map(function (p) { return p.posicao; });
  var distintas = obtidas.filter(function (p, pos) { return p !== null && obtidas.indexOf(p) === pos; }).length === obtidas.length;
  var naOrdem = obtidas.every(function (p, i) { return p === i + 1; });
  preencherTabela($('a_cenTabela'), ['Esperada', 'Inscrição', 'Perfil', 'Score', 'Posição na resposta', 'Posição agora'],
    itens.map(function (item, i) {
      return [selo(String(i + 1), obtidas[i] === i + 1 ? 'ok' : 'erro'), '#' + item.r.inscricaoId, item.perfil.rotulo, numero(item.r.scorePrioridade, 2), String(item.r.posicao), textoDaPosicao(posicoes[i])];
    }), '');
  var correto = distintas && naOrdem;
  escreverVeredito('a_cenRes', correto, correto
    ? PERFIS_SIMULTANEOS.length + ' inscrições em paralelo na turma #' + turmaId + ': posições 1 a ' + PERFIS_SIMULTANEOS.length + ' sem repetição e na ordem do score (a resposta de cada requisição reflete só o momento em que ela terminou).'
    : 'ATENÇÃO: posições ' + (distintas ? 'fora da ordem do score' : 'repetidas ou ausentes') + '. Confira o registro de chamadas.');
}

// Cadastra turma, responsável e criança; devolve {turmaId, criancaId} ou null (com o erro no cenário)
async function prepararCriancaDeCenario(vagas) {
  var turma = await criarTurmaDeCenario('Creche Unica', vagas);
  if (!turma.ok) { escreverVeredito('a_cenRes', false, 'Falha ao criar a turma: ' + mensagemDeErro(turma)); return null; }
  var sufixo = sufixoAleatorio();
  var resp = await acaoCadastrarResponsavel({ nome: 'Resp. Unica ' + sufixo, cpf: '', telefone: '11999990000', email: 'unica.' + sufixo + '@example.com', rua: 'Rua A', numero: 10, lat: -23.55, lon: -46.63 });
  if (!resp.ok) { escreverVeredito('a_cenRes', false, 'Falha no responsável: ' + mensagemDeErro(resp)); return null; }
  var crianca = await acaoCadastrarCrianca({ responsavelId: resp.dados.responsavelId, nome: 'Crianca Unica ' + sufixo, nascimento: '2024-03-10' });
  if (!crianca.ok) { escreverVeredito('a_cenRes', false, 'Falha na criança: ' + mensagemDeErro(crianca)); return null; }
  return { turmaId: turma.dados.turmaId, criancaId: crianca.dados.criancaId };
}

function inscreverCriancaDoCenario(base) {
  return acaoInscrever({ modo: 'id', turmaId: base.turmaId, criancaId: base.criancaId, renda: 350, vulneravel: true });
}

function linhasDeTentativas(respostas) {
  return respostas.map(function (r, i) {
    return ['#' + (i + 1), seloStatus(r.status), r.ok ? 'inscrição #' + r.dados.inscricaoId + ', posição ' + r.dados.posicao : mensagemDeErro(r)];
  });
}

async function executarCenarioMesmaCrianca() {
  limparCenario('Preparando turma, responsável e criança...');
  var base = await prepararCriancaDeCenario(3);
  if (!base) { return; }
  var primeira = await inscreverCriancaDoCenario(base);
  var segunda = await inscreverCriancaDoCenario(base);
  preencherTabela($('a_cenTabela'), ['Tentativa', 'Status', 'Resultado'], linhasDeTentativas([primeira, segunda]), '');
  var correto = primeira.status === 201 && segunda.status === 409;
  escreverVeredito('a_cenRes', correto, correto
    ? 'A criança #' + base.criancaId + ' já tem inscrição ativa: a 1ª tentativa deu 201 e a 2ª deu 409.'
    : 'ATENÇÃO: esperado 201 e 409, recebido ' + primeira.status + ' e ' + segunda.status + '.');
}

async function executarCenarioMesmaCriancaEmParalelo() {
  limparCenario('Preparando turma, responsável e criança...');
  var base = await prepararCriancaDeCenario(5);
  if (!base) { return; }
  var n = 5;
  $('a_cenRes').textContent = 'Disparando ' + n + ' inscrições da mesma criança ao mesmo tempo...';
  var chamadas = [];
  for (var i = 0; i < n; i++) { chamadas.push(inscreverCriancaDoCenario(base)); }
  var respostas = await Promise.all(chamadas);
  var criadas = respostas.filter(function (r) { return r.status === 201; }).length;
  var conflitos = respostas.filter(function (r) { return r.status === 409; }).length;
  preencherTabela($('a_cenTabela'), ['Requisição', 'Status', 'Resultado'], linhasDeTentativas(respostas), '');
  var correto = criadas === 1 && conflitos === n - 1;
  escreverVeredito('a_cenRes', correto, correto
    ? 'Em paralelo, só 1 inscrição da criança #' + base.criancaId + ' foi aceita (201) e as outras ' + conflitos + ' receberam 409.'
    : 'ATENÇÃO: esperado 1 x 201 e ' + (n - 1) + ' x 409; recebido ' + criadas + ' x 201 e ' + conflitos + ' x 409.');
}

function iniciarInscricao() {
  $('a_btnFurar').onclick = executarCenarioFurarFila;
  $('a_btnSimult').onclick = executarCenarioSimultaneas;
  $('a_btnDupla').onclick = executarCenarioMesmaCrianca;
  $('a_btnDuplaPar').onclick = executarCenarioMesmaCriancaEmParalelo;
  preencherTabela($('a_cenTabela'), ['Inscrição'], [], 'Escolha um cenário.');
  ['a_iRenda', 'a_iVuln'].forEach(function (id) { $(id).addEventListener('input', atualizarEstimativaDeScore); });
  $('a_iModo').onchange = renderizarInscricao;
  $('a_iTurma').onchange = function () { $('a_iTurma').setAttribute('data-tocado', '1'); };
  $('a_btnResp').onclick = aoCadastrarResponsavel;
  $('a_btnNovaFamilia').onclick = gerarOutroNome;
  $('a_btnCrianca').onclick = aoCadastrarCrianca;
  $('a_btnInscrever').onclick = aoInscrever;
  $('a_btnPos').onclick = aoConsultarPosicao;
  $('a_btnPosTodas').onclick = aoConsultarTodasAsPosicoes;
  $$('[data-exemplo]').forEach(function (b) { b.onclick = function () { aplicarExemplo(b.getAttribute('data-exemplo')); }; });
  atualizarEstimativaDeScore();
  renderizarInscricao();
}
