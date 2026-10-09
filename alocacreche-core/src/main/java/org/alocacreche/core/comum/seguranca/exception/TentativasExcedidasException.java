package org.alocacreche.core.comum.seguranca.exception;

public class TentativasExcedidasException extends RuntimeException {

    public static final String MENSAGEM = "Muitas tentativas de login; tente novamente mais tarde";

    private final long segundosParaLiberar;

    public TentativasExcedidasException(long segundosParaLiberar) {
        super(MENSAGEM);
        this.segundosParaLiberar = segundosParaLiberar;
    }

    public long getSegundosParaLiberar() {
        return segundosParaLiberar;
    }
}
