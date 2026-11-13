package nz.ac.ledgercore.accounts.persistence;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;

public interface AccountRepository extends JpaRepository<AccountEntity, UUID> {

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select account from AccountEntity account where account.id = :id")
	Optional<AccountEntity> findByIdForUpdate(@Param("id") UUID id);

	boolean existsByIdAndOwnerCustomerId(UUID id, UUID ownerCustomerId);
}
