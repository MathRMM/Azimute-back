package br.dev.azimute.plataforma.web;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * Documentação OpenAPI (Swagger UI em /swagger-ui.html). Ligada só no perfil dev (application-dev.yml):
 * fora dele os endpoints de documentação não existem.
 * <p>
 * Todo endpoint pede o token do dispositivo, exceto o cadastro de dispositivo, que é a forma de obter um.
 */
@Configuration(proxyBeanMethods = false)
class DocumentacaoApiConfig {

    static final String TOKEN = "token";

    @Bean
    OpenAPI documentacaoApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("Azimute Core")
                        .version("v1")
                        .description("API do assistente pessoal. Obtenha o token em POST /api/auth/dispositivos "
                                + "e informe-o em Authorize."))
                .components(new Components().addSecuritySchemes(TOKEN, new SecurityScheme()
                        .type(SecurityScheme.Type.HTTP)
                        .scheme("bearer")
                        .description("Token do dispositivo, mostrado uma vez no cadastro")))
                .addSecurityItem(new SecurityRequirement().addList(TOKEN));
    }

    /** O cadastro de dispositivo é público (SecurityConfig): na documentação, ele não pede token. */
    @Bean
    OpenApiCustomizer cadastroDeDispositivoSemToken() {
        return api -> {
            var cadastro = api.getPaths().get("/api/auth/dispositivos");
            if (cadastro != null && cadastro.getPost() != null) {
                cadastro.getPost().setSecurity(List.of());
            }
        };
    }
}
