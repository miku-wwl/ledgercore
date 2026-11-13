# 固定源码摘录

源码提交：`484b9ad1c70ba052849ee1a40e15e35b6d888c12`。以下代码均由工具从该 Git 对象读取，行号和链接按该对象计算。

## money

`backend/src/main/java/nz/ac/ledgercore/ledger/Money.java:12-27` · [固定提交源码](https://github.com/miku-wwl/ledgercore/blob/484b9ad1c70ba052849ee1a40e15e35b6d888c12/backend/src/main/java/nz/ac/ledgercore/ledger/Money.java#L12-L27)

```text
	public static Money parsePositiveNzd(String value) {
		if (value == null || !CANONICAL_NZD.matcher(value).matches()) {
			throw new IllegalArgumentException("Amount must be a canonical NZD decimal string with two digits.");
		}
		try {
			long minorUnits = new BigDecimal(value).movePointRight(2).longValueExact();
			if (minorUnits <= 0) {
				throw new IllegalArgumentException("Amount must be greater than zero.");
			}
			return new Money(minorUnits);
		}
		catch (ArithmeticException exception) {
			throw new IllegalArgumentException("Amount exceeds the supported NZD range.", exception);
		}
	}
```

## journal_entry

`backend/src/main/java/nz/ac/ledgercore/ledger/JournalEntry.java:11-54` · [固定提交源码](https://github.com/miku-wwl/ledgercore/blob/484b9ad1c70ba052849ee1a40e15e35b6d888c12/backend/src/main/java/nz/ac/ledgercore/ledger/JournalEntry.java#L11-L54)

```text
	public JournalEntry {
		Objects.requireNonNull(id, "id");
		Objects.requireNonNull(type, "type");
		Objects.requireNonNull(postings, "postings");
		postings = List.copyOf(postings);
		if ((type == JournalEntryType.REVERSAL) != (reversesEntryId != null)) {
			throw new IllegalArgumentException("Only a reversal entry may reference the entry it reverses.");
		}
		if (postings.size() < 2) {
			throw new IllegalArgumentException("A journal entry requires at least two postings.");
		}

		Set<UUID> ledgerAccountIds = new HashSet<>();
		long debits = 0;
		long credits = 0;
		for (Posting posting : postings) {
			Objects.requireNonNull(posting, "posting");
			if (!ledgerAccountIds.add(posting.ledgerAccountId())) {
				throw new IllegalArgumentException("A journal entry may post only once to each ledger account.");
			}
			if (posting.side() == PostingSide.DEBIT) {
				debits = Math.addExact(debits, posting.amountMinor());
			}
			else {
				credits = Math.addExact(credits, posting.amountMinor());
			}
		}
		if (debits != credits) {
			throw new IllegalArgumentException("Journal entry debits must equal credits.");
		}
	}

	public static JournalEntry openingFunding(UUID reserveLedgerAccountId, UUID customerLedgerAccountId,
			Money amount) {
		return balancedFunding(JournalEntryType.OPENING_FUNDING, reserveLedgerAccountId, customerLedgerAccountId,
				amount);
	}

	public static JournalEntry simulatedDeposit(UUID reserveLedgerAccountId, UUID customerLedgerAccountId,
			Money amount) {
		return balancedFunding(JournalEntryType.SIMULATED_DEPOSIT, reserveLedgerAccountId, customerLedgerAccountId,
				amount);
	}
```

## controller

`backend/src/main/java/nz/ac/ledgercore/transfers/TransferController.java:37-59` · [固定提交源码](https://github.com/miku-wwl/ledgercore/blob/484b9ad1c70ba052849ee1a40e15e35b6d888c12/backend/src/main/java/nz/ac/ledgercore/transfers/TransferController.java#L37-L59)

```text
	@PostMapping
	ResponseEntity<TransferReceipt> transfer(@AuthenticationPrincipal CustomerPrincipal principal,
			@RequestHeader("Idempotency-Key") String idempotencyKey, @Valid @RequestBody TransferRequest request) {
		Money amount = Money.parsePositiveNzd(request.amount());
		long startedAt = System.nanoTime();
		try {
			TransferReceipt receipt = transferService.transfer(principal.customerId(), request.sourceAccountId(),
					request.targetAccountId(), amount, idempotencyKey);
			logTransfer("succeeded", "NONE", startedAt);
			return ResponseEntity.ok(receipt);
		}
		catch (TransferException exception) {
			String outcome = exception.code() == TransferErrorCode.ACCOUNT_NOT_OPEN
					|| exception.code() == TransferErrorCode.IDEMPOTENCY_KEY_REUSED ? "conflict" : "rejected";
			logTransfer(outcome, exception.code().name(), startedAt);
			throw exception;
		}
		catch (RuntimeException exception) {
			logTransfer("internal_error", "UNEXPECTED", startedAt);
			throw exception;
		}
	}
```

## account_entity

`backend/src/main/java/nz/ac/ledgercore/accounts/persistence/AccountEntity.java:47-50` · [固定提交源码](https://github.com/miku-wwl/ledgercore/blob/484b9ad1c70ba052849ee1a40e15e35b6d888c12/backend/src/main/java/nz/ac/ledgercore/accounts/persistence/AccountEntity.java#L47-L50)

```text
	@Version
	@Column(name = "version", nullable = false)
	private long version;
```

## db_balance_trigger

`backend/src/main/resources/db/migration/V2__create_ledger_tables.sql:76-116` · [固定提交源码](https://github.com/miku-wwl/ledgercore/blob/484b9ad1c70ba052849ee1a40e15e35b6d888c12/backend/src/main/resources/db/migration/V2__create_ledger_tables.sql#L76-L116)

```text
CREATE FUNCTION ledgercore_assert_journal_entry_balanced()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
    target_entry_id UUID;
    posting_count BIGINT;
    debit_total NUMERIC;
    credit_total NUMERIC;
BEGIN
    IF TG_TABLE_NAME = 'journal_entries' THEN
        target_entry_id := NEW.id;
    ELSE
        target_entry_id := NEW.journal_entry_id;
    END IF;

    SELECT count(*),
           COALESCE(sum(amount_minor) FILTER (WHERE side = 'DEBIT'), 0),
           COALESCE(sum(amount_minor) FILTER (WHERE side = 'CREDIT'), 0)
      INTO posting_count, debit_total, credit_total
      FROM postings
     WHERE journal_entry_id = target_entry_id;

    IF posting_count < 2 OR debit_total <> credit_total THEN
        RAISE EXCEPTION 'Journal entry % must contain at least two balanced postings', target_entry_id
            USING ERRCODE = '23514', CONSTRAINT = 'ck_journal_entries_balanced';
    END IF;
    RETURN NULL;
END;
$$;

CREATE CONSTRAINT TRIGGER ct_journal_entries_balanced
    AFTER INSERT ON journal_entries
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION ledgercore_assert_journal_entry_balanced();

CREATE CONSTRAINT TRIGGER ct_postings_keep_entry_balanced
    AFTER INSERT ON postings
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION ledgercore_assert_journal_entry_balanced();
```

## transfer_transaction

`backend/src/main/java/nz/ac/ledgercore/transfers/TransferService.java:42-103` · [固定提交源码](https://github.com/miku-wwl/ledgercore/blob/484b9ad1c70ba052849ee1a40e15e35b6d888c12/backend/src/main/java/nz/ac/ledgercore/transfers/TransferService.java#L42-L103)

```text
	@Transactional(isolation = Isolation.READ_COMMITTED)
	public TransferReceipt transfer(UUID authenticatedCustomerId, UUID sourceAccountId, UUID targetAccountId,
			Money amount, String idempotencyKey) {
		validateRequest(authenticatedCustomerId, sourceAccountId, targetAccountId, amount, idempotencyKey);
		if (!accountService.belongsTo(authenticatedCustomerId, sourceAccountId)) {
			throw error(TransferErrorCode.ACCOUNT_NOT_FOUND, "The source account was not found.");
		}
		if (sourceAccountId.equals(targetAccountId)) {
			throw error(TransferErrorCode.INVALID_REQUEST, "A transfer requires two different accounts.");
		}

		String fingerprint = fingerprint(authenticatedCustomerId, sourceAccountId, targetAccountId, amount);
		int reserved = jdbcTemplate.update("""
				INSERT INTO idempotency_records
					(owner_customer_id, operation, idempotency_key, request_fingerprint)
				VALUES (?, ?, ?, ?)
				ON CONFLICT (owner_customer_id, operation, idempotency_key) DO NOTHING
				""", authenticatedCustomerId, OPERATION, idempotencyKey, fingerprint);
		if (reserved == 0) {
			return replayReceipt(authenticatedCustomerId, idempotencyKey, fingerprint);
		}

		lockAndValidateAccounts(sourceAccountId, targetAccountId);
		UUID transferId = UUID.randomUUID();
		UUID journalEntryId;
		try {
			journalEntryId = ledgerWriter.transferBetweenCustomerAccounts(sourceAccountId, targetAccountId, amount,
					transferId);
		}
		catch (InsufficientLedgerBalanceException exception) {
			throw error(TransferErrorCode.INSUFFICIENT_FUNDS,
					"The source account does not have enough available funds.");
		}
		catch (IllegalArgumentException exception) {
			throw error(TransferErrorCode.ACCOUNT_NOT_FOUND, "One of the accounts was not found.");
		}

		jdbcTemplate.update("""
				INSERT INTO transfers
					(id, owner_customer_id, source_account_id, target_account_id, amount_minor, currency, journal_entry_id)
				VALUES (?, ?, ?, ?, ?, 'NZD', ?)
				""", transferId, authenticatedCustomerId, sourceAccountId, targetAccountId, amount.minorUnits(),
				journalEntryId);
		Timestamp createdAt = jdbcTemplate.queryForObject("SELECT created_at FROM transfers WHERE id = ?",
				Timestamp.class, transferId);
		TransferReceipt receipt = new TransferReceipt(transferId, sourceAccountId, targetAccountId, "POSTED",
				amount.toNzdString(), "NZD", Objects.requireNonNull(createdAt, "createdAt").toInstant());
		int completed = jdbcTemplate.update("""
				UPDATE idempotency_records
				SET status = 'SUCCEEDED', transfer_id = ?, response_payload = jsonb_build_object(
					'transferId', ?, 'sourceAccountId', ?, 'targetAccountId', ?, 'status', ?, 'amount', ?, 'currency', ?,
					'createdAt', ?::timestamptz)
				WHERE owner_customer_id = ? AND operation = ? AND idempotency_key = ? AND status = 'IN_PROGRESS'
			""", transferId, receipt.transferId(), receipt.sourceAccountId(), receipt.targetAccountId(), receipt.status(),
				receipt.amount(), receipt.currency(), receipt.createdAt().toString(), authenticatedCustomerId, OPERATION,
				idempotencyKey);
		if (completed != 1) {
			throw new IllegalStateException("The transfer success receipt could not be finalized.");
		}
		return receipt;
	}
```

## idempotency_reservation

`backend/src/main/java/nz/ac/ledgercore/transfers/TransferService.java:54-63` · [固定提交源码](https://github.com/miku-wwl/ledgercore/blob/484b9ad1c70ba052849ee1a40e15e35b6d888c12/backend/src/main/java/nz/ac/ledgercore/transfers/TransferService.java#L54-L63)

```text
		int reserved = jdbcTemplate.update("""
				INSERT INTO idempotency_records
					(owner_customer_id, operation, idempotency_key, request_fingerprint)
				VALUES (?, ?, ?, ?)
				ON CONFLICT (owner_customer_id, operation, idempotency_key) DO NOTHING
				""", authenticatedCustomerId, OPERATION, idempotencyKey, fingerprint);
		if (reserved == 0) {
			return replayReceipt(authenticatedCustomerId, idempotencyKey, fingerprint);
		}
```

## sorted_locks

`backend/src/main/java/nz/ac/ledgercore/ledger/LedgerWriter.java:169-180` · [固定提交源码](https://github.com/miku-wwl/ledgercore/blob/484b9ad1c70ba052849ee1a40e15e35b6d888c12/backend/src/main/java/nz/ac/ledgercore/ledger/LedgerWriter.java#L169-L180)

```text
	private void lockLedgerAccounts(List<UUID> ledgerAccountIds) {
		List<UUID> sortedIds = new ArrayList<>(ledgerAccountIds.stream().distinct().toList());
		sortedIds.sort(Comparator.naturalOrder());
		for (UUID id : sortedIds) {
			List<UUID> rows = jdbcTemplate.query("SELECT id FROM ledger_accounts WHERE id = ? FOR UPDATE",
					(resultSet, rowNumber) -> resultSet.getObject(1, UUID.class), id);
			if (rows.isEmpty()) {
				throw new IllegalArgumentException("Ledger account does not exist: " + id);
			}
		}
	}
```

## history_query

`backend/src/main/java/nz/ac/ledgercore/transactionhistory/TransactionHistoryService.java:32-44` · [固定提交源码](https://github.com/miku-wwl/ledgercore/blob/484b9ad1c70ba052849ee1a40e15e35b6d888c12/backend/src/main/java/nz/ac/ledgercore/transactionhistory/TransactionHistoryService.java#L32-L44)

```text
		String sql = """
				SELECT t.id, t.source_account_id, t.target_account_id, t.amount_minor, t.currency,
				       t.created_at, source.display_name AS source_name, target.display_name AS target_name
				FROM transfers t
				JOIN accounts current_account ON current_account.id = ? AND current_account.owner_customer_id = ?
				JOIN accounts source ON source.id = t.source_account_id
				JOIN accounts target ON target.id = t.target_account_id
				WHERE (t.source_account_id = ? OR t.target_account_id = ?)
				""";
		if (decoded != null) {
			sql += " AND (t.created_at, t.id) < (?, ?)";
		}
		sql += " ORDER BY t.created_at DESC, t.id DESC LIMIT ?";
```
