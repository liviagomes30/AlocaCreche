package org.alocacreche.core.comum.seguranca.exception;

public class CredenciaisInvalidasException extends RuntimeException {

    public static final String MENSAGEM = "Credenciais inválidas";

    public CredenciaisInvalidasException() {
        super(MENSAGEM);
    }
}
