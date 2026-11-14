package nz.ac.ledgercore.organizations;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import nz.ac.ledgercore.accounts.AccountOwner;
import nz.ac.ledgercore.accounts.AccountService;
import nz.ac.ledgercore.api.ApiNotFoundException;

@Service
public class OrganizationAuthorizationService {

    private final JdbcTemplate jdbcTemplate;
    private final AccountService accountService;

    public OrganizationAuthorizationService(JdbcTemplate jdbcTemplate, AccountService accountService) {
        this.jdbcTemplate = jdbcTemplate;
        this.accountService = accountService;
    }

    @Transactional(readOnly = true)
    public List<OrganizationRole> rolesFor(UUID customerId, UUID organizationId) {
        return jdbcTemplate.query("""
                SELECT r.role
                FROM organization_memberships m
                JOIN organization_membership_roles r
                  ON r.organization_id = m.organization_id AND r.customer_id = m.customer_id
                JOIN organizations o ON o.id = m.organization_id AND o.status = 'ACTIVE'
                WHERE m.organization_id = ? AND m.customer_id = ? AND m.status = 'ACTIVE'
                ORDER BY r.role
                """, (rs, row) -> OrganizationRole.valueOf(rs.getString(1)), organizationId, customerId);
    }

    @Transactional(readOnly = true)
    public void requireMember(UUID customerId, UUID organizationId) {
        if (rolesFor(customerId, organizationId).isEmpty()) {
            throw new ApiNotFoundException("Organization was not found.");
        }
    }

    @Transactional(readOnly = true)
    public void requireRole(UUID customerId, UUID organizationId, OrganizationRole requiredRole) {
        List<OrganizationRole> roles = rolesFor(customerId, organizationId);
        if (roles.isEmpty()) {
            throw new ApiNotFoundException("Organization was not found.");
        }
        if (!roles.contains(requiredRole)) {
            throw new OrganizationAuthorizationException("This organization role is required for the requested action.");
        }
    }

    @Transactional(readOnly = true)
    public void requireCanViewAccounts(UUID customerId, UUID organizationId) {
        requireMember(customerId, organizationId);
    }

    @Transactional(readOnly = true)
    public void requireCanPreparePayroll(UUID customerId, UUID organizationId) {
        requireRole(customerId, organizationId, OrganizationRole.PAYROLL_MAKER);
    }

    @Transactional(readOnly = true)
    public void requireCanApprovePayroll(UUID approverCustomerId, UUID organizationId, UUID makerCustomerId) {
        requireRole(approverCustomerId, organizationId, OrganizationRole.PAYROLL_APPROVER);
        if (approverCustomerId.equals(makerCustomerId)) {
            throw new OrganizationAuthorizationException("A payroll maker cannot approve their own batch.");
        }
    }

    @Transactional(readOnly = true)
    public void requireCanExecutePayroll(UUID customerId, UUID organizationId) {
        List<OrganizationRole> roles = rolesFor(customerId, organizationId);
        if (roles.isEmpty()) {
            throw new ApiNotFoundException("Organization was not found.");
        }
        if (!roles.contains(OrganizationRole.PAYROLL_APPROVER) && !roles.contains(OrganizationRole.OPERATIONS)) {
            throw new OrganizationAuthorizationException(
                    "Payroll execution requires the PAYROLL_APPROVER or OPERATIONS role.");
        }
    }

    @Transactional(readOnly = true)
    public void requireCanManage(UUID customerId, UUID organizationId) {
        requireRole(customerId, organizationId, OrganizationRole.ORG_ADMIN);
    }

    @Transactional(readOnly = true)
    public boolean canDebitAccount(UUID customerId, UUID accountId) {
        Optional<AccountOwner> owner = accountService.ownerOf(accountId);
        if (owner.isEmpty()) {
            return false;
        }
        AccountOwner accountOwner = owner.get();
        if (accountOwner.customerId() != null) {
            return accountOwner.customerId().equals(customerId);
        }
        return rolesFor(customerId, accountOwner.organizationId()).contains(OrganizationRole.PAYROLL_MAKER);
    }
}
