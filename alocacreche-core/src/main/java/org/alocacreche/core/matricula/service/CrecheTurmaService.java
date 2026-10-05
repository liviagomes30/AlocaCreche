package org.alocacreche.core.matricula.service;

public interface CrecheTurmaService {

    CrecheCriadaResponse criarCreche(CriarCrecheRequest request);

    TurmaCriadaResponse criarTurma(CriarTurmaRequest request);
}
