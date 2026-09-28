package org.alocacreche.core.comum;

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

    private String rua;
    private Integer numero;
    private String bairro;
    private String cep;
    private String cidade;
    private String estado;
    private Double latitude;
    private Double longitude;
}
