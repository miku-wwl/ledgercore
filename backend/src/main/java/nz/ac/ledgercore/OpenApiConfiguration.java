package nz.ac.ledgercore;

import org.springframework.context.annotation.Configuration;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.info.Info;

@Configuration(proxyBeanMethods = false)
@OpenAPIDefinition(info = @Info(title = "LedgerCore API", version = "v1",
		description = "Simulated single-currency NZD banking ledger."))
public class OpenApiConfiguration {
}
