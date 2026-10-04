package org.alocacreche.core.comum.seguranca;

public interface AutenticacaoService {

    LoginResponse autenticar(LoginRequest request);
}
