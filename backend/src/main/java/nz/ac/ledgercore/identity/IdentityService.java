package nz.ac.ledgercore.identity;

import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class IdentityService {

	private static final Pattern EMAIL = Pattern.compile("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$");
	private final JdbcTemplate jdbcTemplate;
	private final PasswordEncoder passwordEncoder;

	public IdentityService(JdbcTemplate jdbcTemplate, PasswordEncoder passwordEncoder) {
		this.jdbcTemplate = jdbcTemplate;
		this.passwordEncoder = passwordEncoder;
	}

	@Transactional
	public CustomerProfile register(String email, String password, String displayName) {
		String normalizedEmail = normalizeEmail(email);
		String normalizedName = displayName == null ? "" : displayName.trim();
		if (!EMAIL.matcher(normalizedEmail).matches() || normalizedEmail.length() > 254) {
			throw new IdentityException(IdentityErrorCode.INVALID_REQUEST, "Enter a valid email address.");
		}
		if (password == null || password.length() < 12 || password.length() > 128) {
			throw new IdentityException(IdentityErrorCode.INVALID_REQUEST,
					"Password must contain between 12 and 128 characters.");
		}
		if (normalizedName.isEmpty() || normalizedName.length() > 100) {
			throw new IdentityException(IdentityErrorCode.INVALID_REQUEST, "Name must contain 1 to 100 characters.");
		}
		UUID id = UUID.randomUUID();
		try {
			jdbcTemplate.update("""
					INSERT INTO customers (id, email_normalized, password_hash, display_name)
					VALUES (?, ?, ?, ?)
					""", id, normalizedEmail, passwordEncoder.encode(password), normalizedName);
		}
		catch (DuplicateKeyException exception) {
			throw new IdentityException(IdentityErrorCode.EMAIL_IN_USE, "An account already uses this email address.");
		}
		return new CustomerProfile(id, normalizedEmail, normalizedName);
	}

	private String normalizeEmail(String email) {
		return email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
	}

	public record CustomerProfile(UUID id, String email, String displayName) {
	}
}
