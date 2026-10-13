/*
 * Demonstrações da Stack C que mostram o que costuma ficar escondido: falha e recuperação do envio
 * pela Outbox, prova de idempotência do MSRelatorios, limite de consultas do painel público e o
 * checklist de ambiente para antes da apresentação.
 */

var CAMINHO_OUTBOX = '/api/v1/notificacao/outbox';

var resumoOutbox = null;
var statusHttpOutbox = 0;
var cenarioRodando = false;
var cenarioParar = false;
var idempotenciaRodando = false;
var limiteRodando = false;

// ---------------------------------------------------------------------------
// Estado da Outbox (somente ADMIN), atualizado enquanto a aba Eventos está aberta
// ---------------------------------------------------------------------------

async function lerResumoOutbox() {
  var r = await api('GET', CAMINHO_OUTBOX, { automatica: true, rotulo: 'Outbox: resumo' });
  statusHttpOutbox = r.status;
  resumoOutbox = r.ok ? r.dados : null;
  return resumoOutbox;
}

function abaEventosAberta() { return $('aba-eventos').classList.contains('ativa'); }

function textoDoBroker(resumo) {
  if (!resumo) { return '-'; }
  return resumo.brokerDisponivel ? 'no ar' : 'fora do ar';
}

function renderizarStatusOutbox() {
  var r = resumoOutbox;
  var semAcesso = !r;
  definirValor($('res_pend'), semAcesso ? '-' : r.pendentes);
  definirValor($('res_proc'), semAcesso ? '-' : r.processados);
  definirValor($('res_erro'), semAcesso ? '-' : r.erros);
  definirValor($('res_dlq'), semAcesso || r.dlqMensagens === null ? '-' : r.dlqMensagens);
  definirValor($('res_broker'), textoDoBroker(r));

  var selo = $('res_selo');
  if (semAcesso) {
    selo.textContent = statusHttpOutbox === 403 ? 'somente ADMIN' : 'indisponível';
    selo.className = 'selo aviso';
  } else if (r.publicadorPausado) {
    selo.textContent = 'envio pausado';
    selo.className = 'selo erro';
  } else {
    selo.textContent = 'envio ativo';
    selo.className = 'selo ok';
  }

  var podeControlar = !!r && r.controleDemo;
  var pausado = !!r && r.publicadorPausado;
  $('res_btnCenario').disabled = cenarioRodando || !podeControlar;
  $('res_btnParar').disabled = !cenarioRodando;
  $('res_btnPausar').disabled = cenarioRodando || !podeControlar || pausado;
  $('res_btnRetomar').disabled = cenarioRodando || !podeControlar || !pausado;

  var motivo = $('res_motivo');
  if (semAcesso) {
    motivo.textContent = statusHttpOutbox === 403
      ? 'Entre com o perfil ADMIN para ver e controlar a Outbox.'
      : 'Não foi possível ler a Outbox (core fora do ar?).';
  } else if (!r.controleDemo) {
    motivo.textContent = 'O controle de demonstração está desligado neste core. Suba com scripts/demo-subir.sh (ele liga o controle) para usar os botões.';
  } else {
    motivo.textContent = '';
  }

  var aviso = $('res_aviso');
  var esquecido = !!r && r.publicadorPausado && !cenarioRodando;
  aviso.style.display = esquecido ? '' : 'none';
}

// ---------------------------------------------------------------------------
// Cenário guiado: pausar o envio, gerar eventos, ver a defasagem, retomar e ver a convergência
// ---------------------------------------------------------------------------

var PASSOS_CENARIO = [
  { id: 'pausar', titulo: 'Pausar o envio da Outbox (simula o RabbitMQ indisponível)' },
  { id: 'acao', titulo: 'Criar uma creche e uma turma: a API responde normalmente' },
  { id: 'acumular', titulo: 'Os eventos acumulam como PENDENTE e o relatório não muda' },
  { id: 'espera', titulo: 'Pausa para a plateia observar a defasagem' },
  { id: 'retomar', titulo: 'Retomar o envio' },
  { id: 'convergir', titulo: 'O relatório converge sozinho, sem perder nenhum evento' }
];

function renderizarPassosCenario() {
  var lista = $('res_passos');
  lista.textContent = '';
  PASSOS_CENARIO.forEach(function (p) {
    lista.appendChild(el('li', { id: 'res_p_' + p.id, class: 'guiado' }, [
      el('span', { class: 'selo', id: 'res_ps_' + p.id, texto: 'aguardando' }),
      el('span', {}, [el('b', { texto: p.titulo }), el('div', { class: 'dica', id: 'res_pd_' + p.id, style: 'margin:2px 0 0' })])
    ]));
  });
}

function marcarPasso(id, situacao, detalhe) {
  var rotulos = { aguardando: 'aguardando', andamento: 'em andamento', ok: 'concluído', falhou: 'falhou' };
  var classes = { aguardando: '', andamento: 'aviso', ok: 'ok', falhou: 'erro' };
  var selo = $('res_ps_' + id);
  selo.textContent = rotulos[situacao];
  selo.className = 'selo ' + classes[situacao];
  $('res_p_' + id).setAttribute('data-situacao', situacao);
  if (detalhe !== undefined) { $('res_pd_' + id).textContent = detalhe; }
}

function exigirContinuar() {
  if (cenarioParar) { throw new Error('Interrompido pelo usuário.'); }
}

async function chamarControle(caminho, rotulo) {
  var r = await api('POST', CAMINHO_OUTBOX + caminho, { rotulo: rotulo });
  if (!r.ok) { throw new Error(mensagemDeErro(r)); }
  await lerResumoOutbox();
  renderizarStatusOutbox();
}

function turmaAusenteNoRelatorio(turmaId) {
  return !!snapshot && ocupacaoComAusentes(snapshot).some(function (o) { return o.turmaId === turmaId && o.ausente; });
}

function turmaPresenteNoRelatorio(turmaId) {
  return !!snapshot && snapshot.ocupacao.some(function (o) { return o.turmaId === turmaId; });
}

async function esperarAte(condicao, limiteMs) {
  var fim = Date.now() + limiteMs;
  while (Date.now() < fim) {
    exigirContinuar();
    await lerResumoOutbox();
    await atualizarRelatorios();
    renderizarStatusOutbox();
    if (condicao()) { return true; }
    await esperar(1000);
  }
  return false;
}

async function executarCenarioResiliencia() {
  if (cenarioRodando) { return; }
  if (!resumoOutbox || !resumoOutbox.controleDemo) { avisar('Controle de demonstração indisponível (veja o aviso no painel).', 'erro'); return; }
  cenarioRodando = true;
  cenarioParar = false;
  renderizarPassosCenario();
  $('res_resumo').textContent = '';
  renderizarStatusOutbox();
  var passo = 'pausar';
  var pausou = false;
  var inicio = Date.now();
  try {
    marcarPasso('pausar', 'andamento');
    await chamarControle('/publicador/pausar', 'Pausar envio da Outbox');
    pausou = true;
    marcarPasso('pausar', 'ok', 'Publicador pausado: nada mais sai da Outbox para o RabbitMQ.');

    passo = 'acao';
    marcarPasso('acao', 'andamento');
    var criada = await acaoCriarCrecheETurma({ nome: 'Creche Falha ' + sufixoAleatorio(), capacidade: 30, rua: 'Rua B', lat: -23.56, lon: -46.64 }, 'MATERNAL', 3);
    if (!criada.ok) { throw new Error('Não foi possível criar a creche e a turma: ' + mensagemDeErro(criada)); }
    var turmaId = criada.dados.turmaId;
    marcarPasso('acao', 'ok', 'Turma #' + turmaId + ' criada no core (HTTP ' + criada.status + '). O usuário não percebe nada de errado.');

    passo = 'acumular';
    marcarPasso('acumular', 'andamento', 'Conferindo a Outbox e o relatório...');
    var acumulou = await esperarAte(function () { return resumoOutbox && resumoOutbox.pendentes > 0 && turmaAusenteNoRelatorio(turmaId); }, 15000);
    if (!acumulou) { throw new Error('Não consegui observar eventos pendentes com a turma ausente do relatório.'); }
    marcarPasso('acumular', 'ok', resumoOutbox.pendentes + ' evento(s) PENDENTE na Outbox. A turma #' + turmaId + ' existe no banco, mas ainda não aparece no relatório.');

    passo = 'espera';
    marcarPasso('espera', 'andamento');
    for (var s = 8; s > 0; s--) {
      exigirContinuar();
      $('res_pd_espera').textContent = 'Retomando em ' + s + ' s. Compare o banco com o relatório na Visão geral.';
      await lerResumoOutbox();
      renderizarStatusOutbox();
      await esperar(1000);
    }
    marcarPasso('espera', 'ok', 'A defasagem ficou visível.');

    passo = 'retomar';
    marcarPasso('retomar', 'andamento');
    await chamarControle('/publicador/retomar', 'Retomar envio da Outbox');
    pausou = false;
    marcarPasso('retomar', 'ok', 'Publicador ativo de novo: os eventos pendentes saem em ordem.');

    passo = 'convergir';
    marcarPasso('convergir', 'andamento', 'Aguardando o MSRelatorios aplicar os eventos...');
    var inicioConvergencia = Date.now();
    var convergiu = await esperarAte(function () {
      return resumoOutbox && resumoOutbox.pendentes === 0 && turmaPresenteNoRelatorio(turmaId) && snapshot.divergentes === 0;
    }, 40000);
    if (!convergiu) { throw new Error('O relatório não convergiu em 40 s (MSRelatorios parado?).'); }
    var segundos = ((Date.now() - inicioConvergencia) / 1000).toFixed(1).replace('.', ',');
    marcarPasso('convergir', 'ok', 'Convergiu em ' + segundos + ' s depois de retomar. Nenhum evento foi perdido.');
    $('res_resumo').textContent = 'Cenário concluído em ' + Math.round((Date.now() - inicio) / 1000) + ' s: a Outbox guardou os eventos durante a falha e o relatório se recuperou sozinho.';
    avisar('Cenário de falha e recuperação concluído.', 'ok');
  } catch (erro) {
    marcarPasso(passo, 'falhou', erro.message);
    avisar('Cenário interrompido: ' + erro.message, 'erro');
  } finally {
    if (pausou) {
      try { await chamarControle('/publicador/retomar', 'Retomar envio da Outbox (limpeza)'); } catch (e) { avisar('Atenção: o envio pode continuar pausado. Use "Retomar envio".', 'erro'); }
    }
    cenarioRodando = false;
    await lerResumoOutbox();
    renderizarStatusOutbox();
  }
}

async function alternarPausa(pausar) {
  try {
    await chamarControle(pausar ? '/publicador/pausar' : '/publicador/retomar', pausar ? 'Pausar envio da Outbox' : 'Retomar envio da Outbox');
    avisar(pausar ? 'Envio pausado: os eventos vão acumular na Outbox.' : 'Envio retomado.', 'ok');
  } catch (erro) {
    avisar('Não foi possível alterar o envio: ' + erro.message, 'erro');
  }
}

// ---------------------------------------------------------------------------
// Prova de idempotência: um evento antigo reenviado deve ser ignorado pelo MSRelatorios
// ---------------------------------------------------------------------------

function linhaDoRelatorio(turmaId) {
  var achada = null;
  if (snapshot) { snapshot.ocupacao.forEach(function (o) { if (o.turmaId === turmaId) { achada = o; } }); }
  return achada;
}

function renderizarIdempotencia() {
  var seletor = $('id_turma');
  var atual = seletor.value;
  var turmas = snapshot && snapshot.turmasCore ? snapshot.turmasCore : [];
  var opcoes = turmas.map(function (t) {
    var elegivel = t.vagasDisponiveis < t.vagasTotais;
    var creche = crecheLocal(t.crecheId);
    var nome = (creche ? creche.nome + ', ' : '') + t.faixaEtaria;
    return { valor: t.id, rotulo: 'Turma #' + t.id + ' (' + nome + '): ' + t.vagasDisponiveis + ' de ' + t.vagasTotais + ' vagas' + (elegivel ? '' : ', sem alocação (evento antigo = atual)'), desativada: !elegivel };
  });
  seletor.textContent = '';
  if (!opcoes.length) { seletor.appendChild(el('option', { value: '', texto: '(carregue os relatórios primeiro)' })); }
  opcoes.forEach(function (o) {
    var opcao = el('option', { value: String(o.valor), texto: o.rotulo });
    if (o.desativada) { opcao.disabled = true; }
    seletor.appendChild(opcao);
  });
  var elegiveis = opcoes.filter(function (o) { return !o.desativada; });
  if (atual && opcoes.some(function (o) { return String(o.valor) === atual && !o.desativada; })) { seletor.value = atual; }
  else if (elegiveis.length) { seletor.value = String(elegiveis[elegiveis.length - 1].valor); }
  var semElegivel = !elegiveis.length;
  $('id_btn').disabled = idempotenciaRodando || semElegivel || !(resumoOutbox && resumoOutbox.controleDemo);
  $('id_dica').textContent = semElegivel
    ? 'Para a prova valer, a turma precisa ter pelo menos uma vaga alocada (o evento antigo tem de diferir do atual). Aloque uma vaga na aba Stack B ou rode a demo completa.'
    : '';
}

function linhaDeResultado(rotulo, antes, depois) {
  var igual = String(antes) === String(depois);
  return [rotulo, String(antes), String(depois), selo(igual ? 'igual' : 'mudou', igual ? 'ok' : 'erro')];
}

/*
 * Reenvia o evento antigo da turma e compara o relatório antes e depois. Devolve {erro} se não deu
 * para executar, ou {antes, depois, core, eventId, ignorado}.
 */
async function executarProvaDeIdempotencia(turmaId) {
  await atualizarRelatorios();
  var antes = linhaDoRelatorio(turmaId);
  var core = coreDaTurma(snapshot, turmaId);
  if (!antes) { return { erro: 'A turma #' + turmaId + ' ainda não está no relatório; tente de novo em instantes.' }; }
  var enviado = await api('POST', CAMINHO_OUTBOX + '/reenviar-antigo?turmaId=' + turmaId, { rotulo: 'Reenviar evento antigo da turma ' + turmaId });
  if (!enviado.ok) { return { erro: 'Não foi possível reenviar: ' + mensagemDeErro(enviado) }; }
  await esperar(3000);
  await atualizarRelatorios();
  var depois = linhaDoRelatorio(turmaId);
  var ignorado = !!depois && depois.vagasDisponiveis === antes.vagasDisponiveis && depois.ultimaSequenciaAplicada === antes.ultimaSequenciaAplicada;
  return { antes: antes, depois: depois, core: core, eventId: enviado.dados.eventId, ignorado: ignorado };
}

async function provarIdempotencia() {
  if (idempotenciaRodando) { return; }
  var turmaId = Number($('id_turma').value);
  if (!turmaId) { return; }
  idempotenciaRodando = true;
  $('id_btn').disabled = true;
  var saida = $('id_res');
  saida.textContent = 'Reenviando o evento antigo e aguardando o MSRelatorios...';
  try {
    var prova = await executarProvaDeIdempotencia(turmaId);
    if (prova.erro) { saida.textContent = prova.erro; return; }
    var antes = prova.antes, depois = prova.depois, core = prova.core, ignorado = prova.ignorado;

    saida.textContent = '';
    saida.appendChild(el('p', { style: 'margin:0 0 8px' }, [
      selo(ignorado ? 'evento antigo ignorado' : 'o relatório foi alterado', ignorado ? 'ok' : 'erro'),
      document.createTextNode(' O evento #' + prova.eventId + ' (de quando a turma foi criada, com ' + (core ? core.vagasTotais : '?') + ' vagas) foi reenviado ao RabbitMQ e entregue ao MSRelatorios.')
    ]));
    var tabela = el('table', { class: 'tabela' });
    preencherTabela(tabela, ['Campo do relatório', 'Antes do reenvio', 'Depois do reenvio', 'Resultado'], [
      linhaDeResultado('Vagas disponíveis', antes.vagasDisponiveis, depois ? depois.vagasDisponiveis : '-'),
      linhaDeResultado('Sequência aplicada', antes.ultimaSequenciaAplicada, depois ? depois.ultimaSequenciaAplicada : '-')
    ]);
    saida.appendChild(tabela);
    saida.appendChild(el('p', { class: 'dica', texto: ignorado
      ? 'Por que: o relatório só aplica um evento se o número dele for maior que a última sequência já aplicada. O evento antigo tem número menor, então é descartado e as vagas não voltam ao valor antigo.'
      : 'Algo errado: um evento antigo não deveria alterar o relatório. Verifique o upsert por sequência no MSRelatorios.' }));
  } finally {
    idempotenciaRodando = false;
    renderizarIdempotencia();
  }
}

// ---------------------------------------------------------------------------
// Limite de consultas do painel público
// ---------------------------------------------------------------------------

// Dispara 150 consultas ao painel público (15 lotes de 10) e conta atendidas e recusadas
async function dispararConsultasAoPainel() {
  var contagem = {};
  var retryAfter = null;
  var url = urlPainelPublico();
  for (var lote = 0; lote < 15; lote++) {
    var respostas = await Promise.all(Array.from({ length: 10 }, function () {
      return fetch(url, { cache: 'no-store' }).then(function (r) {
        if (r.status === 429 && !retryAfter) { retryAfter = r.headers.get('Retry-After'); }
        return r.status;
      }).catch(function () { return 0; });
    }));
    respostas.forEach(function (status) { contagem[status] = (contagem[status] || 0) + 1; });
  }
  return { liberadas: contagem[200] || 0, barradas: contagem[429] || 0, retryAfter: retryAfter };
}

async function simularExcessoDeConsultas() {
  if (limiteRodando) { return; }
  limiteRodando = true;
  var botao = $('c_btnLimite');
  var saida = $('c_limiteRes');
  botao.disabled = true;
  saida.textContent = 'Disparando 150 consultas ao painel público...';
  try {
    var resultado = await dispararConsultasAoPainel();
    var liberadas = resultado.liberadas;
    var barradas = resultado.barradas;
    var retryAfter = resultado.retryAfter;
    saida.textContent = '';
    saida.appendChild(el('span', { class: 'chip-res ok', texto: liberadas + ' consultas atendidas (200)' }));
    saida.appendChild(document.createTextNode(' '));
    saida.appendChild(el('span', { class: 'chip-res ' + (barradas ? 'erro' : ''), texto: barradas + ' recusadas (429)' }));
    saida.appendChild(el('p', { class: 'dica', texto: barradas
      ? 'O gateway passou a recusar o IP deste computador (cabeçalho Retry-After: ' + (retryAfter || '?') + ' s). A página pública mostra "Aguardando liberação" e retoma sozinha; o painel desta tela volta quando a janela de 1 minuto vira.'
      : 'Nenhuma consulta foi recusada: o limite configurado é maior que 150 por minuto.' }));
  } finally {
    limiteRodando = false;
    botao.disabled = false;
  }
}

// ---------------------------------------------------------------------------
// Checklist pré-apresentação
// ---------------------------------------------------------------------------

function itemDeVerificacao(situacao, titulo, detalhe, acao) {
  var classes = { ok: 'ok', aviso: 'aviso', erro: 'erro' };
  var rotulos = { ok: 'ok', aviso: 'atenção', erro: 'problema' };
  var filhos = [selo(rotulos[situacao], classes[situacao]), el('span', {}, [el('b', { texto: titulo }), el('div', { class: 'dica', style: 'margin:2px 0 0', texto: detalhe })])];
  if (acao) { filhos.push(acao); }
  return { situacao: situacao, no: el('li', { class: 'guiado' }, filhos) };
}

function botaoDeCorrecao(texto, aoClicar) {
  var b = el('button', { class: 'mini', texto: texto });
  b.onclick = aoClicar;
  return b;
}

async function verificarAmbiente() {
  var botao = $('pre_btn');
  var lista = $('pre_lista');
  botao.disabled = true;
  $('pre_resumo').textContent = 'Verificando...';
  $('pre_resumo').className = 'selo';
  try {
    var s = await consultarRelatorios({});
    var r = s.outbox;
    var itens = [];
    var perfil = perfilDoToken();

    itens.push(perfil === 'ADMIN'
      ? itemDeVerificacao('ok', 'Sessão com perfil ADMIN', 'Todos os controles de demonstração estão disponíveis.')
      : itemDeVerificacao('aviso', 'Sessão com perfil ' + perfil, 'Entre como ADMIN para ver a Outbox e usar o cenário de falha.'));

    var statusCore = s.respostas[5].status;
    itens.push(statusCore === 200 ? itemDeVerificacao('ok', 'Core respondendo', 'Listagem de turmas devolveu 200.')
      : itemDeVerificacao(statusCore === 0 ? 'erro' : 'aviso', 'Core com resposta ' + (statusCore === 0 ? 'ausente' : statusCore), statusCore === 0 ? 'Suba os serviços com scripts/demo-subir.sh.' : 'O perfil atual pode não ter permissão de leitura.'));

    var statusRel = s.status.ocupacao;
    itens.push(statusRel === 200 ? itemDeVerificacao('ok', 'MSRelatorios respondendo', 'Relatório de ocupação devolveu 200.')
      : itemDeVerificacao(statusRel === 0 ? 'erro' : 'aviso', 'MSRelatorios com resposta ' + (statusRel === 0 ? 'ausente' : statusRel), statusRel === 0 ? 'Serviço de relatórios fora do ar.' : 'Confira o perfil e o município da consulta.'));

    var statusPainel = s.status.painel;
    itens.push(statusPainel === 200 ? itemDeVerificacao('ok', 'Painel público respondendo', 'Consulta sem token devolveu 200.')
      : itemDeVerificacao(statusPainel === 429 ? 'aviso' : 'erro', 'Painel público com resposta ' + statusPainel, statusPainel === 429 ? 'Este computador atingiu o limite de consultas; aguarde cerca de 1 minuto.' : 'Verifique o gateway e o MSRelatorios.'));

    if (!r) {
      itens.push(itemDeVerificacao('aviso', 'Outbox, RabbitMQ e job', 'Sem acesso (requer perfil ADMIN): não foi possível verificar o envio de eventos nem o job de matrículas.'));
    } else {
      itens.push(r.brokerDisponivel ? itemDeVerificacao('ok', 'RabbitMQ acessível', 'O core consegue falar com o broker.')
        : itemDeVerificacao('erro', 'RabbitMQ inacessível', 'Suba o broker: docker compose start rabbitmq'));

      var saudavel = r.pendentes === 0 && r.erros === 0 && (r.dlqMensagens === null || r.dlqMensagens === 0);
      itens.push(saudavel ? itemDeVerificacao('ok', 'Outbox em dia', r.processados + ' evento(s) enviados; nenhum pendente, com erro ou nas filas de rejeição.')
        : itemDeVerificacao('aviso', 'Outbox com itens a olhar', r.pendentes + ' pendente(s), ' + r.erros + ' com erro e ' + (r.dlqMensagens === null ? 'filas de rejeição desconhecidas' : r.dlqMensagens + ' mensagem(ns) nas filas de rejeição') + '.'));

      if (r.publicadorPausado) {
        itens.push(itemDeVerificacao('erro', 'Envio da Outbox pausado', 'Nenhum evento está saindo; os relatórios não vão atualizar.',
          botaoDeCorrecao('Retomar envio', async function () { await alternarPausa(false); verificarAmbiente(); })));
      } else if (!r.controleDemo) {
        itens.push(itemDeVerificacao('aviso', 'Controle de demonstração desligado', 'O cenário de falha e a prova de idempotência ficam indisponíveis. Suba com scripts/demo-subir.sh.'));
      } else {
        itens.push(itemDeVerificacao('ok', 'Envio da Outbox ativo', 'Controle de demonstração ligado.'));
      }

      itens.push(r.jobManutencao
        ? itemDeVerificacao('aviso', 'Job de manutenção ligado', 'Ele aloca vagas sozinho e pode quebrar a demo completa. Rode: scripts/demo-subir.sh --reiniciar-core')
        : itemDeVerificacao('ok', 'Job de manutenção desligado', 'A demo completa não será interrompida pela reconciliação.'));
    }

    itens.push(s.divergentes === 0 ? itemDeVerificacao('ok', 'Relatório em dia com o banco', 'Nenhuma turma com vagas diferentes entre o core e o relatório.')
      : itemDeVerificacao('aviso', 'Relatório ainda sincronizando', s.divergentes + ' turma(s) diferem entre o banco e o relatório; aguarde alguns segundos.'));

    lista.textContent = '';
    itens.forEach(function (i) { lista.appendChild(i.no); });
    var erros = itens.filter(function (i) { return i.situacao === 'erro'; }).length;
    var avisos = itens.filter(function (i) { return i.situacao === 'aviso'; }).length;
    var resumo = $('pre_resumo');
    if (erros) { resumo.textContent = erros + ' problema(s) a resolver'; resumo.className = 'selo erro'; }
    else if (avisos) { resumo.textContent = avisos + ' ponto(s) de atenção'; resumo.className = 'selo aviso'; }
    else { resumo.textContent = 'pronto para apresentar'; resumo.className = 'selo ok'; }
  } finally {
    botao.disabled = false;
  }
}

// ---------------------------------------------------------------------------
// Inicialização
// ---------------------------------------------------------------------------

function renderizarResiliencia() {
  renderizarIdempotencia();
  renderizarStatusOutbox();
}

function iniciarResiliencia() {
  renderizarPassosCenario();
  $('res_btnCenario').onclick = executarCenarioResiliencia;
  $('res_btnParar').onclick = function () { cenarioParar = true; $('res_btnParar').disabled = true; };
  $('res_btnPausar').onclick = function () { alternarPausa(true); };
  $('res_btnRetomar').onclick = function () { alternarPausa(false); };
  $('res_btnRetomarAviso').onclick = function () { alternarPausa(false); };
  $('id_btn').onclick = provarIdempotencia;
  $('c_btnLimite').onclick = simularExcessoDeConsultas;
  $('pre_btn').onclick = verificarAmbiente;
  lerResumoOutbox().then(renderizarResiliencia);
  setInterval(function () {
    if (abaEventosAberta() && !cenarioRodando) { lerResumoOutbox().then(renderizarStatusOutbox); }
  }, 2000);
}
