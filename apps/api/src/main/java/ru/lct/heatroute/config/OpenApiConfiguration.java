package ru.lct.heatroute.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.servers.Server;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfiguration {

    @Bean
    public OpenAPI heatRouteOpenApi() {
        return new OpenAPI()
                .servers(List.of(new Server().url("/").description("Same-origin HeatRoute API")))
                .info(new Info()
                        .title("HeatRoute API")
                        .version("2026.09-heatroute")
                        .description("LCT 2026 district-heating network synthesis API. "
                                + "Each immutable run exposes the exact algorithm_version, input SHA-256 "
                                + "and validation result."));
    }
}
