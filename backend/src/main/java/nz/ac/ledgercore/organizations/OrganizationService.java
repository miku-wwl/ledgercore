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
import nz.ac.ledgercore.events.DurableEventStore;

@Service
public class OrganizationService {

    private final JdbcTemplate jdbcTemplate;
    private final AccountService accountService;
    private final AccountsQueryService accountsQueryService;
    private final OrganizationAuthorizationService authorizationService;
    private final DurableEventStore eventStore;

    public OrganizationService(JdbcTemplate jdbcTemplate, AccountService accountService,
            AccountsQueryService accountsQueryService, OrganizationAuthorizationService authorizationService,
            DurableEventStore eventStore) {
        this.jdbcTemplate = jdbcTemplate;
        this.accountService = accountService;
        this.accountsQueryService = accountsQueryService;
        this.authorizationService = authorizationService;
        this.eventStore = eventStore;
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
        eventStore.recordAudit(actorCustomerId, "ORGANIZATION_CREATED", "ORGANIZATION", organizationId,
                organizationId, Map.of("displayName", name));
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
        if (actorCustomerId.equals(customer.id())) {
            throw new OrganizationAuthorizationException("An administrator cannot grant roles to their own membership.");
        }
        List<String> existingStatuses = jdbcTemplate.query("""
                SELECT status FROM organization_memberships WHERE organization_id = ? AND customer_id = ? FOR UPDATE
                """, (rs, row) -> rs.getString(1), organizationId, customer.id());
        if (existingStatuses.contains("SUSPENDED")) {
            throw new OrganizationAuthorizationException(
                    "A suspended membership requires an explicit, audited reactivation action.");
        }
        if (existingStatuses.isEmpty()) {
            jdbcTemplate.update("""
                    INSERT INTO organization_memberships (organization_id, customer_id, status)
                    VALUES (?, ?, 'ACTIVE')
                    """, organizationId, customer.id());
            eventStore.recordAudit(actorCustomerId, "ORGANIZATION_MEMBER_ADDED", "ORGANIZATION_MEMBER",
                    customer.id(), organizationId, Map.of());
        }
        for (OrganizationRole role : roles.stream().distinct().toList()) {
            if (grantRole(organizationId, customer.id(), role)) {
                recordRoleChange(actorCustomerId, organizationId, customer.id(), role, "GRANTED");
            }
        }
        return memberView(organizationId, customer);
    }

    @Transactional
    public MemberView grantRole(UUID actorCustomerId, UUID organizationId, UUID memberCustomerId,
            OrganizationRole role) {
        authorizationService.requireCanManage(actorCustomerId, organizationId);
        if (actorCustomerId.equals(memberCustomerId)) {
            throw new OrganizationAuthorizationException("An administrator cannot grant a role to their own membership.");
        }
        requireActiveMembership(organizationId, memberCustomerId);
        if (grantRole(organizationId, memberCustomerId, role)) {
            recordRoleChange(actorCustomerId, organizationId, memberCustomerId, role, "GRANTED");
        }
        return memberViewForId(organizationId, memberCustomerId);
    }

    @Transactional
    public MemberView revokeRole(UUID actorCustomerId, UUID organizationId, UUID memberCustomerId,
            OrganizationRole role) {
        authorizationService.requireCanManage(actorCustomerId, organizationId);
        requireActiveMembership(organizationId, memberCustomerId);
        int removed = jdbcTemplate.update("""
                DELETE FROM organization_membership_roles
                WHERE organization_id = ? AND customer_id = ? AND role = ?
                """, organizationId, memberCustomerId, role.name());
        if (removed == 1) {
            recordRoleChange(actorCustomerId, organizationId, memberCustomerId, role, "REVOKED");
        }
        return memberViewForId(organizationId, memberCustomerId);
    }

    @Transactional
    public MemberView suspendMember(UUID actorCustomerId, UUID organizationId, UUID memberCustomerId) {
        authorizationService.requireCanManage(actorCustomerId, organizationId);
        if (actorCustomerId.equals(memberCustomerId)) {
            throw new OrganizationAuthorizationException("An administrator cannot suspend their own membership.");
        }
        int updated = jdbcTemplate.update("""
                UPDATE organization_memberships SET status = 'SUSPENDED'
                WHERE organization_id = ? AND customer_id = ? AND status = 'ACTIVE'
                """, organizationId, memberCustomerId);
        if (updated != 1) {
            throw new OrganizationAuthorizationException("An active organization member was not found.");
        }
        eventStore.recordAudit(actorCustomerId, "ORGANIZATION_MEMBER_SUSPENDED", "ORGANIZATION_MEMBER",
                memberCustomerId, organizationId, Map.of());
        eventStore.enqueue("organization.member.suspended:" + organizationId + ":" + memberCustomerId,
                "organization.member.suspended", "ORGANIZATION_MEMBER", memberCustomerId, actorCustomerId,
                organizationId, Map.of("customerId", memberCustomerId.toString()));
        return memberViewForId(organizationId, memberCustomerId);
    }

    @Transactional
    public MemberView reactivateMember(UUID actorCustomerId, UUID organizationId, UUID memberCustomerId) {
        authorizationService.requireCanManage(actorCustomerId, organizationId);
        if (actorCustomerId.equals(memberCustomerId)) {
            throw new OrganizationAuthorizationException("An administrator cannot reactivate their own membership.");
        }
        int updated = jdbcTemplate.update("""
                UPDATE organization_memberships SET status = 'ACTIVE'
                WHERE organization_id = ? AND customer_id = ? AND status = 'SUSPENDED'
                """, organizationId, memberCustomerId);
        if (updated != 1) {
            throw new OrganizationAuthorizationException("A suspended organization member was not found.");
        }
        eventStore.recordAudit(actorCustomerId, "ORGANIZATION_MEMBER_REACTIVATED", "ORGANIZATION_MEMBER",
                memberCustomerId, organizationId, Map.of());
        eventStore.enqueue("organization.member.reactivated:" + organizationId + ":" + memberCustomerId,
                "organization.member.reactivated", "ORGANIZATION_MEMBER", memberCustomerId, actorCustomerId,
                organizationId, Map.of("customerId", memberCustomerId.toString()));
        return memberViewForId(organizationId, memberCustomerId);
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

    private boolean grantRole(UUID organizationId, UUID customerId, OrganizationRole role) {
        return jdbcTemplate.update("""
                INSERT INTO organization_membership_roles (organization_id, customer_id, role)
                VALUES (?, ?, ?)
                ON CONFLICT (organization_id, customer_id, role) DO NOTHING
                """, organizationId, customerId, role.name()) == 1;
    }

    private void requireActiveMembership(UUID organizationId, UUID customerId) {
        Integer active = jdbcTemplate.queryForObject("""
                SELECT count(*) FROM organization_memberships
                WHERE organization_id = ? AND customer_id = ? AND status = 'ACTIVE'
                """, Integer.class, organizationId, customerId);
        if (active == null || active != 1) {
            throw new OrganizationAuthorizationException("An active organization member was not found.");
        }
    }

    private void recordRoleChange(UUID actorCustomerId, UUID organizationId, UUID memberCustomerId,
            OrganizationRole role, String change) {
        eventStore.recordAudit(actorCustomerId, "ORGANIZATION_ROLE_" + change, "ORGANIZATION_MEMBER",
                memberCustomerId, organizationId, Map.of("role", role.name()));
        eventStore.enqueue("organization.role." + change.toLowerCase(Locale.ROOT) + ":" + organizationId + ":"
                + memberCustomerId + ":" + role.name() + ":" + UUID.randomUUID(),
                "organization.role." + change.toLowerCase(Locale.ROOT), "ORGANIZATION_MEMBER", memberCustomerId,
                actorCustomerId, organizationId, Map.of("role", role.name()));
    }

    private MemberView memberViewForId(UUID organizationId, UUID customerId) {
        List<CustomerRow> rows = jdbcTemplate.query("""
                SELECT id, display_name, email_normalized FROM customers WHERE id = ?
                """, (rs, row) -> new CustomerRow(rs.getObject("id", UUID.class), rs.getString("display_name"),
                rs.getString("email_normalized")), customerId);
        if (rows.isEmpty()) {
            throw new ApiNotFoundException("Customer was not found.");
        }
        return memberView(organizationId, rows.getFirst());
    }

    private record CustomerRow(UUID id, String displayName, String email) {
    }

    public record OrganizationView(UUID id, String displayName, List<OrganizationRole> roles) {
    }

    public record MemberView(UUID customerId, String displayName, String email, List<OrganizationRole> roles) {
    }
}
