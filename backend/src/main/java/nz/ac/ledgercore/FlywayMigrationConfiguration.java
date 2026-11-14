package nz.ac.ledgercore;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Registers migration callbacks with Spring Boot's Flyway auto-configuration (it collects Callback beans). */
@Configuration(proxyBeanMethods = false)
class FlywayMigrationConfiguration {

    @Bean
    LegacyTransferOwnerBackfillCallback legacyTransferOwnerBackfillCallback() {
        return new LegacyTransferOwnerBackfillCallback();
    }
}
