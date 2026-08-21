package dev.tendulkar.fineractagent;

import dev.tendulkar.fineractagent.config.DatabaseUrlNormaliser;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class FineractQueryAgentApplication {

    public static void main(String[] args) {
        // Accepts the libpq URL a hosted Postgres shows you, not just the JDBC
        // form Spring needs. Must run before the context starts, because the
        // datasource is built during bean creation.
        DatabaseUrlNormaliser.apply();
        SpringApplication.run(FineractQueryAgentApplication.class, args);
    }
}
