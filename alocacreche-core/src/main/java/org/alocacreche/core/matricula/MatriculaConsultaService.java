package org.alocacreche.core.matricula;

import lombok.RequiredArgsConstructor;
import org.alocacreche.core.inscricao.MatriculaConsulta;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class MatriculaConsultaService implements MatriculaConsulta {

    private final MatriculaRepository matriculaRepository;

    @Override
    public boolean existeMatriculaParaInscricao(Long inscricaoId) {
        return matriculaRepository.existsByInscricaoId(inscricaoId);
    }
}