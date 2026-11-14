package nz.ac.ledgercore.organizations;

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
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import nz.ac.ledgercore.accounts.AccountsQueryService.AccountView;
import nz.ac.ledgercore.identity.CustomerPrincipal;
import nz.ac.ledgercore.organizations.OrganizationService.MemberView;
import nz.ac.ledgercore.organizations.OrganizationService.OrganizationView;

@Validated
@RestController
@RequestMapping("/api/organizations")
public class OrganizationController {

    private final OrganizationService organizationService;

    public OrganizationController(OrganizationService organizationService) {
        this.organizationService = organizationService;
    }

    @Operation(operationId = "listOrganizations")
    @GetMapping
    List<OrganizationView> list(@AuthenticationPrincipal CustomerPrincipal principal) {
        return organizationService.listFor(principal.customerId());
    }

    @Operation(operationId = "createOrganization")
    @ApiResponse(responseCode = "201", description = "Organization created")
    @PostMapping
    ResponseEntity<OrganizationView> create(@AuthenticationPrincipal CustomerPrincipal principal,
            @Valid @RequestBody CreateOrganizationRequest request) {
        OrganizationView organization = organizationService.create(principal.customerId(), request.displayName());
        return ResponseEntity.created(URI.create("/api/organizations/" + organization.id())).body(organization);
    }

    @Operation(operationId = "listOrganizationAccounts")
    @GetMapping("/{organizationId}/accounts")
    List<AccountView> accounts(@AuthenticationPrincipal CustomerPrincipal principal,
            @PathVariable UUID organizationId) {
        return organizationService.accountsFor(principal.customerId(), organizationId);
    }

    @Operation(operationId = "openOrganizationAccount")
    @ApiResponse(responseCode = "201", description = "Corporate account created with simulated opening funds")
    @PostMapping("/{organizationId}/accounts")
    ResponseEntity<AccountView> createAccount(@AuthenticationPrincipal CustomerPrincipal principal,
            @PathVariable UUID organizationId, @Valid @RequestBody CreateAccountRequest request) {
        AccountView account = organizationService.createAccount(principal.customerId(), organizationId,
                request.displayName());
        return ResponseEntity.created(URI.create("/api/accounts/" + account.id())).body(account);
    }

    @Operation(operationId = "listOrganizationMembers")
    @GetMapping("/{organizationId}/members")
    List<MemberView> members(@AuthenticationPrincipal CustomerPrincipal principal,
            @PathVariable UUID organizationId) {
        return organizationService.membersFor(principal.customerId(), organizationId);
    }

    @Operation(operationId = "addOrganizationMember")
    @ApiResponse(responseCode = "201", description = "Organization member granted roles")
    @PostMapping("/{organizationId}/members")
    ResponseEntity<MemberView> addMember(@AuthenticationPrincipal CustomerPrincipal principal,
            @PathVariable UUID organizationId, @Valid @RequestBody AddMemberRequest request) {
        MemberView member = organizationService.addMember(principal.customerId(), organizationId, request.email(),
                request.roles());
        return ResponseEntity.created(URI.create("/api/organizations/" + organizationId + "/members/"
                + member.customerId())).body(member);
    }

    public record CreateOrganizationRequest(@NotBlank @Size(max = 120) String displayName) {
    }

    public record CreateAccountRequest(@NotBlank @Size(max = 80) String displayName) {
    }

    public record AddMemberRequest(@NotBlank @Email @Size(max = 254) String email,
            @NotEmpty List<OrganizationRole> roles) {
    }
}
