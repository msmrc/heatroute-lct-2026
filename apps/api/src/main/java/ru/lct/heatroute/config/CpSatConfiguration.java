package ru.lct.heatroute.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import ru.lct.heatroute.domain.optimization.CpSatRuntime;

/** Собирает native solver как инфраструктурную зависимость без Spring-связей в алгоритме. */
@Configuration
public class CpSatConfiguration {
    @Bean
    public CpSatRuntime cpSatRuntime() {
        return new CpSatRuntime();
    }
}
