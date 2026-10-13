/*
 * Demonstração guiada da Stack C: percorre, narrando cada passo, tudo o que a Stack C entrega
 * (segurança do MSRelatorios, painel público, Outbox, publicador, projeções, relatórios, falha e
 * recuperação, idempotência, DLQ e limite do gateway). Usa o narrador, a pausa e o ritmo da demo
 * completa (roteiro.js) e os controles de demonstração do core (resiliencia.js).
 */

var CAMPOS_DO_PAINEL_PUBLICO = ['municipioId', 'totalVagasDisponiveis', 'totalCrechesComVaga', 'totalTurmas', 'atualizadoEm'];

// Espera uma condição consultando a Outbox e os relatórios a cada segundo (respeita pausa e parada da demo)
async function esperarCondicaoDemo(condicao, limiteMs) {
  var fim = Date.now() + limiteMs;
  while (Date.now() < fim) {
    await pausarSeNecessario();
    await lerResumoOutbox();
    await atualizarRelatorios();
    renderizarStatusOutbox();
    if (condicao()) { return true; }
    await esperar(1000);
  }
  return false;
}

/*
 * Mostra um comando no quadro do narrador e espera a tela detectar sozinha o efeito dele (condicao). Devolve true quando a
 * condição passou, false se o apresentador escolheu "Simular em vez disso" e lança erro se o tempo esgotar.
 */
async function aguardarAcaoNoTerminal(opcoes) {
  var simularEscolhido = false;
  mostrarComandoNoNarrador(opcoes.comando, opcoes.aoSimular ? function () { simularEscolhido = true; opcoes.aoSimular(); } : null);
  var texto = $('narradorDetalhe').textContent;
  var inicio = Date.now();
  try {
    while (Date.now() - inicio < opcoes.limiteMs) {
      await pausarSeNecessario();
      if (simularEscolhido) { return false; }
      await lerResumoOutbox();
      renderizarStatusOutbox();
      if (opcoes.condicao()) { return true; }
      var restante = Math.round((opcoes.limiteMs - (Date.now() - inicio)) / 1000);
      $('narradorDetalhe').textContent = texto + '\n\nAguardando você rodar o comando no terminal... (' + restante + ' s)';
      await esperar(1000);
    }
    throw new Error('Tempo esgotado esperando o comando: ' + opcoes.comando + '. Rode o comando, ou use a falha simulada, e comece de novo.');
  } finally {
    esconderComandoDoNarrador();
    $('narradorDetalhe').textContent = texto;
  }
}

var URL_RABBIT = 'http://localhost:15672';

/*
 * Pausa a demo para o apresentador mostrar o painel do RabbitMQ (usuário guest, senha guest). O quadro mostra o endereço
 * com o botão "Abrir no navegador"; a demo segue quando o apresentador clica em "Continuar".
 */
async function pausarParaMostrarRabbit(orientacao) {
  var texto = $('narradorDetalhe').textContent;
  $('narradorDetalhe').textContent = texto + '\n\n' + orientacao + ' (usuário guest, senha guest)\nQuando terminar, clique em "Continuar" no quadro.';
  mostrarComandoNoNarrador(URL_RABBIT, null, URL_RABBIT);
  demo.pausado = true;
  $('btnDemoPausa').textContent = 'Continuar';
  try {
    await pausarSeNecessario();
  } finally {
    esconderComandoDoNarrador();
    $('narradorDetalhe').textContent = texto;
  }
}

function descreverAmbiente(s) {
  var r = s.outbox;
  var problemas = [];
  if (municipioDosRelatorios() !== municipioId) { problemas.push('o município dos relatórios na tela (' + municipioDosRelatorios() + ') difere do município do token (' + municipioId + ')'); }
  if (s.respostas[5].status !== 200) { problemas.push('o core respondeu ' + s.respostas[5].status); }
  if (s.status.ocupacao !== 200) { problemas.push('o MSRelatorios respondeu ' + s.status.ocupacao); }
  if (s.status.painel !== 200) { problemas.push('o painel público respondeu ' + s.status.painel + (s.status.painel === 429 ? ' (aguarde 1 minuto)' : '')); }
  if (!r) { problemas.push('entre como ADMIN: só ele vê a Outbox e usa os controles'); }
  else {
    if (!r.controleDemo) { problemas.push('o core foi iniciado sem o controle de demonstração (use scripts/demo-subir.sh)'); }
    if (!r.brokerDisponivel) { problemas.push('o RabbitMQ está inacessível (docker compose start rabbitmq)'); }
    if (r.publicadorPausado) { problemas.push('o envio da Outbox está pausado (retome na aba Eventos e Outbox)'); }
    if (r.jobManutencao) { problemas.push('o job de matrículas está ligado e alocaria vagas no meio da demo (scripts/demo-subir.sh --reiniciar-core)'); }
  }
  return problemas;
}

function montarPassosDaStackC(contexto, base, incluirLimite) {
  var passos = [
    { aba: 'relatorios', titulo: 'Conferir o ambiente', detalhe:
      'Antes de começar, a demonstração confere se tudo o que a Stack C usa está no ar: core, MSRelatorios, painel público, RabbitMQ e Outbox.\n' +
      'Também exige perfil ADMIN (para ver e controlar a Outbox) e o job de matrículas desligado, porque ele alocaria vagas no meio do roteiro.',
      rodar: async function () {
        var s = await consultarRelatorios({});
        var problemas = descreverAmbiente(s);
        if (problemas.length) { throw new Error('Ambiente não está pronto: ' + problemas.join('; ') + '.'); }
        contexto.processadosAntes = s.outbox.processados;
        contexto.errosAntes = s.outbox.erros;
        base.matriculas = s.taxa ? s.taxa.totalMatriculas : 0;
        base.confirmadas = s.taxa ? s.taxa.totalConfirmadas : 0;
        seqEventoInicialDaDemo = estado.seqEvento || 0;
        return 'core, MSRelatorios, painel público e RabbitMQ no ar; perfil ADMIN; job desligado.';
      } },

    { aba: 'relatorios', titulo: 'Segurança: o MSRelatorios valida o token sozinho', detalhe:
      'O que foi feito: o MSRelatorios é um serviço separado e não confia no gateway. Ele mesmo valida o JWT emitido pelo core (mesmo segredo, modo stateless) e exige uma permissão por endpoint, como relatorios:ocupacao:list. Por padrão, tudo é negado.\n' +
      'Prova: sem token deve dar 401, com token adulterado 401 e, com um município diferente do token, 403 (isolamento entre prefeituras).\n' +
      'Onde: seguranca/SegurancaConfig e MunicipioDoToken, no alocacreche-relatorios.',
      rodar: async function () {
        var caminho = '/api/v1/relatorios/ocupacao?municipioId=';
        var semToken = await api('GET', caminho + municipioId, { token: null, sem401: true, esperado: [401], motivoEsperado: 'sem token o serviço recusa a chamada', rotulo: 'Sem token: relatório de ocupação' });
        var adulterado = await api('GET', caminho + municipioId, { token: tokenAdulterado(), sem401: true, esperado: [401], motivoEsperado: 'a assinatura do token foi alterada, então ele é recusado', rotulo: 'Token adulterado: relatório de ocupação' });
        var outro = await api('GET', caminho + (municipioId + 1), { sem401: true, esperado: [403], motivoEsperado: 'o município pedido é diferente do município do token', rotulo: 'Relatório de outro município' });
        var provas = [['sem token', semToken, 401], ['token adulterado', adulterado, 401], ['município de outra prefeitura', outro, 403]];
        var falhas = 0;
        provas.forEach(function (p) {
          var ok = p[1].status === p[2];
          registrarNaLista(p[0] + ': HTTP ' + p[1].status + ' (esperado ' + p[2] + ')', ok);
          if (!ok) { falhas++; }
        });
        if (falhas) { throw new Error(falhas + ' proteção(ões) não responderam como esperado'); }
        return 'o serviço se protege sozinho: 401, 401 e 403.';
      } },

    { aba: 'relatorios', titulo: 'Painel público: a única rota sem login', detalhe:
      'O que foi feito: o painel público mostra à população quantas vagas existem, sem exigir login. Só devolve totais por município: nenhum nome, CPF ou renda (LGPD). O resultado é cacheado e o cache é invalidado quando um evento é aplicado.\n' +
      'Prova: chamada sem token deve responder 200 apenas com os campos de totais.\n' +
      'Onde: PainelPublicoController (@SaIgnore) e PainelPublicoService.',
      rodar: async function () {
        var r = checar(await api('GET', urlPainelPublico(), { token: null, rotulo: 'Painel público sem token' }), 'painel público');
        var campos = Object.keys(r.dados || {});
        var extras = campos.filter(function (c) { return CAMPOS_DO_PAINEL_PUBLICO.indexOf(c) < 0; });
        registrarNaLista('campos devolvidos: ' + campos.join(', '), extras.length === 0);
        if (extras.length) { throw new Error('campos inesperados no painel público: ' + extras.join(', ')); }
        return 'HTTP 200 sem token: ' + r.dados.totalVagasDisponiveis + ' vaga(s) em ' + r.dados.totalTurmas + ' turma(s), só totais.';
      } },

    { aba: 'matricula', titulo: 'Cadastrar as creches e turmas da apresentação', detalhe:
      'São os mesmos dados que a Larissa e a Letícia cadastram na apresentação (veja a cola): 5 creches em bairros diferentes de São Paulo e 7 turmas, entre elas a turma Maternal da Creche Sol, com 3 vagas.\n' +
      'O que foi feito na Stack C: Outbox transacional. Cada turma criada grava o evento vaga.liberada numa tabela outbox na MESMA transação da regra de negócio. Ou grava os dois, ou nenhum: nenhum evento se perde e nenhum evento fantasma é criado. Nada é enviado ao RabbitMQ agora; isso é do publicador, mais adiante.\n' +
      'Onde: OutboxEventWriter (exige transação aberta) e a tabela outbox.',
      rodar: async function () {
        var base = await cadastrarCrechesETurmasDaCola();
        contexto.turmas = base.turmas;
        contexto.turmaId = base.turmas['sol-MATERNAL'];
        registrarNaLista('turma da fila: Creche Sol, Maternal, 3 vagas (turma #' + contexto.turmaId + ')', true);
        return Object.keys(base.turmas).length + ' turmas em 5 creches cadastradas; um evento vaga.liberada por turma gravado na Outbox, na mesma transação.';
      } },

    { aba: 'inscricao', titulo: 'As 8 famílias entram na fila, por ordem de chegada', detalhe:
      'Mesmas 8 famílias da cola, na mesma ordem de chegada: da menor para a maior prioridade (Fábio chega primeiro, Ana por último). O score é renda x 0,4 + vulnerabilidade x 0,3; a fila é ordenada por score, e quem chega depois com score maior passa na frente. Gisele e Diego empatam em 0,40: fica na frente quem chegou antes.\n' +
      'Cada inscrição também grava um evento fila.atualizada na Outbox, que alimenta o relatório da fila de espera.',
      rodar: async function () {
        var fila = await inscreverTodasAsFamiliasDaCola(contexto.turmaId);
        contexto.inscricoes = fila.inscricoes;
        var falhas = 0;
        for (var i = 0; i < MASSA.ordemDeChegada.length; i++) {
          var chave = MASSA.ordemDeChegada[i];
          var f = MASSA.familias[chave];
          var consulta = await acaoConsultarPosicao(fila.inscricoes[chave], contexto.turmaId);
          var posicao = consulta.ok ? consulta.dados.posicao : null;
          var ok = posicao === POSICAO_ESPERADA_NA_FILA[chave];
          registrarNaLista(f.responsavel + ' (score ' + numero(f.score, 2) + '): posição ' + posicao + ' (esperado ' + POSICAO_ESPERADA_NA_FILA[chave] + ')', ok);
          if (!ok) { falhas++; }
        }
        if (falhas) { throw new Error(falhas + ' posição(ões) diferente(s) do esperado na cola'); }
        return '8 inscrições; Ana (0,70) em 1º e Fábio (0,00), que chegou primeiro, em 8º; empate Gisele x Diego resolvido por ordem de chegada.' + (fila.cpfsSubstituidos ? ' (' + fila.cpfsSubstituidos + ' CPF(s) já existiam neste banco e foram trocados.)' : '');
      } },

    { aba: 'matricula', titulo: 'Alocar a primeira vaga e confirmar a matrícula', detalhe:
      'A alocação convoca o primeiro da fila (Ana, a de maior score), cria uma matrícula PENDENTE e grava mais eventos na Outbox: vaga.liberada, fila.atualizada e matricula.atualizada. A confirmação grava outro matricula.atualizada.\n' +
      'A turma passa de 3 para 2 vagas e a fila de 8 para 7 famílias.',
      rodar: async function () {
        var m = checar(await acaoAlocar(contexto.turmaId), 'alocar');
        if (m.matricula.inscricaoId !== contexto.inscricoes.ana) { throw new Error('a primeira vaga deveria ir para a Ana (inscrição #' + contexto.inscricoes.ana + ')'); }
        contexto.matriculaId = m.dados.matriculaId;
        checar(await acaoConfirmar(contexto.matriculaId), 'confirmar');
        return 'matrícula #' + contexto.matriculaId + ' da Ana alocada e confirmada (turma com 2 vagas e 7 famílias na fila).';
      } },

    { aba: 'eventos', titulo: 'O publicador envia os eventos ao RabbitMQ', detalhe:
      'O que foi feito: um publicador roda a cada segundo, pega os eventos PENDENTE da Outbox, envia todos de uma vez ao RabbitMQ e espera as confirmações do broker juntas (um prazo único por lote, e não um por evento). Só o evento confirmado vira PROCESSADO.\n' +
      'Detalhes de robustez: o lote é travado com SKIP LOCKED (duas instâncias não enviam o mesmo evento) e um evento recusado entra em recuo (1 s, 2 s, 4 s, até 5 s) para não encher o log.\n' +
      'Onde: OutboxPublicadorService e OutboxPublicadorScheduler, no core.',
      rodar: async function () {
        var ok = await esperarCondicaoDemo(function () { return resumoOutbox && resumoOutbox.pendentes === 0; }, 20000);
        if (!ok) { throw new Error('A Outbox ainda tem eventos pendentes depois de 20 s (RabbitMQ parado?).'); }
        var enviados = resumoOutbox.processados - contexto.processadosAntes;
        var novosErros = resumoOutbox.erros - contexto.errosAntes;
        registrarNaLista('Outbox: ' + enviados + ' evento(s) enviados desde o início, ' + resumoOutbox.pendentes + ' pendente(s), ' + novosErros + ' com erro', novosErros === 0);
        if (novosErros > 0) { throw new Error(novosErros + ' evento(s) foram para ERRO na Outbox'); }
        return enviados + ' evento(s) enviados e confirmados pelo broker.';
      } },

    { aba: 'visao', titulo: 'O MSRelatorios aplica as projeções', detalhe:
      'O que foi feito: o MSRelatorios tem banco próprio. Cada evento chega por uma fila e um consumidor grava, numa transação, a projeção de leitura (ocupação, fila de espera, matrículas) com um upsert por chave de negócio. É a consistência eventual: o relatório alcança o banco em instantes.\n' +
      'Compare o gráfico: a linha do relatório (cheia) encontra a do banco (tracejada). A turma deve ficar com 2 vagas e 7 famílias na fila.\n' +
      'Onde: os listeners em ocupacao, filaespera e matricula, no alocacreche-relatorios.',
      rodar: async function () {
        var motivo = await aguardarRelatorios(contexto.turmaId, 2, 7);
        if (motivo) { throw new Error(motivo); }
        var l = linhaDoRelatorio(contexto.turmaId);
        var c = coreDaTurma(snapshot, contexto.turmaId);
        registrarNaLista('banco (core): ' + c.vagasDisponiveis + ' de ' + c.vagasTotais + ' vagas', true);
        registrarNaLista('relatório (projeção): ' + l.vagasDisponiveis + ' vagas, sequência aplicada ' + l.ultimaSequenciaAplicada, l.vagasDisponiveis === c.vagasDisponiveis);
        return 'o relatório alcançou o banco sozinho.';
      } },

    { aba: 'relatorios', titulo: 'Os relatórios gerenciais', detalhe:
      'O que foi feito: quatro relatórios sobre as projeções, todos escopados por município e protegidos por permissão: ocupação por turma, fila de espera (quantidade e espera média), taxa de confirmação de matrículas e distância média das alocações.\n' +
      'A tela consulta os quatro agora. Esperado: pelo menos 1 matrícula nova e 1 confirmada no período, e 7 famílias aguardando na turma da demo.',
      rodar: async function () {
        $('c_fTurma').value = '';
        $('c_fCreche').value = '';
        var det = await consultarDetalhe();
        var novas = det.taxa ? det.taxa.totalMatriculas - base.matriculas : 0;
        var confirmadas = det.taxa ? det.taxa.totalConfirmadas - base.confirmadas : 0;
        var fila = filaDaTurma(contexto.turmaId, det);
        var confere = [
          ['ocupação: ' + det.ocupacao.length + ' turma(s) no relatório', det.ocupacao.length > 0],
          ['fila de espera da turma da demo: ' + (fila ? fila.quantidadeAguardando : 0) + ' aguardando (esperado 7)', !!fila && fila.quantidadeAguardando === 7],
          ['taxa de confirmação: ' + novas + ' matrícula(s) nova(s) e ' + confirmadas + ' confirmada(s) (esperado pelo menos 1 e 1)', novas >= 1 && confirmadas >= 1],
          ['distância média das alocações: ' + (det.dist && det.dist.distanciaMediaKm !== null ? numero(det.dist.distanciaMediaKm, 2) + ' km' : 'sem dados'), !!det.dist && det.dist.distanciaMediaKm !== null]
        ];
        var falhas = 0;
        confere.forEach(function (c) { registrarNaLista(c[0], c[1]); if (!c[1]) { falhas++; } });
        if (falhas) { throw new Error(falhas + ' relatório(s) divergente(s)'); }
        return 'os quatro relatórios conferem com o que foi feito.';
      } },

    { aba: 'eventos', titulo: contexto.simulada ? 'Falha: o envio da Outbox para' : 'Falha: o RabbitMQ para', detalhe: contexto.simulada ?
      'Vamos simular o RabbitMQ indisponível pausando o publicador do core. Depois, a segunda vaga é alocada normalmente (para o Bruno, o próximo da fila).\n' +
      'Esperado: a API responde 201 (o usuário não percebe nada), os eventos ficam PENDENTE na Outbox e o relatório continua mostrando o valor antigo, enquanto o banco já tem o novo. Com o envio parado, a demo faz mais duas ações (a inscrição do Marcos e a confirmação da matrícula do Bruno): ao todo 5 eventos se acumulam na Outbox.' :
      'Aqui o RabbitMQ para de verdade. Rode no terminal, na pasta do projeto, o comando que aparece no quadro (botão "Copiar comando"). A tela detecta sozinha quando o broker parar.\n' +
      'Depois, a segunda vaga é alocada (para o Bruno, o próximo da fila). Esperado: a API responde 201 (o usuário não percebe nada), os eventos ficam PENDENTE na Outbox e o relatório mostra o valor antigo, enquanto o banco já tem o novo. Com o broker parado, a demo faz mais duas ações (a inscrição do Marcos e a confirmação da matrícula do Bruno): ao todo 5 eventos se acumulam na Outbox.\n' +
      'Sem tempo para o terminal? Use "Simular em vez disso".',
      rodar: async function () {
        if (!contexto.simulada) {
          var parou = await aguardarAcaoNoTerminal({
            comando: 'docker compose stop rabbitmq',
            condicao: function () { return resumoOutbox && resumoOutbox.brokerDisponivel === false; },
            limiteMs: 120000,
            aoSimular: function () {
              contexto.simulada = true;
              // O passo de recuperação já foi montado no modo real: passa a falar da retomada do envio
              var recuperacao = passos.filter(function (p) { return p.titulo.indexOf('Recuperação') === 0; })[0];
              if (recuperacao) {
                recuperacao.titulo = 'Recuperação: o relatório converge sozinho';
                recuperacao.detalhe = 'O que foi feito: ao retomar o envio, o publicador manda os eventos pendentes em ordem (pelo id da Outbox) e o relatório os aplica. Ninguém precisa corrigir nada à mão.';
              }
            }
          });
          if (parou) {
            contexto.rabbitParado = true;
            registrarNaLista('RabbitMQ parado (o core acusa o broker fora do ar)', true);
          }
        }
        if (contexto.simulada) {
          await chamarControle('/publicador/pausar', 'Pausar envio da Outbox (demo)');
          contexto.pausou = true;
        }
        var m = checar(await acaoAlocar(contexto.turmaId, contexto.simulada ? 'Alocar a segunda vaga (envio pausado)' : 'Alocar a segunda vaga (RabbitMQ parado)'), 'alocar');
        if (m.matricula.inscricaoId !== contexto.inscricoes.bruno) { throw new Error('a segunda vaga deveria ir para o Bruno (inscrição #' + contexto.inscricoes.bruno + ')'); }
        contexto.matriculaBruno = m.dados.matriculaId;
        // Mais eventos enquanto o broker está fora: uma nova inscrição (fila.atualizada) e a confirmação da matrícula (matricula.atualizada)
        checar(await inscreverFamiliaDaCola('marcos', contexto.turmaId), 'inscrever o Marcos durante a falha');
        checar(await acaoConfirmar(contexto.matriculaBruno), 'confirmar a matrícula do Bruno durante a falha');
        registrarNaLista('durante a falha: alocação do Bruno (3 eventos), inscrição do Marcos (1) e confirmação do Bruno (1) = 5 eventos novos', true);
        var ok = await esperarCondicaoDemo(function () {
          var l = linhaDoRelatorio(contexto.turmaId);
          var c = coreDaTurma(snapshot, contexto.turmaId);
          return resumoOutbox && resumoOutbox.pendentes >= 5 && !!l && !!c && l.vagasDisponiveis !== c.vagasDisponiveis;
        }, 15000);
        if (!ok) { throw new Error('Não consegui observar a defasagem entre o banco e o relatório.'); }
        var l = linhaDoRelatorio(contexto.turmaId);
        var c = coreDaTurma(snapshot, contexto.turmaId);
        registrarNaLista('banco (core): ' + c.vagasDisponiveis + ' vaga(s); relatório: ' + l.vagasDisponiveis + ' vaga(s); Outbox: ' + resumoOutbox.pendentes + ' pendente(s)', true);
        return 'alocação #' + m.dados.matriculaId + ' respondeu HTTP ' + m.status + ' e, com as outras duas ações, a Outbox acumulou ' + resumoOutbox.pendentes + ' eventos, mas o relatório ainda está defasado.';
      } },

    { aba: 'visao', titulo: 'Observar a defasagem', detalhe:
      'Veja a Visão geral: o selo "sincronizando..." e os pontos laranja no gráfico mostram a diferença entre o banco e o relatório.\n' +
      'Nada se perdeu: o evento está guardado na Outbox. A tela espera alguns segundos para a plateia enxergar.',
      rodar: async function () {
        var texto = $('narradorDetalhe').textContent;
        for (var s = 6; s > 0; s--) {
          await pausarSeNecessario();
          $('narradorDetalhe').textContent = texto + '\n\nPróximo passo em ' + s + ' s...';
          await lerResumoOutbox();
          await atualizarRelatorios();
          renderizarStatusOutbox();
          await esperar(1000);
        }
        $('narradorDetalhe').textContent = texto;
        return resumoOutbox.pendentes + ' evento(s) guardado(s) na Outbox durante a falha.';
      } },

    { aba: 'eventos', titulo: contexto.simulada ? 'Recuperação: o relatório converge sozinho' : 'Recuperação: o RabbitMQ volta e o relatório converge', detalhe: contexto.simulada ?
      'O que foi feito: ao retomar o envio, o publicador manda os eventos pendentes em ordem (pelo id da Outbox) e o relatório os aplica. Ninguém precisa corrigir nada à mão.' :
      'Rode no terminal o comando do quadro para subir o RabbitMQ de novo (botão "Copiar comando"). A tela detecta quando o broker voltar.\n' +
      'O que acontece: o publicador manda os eventos pendentes em ordem (pelo id da Outbox) e o relatório os aplica. Ninguém precisa corrigir nada à mão. Costuma levar uns 15 segundos, porque o broker leva alguns segundos para aceitar conexões.',
      rodar: async function () {
        if (contexto.rabbitParado) {
          await aguardarAcaoNoTerminal({
            comando: 'docker compose start rabbitmq',
            condicao: function () { return resumoOutbox && resumoOutbox.brokerDisponivel === true; },
            limiteMs: 120000
          });
          contexto.rabbitParado = false;
          registrarNaLista('RabbitMQ no ar de novo', true);
        } else {
          await chamarControle('/publicador/retomar', 'Retomar envio da Outbox (demo)');
          contexto.pausou = false;
        }
        var inicio = Date.now();
        var motivo = await aguardarRelatorios(contexto.turmaId, 1, 7);
        if (motivo) { throw new Error(motivo); }
        var segundos = ((Date.now() - inicio) / 1000).toFixed(1).replace('.', ',');
        var resultadoRecuperacao = 'convergiu em ' + segundos + ' s depois de ' + (contexto.simulada ? 'retomar o envio' : 'o RabbitMQ voltar') + '; nenhum evento perdido.';
        if (!contexto.simulada && contexto.mostrarRabbit) {
          await pausarParaMostrarRabbit('Mostre as filas e os consumidores de volta (aba Queues): cada fila do relatório com 1 consumidor e 0 mensagens esperando.');
        }
        return resultadoRecuperacao;
      } },

    { aba: 'matricula', titulo: 'Desistência: a vaga volta e o próximo da fila é convocado', detalhe:
      'Mesmo cenário da Letícia: o Bruno desiste. A matrícula dele é cancelada, a vaga volta para a turma e, logo depois do commit, o servidor convoca sozinho o próximo da fila (Carla, a de maior score entre as que restam), criando uma nova matrícula PENDENTE.\n' +
      'Isso gera vários eventos de uma vez (vaga.liberada, matricula.atualizada CANCELADA e os três da alocação automática). O relatório deve terminar com 1 vaga e 6 famílias na fila.',
      rodar: async function () {
        var r = checar(await acaoDesistir(contexto.matriculaBruno, 'Desistir da matrícula do Bruno (demo)'), 'desistir');
        var resumo = r.resumo;
        if (!resumo.realocada) { throw new Error('a vaga devolvida não foi dada ao próximo da fila'); }
        if (resumo.proximo.id !== contexto.inscricoes.carla) { throw new Error('o próximo convocado deveria ser a Carla (inscrição #' + contexto.inscricoes.carla + ')'); }
        registrarNaLista('matrícula do Bruno CANCELADA; vaga voltou a ' + resumo.vagasAposCancelar + ' e a Carla (inscrição #' + resumo.proximo.id + ') foi convocada sozinha', true);
        var motivo = await aguardarRelatorios(contexto.turmaId, 1, 6);
        if (motivo) { throw new Error(motivo); }
        return 'relatório com 1 vaga e 6 famílias na fila, igual ao banco.';
      } },

    { aba: 'eventos', titulo: 'Job de manutenção: o prazo vence e a matrícula expira', detalhe:
      'O que foi feito: um job agendado do core (a cada 2 minutos) faz duas coisas por município: expira as matrículas PENDENTE com prazo vencido, devolvendo a vaga, e depois preenche com a fila toda turma que tenha vaga livre. É também a rede de segurança contra a alocação automática em memória que se perdeu.\n' +
      'Para não esperar o relógio nem ligar o job agendado, a demo coloca o prazo da matrícula da Carla (a convocada) no passado e executa uma rodada do job na hora, com a mesma lógica do agendador.\n' +
      'Esperado: a matrícula da Carla vira EXPIRADA, a vaga volta e o job convoca os próximos da fila (Gisele e Diego).\n' +
      'Onde: ManutencaoMatriculaScheduler e ManutencaoMatriculaPorMunicipio, no core.',
      rodar: async function () {
        var antes = checar(await api('GET', '/api/v1/matricula/matriculas', { automatica: true, rotulo: 'Core: matrículas (antes do job)' }), 'listar matrículas');
        var carla = antes.dados.filter(function (m) { return m.inscricaoId === contexto.inscricoes.carla && m.status === 'PENDENTE'; })[0];
        if (!carla) { throw new Error('não achei a matrícula PENDENTE da Carla'); }
        var vencido = checar(await api('POST', '/api/v1/matricula/matriculas/' + carla.id + '/vencer-prazo', { rotulo: 'Vencer o prazo da matrícula da Carla' }), 'vencer o prazo');
        registrarNaLista('prazo da matrícula #' + carla.id + ' da Carla colocado em ' + String(vencido.dados.prazoConfirmacao).slice(0, 10) + ' (vencido); ela ainda está PENDENTE', true);
        var tentativa = await api('POST', '/api/v1/matricula/matriculas/' + carla.id + '/confirmar', { sem401: true, esperado: [409], motivoEsperado: 'o prazo de confirmação já venceu', rotulo: 'Confirmar a matrícula com o prazo vencido' });
        registrarNaLista('confirmar a matrícula da Carla com o prazo vencido: HTTP ' + tentativa.status + ' (esperado 409)', tentativa.status === 409);
        if (tentativa.status !== 409) { throw new Error('confirmar depois do prazo deveria responder 409'); }
        checar(await api('POST', '/api/v1/matricula/manutencao/executar', { rotulo: 'Executar uma rodada do job de manutenção' }), 'rodada do job');
        var depois = checar(await api('GET', '/api/v1/matricula/matriculas', { automatica: true, rotulo: 'Core: matrículas (depois do job)' }), 'listar matrículas');
        var statusCarla = depois.dados.filter(function (m) { return m.id === carla.id; })[0].status;
        registrarNaLista('matrícula da Carla: ' + statusCarla + ' (esperado EXPIRADA)', statusCarla === 'EXPIRADA');
        if (statusCarla !== 'EXPIRADA') { throw new Error('a matrícula da Carla deveria ter expirado'); }
        var nomePorInscricao = {};
        Object.keys(contexto.inscricoes).forEach(function (chave) { nomePorInscricao[contexto.inscricoes[chave]] = MASSA.familias[chave].responsavel; });
        var convocadas = depois.dados.filter(function (m) { return m.turmaId === contexto.turmaId && m.status === 'PENDENTE' && m.id > carla.id; });
        registrarNaLista('o job convocou ' + convocadas.length + ' família(s) da fila: ' + convocadas.map(function (m) { return nomePorInscricao[m.inscricaoId] || '#' + m.inscricaoId; }).join(' e '), convocadas.length === 2);
        var motivo = await aguardarRelatorios(contexto.turmaId, 0, 4);
        if (motivo) { throw new Error(motivo); }
        return 'matrícula da Carla expirada, vaga devolvida e preenchida pelo job; relatório com 0 vagas e 4 famílias na fila, igual ao banco.';
      } },

    { aba: 'matricula', titulo: 'Outra creche: preencher todas as vagas de uma turma', detalhe:
      'Agora outra creche, com outros dados: a Creche Esperança (Penha), turma Maternal com 2 vagas. Duas famílias novas entram na fila, as duas vagas são alocadas e confirmadas, e a turma fica lotada.\n' +
      'Esperado: uma nova alocação nessa turma responde 409 (turma sem vaga) e o relatório mostra 0 vagas e fila vazia. Cada operação gera seus eventos, que o MSRelatórios aplica por turma, sem misturar com a Creche Sol.',
      rodar: async function () {
        var turma = contexto.turmas['esperanca-MATERNAL'];
        var karina = checar(await inscreverFamiliaDaCola('karina', turma), 'inscrever a Karina');
        var leandro = checar(await inscreverFamiliaDaCola('leandro', turma), 'inscrever o Leandro');
        var m1 = checar(await acaoAlocar(turma, 'Alocar a 1ª vaga da Creche Esperança'), 'alocar');
        if (m1.matricula.inscricaoId !== karina.dados.inscricaoId) { throw new Error('a 1ª vaga deveria ir para a Karina (score 0,70)'); }
        var m2 = checar(await acaoAlocar(turma, 'Alocar a 2ª vaga da Creche Esperança'), 'alocar');
        if (m2.matricula.inscricaoId !== leandro.dados.inscricaoId) { throw new Error('a 2ª vaga deveria ir para o Leandro (score 0,20)'); }
        checar(await acaoConfirmar(m1.dados.matriculaId), 'confirmar a matrícula da Karina');
        checar(await acaoConfirmar(m2.dados.matriculaId), 'confirmar a matrícula do Leandro');
        registrarNaLista('Karina (0,70) ficou com a 1ª vaga e o Leandro (0,20) com a 2ª; as duas matrículas foram confirmadas', true);
        var cheia = await api('POST', '/api/v1/matricula/vagas/alocar', { corpo: { turmaId: turma }, esperado: [409], motivoEsperado: 'a turma está lotada, não há vaga para alocar', rotulo: 'Alocar com a turma lotada' });
        registrarNaLista('alocar com a turma lotada: HTTP ' + cheia.status + ' (esperado 409)', cheia.status === 409);
        if (cheia.status !== 409) { throw new Error('uma turma lotada deveria recusar a alocação com 409'); }
        var motivo = await aguardarRelatorios(turma, 0, 0);
        if (motivo) { throw new Error(motivo); }
        return 'Creche Esperança lotada (2 de 2 vagas, as duas confirmadas); relatório com 0 vagas e fila vazia; alocar de novo responde 409.';
      } },

    { aba: 'matricula', titulo: 'Turma lotada: a fila cresce sem vaga e a desistência convoca', detalhe:
      'Terceira creche: a Creche Aurora (Pinheiros), turma Maternal com 1 vaga. A Irene fica com a única vaga. O Júlio se inscreve depois e entra na fila mesmo sem vaga: a fila continua aceitando famílias, o que muda é que ninguém é convocado.\n' +
      'Duas conferências: a turma lotada continua na sugestão de creches próximas (com 0 vagas), porque a família ainda pode entrar na fila; e, quando a Irene desiste, o Júlio é convocado sozinho.',
      rodar: async function () {
        var turma = contexto.turmas['aurora-MATERNAL'];
        var irene = checar(await inscreverFamiliaDaCola('irene', turma), 'inscrever a Irene');
        var m = checar(await acaoAlocar(turma, 'Alocar a única vaga da Creche Aurora'), 'alocar');
        if (m.matricula.inscricaoId !== irene.dados.inscricaoId) { throw new Error('a única vaga deveria ir para a Irene'); }
        var julio = checar(await inscreverFamiliaDaCola('julio', turma), 'inscrever o Júlio');
        registrarNaLista('Júlio entrou na fila da turma lotada: posição ' + julio.dados.posicao + ' (esperado 1)', julio.dados.posicao === 1);
        var motivo = await aguardarRelatorios(turma, 0, 1);
        if (motivo) { throw new Error(motivo); }
        registrarNaLista('relatório: Creche Aurora com 0 vagas e 1 família na fila', true);

        var sugestao = checar(await api('GET', '/api/v1/matricula/creches/proximas?latitude=-23.565&longitude=-46.69&faixaEtaria=MATERNAL&limite=20', { rotulo: 'Sugerir creches próximas (Pinheiros)' }), 'sugerir creches');
        var lotada = sugestao.dados.filter(function (x) { return x.turmaId === turma; })[0];
        var naLista = !!lotada && lotada.vagasDisponiveis === 0;
        registrarNaLista('sugestão a partir de Pinheiros: a turma lotada continua na lista' + (lotada ? ', a ' + numero(lotada.distanciaKm, 2) + ' km, com ' + lotada.vagasDisponiveis + ' vaga(s)' : ' (não apareceu)'), naLista);
        if (!naLista) { throw new Error('a turma lotada deveria aparecer na sugestão com 0 vagas'); }

        var d = checar(await acaoDesistir(m.dados.matriculaId, 'Irene desiste da Creche Aurora'), 'desistir');
        if (!d.resumo.realocada || d.resumo.proximo.id !== julio.dados.inscricaoId) { throw new Error('a vaga devolvida deveria ir sozinha para o Júlio'); }
        registrarNaLista('a Irene desistiu e o Júlio foi convocado sozinho', true);
        var motivoFinal = await aguardarRelatorios(turma, 0, 0);
        if (motivoFinal) { throw new Error(motivoFinal); }
        return 'Creche Aurora: o Júlio entrou na fila sem vaga (0 vagas e 1 na fila); com a desistência da Irene ele foi convocado (0 vagas e fila vazia).';
      } },

    { aba: 'relatorios', titulo: 'O painel público confere com o banco', detalhe:
      'Com várias creches lotadas, a prova final: os totais do painel público (que qualquer cidadão vê, sem login) precisam ser os mesmos que o banco do core tem agora.\n' +
      'A tela soma as vagas de todas as turmas direto do core e compara com o painel: vagas disponíveis, creches com pelo menos uma turma com vaga e total de turmas. O painel tem cache, que é invalidado quando um evento é aplicado, então os números chegam iguais em poucos segundos.',
      rodar: async function () {
        var inicio = Date.now();
        var core = null;
        var painel = null;
        var igual = false;
        while (Date.now() - inicio < 30000 && !igual) {
          await pausarSeNecessario();
          await atualizarRelatorios();
          var resposta = await api('GET', urlPainelPublico(), { token: null, automatica: true, rotulo: 'Painel público (conferência)' });
          painel = resposta.ok ? resposta.dados : null;
          var comVaga = {};
          core = { vagas: 0, turmas: snapshot.turmasCore.length };
          snapshot.turmasCore.forEach(function (t) { core.vagas += t.vagasDisponiveis; if (t.vagasDisponiveis > 0) { comVaga[t.crecheId] = true; } });
          core.creches = Object.keys(comVaga).length;
          igual = !!painel && painel.totalVagasDisponiveis === core.vagas && painel.totalCrechesComVaga === core.creches && painel.totalTurmas === core.turmas;
          if (!igual) { await esperar(1000); }
        }
        registrarNaLista('banco (core): ' + core.vagas + ' vaga(s) livres, ' + core.creches + ' creche(s) com vaga, ' + core.turmas + ' turma(s)', true);
        registrarNaLista('painel público: ' + (painel ? painel.totalVagasDisponiveis + ' vaga(s) livres, ' + painel.totalCrechesComVaga + ' creche(s) com vaga, ' + painel.totalTurmas + ' turma(s)' : 'indisponível'), igual);
        if (!igual) { throw new Error('o painel público não bateu com o banco em 30 s'); }
        return 'o painel público mostra os mesmos totais do banco: ' + core.vagas + ' vaga(s), ' + core.creches + ' creche(s) com vaga e ' + core.turmas + ' turma(s).';
      } },

    { aba: 'eventos', titulo: 'Idempotência: o evento antigo é ignorado', detalhe:
      'O que foi feito: o RabbitMQ entrega cada mensagem pelo menos uma vez, então um evento pode chegar repetido ou atrasado. Cada evento leva o id da Outbox como sequência, e o consumidor só aplica um evento se a sequência dele for maior que a última já aplicada para aquela turma.\n' +
      'Prova: reenviamos o evento mais antigo da turma (de quando ela tinha todas as vagas livres). O relatório não pode voltar para esse valor antigo.\n' +
      'Onde: o upsert condicional no OcupacaoTurmaRepository.',
      rodar: async function () {
        var prova = await executarProvaDeIdempotencia(contexto.turmaId);
        if (prova.erro) { throw new Error(prova.erro); }
        registrarNaLista('vagas no relatório: ' + prova.antes.vagasDisponiveis + ' antes e ' + (prova.depois ? prova.depois.vagasDisponiveis : '-') + ' depois', prova.ignorado);
        registrarNaLista('sequência aplicada: ' + prova.antes.ultimaSequenciaAplicada + ' antes e ' + (prova.depois ? prova.depois.ultimaSequenciaAplicada : '-') + ' depois', prova.ignorado);
        if (!prova.ignorado) { throw new Error('o relatório foi alterado por um evento antigo'); }
        return 'o evento #' + prova.eventId + ' foi reenviado e descartado; o relatório não regrediu.';
      } },

    { aba: 'eventos', titulo: 'Fila de rejeição: uma mensagem inválida cai na DLQ', detalhe:
      'O que foi feito: se um consumidor falha (por exemplo, o banco do relatório cai por um instante), o Spring tenta de novo até 3 vezes, esperando 0,5 s e depois 1 s. Erros de contrato (evento malformado) não são retentados e vão direto para a fila de rejeição (DLQ), que guarda a mensagem para análise em vez de perdê-la.\n' +
      'Prova: a demo publica no RabbitMQ um vaga.liberada sem turmaId. O MSRelatórios o rejeita ("turmaId ausente") e a mensagem aparece em relatorios.ocupacao.dlq, e o card "na DLQ" da tela passa de 0 para 1. Depois a demo limpa essa mensagem de teste.\n' +
      'Onde: RabbitMqConfig, no alocacreche-relatorios.',
      rodar: async function () {
        await lerResumoOutbox();
        var base = resumoOutbox.dlqMensagens === null ? 0 : resumoOutbox.dlqMensagens;
        var publicada = checar(await api('POST', CAMINHO_OUTBOX + '/dlq/provocar', { rotulo: 'Publicar uma mensagem inválida (demonstração)' }), 'publicar a mensagem inválida');
        registrarNaLista('mensagem inválida publicada em vaga.liberada (sem turmaId); a fila de rejeição esperada é ' + publicada.dados.filaDeRejeicao, true);
        var caiu = await esperarCondicaoDemo(function () { return resumoOutbox && resumoOutbox.dlqMensagens === base + 1; }, 25000);
        if (!caiu) { throw new Error('a mensagem inválida não apareceu na fila de rejeição em 25 s'); }
        registrarNaLista('filas de rejeição (DLQ): ' + resumoOutbox.dlqMensagens + ' mensagem(ns) (eram ' + base + ')', true);
        if (contexto.mostrarRabbit) {
          await pausarParaMostrarRabbit('Mostre a fila relatorios.ocupacao.dlq com 1 mensagem (aba Queues).');
        }
        if (base === 0) {
          checar(await api('POST', CAMINHO_OUTBOX + '/dlq/limpar', { rotulo: 'Limpar a mensagem de teste da DLQ' }), 'limpar a DLQ');
          var limpa = await esperarCondicaoDemo(function () { return resumoOutbox && resumoOutbox.dlqMensagens === 0; }, 15000);
          registrarNaLista('DLQ limpa: voltou a ' + (limpa ? '0' : resumoOutbox.dlqMensagens), limpa);
        } else {
          registrarNaLista('a DLQ já tinha ' + base + ' mensagem(ns) antes da demo e não foi limpa', true);
        }
        registrarNaLista('RabbitMQ: ' + (resumoOutbox.brokerDisponivel ? 'no ar' : 'fora do ar'), resumoOutbox.brokerDisponivel);
        return 'a mensagem inválida foi rejeitada para a DLQ (0 → 1) e a mensagem de teste foi limpa.';
      } }
  ];

  if (contexto.mostrarRabbit) {
    var passoMostrarRabbit = { aba: 'eventos', titulo: 'Mostrar o RabbitMQ por dentro', detalhe:
      'Antes de provocar a falha, abra o painel do RabbitMQ (botão "Abrir no navegador" do quadro). Na aba Queues: as três filas do relatório (relatorios.ocupacao, relatorios.filaespera e relatorios.matricula), cada uma com 1 consumidor e 0 mensagens esperando, e as três filas de rejeição (.dlq) vazias. Na aba Exchanges: alocacreche.dominio, que roteia cada tipo de evento para a fila certa.\n' +
      'É por esse caminho que os eventos da Outbox chegam ao relatório.',
      rodar: async function () {
        await pausarParaMostrarRabbit('Mostre as filas (aba Queues) e o exchange alocacreche.dominio (aba Exchanges).');
        await lerResumoOutbox();
        renderizarStatusOutbox();
        registrarNaLista('RabbitMQ: ' + (resumoOutbox.brokerDisponivel ? 'no ar' : 'fora do ar') + '; mensagens nas filas de rejeição: ' + (resumoOutbox.dlqMensagens === null ? 'filas ainda não criadas' : resumoOutbox.dlqMensagens), resumoOutbox.brokerDisponivel);
        return 'o caminho dos eventos até o relatório, visto por dentro do RabbitMQ.';
      } };
    var indiceFalha = passos.findIndex(function (p) { return p.titulo.indexOf('Falha:') === 0; });
    passos.splice(indiceFalha, 0, passoMostrarRabbit);
  }

  if (incluirLimite) {
    passos.push({ aba: 'relatorios', titulo: 'Gateway: limite de consultas no painel público', detalhe:
      'O que foi feito: como o painel público é a única rota sem login, o gateway limita as consultas por IP (120 por minuto). Acima disso responde 429 com o cabeçalho Retry-After, e a página pública mostra "Aguardando liberação" e retoma sozinha.\n' +
      'Agora a tela dispara 150 consultas seguidas. Atenção: o painel público deste computador fica bloqueado por cerca de 1 minuto.\n' +
      'Onde: LimitadorPainelPublicoFilter, no alocacreche-gateway.',
      rodar: async function () {
        var r = await dispararConsultasAoPainel();
        registrarNaLista(r.liberadas + ' consulta(s) atendida(s) (200) e ' + r.barradas + ' recusada(s) (429); Retry-After: ' + (r.retryAfter || '-') + ' s', r.barradas > 0);
        if (!r.barradas) { throw new Error('Nenhuma consulta foi recusada: o limite configurado é maior que 150 por minuto.'); }
        return 'o gateway recusou o excesso e informou quando tentar de novo.';
      } });
  }

  passos.push({ aba: 'relatorios', titulo: 'Resumo da Stack C', detalhe:
    'Fechamento: o que a Stack C entrega e que acabou de ser demonstrado, do evento gravado no core até o painel público.',
    rodar: async function () {
      [
        'Outbox transacional: o evento nasce na mesma transação da regra de negócio (core/outbox)',
        'Publicador em lote: confirmação do broker, SKIP LOCKED e recuo por evento',
        'MSRelatorios: banco próprio e projeções por upsert com sequência (consistência eventual)',
        'Relatórios de ocupação, fila de espera, confirmação de matrícula e distância média',
        'Painel público sem login, só com totais (LGPD), com cache invalidado por evento',
        'Segurança própria no MSRelatorios: JWT, permissões e isolamento por município',
        'Falha e recuperação: com o RabbitMQ fora, 5 eventos se acumulam na Outbox e o relatório converge sozinho ao voltar',
        'RabbitMQ por dentro: filas, consumidores, exchange e a DLQ recebendo uma mensagem inválida',
        'Job de manutenção: matrícula com prazo vencido expira, a vaga volta e a fila é preenchida (ManutencaoMatriculaScheduler)',
        'Outras creches: turma lotada recusa alocação (409), a fila cresce sem vaga, a desistência convoca e o painel público confere com o banco',
        'Idempotência: evento antigo ou duplicado não regride o relatório',
        'Consumidor com tentativas e fila de rejeição; gateway com limite por IP no painel público'
      ].forEach(function (linha) { registrarNaLista(linha, true); });
      return 'todos os componentes da Stack C foram exercitados com evidência.';
    } });
  return passos;
}

async function executarDemoStackC() {
  if (demo.rodando) { return; }
  prepararNarracao(['r_btnDemo', 'c_btnDemoC'], 'c_ritmo');
  var contexto = { pausou: false, rabbitParado: false, simulada: $('c_modoFalha').value !== 'real', mostrarRabbit: $('c_mostrarRabbit').checked };
  var base = { matriculas: 0, confirmadas: 0 };
  var passos = montarPassosDaStackC(contexto, base, $('c_demoLimite').checked);
  try {
    await executarPassosNarrados(passos, {
      concluido: ['Demonstração da Stack C concluída', 'Do evento gravado no core até o relatório e o painel público, cada componente foi exercitado e conferido. A lista abaixo traz a evidência de cada passo.'],
      aviso: 'Demonstração da Stack C concluída.'
    });
  } finally {
    if (contexto.pausou) {
      try { await chamarControle('/publicador/retomar', 'Retomar envio da Outbox (limpeza da demo)'); }
      catch (erro) { avisar('Atenção: o envio da Outbox pode continuar pausado. Use "Retomar envio" na aba Eventos e Outbox.', 'erro'); }
    }
    if (contexto.rabbitParado) {
      // A demo não consegue subir o RabbitMQ sozinha: deixa o comando à vista para o apresentador
      narrar('O RabbitMQ continua parado', 'A demonstração terminou com o RabbitMQ parado. Rode no terminal o comando abaixo para subir de novo: enquanto ele estiver parado, nenhum evento chega aos relatórios.');
      mostrarComandoNoNarrador('docker compose start rabbitmq', null);
      avisar('Atenção: o RabbitMQ continua parado. Rode: docker compose start rabbitmq', 'erro');
    }
  }
}

function iniciarDemoStackC() {
  $('c_btnDemoC').onclick = executarDemoStackC;
}
