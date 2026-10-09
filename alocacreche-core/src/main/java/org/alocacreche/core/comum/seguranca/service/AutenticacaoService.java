package org.alocacreche.core.comum.seguranca.service;

import org.alocacreche.core.comum.seguranca.dto.LoginRequest;
import org.alocacreche.core.comum.seguranca.dto.LoginResponse;

public interface AutenticacaoService {

    LoginResponse autenticar(LoginRequest request);
}
