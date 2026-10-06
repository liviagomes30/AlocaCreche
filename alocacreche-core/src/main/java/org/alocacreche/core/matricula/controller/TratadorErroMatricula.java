package org.alocacreche.core.matricula.controller;

import java.util.Map;
import org.alocacreche.core.comum.excecao.ConflitoException;
import org.alocacreche.core.comum.excecao.RecursoNaoEncontradoException;
import org.alocacreche.core.matricula.service.DadoInvalidoException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(basePackages = "org.alocacreche.core.matricula.controller")
public class TratadorErroMatricula {

    @ExceptionHandler(RecursoNaoEncontradoException.class)
    public ResponseEntity<Map<String, String>> tratarNaoEncontrado(RecursoNaoEncontradoException excecao) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("erro", excecao.getMessage()));
    }

    @ExceptionHandler(ConflitoException.class)
    public ResponseEntity<Map<String, String>> tratarConflito(ConflitoException excecao) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("erro", excecao.getMessage()));
    }

    @ExceptionHandler(DadoInvalidoException.class)
    public ResponseEntity<Map<String, String>> tratarDadoInvalido(DadoInvalidoException excecao) {
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(Map.of("erro", excecao.getMessage()));
    }
}
