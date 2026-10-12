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
        var semToken = await chamadaSemSessao('GET', caminho + municipioId, null, 'Sem token: relatório de ocupação');
        var adulterado = await chamadaComTokenAdulterado('GET', caminho + municipioId, null, 'Token adulterado: relatório de ocupação');
        var outro = await api('GET', caminho + (municipioId + 1), { rotulo: 'Relatório de outro município (esperado 403)', sem401: true });
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

    { aba: 'matricula', titulo: 'Uma ação de negócio grava o evento na Outbox', detalhe:
      'O que foi feito: Outbox transacional. Quando o core muda algo (aqui, criar uma turma), ele grava o evento vaga.liberada numa tabela outbox na MESMA transação da regra de negócio. Ou grava os dois, ou nenhum: nenhum evento se perde e nenhum evento fantasma é criado.\n' +
      'Nada é enviado ao RabbitMQ neste momento; isso é trabalho do publicador, no passo seguinte.\n' +
      'Onde: OutboxEventWriter (exige transação aberta) e a tabela outbox.',
      rodar: async function () {
        var r = checar(await acaoCriarCrecheETurma({ nome: 'Creche Stack C ' + sufixoAleatorio(), capacidade: 30, rua: 'Rua B', lat: -23.56, lon: -46.64 }, 'MATERNAL', 3), 'criar creche e turma');
        contexto.turmaId = r.dados.turmaId;
        return 'turma #' + contexto.turmaId + ' criada (HTTP ' + r.status + '); evento vaga.liberada gravado na Outbox junto com a turma.';
      } },

    { aba: 'inscricao', titulo: 'Inscrições, alocação e confirmação geram mais eventos', detalhe:
      'Cada operação do núcleo grava seus eventos na Outbox: a inscrição gera fila.atualizada; a alocação gera vaga.liberada, fila.atualizada e matricula.atualizada; a confirmação gera matricula.atualizada.\n' +
      'Aqui duas crianças entram na fila da turma, uma vaga é alocada e a matrícula é confirmada. Esses são os três tipos de evento que alimentam as projeções do MSRelatorios.',
      rodar: async function () {
        var a = checar(await acaoInscreverExemplo(contexto.turmaId, 'baixa'), 'inscrever');
        var b = checar(await acaoInscreverExemplo(contexto.turmaId, 'media'), 'inscrever');
        var m = checar(await acaoAlocar(contexto.turmaId), 'alocar');
        contexto.matriculaId = m.dados.matriculaId;
        checar(await acaoConfirmar(contexto.matriculaId), 'confirmar');
        return 'inscrições #' + a.dados.inscricaoId + ' e #' + b.dados.inscricaoId + ', matrícula #' + contexto.matriculaId + ' alocada e confirmada (turma com 2 vagas e 1 criança na fila).';
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
      'Compare o gráfico: a linha do relatório (cheia) encontra a do banco (tracejada). A turma deve ficar com 2 vagas e 1 criança na fila.\n' +
      'Onde: os listeners em ocupacao, filaespera e matricula, no alocacreche-relatorios.',
      rodar: async function () {
        var motivo = await aguardarRelatorios(contexto.turmaId, 2, 1);
        if (motivo) { throw new Error(motivo); }
        var l = linhaDoRelatorio(contexto.turmaId);
        var c = coreDaTurma(snapshot, contexto.turmaId);
        registrarNaLista('banco (core): ' + c.vagasDisponiveis + ' de ' + c.vagasTotais + ' vagas', true);
        registrarNaLista('relatório (projeção): ' + l.vagasDisponiveis + ' vagas, sequência aplicada ' + l.ultimaSequenciaAplicada, l.vagasDisponiveis === c.vagasDisponiveis);
        return 'o relatório alcançou o banco sozinho.';
      } },

    { aba: 'relatorios', titulo: 'Os relatórios gerenciais', detalhe:
      'O que foi feito: quatro relatórios sobre as projeções, todos escopados por município e protegidos por permissão: ocupação por turma, fila de espera (quantidade e espera média), taxa de confirmação de matrículas e distância média das alocações.\n' +
      'A tela consulta os quatro agora. Esperado: pelo menos 1 matrícula nova e 1 confirmada no período, e 1 criança aguardando na turma da demo.',
      rodar: async function () {
        $('c_fTurma').value = '';
        $('c_fCreche').value = '';
        var det = await consultarDetalhe();
        var novas = det.taxa ? det.taxa.totalMatriculas - base.matriculas : 0;
        var confirmadas = det.taxa ? det.taxa.totalConfirmadas - base.confirmadas : 0;
        var fila = filaDaTurma(contexto.turmaId, det);
        var confere = [
          ['ocupação: ' + det.ocupacao.length + ' turma(s) no relatório', det.ocupacao.length > 0],
          ['fila de espera da turma da demo: ' + (fila ? fila.quantidadeAguardando : 0) + ' aguardando (esperado 1)', !!fila && fila.quantidadeAguardando === 1],
          ['taxa de confirmação: ' + novas + ' matrícula(s) nova(s) e ' + confirmadas + ' confirmada(s) (esperado pelo menos 1 e 1)', novas >= 1 && confirmadas >= 1],
          ['distância média das alocações: ' + (det.dist && det.dist.distanciaMediaKm !== null ? numero(det.dist.distanciaMediaKm, 2) + ' km' : 'sem dados'), !!det.dist && det.dist.distanciaMediaKm !== null]
        ];
        var falhas = 0;
        confere.forEach(function (c) { registrarNaLista(c[0], c[1]); if (!c[1]) { falhas++; } });
        if (falhas) { throw new Error(falhas + ' relatório(s) divergente(s)'); }
        return 'os quatro relatórios conferem com o que foi feito.';
      } },

    { aba: 'eventos', titulo: 'Falha: o envio da Outbox para', detalhe:
      'Vamos simular o RabbitMQ indisponível pausando o publicador do core. Depois, uma nova alocação acontece normalmente.\n' +
      'Esperado: a API responde 201 (o usuário não percebe nada), o evento fica PENDENTE na Outbox e o relatório continua mostrando o valor antigo, enquanto o banco já tem o novo.',
      rodar: async function () {
        await chamarControle('/publicador/pausar', 'Pausar envio da Outbox (demo)');
        contexto.pausou = true;
        var m = checar(await acaoAlocar(contexto.turmaId, 'Alocar a segunda vaga (envio pausado)'), 'alocar');
        var ok = await esperarCondicaoDemo(function () {
          var l = linhaDoRelatorio(contexto.turmaId);
          var c = coreDaTurma(snapshot, contexto.turmaId);
          return resumoOutbox && resumoOutbox.pendentes > 0 && !!l && !!c && l.vagasDisponiveis !== c.vagasDisponiveis;
        }, 15000);
        if (!ok) { throw new Error('Não consegui observar a defasagem entre o banco e o relatório.'); }
        var l = linhaDoRelatorio(contexto.turmaId);
        var c = coreDaTurma(snapshot, contexto.turmaId);
        registrarNaLista('banco (core): ' + c.vagasDisponiveis + ' vaga(s); relatório: ' + l.vagasDisponiveis + ' vaga(s); Outbox: ' + resumoOutbox.pendentes + ' pendente(s)', true);
        return 'alocação #' + m.dados.matriculaId + ' respondeu HTTP ' + m.status + ', mas o relatório ainda está defasado.';
      } },

    { aba: 'visao', titulo: 'Observar a defasagem', detalhe:
      'Veja a Visão geral: o selo "sincronizando..." e os pontos laranja no gráfico mostram a diferença entre o banco e o relatório.\n' +
      'Nada se perdeu: o evento está guardado na Outbox. A tela espera alguns segundos para a plateia enxergar.',
      rodar: async function () {
        var texto = $('narradorDetalhe').textContent;
        for (var s = 6; s > 0; s--) {
          await pausarSeNecessario();
          $('narradorDetalhe').textContent = texto + '\n\nRetomando o envio em ' + s + ' s...';
          await lerResumoOutbox();
          await atualizarRelatorios();
          renderizarStatusOutbox();
          await esperar(1000);
        }
        $('narradorDetalhe').textContent = texto;
        return resumoOutbox.pendentes + ' evento(s) guardado(s) na Outbox durante a falha.';
      } },

    { aba: 'eventos', titulo: 'Recuperação: o relatório converge sozinho', detalhe:
      'O que foi feito: ao retomar o envio, o publicador manda os eventos pendentes em ordem (pelo id da Outbox) e o relatório os aplica. Ninguém precisa corrigir nada à mão.',
      rodar: async function () {
        await chamarControle('/publicador/retomar', 'Retomar envio da Outbox (demo)');
        contexto.pausou = false;
        var inicio = Date.now();
        var motivo = await aguardarRelatorios(contexto.turmaId, 1, 0);
        if (motivo) { throw new Error(motivo); }
        var segundos = ((Date.now() - inicio) / 1000).toFixed(1).replace('.', ',');
        return 'convergiu em ' + segundos + ' s depois de retomar; nenhum evento perdido.';
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

    { aba: 'eventos', titulo: 'Consumidor resiliente: tentativas e fila de rejeição', detalhe:
      'O que foi feito: se um consumidor falha (por exemplo, o banco do relatório cai por um instante), o Spring tenta de novo até 3 vezes, esperando 0,5 s e depois 1 s. Erros de contrato (evento malformado) não são retentados e vão direto para a fila de rejeição (DLQ), que guarda a mensagem para análise em vez de perdê-la.\n' +
      'Conferimos agora que não há mensagens rejeitadas e que o broker responde.\n' +
      'Onde: RabbitMqConfig, no alocacreche-relatorios.',
      rodar: async function () {
        await lerResumoOutbox();
        renderizarStatusOutbox();
        var r = resumoOutbox;
        var limpa = r.dlqMensagens === null || r.dlqMensagens === 0;
        registrarNaLista('filas de rejeição (DLQ): ' + (r.dlqMensagens === null ? 'ainda não criadas' : r.dlqMensagens + ' mensagem(ns)'), limpa);
        registrarNaLista('RabbitMQ: ' + (r.brokerDisponivel ? 'no ar' : 'fora do ar'), r.brokerDisponivel);
        return limpa ? 'nenhuma mensagem rejeitada.' : r.dlqMensagens + ' mensagem(ns) na DLQ: vale investigar (atenção, não é falha da demo).';
      } }
  ];

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
        'Falha e recuperação: a Outbox guarda os eventos e o relatório converge sozinho',
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
  var contexto = { pausou: false };
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
  }
}

function iniciarDemoStackC() {
  $('c_btnDemoC').onclick = executarDemoStackC;
}
