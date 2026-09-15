package ru.lct.heatroute.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfiguration {

    @Bean
    public OpenAPI heatRouteOpenApi() {
        return new OpenAPI().info(new Info()
                .title("HeatRoute API")
                .version("0.1.0-java")
                .description("LCT 2026 district-heating route calculation backend"));
    }
}
