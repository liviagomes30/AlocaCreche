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
        el('span', { title: i.explicacao, texto: numero(i.score, 2) }), selo(i.status, i.status === 'AGUARDANDO' ? 'info' : 'ok')];
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

function iniciarInscricao() {
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
