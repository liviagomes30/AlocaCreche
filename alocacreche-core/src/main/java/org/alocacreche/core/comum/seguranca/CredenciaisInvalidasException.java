package org.alocacreche.core.comum.seguranca;

public class CredenciaisInvalidasException extends RuntimeException {

    public static final String MENSAGEM = "Credenciais inválidas";

    public CredenciaisInvalidasException() {
        super(MENSAGEM);
    }
}
