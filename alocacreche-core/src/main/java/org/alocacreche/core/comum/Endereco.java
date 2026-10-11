package org.alocacreche.core.comum;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.persistence.Embeddable;
import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Embeddable
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode
public class Endereco {

    @Schema(description = "Logradouro.", example = "Rua das Flores")
    private String rua;
    @Schema(description = "Número.", example = "100")
    private Integer numero;
    @Schema(description = "Bairro.", example = "Centro")
    private String bairro;
    @Schema(description = "CEP (fictício no exemplo).", example = "00000-000")
    private String cep;
    @Schema(description = "Cidade.", example = "Município Demo")
    private String cidade;
    @Schema(description = "UF.", example = "SP")
    private String estado;
    @Schema(description = "Latitude.", example = "-23.5505")
    private Double latitude;
    @Schema(description = "Longitude.", example = "-46.6333")
    private Double longitude;
}
