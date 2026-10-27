package io.beangate.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI beanGateOpenApi() {
        return new OpenAPI().info(new Info()
                .title("BeanGate")
                .version("0.1.0")
                .description("""
                        OpenAI-compatible AI gateway.
                        /v1/** requires `Authorization: Bearer sk-gw-...`.
                        /admin/** requires `X-Admin-Token`.
                        """));
    }
}
