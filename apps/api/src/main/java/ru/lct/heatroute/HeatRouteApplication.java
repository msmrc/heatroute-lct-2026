package ru.lct.heatroute;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class HeatRouteApplication {

    public static void main(String[] args) {
        SpringApplication.run(HeatRouteApplication.class, args);
    }
}
