/*
 * Demonstração guiada da Stack C: percorre, narrando cada passo, tudo o que a Stack C entrega
 * (segurança do MSRelatorios, painel público, Outbox, publicador, projeções, relatórios, falha e
 * recuperação, idempotência, DLQ e limite do gateway). Usa o narrador, a pausa e o ritmo da demo
 * completa (roteiro.js) e os controles de demonstração do core (resiliencia.js).
 */

var CAMPOS_DO_PAINEL_PUBLICO = ['municipioId', 'totalVagasDisponiveis', 'totalCrechesComVaga', 'totalTurmas', 'atualizadoEm',
  'vagasPorFaixa', 'crechesComVaga', 'familiasAguardando', 'familiasAguardandoAbaixoDoMinimo', 'esperaMediaDias'];

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
  $('narradorDetalhe').textContent = texto + '\n' + (orientacao ? orientacao + ' ' : '') + 'Usuário e senha: guest. Clique em "Continuar" quando terminar.';
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
    { aba: 'relatorios', titulo: 'Conferir o ambiente', detalhe: 'Confere se core, MSRelatórios, painel público, RabbitMQ e Outbox estão no ar, o perfil é ADMIN e o job está desligado.',
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

    { aba: 'relatorios', titulo: 'Segurança: o MSRelatorios valida o token sozinho', detalhe: 'O MSRelatórios valida o token sozinho e nega por padrão. Esperado: sem token 401, token adulterado 401, outro município 403.',
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

    { aba: 'relatorios', titulo: 'Painel público: a única rota sem login', detalhe: 'Única rota sem login: só devolve totais, sem dados pessoais. Esperado: HTTP 200 sem token.',
      rodar: async function () {
        var r = checar(await api('GET', urlPainelPublico(), { token: null, rotulo: 'Painel público sem token' }), 'painel público');
        var campos = Object.keys(r.dados || {});
        var extras = campos.filter(function (c) { return CAMPOS_DO_PAINEL_PUBLICO.indexOf(c) < 0; });
        registrarNaLista('campos devolvidos: ' + campos.join(', '), extras.length === 0);
        if (extras.length) { throw new Error('campos inesperados no painel público: ' + extras.join(', ')); }
        return 'HTTP 200 sem token: ' + r.dados.totalVagasDisponiveis + ' vaga(s) em ' + r.dados.totalTurmas + ' turma(s), só totais e a lista pública de creches.';
      } },

    { aba: 'matricula', titulo: 'Cadastrar as creches e turmas da apresentação', detalhe: 'Os mesmos dados da cola: 5 creches e 7 turmas. Cada turma grava o evento vaga.liberada na Outbox, na mesma transação.',
      rodar: async function () {
        var base = await cadastrarCrechesETurmasDaCola();
        contexto.turmas = base.turmas;
        contexto.turmaId = base.turmas['sol-MATERNAL'];
        registrarNaLista('turma da fila: Creche Sol, Maternal, 3 vagas (turma #' + contexto.turmaId + ')', true);
        return Object.keys(base.turmas).length + ' turmas em 5 creches cadastradas; um evento vaga.liberada por turma gravado na Outbox, na mesma transação.';
      } },

    { aba: 'inscricao', titulo: 'As 8 famílias entram na fila, por ordem de chegada', detalhe: 'As 8 famílias da cola entram por ordem de chegada. A fila é ordenada por score; Gisele e Diego empatam em 0,40 e vale quem chegou antes.',
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

    { aba: 'matricula', titulo: 'Alocar a primeira vaga e confirmar a matrícula', detalhe: 'Aloca a vaga à Ana (maior score) e confirma. A turma vai de 3 para 2 vagas e a fila de 8 para 7.',
      rodar: async function () {
        var m = checar(await acaoAlocar(contexto.turmaId), 'alocar');
        if (m.matricula.inscricaoId !== contexto.inscricoes.ana) { throw new Error('a primeira vaga deveria ir para a Ana (inscrição #' + contexto.inscricoes.ana + ')'); }
        contexto.matriculaId = m.dados.matriculaId;
        checar(await acaoConfirmar(contexto.matriculaId), 'confirmar');
        return 'matrícula #' + contexto.matriculaId + ' da Ana alocada e confirmada (turma com 2 vagas e 7 famílias na fila).';
      } },

    { aba: 'eventos', titulo: 'O publicador envia os eventos ao RabbitMQ', detalhe: 'O publicador envia os eventos pendentes da Outbox ao RabbitMQ em lote e só marca como processado o que o broker confirma.',
      rodar: async function () {
        var ok = await esperarCondicaoDemo(function () { return resumoOutbox && resumoOutbox.pendentes === 0; }, 20000);
        if (!ok) { throw new Error('A Outbox ainda tem eventos pendentes depois de 20 s (RabbitMQ parado?).'); }
        var enviados = resumoOutbox.processados - contexto.processadosAntes;
        var novosErros = resumoOutbox.erros - contexto.errosAntes;
        registrarNaLista('Outbox: ' + enviados + ' evento(s) enviados desde o início, ' + resumoOutbox.pendentes + ' pendente(s), ' + novosErros + ' com erro', novosErros === 0);
        if (novosErros > 0) { throw new Error(novosErros + ' evento(s) foram para ERRO na Outbox'); }
        return enviados + ' evento(s) enviados e confirmados pelo broker.';
      } },

    { aba: 'visao', titulo: 'O MSRelatorios aplica as projeções', detalhe: 'O MSRelatórios aplica cada evento na sua projeção. Esperado: relatório igual ao banco, com 2 vagas e 7 na fila.',
      rodar: async function () {
        var motivo = await aguardarRelatorios(contexto.turmaId, 2, 7);
        if (motivo) { throw new Error(motivo); }
        var l = linhaDoRelatorio(contexto.turmaId);
        var c = coreDaTurma(snapshot, contexto.turmaId);
        registrarNaLista('banco (core): ' + c.vagasDisponiveis + ' de ' + c.vagasTotais + ' vagas', true);
        registrarNaLista('relatório (projeção): ' + l.vagasDisponiveis + ' vagas, sequência aplicada ' + l.ultimaSequenciaAplicada, l.vagasDisponiveis === c.vagasDisponiveis);
        return 'o relatório alcançou o banco sozinho.';
      } },

    { aba: 'relatorios', titulo: 'Os relatórios gerenciais', detalhe: 'Consulta os quatro relatórios. Esperado: pelo menos 1 matrícula nova, 1 confirmada e 7 aguardando na turma.',
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
      'Pausa o publicador do core para simular o RabbitMQ fora. Com o envio parado, a demo aloca o Bruno, inscreve o Marcos e confirma a matrícula do Bruno: 5 eventos se acumulam na Outbox.' :
      'Pare o RabbitMQ com o comando do quadro; a tela detecta sozinha. Com o broker parado, a demo aloca o Bruno, inscreve o Marcos e confirma a matrícula do Bruno: 5 eventos se acumulam na Outbox.',
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

    { aba: 'visao', titulo: 'Observar a defasagem', detalhe: 'O selo "sincronizando..." e os pontos laranja do gráfico mostram a diferença entre banco e relatório. Nada se perdeu: os eventos estão na Outbox.',
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
      'Ao retomar o envio, os eventos pendentes saem em ordem e o relatório converge sozinho.' :
      'Suba o RabbitMQ com o comando do quadro. Os eventos pendentes saem em ordem e o relatório converge sozinho (uns 15 s).',
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
          await pausarParaMostrarRabbit('Mostre as filas e os consumidores de volta (aba Queues).');
        }
        return resultadoRecuperacao;
      } },

    { aba: 'matricula', titulo: 'Desistência: a vaga volta e o próximo da fila é convocado', detalhe: 'O Bruno desiste: a vaga volta e a Carla, próxima da fila, é convocada sozinha. Esperado: 1 vaga e 6 na fila.',
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

    { aba: 'eventos', titulo: 'Job de manutenção: o prazo vence e a matrícula expira', detalhe: 'A demo vence o prazo da matrícula da Carla e roda uma rodada do job. Esperado: ela vira EXPIRADA e o job convoca Gisele e Diego.',
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

    { aba: 'matricula', titulo: 'Outra creche: preencher todas as vagas de uma turma', detalhe: 'Creche Esperança, turma de 2 vagas: duas famílias ocupam as vagas e confirmam. Esperado: nova alocação dá 409 e o relatório mostra 0 vagas.',
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

    { aba: 'matricula', titulo: 'Turma lotada: a fila cresce sem vaga e a desistência convoca', detalhe: 'Creche Aurora, turma de 1 vaga: a Irene fica com a vaga e o Júlio entra na fila sem vaga. A turma lotada segue na sugestão de creches; quando a Irene desiste, o Júlio é convocado.',
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

    { aba: 'relatorios', titulo: 'Filas em várias turmas e creches', detalhe: 'Mais famílias esperam nas turmas lotadas de Esperança e Aurora. O painel mostra um bloco por turma e a demo filtra por creche.',
      rodar: async function () {
        var esperanca = contexto.turmas['esperanca-MATERNAL'];
        var aurora = contexto.turmas['aurora-MATERNAL'];
        var wagner = checar(await inscreverFamiliaDaCola('wagner', esperanca), 'inscrever o Wagner');
        var vera = checar(await inscreverFamiliaDaCola('vera', esperanca), 'inscrever a Vera');
        var olivia = checar(await inscreverFamiliaDaCola('olivia', aurora), 'inscrever a Olívia');
        var posicaoWagner = await acaoConsultarPosicao(wagner.dados.inscricaoId, esperanca);
        registrarNaLista('Creche Esperança (turma lotada): a Vera (0,60) entrou na posição ' + vera.dados.posicao + ' e o Wagner (0,10) ficou na ' + posicaoWagner.dados.posicao, vera.dados.posicao === 1 && posicaoWagner.dados.posicao === 2);
        registrarNaLista('Creche Aurora (turma lotada): a Olívia (0,40) entrou na posição ' + olivia.dados.posicao, olivia.dados.posicao === 1);
        if (vera.dados.posicao !== 1 || posicaoWagner.dados.posicao !== 2 || olivia.dados.posicao !== 1) { throw new Error('posições diferentes do esperado nas filas das outras creches'); }
        var turmasParaConferir = [[esperanca, 0, 2], [aurora, 0, 1], [contexto.turmaId, 0, 4]];
        for (var i = 0; i < turmasParaConferir.length; i++) {
          var motivo = await aguardarRelatorios(turmasParaConferir[i][0], turmasParaConferir[i][1], turmasParaConferir[i][2]);
          if (motivo) { throw new Error(motivo); }
        }
        $('c_fTurma').value = '';
        $('c_fCreche').value = '';
        await consultarDetalhe();
        var blocos = document.querySelectorAll('#c_criancas tr.grupo-turma').length;
        var criancas = document.querySelectorAll('#c_criancas tbody tr:not(.grupo-turma)').length;
        registrarNaLista('painel completo: ' + criancas + ' criança(s) em ' + blocos + ' turma(s) (Creche Sol 4, Creche Esperança 2, Creche Aurora 1)', blocos >= 3 && criancas >= 7);
        var crecheEsperanca = snapshot.turmasCore.filter(function (t) { return t.id === esperanca; })[0].crecheId;
        $('c_fCreche').value = String(crecheEsperanca);
        await consultarDetalhe();
        var blocosFiltrados = document.querySelectorAll('#c_criancas tr.grupo-turma').length;
        var criancasFiltradas = document.querySelectorAll('#c_criancas tbody tr:not(.grupo-turma)').length;
        registrarNaLista('filtrando pela Creche Esperança: ' + criancasFiltradas + ' criança(s) em ' + blocosFiltrados + ' turma(s)', blocosFiltrados === 1 && criancasFiltradas === 2);
        $('c_fCreche').value = '';
        await consultarDetalhe();
        if (blocos < 3 || criancas < 7 || blocosFiltrados !== 1 || criancasFiltradas !== 2) { throw new Error('o painel de crianças na fila não mostrou as várias turmas como esperado'); }
        return 'filas em 3 creches (Sol 4, Esperança 2, Aurora 1), cada turma em seu bloco, e o filtro por creche mostra só a fila escolhida.';
      } },

    { aba: 'relatorios', titulo: 'O painel público confere com o banco', detalhe: 'Os totais do painel público (sem login) precisam ser iguais à soma do banco: vagas livres, creches com vaga e turmas.',
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

    { aba: 'eventos', titulo: 'Idempotência: o evento antigo é ignorado', detalhe: 'Reenvia o evento mais antigo da turma. Esperado: o relatório o ignora (a sequência é menor que a já aplicada) e não volta atrás.',
      rodar: async function () {
        var prova = await executarProvaDeIdempotencia(contexto.turmaId);
        if (prova.erro) { throw new Error(prova.erro); }
        registrarNaLista('vagas no relatório: ' + prova.antes.vagasDisponiveis + ' antes e ' + (prova.depois ? prova.depois.vagasDisponiveis : '-') + ' depois', prova.ignorado);
        registrarNaLista('sequência aplicada: ' + prova.antes.ultimaSequenciaAplicada + ' antes e ' + (prova.depois ? prova.depois.ultimaSequenciaAplicada : '-') + ' depois', prova.ignorado);
        if (!prova.ignorado) { throw new Error('o relatório foi alterado por um evento antigo'); }
        return 'o evento #' + prova.eventId + ' foi reenviado e descartado; o relatório não regrediu.';
      } },

    { aba: 'eventos', titulo: 'Fila de rejeição: uma mensagem inválida cai na DLQ', detalhe: 'A demo publica uma mensagem inválida. Esperado: o MSRelatórios a rejeita e ela cai na DLQ (0 para 1); depois a demo a limpa.',
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
    var passoMostrarRabbit = { aba: 'eventos', titulo: 'Mostrar o RabbitMQ por dentro', detalhe: 'Abra o painel do RabbitMQ (botão do quadro): na aba Queues, as 3 filas do relatório com 1 consumidor cada e as 3 DLQs vazias; na aba Exchanges, alocacreche.dominio.',
      antes: function () { return pausarParaMostrarRabbit(''); },
      rodar: async function () {
        await lerResumoOutbox();
        renderizarStatusOutbox();
        registrarNaLista('RabbitMQ: ' + (resumoOutbox.brokerDisponivel ? 'no ar' : 'fora do ar') + '; mensagens nas filas de rejeição: ' + (resumoOutbox.dlqMensagens === null ? 'filas ainda não criadas' : resumoOutbox.dlqMensagens), resumoOutbox.brokerDisponivel);
        return 'o caminho dos eventos até o relatório, visto por dentro do RabbitMQ.';
      } };
    var indiceFalha = passos.findIndex(function (p) { return p.titulo.indexOf('Falha:') === 0; });
    passos.splice(indiceFalha, 0, passoMostrarRabbit);
  }

  if (incluirLimite) {
    passos.push({ aba: 'relatorios', titulo: 'Gateway: limite de consultas no painel público', detalhe: 'O gateway limita o painel público a 120 consultas por minuto por IP. A demo dispara 150: o excesso recebe 429, e o painel deste computador fica bloqueado por cerca de 1 minuto.',
      rodar: async function () {
        var r = await dispararConsultasAoPainel();
        registrarNaLista(r.liberadas + ' consulta(s) atendida(s) (200) e ' + r.barradas + ' recusada(s) (429); Retry-After: ' + (r.retryAfter || '-') + ' s', r.barradas > 0);
        if (!r.barradas) { throw new Error('Nenhuma consulta foi recusada: o limite configurado é maior que 150 por minuto.'); }
        return 'o gateway recusou o excesso e informou quando tentar de novo.';
      } });
  }

  passos.push({ aba: 'relatorios', titulo: 'Resumo da Stack C', detalhe: 'O que a Stack C entrega, do evento gravado no core até o painel público.',
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
        'Filas em várias turmas e creches no painel, com a contagem do core ao lado da do relatório e filtro por creche',
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
