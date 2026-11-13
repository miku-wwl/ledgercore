package nz.ac.ledgercore.accounts.persistence;

import java.time.Instant;
import java.util.UUID;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import nz.ac.ledgercore.accounts.AccountStatus;
import nz.ac.ledgercore.accounts.domain.Account;

@Entity
@Table(name = "accounts")
public class AccountEntity {

	@Id
	@Column(name = "id", nullable = false)
	private UUID id;

	@Column(name = "owner_customer_id", nullable = false)
	private UUID ownerCustomerId;

	@Column(name = "display_name", nullable = false, length = 80)
	private String displayName;

	@Enumerated(EnumType.STRING)
	@Column(name = "status", nullable = false, length = 16)
	private AccountStatus status;

	@JdbcTypeCode(SqlTypes.CHAR)
	@Column(name = "currency", nullable = false, length = 3, insertable = false, updatable = false)
	private String currency;

	@Column(name = "created_at", nullable = false, insertable = false, updatable = false)
	private Instant createdAt;

	@Column(name = "closed_at")
	private Instant closedAt;

	@Version
	@Column(name = "version", nullable = false)
	private long version;

	protected AccountEntity() {
	}

	public AccountEntity(Account account) {
		this.id = account.id();
		this.ownerCustomerId = account.ownerCustomerId();
		this.displayName = account.displayName();
		this.status = account.status();
		this.version = account.version();
	}

	public Account toDomain() {
		return new Account(id, ownerCustomerId, displayName, status, version);
	}

	public void transitionTo(Account account) {
		if (!id.equals(account.id()) || !ownerCustomerId.equals(account.ownerCustomerId())) {
			throw new IllegalArgumentException("Account identity cannot change during a status transition.");
		}
		this.status = account.status();
		this.closedAt = account.status() == AccountStatus.CLOSED ? Instant.now() : null;
	}
}
