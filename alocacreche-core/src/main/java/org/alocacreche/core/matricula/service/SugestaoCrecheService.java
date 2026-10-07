package org.alocacreche.core.matricula.service;

import java.util.List;

public interface SugestaoCrecheService {

    List<CrecheProximaResponse> sugerirCrechesProximas(SugerirCrechesRequest request);
}
