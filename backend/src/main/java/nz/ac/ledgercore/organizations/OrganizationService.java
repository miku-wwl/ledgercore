package nz.ac.ledgercore.organizations;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import nz.ac.ledgercore.accounts.AccountService;
import nz.ac.ledgercore.accounts.AccountsQueryService;
import nz.ac.ledgercore.accounts.AccountsQueryService.AccountView;
import nz.ac.ledgercore.api.ApiNotFoundException;

@Service
public class OrganizationService {

    private final JdbcTemplate jdbcTemplate;
    private final AccountService accountService;
    private final AccountsQueryService accountsQueryService;
    private final OrganizationAuthorizationService authorizationService;

    public OrganizationService(JdbcTemplate jdbcTemplate, AccountService accountService,
            AccountsQueryService accountsQueryService, OrganizationAuthorizationService authorizationService) {
        this.jdbcTemplate = jdbcTemplate;
        this.accountService = accountService;
        this.accountsQueryService = accountsQueryService;
        this.authorizationService = authorizationService;
    }

    @Transactional
    public OrganizationView create(UUID actorCustomerId, String displayName) {
        String name = displayName == null ? "" : displayName.trim();
        if (name.isBlank() || name.length() > 120) {
            throw new IllegalArgumentException("Organization name must contain 1 to 120 characters.");
        }
        UUID organizationId = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO organizations (id, display_name) VALUES (?, ?)", organizationId, name);
        jdbcTemplate.update("""
                INSERT INTO organization_memberships (organization_id, customer_id)
                VALUES (?, ?)
                """, organizationId, actorCustomerId);
        grantRole(organizationId, actorCustomerId, OrganizationRole.ORG_ADMIN);
        return new OrganizationView(organizationId, name, List.of(OrganizationRole.ORG_ADMIN));
    }

    @Transactional(readOnly = true)
    public List<OrganizationView> listFor(UUID actorCustomerId) {
        List<UUID> ids = jdbcTemplate.query("""
                SELECT o.id
                FROM organizations o
                JOIN organization_memberships m ON m.organization_id = o.id
                WHERE m.customer_id = ? AND m.status = 'ACTIVE' AND o.status = 'ACTIVE'
                ORDER BY o.display_name, o.id
                """, (rs, row) -> rs.getObject(1, UUID.class), actorCustomerId);
        List<OrganizationView> result = new ArrayList<>();
        for (UUID id : ids) {
            result.add(viewFor(actorCustomerId, id));
        }
        return result;
    }

    @Transactional(readOnly = true)
    public List<AccountView> accountsFor(UUID actorCustomerId, UUID organizationId) {
        authorizationService.requireCanViewAccounts(actorCustomerId, organizationId);
        return accountsQueryService.listForOrganization(organizationId);
    }

    @Transactional
    public AccountView createAccount(UUID actorCustomerId, UUID organizationId, String displayName) {
        authorizationService.requireCanManage(actorCustomerId, organizationId);
        UUID accountId = accountService.openOrganizationAccount(organizationId, displayName);
        return accountsQueryService.findForOrganization(organizationId, accountId)
                .orElseThrow(() -> new IllegalStateException("New corporate account was not visible after creation."));
    }

    @Transactional
    public MemberView addMember(UUID actorCustomerId, UUID organizationId, String email,
            List<OrganizationRole> roles) {
        authorizationService.requireCanManage(actorCustomerId, organizationId);
        if (roles == null || roles.isEmpty()) {
            throw new IllegalArgumentException("At least one organization role is required.");
        }
        String normalizedEmail = email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
        List<CustomerRow> customers = jdbcTemplate.query("""
                SELECT id, display_name, email_normalized
                FROM customers
                WHERE email_normalized = ?
                """, (rs, row) -> new CustomerRow(rs.getObject("id", UUID.class), rs.getString("display_name"),
                rs.getString("email_normalized")), normalizedEmail);
        if (customers.isEmpty()) {
            throw new ApiNotFoundException("Customer was not found.");
        }
        CustomerRow customer = customers.getFirst();
        jdbcTemplate.update("""
                INSERT INTO organization_memberships (organization_id, customer_id, status)
                VALUES (?, ?, 'ACTIVE')
                ON CONFLICT (organization_id, customer_id) DO UPDATE SET status = 'ACTIVE'
                """, organizationId, customer.id());
        roles.stream().distinct().forEach(role -> grantRole(organizationId, customer.id(), role));
        return memberView(organizationId, customer);
    }

    @Transactional(readOnly = true)
    public List<MemberView> membersFor(UUID actorCustomerId, UUID organizationId) {
        authorizationService.requireCanManage(actorCustomerId, organizationId);
        List<CustomerRow> customers = jdbcTemplate.query("""
                SELECT c.id, c.display_name, c.email_normalized
                FROM organization_memberships m
                JOIN customers c ON c.id = m.customer_id
                WHERE m.organization_id = ? AND m.status = 'ACTIVE'
                ORDER BY c.display_name, c.id
                """, (rs, row) -> new CustomerRow(rs.getObject("id", UUID.class), rs.getString("display_name"),
                rs.getString("email_normalized")), organizationId);
        return customers.stream().map(customer -> memberView(organizationId, customer)).toList();
    }

    private OrganizationView viewFor(UUID actorCustomerId, UUID organizationId) {
        List<OrganizationView> rows = jdbcTemplate.query("""
                SELECT id, display_name FROM organizations WHERE id = ?
                """, (rs, row) -> new OrganizationView(rs.getObject("id", UUID.class),
                rs.getString("display_name"), authorizationService.rolesFor(actorCustomerId, organizationId)),
                organizationId);
        if (rows.isEmpty()) {
            throw new ApiNotFoundException("Organization was not found.");
        }
        return rows.getFirst();
    }

    private MemberView memberView(UUID organizationId, CustomerRow customer) {
        return new MemberView(customer.id(), customer.displayName(), customer.email(),
                authorizationService.rolesFor(customer.id(), organizationId));
    }

    private void grantRole(UUID organizationId, UUID customerId, OrganizationRole role) {
        jdbcTemplate.update("""
                INSERT INTO organization_membership_roles (organization_id, customer_id, role)
                VALUES (?, ?, ?)
                ON CONFLICT (organization_id, customer_id, role) DO NOTHING
                """, organizationId, customerId, role.name());
    }

    private record CustomerRow(UUID id, String displayName, String email) {
    }

    public record OrganizationView(UUID id, String displayName, List<OrganizationRole> roles) {
    }

    public record MemberView(UUID customerId, String displayName, String email, List<OrganizationRole> roles) {
    }
}
