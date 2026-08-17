package dev.tendulkar.fineractagent;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class FineractQueryAgentApplication {

    public static void main(String[] args) {
        SpringApplication.run(FineractQueryAgentApplication.class, args);
    }
}
