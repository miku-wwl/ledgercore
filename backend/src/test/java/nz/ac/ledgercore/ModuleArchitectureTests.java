package nz.ac.ledgercore;

import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;

class ModuleArchitectureTests {

	@Test
	void moduleBoundariesAreAcyclic() {
		ApplicationModules.of(LedgerCoreApplication.class).verify();
	}
}
