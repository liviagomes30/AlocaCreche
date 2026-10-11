package org.alocacreche.core.comum.seguranca.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Constraint;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import jakarta.validation.Payload;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.nio.charset.StandardCharsets;

public record LoginRequest(
        @Schema(description = "E-mail do usuário.", example = "admin@demo.local")
        @NotBlank @Email @Size(max = 254) String email,
        @Schema(description = "Senha do usuário (valor fictício no exemplo).", example = "SenhaFicticia#123")
        @NotBlank @MaximoBytesUtf8(72) String senha,
        @Schema(description = "Município em que o usuário está cadastrado.", example = "1")
        @NotNull @Positive Long municipioId) {

    @Override
    public String toString() {
        return "LoginRequest[municipioId=" + municipioId + ", email=<omitido>, senha=<omitida>]";
    }

    @Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.RECORD_COMPONENT, ElementType.METHOD})
    @Retention(RetentionPolicy.RUNTIME)
    @Constraint(validatedBy = MaximoBytesUtf8Validator.class)
    public @interface MaximoBytesUtf8 {

        int value();

        String message() default "deve ter no maximo {value} bytes";

        Class<?>[] groups() default {};

        Class<? extends Payload>[] payload() default {};
    }

    public static class MaximoBytesUtf8Validator implements ConstraintValidator<MaximoBytesUtf8, String> {

        private int maximo;

        @Override
        public void initialize(MaximoBytesUtf8 anotacao) {
            maximo = anotacao.value();
        }

        @Override
        public boolean isValid(String valor, ConstraintValidatorContext context) {
            return valor == null || valor.getBytes(StandardCharsets.UTF_8).length <= maximo;
        }
    }
}
