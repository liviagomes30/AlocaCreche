package org.alocacreche.core.comum.seguranca;

import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes = AuthController.class)
public class TratadorErroLogin {

    @ExceptionHandler(CredenciaisInvalidasException.class)
    public ResponseEntity<Map<String, String>> tratarCredenciaisInvalidas(CredenciaisInvalidasException excecao) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("erro", excecao.getMessage()));
    }

    @ExceptionHandler(TentativasExcedidasException.class)
    public ResponseEntity<Map<String, String>> tratarTentativasExcedidas(TentativasExcedidasException excecao) {
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .header(HttpHeaders.RETRY_AFTER, String.valueOf(excecao.getSegundosParaLiberar()))
                .body(Map.of("erro", excecao.getMessage()));
    }
}
