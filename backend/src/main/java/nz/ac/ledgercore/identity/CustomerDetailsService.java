package nz.ac.ledgercore.identity;

import java.util.Locale;
import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CustomerDetailsService implements UserDetailsService {

	private final JdbcTemplate jdbcTemplate;

	public CustomerDetailsService(JdbcTemplate jdbcTemplate) {
		this.jdbcTemplate = jdbcTemplate;
	}

	@Override
	@Transactional(readOnly = true)
	public CustomerPrincipal loadUserByUsername(String username) throws UsernameNotFoundException {
		String normalizedEmail = username == null ? "" : username.trim().toLowerCase(Locale.ROOT);
		List<CustomerPrincipal> customers = jdbcTemplate.query("""
				SELECT id, email_normalized, password_hash, display_name
				FROM customers WHERE email_normalized = ?
				""", (rs, row) -> new CustomerPrincipal(rs.getObject("id", java.util.UUID.class),
					rs.getString("email_normalized"), rs.getString("password_hash"), rs.getString("display_name")),
				normalizedEmail);
		if (customers.isEmpty()) {
			throw new UsernameNotFoundException("Customer credentials were not recognized.");
		}
		return customers.getFirst();
	}
}
