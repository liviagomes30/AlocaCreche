/*
 * Aba Roteiro: checklist de apresentação (persistido no sessionStorage) e demo completa
 * automática, que narra cada passo e pode ser pausada ou interrompida.
 */

var PASSOS_LARISSA = [
  { id: 'L1', aba: 'visao', titulo: 'Abrir a Visão geral e mostrar o usuário logado', esperado: 'Topo mostra o perfil <b>ADMIN</b> e o município; o painel mostra números dos relatórios.' },
  { id: 'L2', aba: 'matricula', titulo: 'Pré-requisito: criar creche e turma (botão "Criar creche + turma")', esperado: 'Toast de sucesso, turma na tabela e evento <b>vaga.liberada</b> na linha do tempo.' },
  { id: 'L3', aba: 'inscricao', titulo: 'Explicar os pesos do score (renda 0,4, vulnerabilidade 0,3, distância 0,2, irmão 0,1)', esperado: 'A estimativa muda ao alterar a renda e a vulnerabilidade.' },
  { id: 'L4', aba: 'inscricao', titulo: 'Cadastrar responsável e criança; inscrever por criancaId', esperado: '<b>201</b> em cada chamada; inscrição com <b>posição 1</b> e score calculado.' },
  { id: 'L5', aba: 'inscricao', titulo: 'Fila por prioridade: botão "Furar a fila" (renda alta, depois baixa + vulnerável)', esperado: 'A segunda inscrição (score <b>0,70</b>) fica na <b>posição 1</b> e a renda alta (score 0,00) cai para a <b>2</b>, mesmo tendo chegado antes.' },
  { id: 'L6', aba: 'inscricao', titulo: 'Consultar a posição na fila (Redis com fallback no banco)', esperado: '<b>200</b> com a posição da inscrição escolhida.' },
  { id: 'L7', aba: 'inscricao', titulo: 'Rodar o exemplo "Irmão matriculado"', esperado: 'Irmão B com score <b>0,10 maior</b> que o irmão A (peso do irmão matriculado).' },
  { id: 'L8', aba: 'inscricao', titulo: 'Concorrência: "Inscrições simultâneas" (8 em paralelo na mesma turma)', esperado: 'Posições <b>1 a 8 sem repetição</b>, na ordem do score (lock por turma no servidor).' },
  { id: 'L9', aba: 'inscricao', titulo: '"Mesma criança duas vezes" e "em paralelo"', esperado: 'Sequencial: <b>201 e 409</b>. Em paralelo: <b>1 x 201</b> e as demais <b>409</b> (criança com inscrição ativa não se inscreve de novo).' },
  { id: 'L10', aba: 'seguranca', titulo: 'Mostrar CPF duplicado (409) e criança/turma inexistente (404)', esperado: 'Testes "CPF duplicado" e "inexistentes" marcados como passou.' }
];

var PASSOS_LETICIA = [
  { id: 'B1', aba: 'matricula', titulo: 'Alocar a vaga ao primeiro da fila (o de maior score)', esperado: '<b>201</b> com a matrícula PENDENTE; vagas restantes da turma diminuem em 1.' },
  { id: 'B2', aba: 'matricula', titulo: 'Confirmar a matrícula e tentar confirmar de novo', esperado: '<b>200</b> na primeira vez e <b>409</b> na segunda.' },
  { id: 'B3', aba: 'matricula', titulo: 'Concorrência: "Preparar corrida" e disparar 5 alocações em paralelo', esperado: 'Exatamente <b>1 vencedora (201)</b> e as outras <b>409</b>; nenhuma matrícula repetida.' },
  { id: 'B4', aba: 'matricula', titulo: 'Desistência com alocação automática: "Cenário completo" ou botão Desistir', esperado: '<b>200</b>; a matrícula vira CANCELADA, o próximo da fila sai da fila (posição <b>404</b>) e ganha uma nova matrícula PENDENTE; as vagas voltam ao mesmo número.' },
  { id: 'B5', aba: 'matricula', titulo: 'Desistir duas vezes e de matrícula inexistente', esperado: '<b>409</b> na segunda desistência e <b>404</b> para o id inexistente.' },
  { id: 'B6', aba: 'matricula', titulo: 'Sugerir creches próximas (Praça da Sé) e provocar o erro 400', esperado: 'Lista ordenada por distância (km); turmas <b>sem vaga destacadas</b> (a família pode entrar na fila); parâmetros inválidos respondem <b>400</b> com mensagem amigável.' }
];

var PASSOS_VOCE = [
  { id: 'V1', aba: 'visao', titulo: 'Acompanhar a Visão geral com atualização automática', esperado: 'Vagas disponíveis, fila, taxa e distância mudam alguns segundos depois e ficam destacados.' },
  { id: 'V2', aba: 'relatorios', titulo: 'Consultar os relatórios e abrir o painel público em outra aba', esperado: 'Painel público responde <b>200 sem token</b>; município diferente do token responde <b>403</b>.' },
  { id: 'V3', aba: 'eventos', titulo: 'Linha do tempo de eventos e fluxo Outbox, RabbitMQ e MSRelatorios', esperado: 'Eventos passam de "aguardando" para <b>refletido nos relatórios</b>, inclusive os de desistência (<b>vaga.liberada</b> e <b>matricula.atualizada</b> CANCELADA).' },
  { id: 'V4', aba: 'eventos', titulo: 'Resiliência: parar o RabbitMQ, agir, subir de novo', esperado: 'API segue respondendo, relatórios param de mudar e depois convergem ao subir o RabbitMQ.' },
  { id: 'V5', aba: 'eventos', titulo: 'Job de expiração: vencer o prazo de uma matrícula PENDENTE por SQL', esperado: 'Em até 2 min (ou no intervalo configurado) a matrícula vira <b>EXPIRADA</b>, a vaga volta e o próximo da fila é convocado.' },
  { id: 'V6', aba: 'seguranca', titulo: 'Executar todos os testes de segurança', esperado: '401 sem token e com token adulterado, 403 de outro município, <b>429</b> com Retry-After após 5 senhas erradas.' },
  { id: 'V7', aba: 'seguranca', titulo: 'Matriz de permissões com ADMIN, ATENDENTE e GESTOR (trocar de usuário)', esperado: 'ATENDENTE inscreve, consulta e desiste; GESTOR lê relatórios e opera creches, turmas, alocação, confirmação e desistência; ADMIN faz tudo. Coluna Resultado = confere.' }
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
  var total = Number($(demo.ritmoId || 'r_ritmo').value);
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
// Id do último evento esperado antes da demo: eventos de rodadas anteriores não entram na espera
var seqEventoInicialDaDemo = 0;

// Espera os relatórios refletirem a turma da demo e devolve null se convergiram, ou o motivo da falha
async function aguardarRelatorios(turmaId, vagasEsperadas, filaEsperada) {
  var limite = Date.now() + 40000;
  var motivo = 'sem resposta dos relatórios';
  while (Date.now() < limite) {
    await pausarSeNecessario();
    await atualizarRelatorios();
    var linha = null;
    snapshot.ocupacao.forEach(function (o) { if (o.turmaId === turmaId) { linha = o; } });
    var fila = filaDaTurma(turmaId);
    var filaAtual = fila ? fila.quantidadeAguardando : 0;
    var pendentesDaDemo = eventosPendentes().filter(function (e) { return e.id > seqEventoInicialDaDemo; }).length;
    if (snapshot.status.ocupacao !== 200) {
      motivo = 'o relatório de ocupação respondeu ' + snapshot.status.ocupacao;
    } else if (!linha) {
      motivo = 'a turma #' + turmaId + ' ainda não aparece no relatório de ocupação';
    } else if (linha.vagasDisponiveis !== vagasEsperadas || filaAtual !== filaEsperada) {
      motivo = 'relatório mostra ' + linha.vagasDisponiveis + ' vaga(s) e ' + filaAtual + ' na fila; esperado ' + vagasEsperadas + ' e ' + filaEsperada;
    } else if (pendentesDaDemo > 0) {
      motivo = 'números corretos, mas ' + pendentesDaDemo + ' evento(s) desta demonstração seguem como "aguardando relatórios" (veja a aba Eventos)';
    } else {
      return null;
    }
    await esperar(1500);
  }
  return motivo;
}

// Deixa o quadro do narrador pronto e bloqueia os botões das demos automáticas enquanto uma roda
var botoesDaDemoEmExecucao = [];

function prepararNarracao(idsBotoes, idRitmo) {
  demo = { rodando: true, pausado: false, parar: false, ritmoId: idRitmo };
  esconderComandoDoNarrador();
  botoesDaDemoEmExecucao = idsBotoes;
  idsBotoes.forEach(function (id) { $(id).disabled = true; });
  definirNarradorRecolhido(false);
  $('narradorLista').textContent = '';
  $('btnDemoPausa').textContent = 'Pausar';
  $('btnDemoParar').textContent = 'Parar';
}

/*
 * Executa passos {aba, titulo, detalhe, rodar} narrando cada um no quadro do topo. Compartilhado
 * pelas demos automáticas: textos.concluido = [título, detalhe] e textos.aviso = toast final.
 */
async function executarPassosNarrados(passos, textos) {
  var total = passos.length;
  var passo = 0;
  try {
    for (passo = 0; passo < total; passo++) {
      await pausarSeNecessario();
      var item = passos[passo];
      irParaAba(item.aba);
      narrar('Passo ' + (passo + 1) + '/' + total + ': ' + item.titulo, item.detalhe, passo / total);
      // Passos que precisam do apresentador (ex.: mostrar o RabbitMQ) pausam logo que aparecem, antes da espera do ritmo
      if (item.antes) { await item.antes(); }
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
    narrar(textos.concluido[0], textos.concluido[1], 1);
    avisar(textos.aviso, 'ok');
  } catch (erro) {
    narrar('Demo interrompida', 'Interrompida pelo usuário.', passo / total);
  } finally {
    demo.rodando = false;
    botoesDaDemoEmExecucao.forEach(function (id) { $(id).disabled = false; });
    $('btnDemoPausa').textContent = 'Pausar';
    $('btnDemoParar').textContent = 'Fechar';
  }
}

async function executarDemoCompleta() {
  if (demo.rodando) { return; }
  prepararNarracao(['r_btnDemo', 'c_btnDemoC'], 'r_ritmo');
  var contexto = {};
  seqEventoInicialDaDemo = estado.seqEvento || 0;
  var sufixo = new Date().toTimeString().slice(0, 5).replace(':', 'h');
  // Valores dos relatórios antes da demo: os totais de matrículas são do município inteiro
  await atualizarRelatorios();
  var base = { matriculas: snapshot && snapshot.taxa ? snapshot.taxa.totalMatriculas : 0, confirmadas: snapshot && snapshot.taxa ? snapshot.taxa.totalConfirmadas : 0 };

  var roteiro = [
    { aba: 'matricula', titulo: 'Criar a creche', detalhe: 'POST /matricula/creches com latitude e longitude (usadas na distância e na sugestão de creches próximas).', rodar: async function () {
      contexto.lat = -23.57 + Math.random() * 0.02;
      contexto.lon = -46.65 + Math.random() * 0.02;
      var r = checar(await acaoCriarCreche({ nome: 'Creche Demo ' + sufixo, capacidade: 30, rua: 'Rua B', lat: contexto.lat, lon: contexto.lon }), 'criar creche');
      contexto.crecheId = r.dados.crecheId;
      return 'Creche #' + contexto.crecheId + ' criada.';
    } },
    { aba: 'matricula', titulo: 'Criar a turma com 3 vagas', detalhe: 'POST /matricula/turmas grava o evento vaga.liberada na Outbox.', rodar: async function () {
      var r = checar(await acaoCriarTurma({ crecheId: contexto.crecheId, faixa: 'MATERNAL', vagas: 3 }), 'criar turma');
      contexto.turmaId = r.dados.turmaId;
      return 'Turma #' + contexto.turmaId + ' com 3 vagas.';
    } },
    { aba: 'inscricao', titulo: 'Inscrição 1: renda alta (chega primeiro)', detalhe: 'Cadastro inline; score esperado 0,00. Como a fila está vazia, vai para a posição 1.', rodar: async function () {
      var r = checar(await acaoInscreverExemplo(contexto.turmaId, 'alta'), 'inscrição 1');
      contexto.insAlta = r.dados.inscricaoId;
      return 'Inscrição #' + r.dados.inscricaoId + ' (renda alta): posição ' + r.dados.posicao + ', score ' + numero(r.dados.scorePrioridade, 2) + '.';
    } },
    { aba: 'inscricao', titulo: 'Inscrição 2: renda média (chega depois)', detalhe: 'Score esperado 0,20 (renda 0,50 x 0,4): maior que o da primeira, então passa na frente.', rodar: async function () {
      var r = checar(await acaoInscreverExemplo(contexto.turmaId, 'media'), 'inscrição 2');
      contexto.insMedia = r.dados.inscricaoId;
      if (r.dados.posicao !== 1) { throw new Error('a inscrição de maior score deveria ficar na posição 1; recebida ' + r.dados.posicao); }
      return 'Inscrição #' + r.dados.inscricaoId + ' (renda média): posição ' + r.dados.posicao + ', score ' + numero(r.dados.scorePrioridade, 2) + '. Furou a fila.';
    } },
    { aba: 'inscricao', titulo: 'Inscrição 3: renda baixa e vulnerável (a última a chegar)', detalhe: 'Score esperado 0,70 (renda 1,0 x 0,4 + vulnerabilidade 1 x 0,3): vai para a frente de todas.', rodar: async function () {
      var r = checar(await acaoInscreverExemplo(contexto.turmaId, 'baixa'), 'inscrição 3');
      contexto.insBaixa = r.dados.inscricaoId;
      if (r.dados.posicao !== 1) { throw new Error('a inscrição de maior score deveria ficar na posição 1; recebida ' + r.dados.posicao); }
      return 'Inscrição #' + r.dados.inscricaoId + ' (baixa + vulnerável): posição ' + r.dados.posicao + ', score ' + numero(r.dados.scorePrioridade, 2) + '.';
    } },
    { aba: 'inscricao', titulo: 'Conferir as posições na fila (por prioridade)', detalhe: 'GET .../posicao das três inscrições: esperado 1 (baixa + vulnerável), 2 (média) e 3 (alta), apesar de a ordem de chegada ter sido a inversa.', rodar: async function () {
      var esperado = [[contexto.insBaixa, 1, 'baixa + vulnerável'], [contexto.insMedia, 2, 'média'], [contexto.insAlta, 3, 'alta']];
      var textos = [];
      for (var i = 0; i < esperado.length; i++) {
        var r = checar(await acaoConsultarPosicao(esperado[i][0], contexto.turmaId), 'posição ' + esperado[i][2]);
        if (r.dados.posicao !== esperado[i][1]) { throw new Error('inscrição ' + esperado[i][2] + ': posição ' + r.dados.posicao + ' (esperada ' + esperado[i][1] + ')'); }
        textos.push(esperado[i][2] + ' ' + r.dados.posicao);
      }
      return 'Posições: ' + textos.join(', ') + '.';
    } },
    { aba: 'matricula', titulo: 'Sugerir creches próximas', detalhe: 'GET /matricula/creches/proximas a partir da coordenada da creche da demo: ela deve ser a primeira da lista, a 0 km, com 3 vagas.', rodar: async function () {
      var r = checar(await api('GET', '/api/v1/matricula/creches/proximas?latitude=' + contexto.lat + '&longitude=' + contexto.lon + '&faixaEtaria=MATERNAL&limite=5', { rotulo: 'Sugerir creches próximas' }), 'creches próximas');
      var lista = Array.isArray(r.dados) ? r.dados : [];
      if (!lista.length || lista[0].crecheId !== contexto.crecheId) { throw new Error('a creche da demo deveria ser a mais próxima (primeira da lista)'); }
      if (lista[0].vagasDisponiveis !== 3) { throw new Error('esperadas 3 vagas na turma, recebidas ' + lista[0].vagasDisponiveis); }
      return lista.length + ' turma(s) sugerida(s); a primeira é a creche #' + lista[0].crecheId + ', a ' + numero(lista[0].distanciaKm, 2) + ' km, com ' + lista[0].vagasDisponiveis + ' vagas.';
    } },
    { aba: 'matricula', titulo: 'Alocar a vaga ao primeiro da fila', detalhe: 'POST /matricula/vagas/alocar: convoca a maior prioridade (baixa + vulnerável); a turma passa a ter 2 vagas.', rodar: async function () {
      var r = checar(await acaoAlocar(contexto.turmaId), 'alocar');
      contexto.matriculaId = r.dados.matriculaId;
      if (r.matricula.inscricaoId !== contexto.insBaixa) { throw new Error('a matrícula deveria ser da inscrição de maior score (#' + contexto.insBaixa + ')'); }
      return 'Matrícula #' + contexto.matriculaId + ' PENDENTE para a inscrição #' + r.matricula.inscricaoId + ' (a de maior prioridade).';
    } },
    { aba: 'matricula', titulo: 'Confirmar a matrícula', detalhe: 'POST /matricula/matriculas/{id}/confirmar; a inscrição vira MATRICULADA.', rodar: async function () {
      checar(await acaoConfirmar(contexto.matriculaId), 'confirmar');
      return 'Matrícula #' + contexto.matriculaId + ' CONFIRMADA.';
    } },
    { aba: 'matricula', titulo: 'Alocar uma segunda vaga', detalhe: 'Convoca a segunda da fila (renda média): a turma passa a ter 1 vaga e 1 inscrição aguardando.', rodar: async function () {
      var r = checar(await acaoAlocar(contexto.turmaId, 'Alocar a segunda vaga'), 'alocar');
      contexto.matriculaDesistida = r.dados.matriculaId;
      if (r.matricula.inscricaoId !== contexto.insMedia) { throw new Error('a segunda matrícula deveria ser da inscrição de renda média (#' + contexto.insMedia + ')'); }
      return 'Matrícula #' + contexto.matriculaDesistida + ' PENDENTE para a inscrição #' + r.matricula.inscricaoId + '.';
    } },
    { aba: 'matricula', titulo: 'Desistir da segunda matrícula: alocação automática', detalhe: 'POST .../desistir libera a vaga e, logo após o commit, o servidor convoca sozinho o último da fila (renda alta).', rodar: async function () {
      var r = checar(await acaoDesistir(contexto.matriculaDesistida, 'Desistir da segunda matrícula (demo)'), 'desistir');
      var resumo = r.resumo;
      if (!resumo.realocada) { throw new Error('a vaga devolvida não foi dada ao próximo da fila (posição respondeu ' + resumo.statusPosicao + ')'); }
      if (resumo.vagasFinais !== 1) { throw new Error('esperada 1 vaga disponível depois da realocação, calculada ' + resumo.vagasFinais); }
      return 'Matrícula #' + contexto.matriculaDesistida + ' CANCELADA (vaga voltou a ' + resumo.vagasAposCancelar + ') e a inscrição #' + resumo.proximo.id + ' foi convocada automaticamente: turma com 1 vaga e fila vazia.';
    } },
    { aba: 'visao', titulo: 'Aguardar os relatórios (consistência eventual)', detalhe: 'Core grava na Outbox, o Publicador envia ao RabbitMQ e o MSRelatorios atualiza as projeções.', rodar: async function () {
      var motivo = await aguardarRelatorios(contexto.turmaId, 1, 0);
      if (motivo) { throw new Error('os relatórios não convergiram em 40 s: ' + motivo + ' (RabbitMQ ou MSRelatorios parados?)'); }
      return 'Relatórios convergiram.';
    } },
    { aba: 'visao', titulo: 'Conferir os números esperados', detalhe: 'Ocupação 1 de 3 vagas disponíveis, fila vazia, 3 matrículas novas no período (1 confirmada), distância e painel público.', rodar: async function () {
      var s = snapshot;
      var linha = null;
      s.ocupacao.forEach(function (o) { if (o.turmaId === contexto.turmaId) { linha = o; } });
      var fila = filaDaTurma(contexto.turmaId);
      var novasMatriculas = s.taxa ? s.taxa.totalMatriculas - base.matriculas : 0;
      var novasConfirmadas = s.taxa ? s.taxa.totalConfirmadas - base.confirmadas : 0;
      var confere = [
        ['ocupação: ' + (linha ? linha.vagasDisponiveis : '?') + ' de 3 vagas disponíveis (esperado 1)', !!linha && linha.vagasDisponiveis === 1],
        ['fila de espera: ' + (fila ? fila.quantidadeAguardando : 0) + ' aguardando (esperado 0)', !fila || fila.quantidadeAguardando === 0],
        ['matrículas novas no período: ' + novasMatriculas + ' (esperado 3: confirmada, cancelada e a pendente da alocação automática)', novasMatriculas === 3],
        ['confirmadas novas: ' + novasConfirmadas + ' (esperado 1); taxa do município: ' + (s.taxa && s.taxa.taxaConfirmacao !== null ? numero(s.taxa.taxaConfirmacao * 100, 0) + '%' : 'sem dados'), novasConfirmadas === 1],
        ['distância média: ' + (s.dist && s.dist.distanciaMediaKm !== null ? numero(s.dist.distanciaMediaKm, 2) + ' km' : 'sem dados'), !!s.dist && s.dist.distanciaMediaKm > 0],
        ['painel público (sem token): ' + (s.painel ? s.painel.totalVagasDisponiveis + ' vagas disponíveis' : 'indisponível'), !!s.painel && s.painel.totalVagasDisponiveis >= 1]
      ];
      var falhas = 0;
      confere.forEach(function (c) { registrarNaLista(c[0], c[1]); if (!c[1]) { falhas++; } });
      if (falhas) { throw new Error(falhas + ' verificação(ões) divergente(s)'); }
      return 'Todos os números conferem.';
    } }
  ];
  await executarPassosNarrados(roteiro, {
    concluido: ['Demo concluída com sucesso', 'Todos os passos e números esperados conferem. Veja a linha do tempo na aba Eventos e Outbox.'],
    aviso: 'Demo completa concluída.'
  });
}

/*
 * Mostra no quadro do narrador um comando para o apresentador rodar no terminal, com o botão "Copiar comando" e,
 * se houver aoSimular, o botão "Simular em vez disso". Expande o quadro para o comando não ficar escondido.
 */
function mostrarComandoNoNarrador(comando, aoSimular, urlParaAbrir) {
  definirNarradorRecolhido(false);
  $('narradorComandoTexto').textContent = comando;
  $('btnNarradorCopiar').onclick = function () { copiar(comando, 'Comando copiado. Cole no terminal.'); };
  var abrir = $('btnNarradorAbrir');
  abrir.style.display = urlParaAbrir ? '' : 'none';
  abrir.onclick = function () { window.open(urlParaAbrir, '_blank', 'noopener'); };
  var simular = $('btnNarradorSimular');
  simular.style.display = aoSimular ? '' : 'none';
  simular.onclick = function () { esconderComandoDoNarrador(); aoSimular(); };
  $('narradorComando').style.display = 'flex';
}

function esconderComandoDoNarrador() { $('narradorComando').style.display = 'none'; }

// Recolhe ou expande o quadro do narrador (recolhido, ele não cobre o conteúdo da tela)
function definirNarradorRecolhido(recolher) {
  $('narrador').classList.toggle('recolhido', recolher);
  $('btnDemoRecolher').textContent = recolher ? 'Expandir' : 'Recolher';
  $('btnDemoRecolher').setAttribute('aria-expanded', String(!recolher));
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
  renderizarListaDoRoteiro('r_leticia', PASSOS_LETICIA);
  renderizarListaDoRoteiro('r_voce', PASSOS_VOCE);
  $('r_btnDemo').onclick = executarDemoCompleta;
  $('btnDemoPausa').onclick = alternarPausa;
  $('btnDemoParar').onclick = pararOuFecharDemo;
  $('btnDemoRecolher').onclick = function () { definirNarradorRecolhido(!$('narrador').classList.contains('recolhido')); };
  $('r_btnLimpar').onclick = function () { marcados = {}; salvarMarcas(); renderizarListaDoRoteiro('r_larissa', PASSOS_LARISSA); renderizarListaDoRoteiro('r_leticia', PASSOS_LETICIA); renderizarListaDoRoteiro('r_voce', PASSOS_VOCE); };
}
