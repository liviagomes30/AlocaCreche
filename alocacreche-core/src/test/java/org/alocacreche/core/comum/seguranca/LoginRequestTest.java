package org.alocacreche.core.comum.seguranca;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Validacao de entrada do login (AUTH-05): email ate 254 caracteres e bem formado, senha nao vazia
 * de ate 72 bytes e municipioId positivo.
 */
@DisplayName("LoginRequest")
class LoginRequestTest {

    private static final String EMAIL_VALIDO = "servidora@prefeitura.gov.br";
    private static final String SENHA_VALIDA = "senha-correta";

    private static ValidatorFactory factory;
    private static Validator validator;

    @BeforeAll
    static void criarValidator() {
        factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @AfterAll
    static void fecharValidator() {
        factory.close();
    }

    @Test
    @DisplayName("um pedido completo e bem formado nao tem violacao")
    void pedidoValidoNaoTemViolacao() {
        assertThat(camposInvalidos(new LoginRequest(EMAIL_VALIDO, SENHA_VALIDA, 1L))).isEmpty();
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "   ", "nao-e-um-email", "sem-arroba.gov.br"})
    @DisplayName("email ausente, em branco ou mal formado e invalido")
    void emailAusenteOuMalFormadoEInvalido(String email) {
        assertThat(camposInvalidos(new LoginRequest(email, SENHA_VALIDA, 1L))).containsExactly("email");
    }

    @Test
    @DisplayName("email com 255 caracteres e invalido")
    void emailCom255CaracteresEInvalido() {
        String email = emailComTamanho(255);

        assertThat(email).hasSize(255);
        assertThat(camposInvalidos(new LoginRequest(email, SENHA_VALIDA, 1L))).containsExactly("email");
    }

    @Test
    @DisplayName("email com exatamente 254 caracteres e valido")
    void emailCom254CaracteresEValido() {
        String email = emailComTamanho(254);

        assertThat(email).hasSize(254);
        assertThat(camposInvalidos(new LoginRequest(email, SENHA_VALIDA, 1L))).isEmpty();
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "   "})
    @DisplayName("senha ausente ou em branco e invalida")
    void senhaAusenteOuEmBrancoEInvalida(String senha) {
        assertThat(camposInvalidos(new LoginRequest(EMAIL_VALIDO, senha, 1L))).containsExactly("senha");
    }

    @Test
    @DisplayName("senha com 73 bytes e invalida")
    void senhaCom73BytesEInvalida() {
        assertThat(camposInvalidos(new LoginRequest(EMAIL_VALIDO, "a".repeat(73), 1L))).containsExactly("senha");
    }

    @Test
    @DisplayName("senha com exatamente 72 bytes e valida")
    void senhaCom72BytesEValida() {
        assertThat(camposInvalidos(new LoginRequest(EMAIL_VALIDO, "a".repeat(72), 1L))).isEmpty();
    }

    @Test
    @DisplayName("senha multibyte com 37 caracteres e 74 bytes e invalida")
    void senhaMultibyteAcimaDe72BytesEInvalida() {
        assertThat(camposInvalidos(new LoginRequest(EMAIL_VALIDO, "ç".repeat(37), 1L))).containsExactly("senha");
    }

    @Test
    @DisplayName("senha multibyte com 36 caracteres e 72 bytes e valida")
    void senhaMultibyteCom72BytesEValida() {
        assertThat(camposInvalidos(new LoginRequest(EMAIL_VALIDO, "ç".repeat(36), 1L))).isEmpty();
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(longs = {0L, -1L})
    @DisplayName("municipioId ausente, zero ou negativo e invalido")
    void municipioIdAusenteOuNaoPositivoEInvalido(Long municipioId) {
        assertThat(camposInvalidos(new LoginRequest(EMAIL_VALIDO, SENHA_VALIDA, municipioId)))
                .containsExactly("municipioId");
    }

    @Test
    @DisplayName("todos os campos invalidos sao listados juntos")
    void todosOsCamposInvalidosSaoListados() {
        assertThat(camposInvalidos(new LoginRequest(null, null, null)))
                .containsExactlyInAnyOrder("email", "senha", "municipioId");
    }

    private static Set<String> camposInvalidos(LoginRequest request) {
        Set<ConstraintViolation<LoginRequest>> violacoes = validator.validate(request);
        return violacoes.stream()
                .map(violacao -> violacao.getPropertyPath().toString())
                .collect(Collectors.toSet());
    }

    private static String emailComTamanho(int tamanho) {
        String parteLocal = "a".repeat(60) + "@";
        String dominioFinal = "prefeitura.gov.br";
        int restante = tamanho - parteLocal.length() - dominioFinal.length();
        StringBuilder subdominios = new StringBuilder();
        while (restante > 0) {
            int rotulo = Math.min(restante - 1, 60);
            subdominios.append("b".repeat(rotulo)).append('.');
            restante -= rotulo + 1;
        }
        return parteLocal + subdominios + dominioFinal;
    }
}
