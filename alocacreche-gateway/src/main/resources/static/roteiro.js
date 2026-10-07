/*
 * Aba Roteiro: checklist de apresentação (persistido no sessionStorage) e demo completa
 * automática, que narra cada passo e pode ser pausada ou interrompida.
 */

var PASSOS_LARISSA = [
  { id: 'L1', aba: 'visao', titulo: 'Abrir a Visão geral e mostrar o usuário logado', esperado: 'Topo mostra o perfil <b>ADMIN</b> e o município; o painel mostra números dos relatórios.' },
  { id: 'L2', aba: 'matricula', titulo: 'Pré-requisito: criar creche e turma (botão "Criar creche + turma")', esperado: 'Toast de sucesso, turma na tabela e evento <b>vaga.liberada</b> na linha do tempo.' },
  { id: 'L3', aba: 'inscricao', titulo: 'Explicar os pesos do score (renda 0,4, vulnerabilidade 0,3, distância 0,2, irmão 0,1)', esperado: 'A estimativa muda ao alterar a renda e a vulnerabilidade.' },
  { id: 'L4', aba: 'inscricao', titulo: 'Cadastrar responsável e criança; inscrever por criancaId', esperado: '<b>201</b> em cada chamada; inscrição com <b>posição 1</b> e score calculado.' },
  { id: 'L5', aba: 'inscricao', titulo: 'Inscrever com cadastro inline usando os exemplos "renda baixa + vulnerável" e "renda alta"', esperado: 'Posições 2 e 3; scores <b>0,70</b> (renda baixa + vulnerável) e <b>0,00</b> (renda alta).' },
  { id: 'L6', aba: 'inscricao', titulo: 'Consultar a posição na fila (Redis com fallback no banco)', esperado: '<b>200</b> com a posição da inscrição escolhida.' },
  { id: 'L7', aba: 'inscricao', titulo: 'Rodar o exemplo "Irmão matriculado"', esperado: 'Irmão B com score <b>0,10 maior</b> que o irmão A (peso do irmão matriculado).' },
  { id: 'L8', aba: 'seguranca', titulo: 'Mostrar CPF duplicado (409) e criança/turma inexistente (404)', esperado: 'Testes "CPF duplicado" e "inexistentes" marcados como passou.' }
];

var PASSOS_VOCE = [
  { id: 'V1', aba: 'matricula', titulo: 'Alocar a vaga ao primeiro da fila', esperado: '<b>201</b> com a matrícula PENDENTE; vagas restantes da turma diminuem em 1.' },
  { id: 'V2', aba: 'matricula', titulo: 'Confirmar a matrícula e tentar confirmar de novo', esperado: '<b>200</b> na primeira vez e <b>409</b> na segunda.' },
  { id: 'V3', aba: 'matricula', titulo: 'Concorrência: "Preparar corrida" e disparar 5 alocações em paralelo', esperado: 'Exatamente <b>1 vencedora (201)</b> e as outras <b>409</b>; nenhuma matrícula repetida.' },
  { id: 'V4', aba: 'visao', titulo: 'Acompanhar a Visão geral com atualização automática', esperado: 'Vagas disponíveis, fila, taxa e distância mudam alguns segundos depois e ficam destacados.' },
  { id: 'V5', aba: 'relatorios', titulo: 'Consultar os relatórios e abrir o painel público em outra aba', esperado: 'Painel público responde <b>200 sem token</b>; município diferente do token responde <b>403</b>.' },
  { id: 'V6', aba: 'eventos', titulo: 'Linha do tempo de eventos e fluxo Outbox, RabbitMQ e MSRelatorios', esperado: 'Eventos passam de "aguardando" para <b>refletido nos relatórios</b>.' },
  { id: 'V7', aba: 'eventos', titulo: 'Resiliência: parar o RabbitMQ, agir, subir de novo', esperado: 'API segue respondendo, relatórios param de mudar e depois convergem ao subir o RabbitMQ.' },
  { id: 'V8', aba: 'seguranca', titulo: 'Executar todos os testes de segurança', esperado: '401 sem token e com token adulterado, 403 de outro município, <b>429</b> com Retry-After após 5 senhas erradas.' },
  { id: 'V9', aba: 'seguranca', titulo: 'Matriz de permissões com ADMIN, ATENDENTE e GESTOR (trocar de usuário)', esperado: 'ATENDENTE só inscreve; GESTOR só lê relatórios; ADMIN faz tudo. Coluna Resultado = confere.' }
];

var marcados = lerJson(CHAVES.roteiro) || {};

function salvarMarcas() { sessionStorage.setItem(CHAVES.roteiro, JSON.stringify(marcados)); }

function renderizarListaDoRoteiro(idLista, passos) {
  var lista = $(idLista);
  lista.textContent = '';
  passos.forEach(function (p, indice) {
    var caixa = el('input', { type: 'checkbox', id: 'chk-' + p.id });
    caixa.checked = !!marcados[p.id];
    var item = el('li', { class: 'passo' + (caixa.checked ? ' feito' : '') });
    caixa.onchange = function () {
      marcados[p.id] = caixa.checked;
      salvarMarcas();
      item.classList.toggle('feito', caixa.checked);
    };
    var botao = el('button', { class: 'mini fantasma', texto: 'Ir para a tela' });
    botao.onclick = function () { irParaAba(p.aba); };
    item.appendChild(caixa);
    item.appendChild(el('div', {}, [
      el('div', { class: 'titulo', texto: (indice + 1) + '. ' + p.titulo }),
      el('div', { class: 'esperado', html: 'Esperado: ' + p.esperado })
    ]));
    item.appendChild(botao);
    lista.appendChild(item);
  });
}

// ---------------------------------------------------------------------------
// Demo completa automática
// ---------------------------------------------------------------------------

var demo = { rodando: false, pausado: false, parar: false };

function narrar(titulo, detalhe, progresso) {
  $('narrador').classList.add('visivel');
  $('narradorTitulo').textContent = titulo;
  $('narradorDetalhe').textContent = detalhe || '';
  if (progresso !== undefined) { $('narradorBarra').style.width = Math.round(progresso * 100) + '%'; }
}

function registrarNaLista(texto, ok) {
  $('narradorLista').appendChild(el('li', { class: ok ? 'ok' : 'erro', texto: (ok ? '[ok] ' : '[falhou] ') + texto }));
}

async function pausarSeNecessario() {
  while (demo.pausado && !demo.parar) { await esperar(200); }
  if (demo.parar) { throw new Error('demo interrompida'); }
}

async function esperarRitmo() {
  var total = Number($('r_ritmo').value);
  var gasto = 0;
  while (gasto < total) {
    await pausarSeNecessario();
    await esperar(200);
    gasto += 200;
  }
}

function checar(resposta, rotulo) {
  if (!resposta.ok) { throw new Error(rotulo + ': ' + mensagemDeErro(resposta)); }
  return resposta;
}

// Espera os relatórios refletirem o estado final esperado (consistência eventual)
async function aguardarRelatorios(turmaId, vagasEsperadas, filaEsperada) {
  var limite = Date.now() + 40000;
  while (Date.now() < limite) {
    await pausarSeNecessario();
    await atualizarRelatorios();
    var linha = null;
    snapshot.ocupacao.forEach(function (o) { if (o.turmaId === turmaId) { linha = o; } });
    var fila = filaDaTurma(turmaId);
    var filaAtual = fila ? fila.quantidadeAguardando : 0;
    if (linha && linha.vagasDisponiveis === vagasEsperadas && filaAtual === filaEsperada && contarPendentes() === 0) { return true; }
    await esperar(1500);
  }
  return false;
}

async function executarDemoCompleta() {
  if (demo.rodando) { return; }
  demo = { rodando: true, pausado: false, parar: false };
  $('r_btnDemo').disabled = true;
  $('narradorLista').textContent = '';
  $('btnDemoPausa').textContent = 'Pausar';
  $('btnDemoParar').textContent = 'Parar';
  var total = 10;
  var passo = 0;
  var contexto = {};
  var sufixo = new Date().toTimeString().slice(0, 5).replace(':', 'h');

  var roteiro = [
    { aba: 'matricula', titulo: 'Criar a creche', detalhe: 'POST /matricula/creches com latitude e longitude (usadas no cálculo da distância).', rodar: async function () {
      var r = checar(await acaoCriarCreche({ nome: 'Creche Demo ' + sufixo, capacidade: 30, rua: 'Rua B', lat: -23.56, lon: -46.64 }), 'criar creche');
      contexto.crecheId = r.dados.crecheId;
      return 'Creche #' + contexto.crecheId + ' criada.';
    } },
    { aba: 'matricula', titulo: 'Criar a turma com 3 vagas', detalhe: 'POST /matricula/turmas grava o evento vaga.liberada na Outbox.', rodar: async function () {
      var r = checar(await acaoCriarTurma({ crecheId: contexto.crecheId, faixa: 'MATERNAL', vagas: 3 }), 'criar turma');
      contexto.turmaId = r.dados.turmaId;
      return 'Turma #' + contexto.turmaId + ' com 3 vagas.';
    } },
    { aba: 'inscricao', titulo: 'Inscrição 1: renda baixa e vulnerável', detalhe: 'Cadastro inline; score esperado 0,70 (renda 1,0 x 0,4 + vulnerabilidade 1 x 0,3).', rodar: async function () {
      var r = checar(await acaoInscreverExemplo(contexto.turmaId, 'baixa'), 'inscrição 1');
      contexto.ins1 = r.dados.inscricaoId;
      return 'Inscrição #' + r.dados.inscricaoId + ': posição ' + r.dados.posicao + ', score ' + numero(r.dados.scorePrioridade, 2) + '.';
    } },
    { aba: 'inscricao', titulo: 'Inscrição 2: renda média', detalhe: 'Score esperado 0,20 (renda 0,50 x 0,4).', rodar: async function () {
      var r = checar(await acaoInscreverExemplo(contexto.turmaId, 'media'), 'inscrição 2');
      return 'Inscrição #' + r.dados.inscricaoId + ': posição ' + r.dados.posicao + ', score ' + numero(r.dados.scorePrioridade, 2) + '.';
    } },
    { aba: 'inscricao', titulo: 'Inscrição 3: renda alta', detalhe: 'Score esperado 0,00.', rodar: async function () {
      var r = checar(await acaoInscreverExemplo(contexto.turmaId, 'alta'), 'inscrição 3');
      return 'Inscrição #' + r.dados.inscricaoId + ': posição ' + r.dados.posicao + ', score ' + numero(r.dados.scorePrioridade, 2) + '.';
    } },
    { aba: 'inscricao', titulo: 'Consultar a posição da inscrição 1', detalhe: 'GET .../posicao: lida do Redis, com fallback para o banco.', rodar: async function () {
      var r = checar(await acaoConsultarPosicao(contexto.ins1, contexto.turmaId), 'posição');
      if (r.dados.posicao !== 1) { throw new Error('posição esperada 1, recebida ' + r.dados.posicao); }
      return 'Inscrição #' + contexto.ins1 + ' está na posição ' + r.dados.posicao + '.';
    } },
    { aba: 'matricula', titulo: 'Alocar a vaga ao primeiro da fila', detalhe: 'POST /matricula/vagas/alocar: transação com lock otimista; a turma passa a ter 2 vagas.', rodar: async function () {
      var r = checar(await acaoAlocar(contexto.turmaId), 'alocar');
      contexto.matriculaId = r.dados.matriculaId;
      return 'Matrícula #' + contexto.matriculaId + ' PENDENTE para a inscrição #' + (r.matricula.inscricaoId || '?') + '.';
    } },
    { aba: 'matricula', titulo: 'Confirmar a matrícula', detalhe: 'POST /matricula/matriculas/{id}/confirmar; a inscrição vira MATRICULADA.', rodar: async function () {
      checar(await acaoConfirmar(contexto.matriculaId), 'confirmar');
      return 'Matrícula #' + contexto.matriculaId + ' CONFIRMADA.';
    } },
    { aba: 'visao', titulo: 'Aguardar os relatórios (consistência eventual)', detalhe: 'Core grava na Outbox, o Publicador envia ao RabbitMQ e o MSRelatorios atualiza as projeções.', rodar: async function () {
      var ok = await aguardarRelatorios(contexto.turmaId, 2, 2);
      if (!ok) { throw new Error('os relatórios não convergiram em 40 s (RabbitMQ ou MSRelatorios parados?)'); }
      return 'Relatórios convergiram.';
    } },
    { aba: 'visao', titulo: 'Conferir os números esperados', detalhe: 'Ocupação 2 de 3 vagas disponíveis, 2 na fila, taxa, distância e painel público.', rodar: async function () {
      var s = snapshot;
      var linha = null;
      s.ocupacao.forEach(function (o) { if (o.turmaId === contexto.turmaId) { linha = o; } });
      var fila = filaDaTurma(contexto.turmaId);
      var confere = [
        ['ocupação: ' + (linha ? linha.vagasDisponiveis : '?') + ' de 3 vagas disponíveis (esperado 2)', !!linha && linha.vagasDisponiveis === 2],
        ['fila de espera: ' + (fila ? fila.quantidadeAguardando : 0) + ' aguardando (esperado 2)', !!fila && fila.quantidadeAguardando === 2],
        ['taxa de confirmação: ' + (s.taxa && s.taxa.taxaConfirmacao !== null ? numero(s.taxa.taxaConfirmacao * 100, 0) + '%' : 'sem dados') + ' (' + (s.taxa ? s.taxa.totalConfirmadas : 0) + ' confirmada(s))', !!s.taxa && s.taxa.totalConfirmadas >= 1],
        ['distância média: ' + (s.dist && s.dist.distanciaMediaKm !== null ? numero(s.dist.distanciaMediaKm, 2) + ' km' : 'sem dados'), !!s.dist && s.dist.distanciaMediaKm > 0],
        ['painel público (sem token): ' + (s.painel ? s.painel.totalVagasDisponiveis + ' vagas disponíveis' : 'indisponível'), !!s.painel && s.painel.totalVagasDisponiveis >= 2]
      ];
      var falhas = 0;
      confere.forEach(function (c) { registrarNaLista(c[0], c[1]); if (!c[1]) { falhas++; } });
      if (falhas) { throw new Error(falhas + ' verificação(ões) divergente(s)'); }
      return 'Todos os números conferem.';
    } }
  ];

  try {
    for (passo = 0; passo < roteiro.length; passo++) {
      await pausarSeNecessario();
      var item = roteiro[passo];
      irParaAba(item.aba);
      narrar('Passo ' + (passo + 1) + '/' + total + ': ' + item.titulo, item.detalhe, passo / total);
      await esperarRitmo();
      try {
        var resultado = await item.rodar();
        if (passo < total - 1) { registrarNaLista(item.titulo + ': ' + resultado, true); }
        else { registrarNaLista('Resumo: ' + resultado, true); }
      } catch (erro) {
        if (demo.parar) { throw erro; }
        registrarNaLista(item.titulo + ': ' + erro.message, false);
        narrar('Demo interrompida no passo ' + (passo + 1), erro.message, passo / total);
        return;
      }
    }
    narrar('Demo concluída com sucesso', 'Todos os passos e números esperados conferem. Veja a linha do tempo na aba Eventos e Outbox.', 1);
    avisar('Demo completa concluída.', 'ok');
  } catch (erro) {
    narrar('Demo interrompida', 'Interrompida pelo usuário.', passo / total);
  } finally {
    demo.rodando = false;
    $('r_btnDemo').disabled = false;
    $('btnDemoPausa').textContent = 'Pausar';
    $('btnDemoParar').textContent = 'Fechar';
  }
}

function alternarPausa() {
  if (!demo.rodando) { return; }
  demo.pausado = !demo.pausado;
  $('btnDemoPausa').textContent = demo.pausado ? 'Continuar' : 'Pausar';
}

function pararOuFecharDemo() {
  if (demo.rodando) { demo.parar = true; demo.pausado = false; return; }
  $('narrador').classList.remove('visivel');
}

function iniciarRoteiro() {
  renderizarListaDoRoteiro('r_larissa', PASSOS_LARISSA);
  renderizarListaDoRoteiro('r_voce', PASSOS_VOCE);
  $('r_btnDemo').onclick = executarDemoCompleta;
  $('btnDemoPausa').onclick = alternarPausa;
  $('btnDemoParar').onclick = pararOuFecharDemo;
  $('r_btnLimpar').onclick = function () { marcados = {}; salvarMarcas(); renderizarListaDoRoteiro('r_larissa', PASSOS_LARISSA); renderizarListaDoRoteiro('r_voce', PASSOS_VOCE); };
}
