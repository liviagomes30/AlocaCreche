package org.alocacreche.relatorios.seguranca.exception;

import cn.dev33.satoken.exception.NotLoginException;
import cn.dev33.satoken.exception.NotPermissionException;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class TratadorExcecaoSeguranca {

    static final String MENSAGEM_NAO_AUTENTICADO = "Autenticação necessária";
    static final String MENSAGEM_SEM_PERMISSAO = "Permissão insuficiente";

    @ExceptionHandler(NotLoginException.class)
    public ResponseEntity<Map<String, String>> tratarNaoAutenticado(NotLoginException excecao) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("erro", MENSAGEM_NAO_AUTENTICADO));
    }

    @ExceptionHandler(NotPermissionException.class)
    public ResponseEntity<Map<String, String>> tratarSemPermissao(NotPermissionException excecao) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("erro", MENSAGEM_SEM_PERMISSAO));
    }
}
