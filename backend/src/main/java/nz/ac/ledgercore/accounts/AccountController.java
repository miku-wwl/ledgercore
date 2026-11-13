package nz.ac.ledgercore.accounts;

import java.net.URI;
import java.util.List;
import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import nz.ac.ledgercore.api.ApiNotFoundException;
import nz.ac.ledgercore.identity.CustomerPrincipal;

@Validated
@RestController
@RequestMapping("/api/accounts")
public class AccountController {

	private final AccountService accountService;
	private final AccountsQueryService accountsQueryService;

	public AccountController(AccountService accountService, AccountsQueryService accountsQueryService) {
		this.accountService = accountService;
		this.accountsQueryService = accountsQueryService;
	}

	@GetMapping
	List<AccountsQueryService.AccountView> list(@AuthenticationPrincipal CustomerPrincipal principal) {
		return accountsQueryService.listForOwner(principal.customerId());
	}

	@PostMapping
	@ApiResponse(responseCode = "201", description = "Account created with simulated opening funds")
	ResponseEntity<AccountsQueryService.AccountView> create(@AuthenticationPrincipal CustomerPrincipal principal,
			@Valid @RequestBody CreateAccountRequest request) {
		UUID accountId = accountService.openAccount(principal.customerId(), request.displayName());
		AccountsQueryService.AccountView account = accountsQueryService.findForOwner(principal.customerId(), accountId)
				.orElseThrow(() -> new IllegalStateException("New account was not visible after creation."));
		return ResponseEntity.created(URI.create("/api/accounts/" + accountId)).body(account);
	}

	@GetMapping("/{accountId}")
	AccountsQueryService.AccountView get(@AuthenticationPrincipal CustomerPrincipal principal,
			@PathVariable UUID accountId) {
		return accountsQueryService.findForOwner(principal.customerId(), accountId)
				.orElseThrow(() -> new ApiNotFoundException("Account was not found."));
	}

	public record CreateAccountRequest(@NotBlank @Size(max = 80) String displayName) {
	}
}
