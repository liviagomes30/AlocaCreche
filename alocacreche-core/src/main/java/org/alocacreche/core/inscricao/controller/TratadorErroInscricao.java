package org.alocacreche.core.inscricao.controller;

import java.util.List;
import java.util.Map;
import org.alocacreche.core.inscricao.service.ConflitoException;
import org.alocacreche.core.inscricao.service.RecursoNaoEncontradoException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes = InscricaoController.class)
public class TratadorErroInscricao {

    static final String MENSAGEM_REQUISICAO_INVALIDA = "Requisição inválida";

    @ExceptionHandler(RecursoNaoEncontradoException.class)
    public ResponseEntity<Map<String, String>> tratarNaoEncontrado(RecursoNaoEncontradoException excecao) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("erro", excecao.getMessage()));
    }

    @ExceptionHandler(ConflitoException.class)
    public ResponseEntity<Map<String, String>> tratarConflito(ConflitoException excecao) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("erro", excecao.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> tratarValidacao(MethodArgumentNotValidException excecao) {
        List<String> campos = excecao.getBindingResult().getFieldErrors().stream()
                .map(erro -> erro.getField())
                .distinct()
                .sorted()
                .toList();
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(Map.of("erro", MENSAGEM_REQUISICAO_INVALIDA, "campos", campos));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, String>> tratarJsonIlegivel(HttpMessageNotReadableException excecao) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("erro", MENSAGEM_REQUISICAO_INVALIDA));
    }
}
