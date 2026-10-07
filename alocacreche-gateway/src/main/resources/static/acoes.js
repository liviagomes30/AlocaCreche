/*
 * Ações de negócio chamadas pelas telas, pela demo automática e pelos testes.
 * Cada ação chama a API, atualiza as listas locais e registra os eventos esperados.
 */

function turmaLocal(id) { return acharPorId(estado.turmas, id); }
function crecheLocal(id) { return acharPorId(estado.creches, id); }

function filaLocalDaTurma(turmaId) {
  return estado.inscricoes
    .filter(function (i) { return i.turmaId === turmaId && i.status === 'AGUARDANDO'; })
    .sort(function (a, b) { return a.posicao - b.posicao; });
}

function rotuloTurma(turma) {
  var creche = crecheLocal(turma.crecheId);
  return 'Turma ' + turma.id + ' (' + (creche ? creche.nome : 'creche ' + turma.crecheId) + ', ' + turma.faixa + ', ' + turma.vagas + ' vagas)';
}

function aposAcao() {
  salvarEstado();
  if (typeof aoMudarEstado === 'function') { aoMudarEstado(); }
  if (typeof iniciarAcompanhamento === 'function') { iniciarAcompanhamento(); }
}

async function acaoCriarCreche(dados) {
  var resposta = await api('POST', '/api/v1/matricula/creches', {
    rotulo: 'Criar creche',
    corpo: {
      nome: dados.nome, capacidade: dados.capacidade,
      endereco: { rua: dados.rua || 'Rua B', numero: 20, bairro: 'Centro', cidade: 'Sao Paulo', estado: 'SP', latitude: dados.lat, longitude: dados.lon }
    }
  });
  if (resposta.ok) {
    estado.creches.push({ id: resposta.dados.crecheId, nome: dados.nome, capacidade: dados.capacidade, lat: dados.lat, lon: dados.lon });
    aposAcao();
  }
  return resposta;
}

async function acaoCriarTurma(dados) {
  var resposta = await api('POST', '/api/v1/matricula/turmas', {
    rotulo: 'Criar turma',
    corpo: { crecheId: dados.crecheId, faixaEtaria: dados.faixa, vagasTotais: dados.vagas }
  });
  if (resposta.ok) {
    var turma = { id: resposta.dados.turmaId, crecheId: dados.crecheId, faixa: dados.faixa, vagas: dados.vagas, alocadas: 0 };
    estado.turmas.push(turma);
    estado.turmaAtual = turma.id;
    registrarEvento('Turma ' + turma.id + ' criada', 'vaga.liberada', { tipo: 'vaga', turmaId: turma.id, valor: dados.vagas });
    aposAcao();
  }
  return resposta;
}

async function acaoCriarCrecheETurma(dadosCreche, faixa, vagas) {
  var creche = await acaoCriarCreche(dadosCreche);
  if (!creche.ok) { return creche; }
  return acaoCriarTurma({ crecheId: creche.dados.crecheId, faixa: faixa, vagas: vagas });
}

function montarEndereco(dados) {
  return { rua: dados.rua || 'Rua A', numero: dados.numero || 10, bairro: 'Centro', latitude: dados.lat, longitude: dados.lon };
}

async function acaoCadastrarResponsavel(dados) {
  var cpf = dados.cpf || cpfAleatorio();
  var resposta = await api('POST', '/api/v1/inscricao/responsaveis', {
    rotulo: 'Cadastrar responsável',
    corpo: { nome: dados.nome, cpf: cpf, telefone: dados.telefone, email: dados.email, endereco: montarEndereco(dados) }
  });
  if (resposta.ok) {
    estado.responsaveis.push({ id: resposta.dados.responsavelId, nome: dados.nome, lat: dados.lat, lon: dados.lon });
    aposAcao();
  }
  resposta.cpfUsado = cpf;
  return resposta;
}

async function acaoCadastrarCrianca(dados) {
  var resposta = await api('POST', '/api/v1/inscricao/criancas', {
    rotulo: 'Cadastrar criança',
    corpo: { responsavelId: dados.responsavelId, nome: dados.nome, dataNascimento: dados.nascimento }
  });
  if (resposta.ok) {
    estado.criancas.push({ id: resposta.dados.criancaId, responsavelId: dados.responsavelId, nome: dados.nome });
    aposAcao();
  }
  return resposta;
}

// Texto que explica o score a partir dos fatores informados (sem guardar a renda)
function explicarScore(renda, vulneravel, irmao) {
  var fatorRenda = renda <= 500 ? 1 : renda <= 1000 ? 0.75 : renda <= 1500 ? 0.5 : renda <= 2000 ? 0.25 : 0;
  var total = fatorRenda * 0.4 + (vulneravel ? 0.3 : 0) + (irmao ? 0.1 : 0);
  return {
    total: total,
    texto: 'renda ' + numero(fatorRenda, 2) + ' x 0,4 + vulnerabilidade ' + (vulneravel ? 1 : 0) + ' x 0,3 + distância 0 x 0,2 + irmão ' + (irmao ? 1 : 0) + ' x 0,1'
  };
}

/*
 * dados.modo: 'id' (criancaId) ou 'inline' (responsável e criança no corpo)
 * dados.responsavel, dados.crianca: usados no modo inline
 */
async function acaoInscrever(dados) {
  var corpo = {
    turmaId: dados.turmaId,
    rendaPerCapita: dados.renda,
    criterioVulnerabilidade: dados.vulneravel
  };
  var nomeCrianca;
  var origem = null;
  if (dados.modo === 'id') {
    corpo.criancaId = dados.criancaId;
    var crianca = acharPorId(estado.criancas, dados.criancaId);
    nomeCrianca = crianca ? crianca.nome : 'criança ' + dados.criancaId;
    var resp = crianca ? acharPorId(estado.responsaveis, crianca.responsavelId) : null;
    origem = resp ? { lat: resp.lat, lon: resp.lon } : null;
  } else {
    var cpf = dados.responsavel.cpf || cpfAleatorio();
    corpo.responsavel = {
      nome: dados.responsavel.nome, cpf: cpf, telefone: dados.responsavel.telefone, email: dados.responsavel.email,
      endereco: montarEndereco(dados.responsavel)
    };
    corpo.crianca = { nome: dados.crianca.nome, dataNascimento: dados.crianca.nascimento };
    nomeCrianca = dados.crianca.nome;
    origem = { lat: dados.responsavel.lat, lon: dados.responsavel.lon };
  }
  var resposta = await api('POST', '/api/v1/inscricao/inscricoes', {
    rotulo: 'Inscrever ' + nomeCrianca + (dados.modo === 'id' ? ' (por criancaId)' : ' (cadastro inline)'),
    corpo: corpo
  });
  if (resposta.ok) {
    var d = resposta.dados;
    estado.inscricoes.push({
      id: d.inscricaoId, turmaId: dados.turmaId, nome: nomeCrianca, posicao: d.posicao, score: d.scorePrioridade,
      status: d.status, origem: origem, modo: dados.modo,
      explicacao: explicarScore(dados.renda, dados.vulneravel, !!dados.irmao).texto
    });
    registrarEvento('Inscrição ' + d.inscricaoId + ' na turma ' + dados.turmaId, 'fila.atualizada',
      { tipo: 'fila', turmaId: dados.turmaId, valor: filaLocalDaTurma(dados.turmaId).length });
    aposAcao();
  }
  return resposta;
}

async function acaoConsultarPosicao(inscricaoId, turmaId) {
  var resposta = await api('GET', '/api/v1/inscricao/inscricoes/' + inscricaoId + '/posicao?turmaId=' + turmaId, { rotulo: 'Consultar posição na fila' });
  if (resposta.ok) {
    var ins = acharPorId(estado.inscricoes, inscricaoId);
    if (ins) { ins.posicao = resposta.dados.posicao; salvarEstado(); }
  }
  return resposta;
}

async function acaoAlocar(turmaId, rotulo) {
  await garantirSnapshot();
  var resposta = await api('POST', '/api/v1/matricula/vagas/alocar', { rotulo: rotulo || 'Alocar vaga', corpo: { turmaId: turmaId } });
  if (!resposta.ok) { return resposta; }
  var matriculaId = resposta.dados.matriculaId;
  var turma = turmaLocal(turmaId);
  var fila = filaLocalDaTurma(turmaId);
  var convocada = fila.length ? fila[0] : null;
  var creche = turma ? crecheLocal(turma.crecheId) : null;
  var distancia = null;
  if (convocada) {
    convocada.status = 'CONVOCADA';
    convocada.posicao = null;
    fila.slice(1).forEach(function (i) { i.posicao = Math.max(1, i.posicao - 1); });
    if (convocada.origem && creche) { distancia = haversineKm(convocada.origem.lat, convocada.origem.lon, creche.lat, creche.lon); }
  }
  if (turma) { turma.alocadas += 1; }
  estado.matriculas.push({ id: matriculaId, turmaId: turmaId, inscricaoId: convocada ? convocada.id : null, nome: convocada ? convocada.nome : '?', status: 'PENDENTE', distanciaKm: distancia });
  estado.matriculaAtual = matriculaId;
  var disponiveis = turma ? turma.vagas - turma.alocadas : null;
  var acaoId = 'alocacao-' + matriculaId;
  registrarEvento('Alocação na turma ' + turmaId + ' (matrícula ' + matriculaId + ')', 'vaga.liberada', { tipo: 'vaga', turmaId: turmaId, valor: disponiveis }, acaoId);
  registrarEvento('Alocação na turma ' + turmaId, 'fila.atualizada', { tipo: 'fila', turmaId: turmaId, valor: filaLocalDaTurma(turmaId).length }, acaoId);
  registrarEvento('Alocação na turma ' + turmaId, 'matricula.atualizada', { tipo: 'matriculas' }, acaoId);
  resposta.matricula = estado.matriculas[estado.matriculas.length - 1];
  aposAcao();
  return resposta;
}

async function acaoConfirmar(matriculaId, rotulo) {
  await garantirSnapshot();
  var resposta = await api('POST', '/api/v1/matricula/matriculas/' + matriculaId + '/confirmar', { rotulo: rotulo || 'Confirmar matrícula' });
  if (resposta.ok) {
    var m = acharPorId(estado.matriculas, matriculaId);
    if (m) {
      m.status = 'CONFIRMADA';
      var ins = acharPorId(estado.inscricoes, m.inscricaoId);
      if (ins) { ins.status = 'MATRICULADA'; }
    }
    registrarEvento('Confirmação da matrícula ' + matriculaId, 'matricula.atualizada', { tipo: 'confirmadas' });
    aposAcao();
  }
  return resposta;
}

// Turma a usar quando a tela não tem seleção explícita
function turmaPadrao() {
  if (estado.turmaAtual && turmaLocal(estado.turmaAtual)) { return estado.turmaAtual; }
  return estado.turmas.length ? estado.turmas[estado.turmas.length - 1].id : null;
}

// Dados de exemplo para uma inscrição rápida com perfis diferentes
var PERFIS_EXEMPLO = {
  baixa: { renda: 350, vulneravel: true, rotulo: 'renda baixa e vulnerável', nome: 'Ana' },
  media: { renda: 1200, vulneravel: false, rotulo: 'renda média', nome: 'Bruno' },
  alta: { renda: 4500, vulneravel: false, rotulo: 'renda alta', nome: 'Carla' }
};

// Inscreve uma família nova (cadastro inline) com o perfil escolhido
async function acaoInscreverExemplo(turmaId, perfil) {
  var p = PERFIS_EXEMPLO[perfil];
  var sufixo = sufixoAleatorio();
  return acaoInscrever({
    modo: 'inline', turmaId: turmaId, renda: p.renda, vulneravel: p.vulneravel,
    responsavel: { nome: 'Resp. ' + p.nome + ' ' + sufixo, cpf: '', telefone: '11999990000', email: 'resp.' + sufixo + '@example.com', lat: -23.55 + Math.random() * 0.02, lon: -46.63 + Math.random() * 0.02 },
    crianca: { nome: 'Crianca ' + p.nome + ' ' + sufixo, nascimento: '2024-03-10' }
  });
}
