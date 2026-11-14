import { useState, type FormEvent } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Building2, Plus, Users, X } from 'lucide-react'
import {
  addOrganizationMember,
  createOrganization,
  createOrganizationAccount,
  getOrganizationAccounts,
  getOrganizationMembers,
  getOrganizations,
  revokeOrganizationMemberRole,
  type OrganizationRole,
} from '@/api/client'
import { Alert, AlertDescription, AlertTitle } from '@/components/ui/alert'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'

const roleOptions: { value: OrganizationRole; label: string }[] = [
  { value: 'ORG_ADMIN', label: 'Organization admin' },
  { value: 'PAYROLL_MAKER', label: 'Payroll maker' },
  { value: 'PAYROLL_APPROVER', label: 'Payroll approver' },
  { value: 'PAYMENT_MAKER', label: 'Payment maker' },
  { value: 'PAYMENT_APPROVER', label: 'Payment approver' },
  { value: 'FINANCE_VIEWER', label: 'Finance viewer' },
  { value: 'OPERATIONS', label: 'Operations' },
]

function errorMessage(error: unknown, fallback: string): string {
  return error instanceof Error ? error.message : fallback
}

export function OrganizationPage() {
  const queryClient = useQueryClient()
  const organizationsQuery = useQuery({ queryKey: ['organizations'], queryFn: getOrganizations })
  const organizations = organizationsQuery.data ?? []
  const [selectedId, setSelectedId] = useState('')
  const [organizationName, setOrganizationName] = useState('')
  const [accountName, setAccountName] = useState('')
  const [memberEmail, setMemberEmail] = useState('')
  const [memberRole, setMemberRole] = useState<OrganizationRole>('PAYROLL_MAKER')
  const organization = organizations.find((item) => item.id === selectedId) ?? organizations[0]
  const organizationId = organization?.id
  const roles = organization?.roles ?? []
  const isAdmin = roles.includes('ORG_ADMIN')

  const accountsQuery = useQuery({
    queryKey: ['organizations', organizationId, 'accounts'],
    queryFn: () => getOrganizationAccounts(organizationId as string),
    enabled: Boolean(organizationId),
  })
  const membersQuery = useQuery({
    queryKey: ['organizations', organizationId, 'members'],
    queryFn: () => getOrganizationMembers(organizationId as string),
    enabled: Boolean(organizationId && isAdmin),
  })

  const createOrganizationMutation = useMutation({
    mutationFn: createOrganization,
    onSuccess: async (created) => {
      setOrganizationName('')
      setSelectedId(created.id)
      await queryClient.invalidateQueries({ queryKey: ['organizations'] })
    },
  })
  const createAccountMutation = useMutation({
    mutationFn: (name: string) => createOrganizationAccount(organizationId as string, name),
    onSuccess: async () => {
      setAccountName('')
      await queryClient.invalidateQueries({ queryKey: ['organizations', organizationId, 'accounts'] })
      await queryClient.invalidateQueries({ queryKey: ['transfer-accounts'] })
    },
  })
  const addMemberMutation = useMutation({
    mutationFn: (input: { email: string; roles: OrganizationRole[] }) =>
      addOrganizationMember(organizationId as string, input),
    onSuccess: async () => {
      setMemberEmail('')
      await queryClient.invalidateQueries({ queryKey: ['organizations', organizationId, 'members'] })
      await queryClient.invalidateQueries({ queryKey: ['organizations'] })
    },
  })
  const revokeRoleMutation = useMutation({
    mutationFn: ({ customerId, role }: { customerId: string; role: OrganizationRole }) =>
      revokeOrganizationMemberRole(organizationId as string, customerId, role),
    onSuccess: async () => {
      await Promise.all([
        queryClient.invalidateQueries({ queryKey: ['organizations', organizationId, 'members'] }),
        queryClient.invalidateQueries({ queryKey: ['organizations'] }),
      ])
    },
  })

  function submitOrganization(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    if (organizationName.trim()) createOrganizationMutation.mutate(organizationName.trim())
  }

  function submitAccount(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    if (organizationId && accountName.trim()) createAccountMutation.mutate(accountName.trim())
  }

  function submitMember(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    if (organizationId && memberEmail.trim()) {
      addMemberMutation.mutate({ email: memberEmail.trim(), roles: [memberRole] })
    }
  }

  return (
    <div className="page-stack">
      <div className="page-heading">
        <p className="eyebrow">Enterprise banking</p>
        <h1>Organizations</h1>
        <p className="muted">Manage corporate membership and view organization-owned accounts.</p>
      </div>

      {organizationsQuery.isError && (
        <Alert variant="destructive" role="alert">
          <AlertTitle>Organizations could not load</AlertTitle>
          <AlertDescription>{errorMessage(organizationsQuery.error, 'Refresh and try again.')}</AlertDescription>
        </Alert>
      )}

      <Card>
        <CardHeader>
          <CardTitle>Set up an organization</CardTitle>
          <CardDescription>Your account becomes the organization administrator.</CardDescription>
        </CardHeader>
        <CardContent>
          <form className="grid gap-3 sm:grid-cols-[1fr_auto]" onSubmit={submitOrganization}>
            <div className="grid gap-2">
              <Label htmlFor="organization-name">Organization name</Label>
              <Input id="organization-name" maxLength={120} value={organizationName}
                onChange={(event) => setOrganizationName(event.target.value)} placeholder="Friday Payroll Ltd" />
            </div>
            <Button className="self-end" type="submit" disabled={!organizationName.trim() || createOrganizationMutation.isPending}>
              <Plus size={16} /> Create organization
            </Button>
          </form>
          {createOrganizationMutation.isError && (
            <p className="mt-3 text-sm text-red-700" role="alert">
              {errorMessage(createOrganizationMutation.error, 'Organization could not be created.')}
            </p>
          )}
        </CardContent>
      </Card>

      {organizations.length === 0 && !organizationsQuery.isLoading ? (
        <Card className="empty-card">
          <CardContent>
            <Building2 size={24} />
            <h3>No organization memberships yet</h3>
            <p>Create an organization or ask its administrator to add your customer account.</p>
          </CardContent>
        </Card>
      ) : organization ? (
        <>
          <Card>
            <CardHeader>
              <CardDescription>Your organization access</CardDescription>
              <CardTitle>{organization.displayName}</CardTitle>
            </CardHeader>
            <CardContent className="grid gap-5">
              {organizations.length > 1 && (
                <div className="grid max-w-md gap-2">
                  <Label htmlFor="organization-choice">Choose organization</Label>
                  <select id="organization-choice" value={organization.id}
                    onChange={(event) => setSelectedId(event.target.value)}>
                    {organizations.map((item) => <option key={item.id} value={item.id}>{item.displayName}</option>)}
                  </select>
                </div>
              )}
              <div className="flex flex-wrap items-center gap-2" aria-label="Your organization roles">
                <span className="text-sm font-medium">Your roles</span>
                {roles.map((role) => <Badge key={role} variant="secondary">{role.replaceAll('_', ' ')}</Badge>)}
              </div>
              {roles.includes('PAYROLL_MAKER') && (
                <p className="text-sm text-muted-foreground">Payroll Maker access allows you to prepare payroll batches. Independent approval is required before execution.</p>
              )}
              {(roles.includes('PAYMENT_MAKER') || roles.includes('PAYMENT_APPROVER')) && (
                <p className="text-sm text-muted-foreground">Corporate payment creation and approval are available in Payments. Maker and checker roles must be held by different members.</p>
              )}
              {roles.includes('FINANCE_VIEWER') && !roles.includes('PAYROLL_MAKER') && !isAdmin && (
                <p className="text-sm text-muted-foreground">Finance Viewer access is read-only. Debit and approval actions are not available.</p>
              )}
            </CardContent>
          </Card>

          <section className="grid gap-4">
            <div className="flex items-end justify-between gap-3">
              <div><p className="eyebrow">Organization accounts</p><h2 className="text-xl font-semibold">Balances</h2></div>
              <Badge variant="secondary">NZD simulation</Badge>
            </div>
            {accountsQuery.isError && (
              <Alert variant="destructive" role="alert">
                <AlertTitle>Accounts could not load</AlertTitle>
                <AlertDescription>{errorMessage(accountsQuery.error, 'Refresh and try again.')}</AlertDescription>
              </Alert>
            )}
            <div className="grid gap-3 md:grid-cols-2">
              {(accountsQuery.data ?? []).map((account) => (
                <Card key={account.id}>
                  <CardContent className="flex items-start justify-between gap-4 p-5">
                    <div><p className="font-semibold">{account.displayName}</p><p className="text-sm text-muted-foreground">{account.status}</p></div>
                    <strong className="text-lg">{'NZ$' + account.balance}</strong>
                  </CardContent>
                </Card>
              ))}
              {!accountsQuery.isLoading && (accountsQuery.data?.length ?? 0) === 0 && (
                <Card><CardContent className="p-5 text-sm text-muted-foreground">No corporate accounts are open yet.</CardContent></Card>
              )}
            </div>
          </section>

          {isAdmin && (
            <div className="grid gap-4 lg:grid-cols-2">
              <Card>
                <CardHeader><CardTitle>Create corporate account</CardTitle><CardDescription>Opening funds are posted through the simulation reserve.</CardDescription></CardHeader>
                <CardContent>
                  <form className="grid gap-3" onSubmit={submitAccount}>
                    <div className="grid gap-2"><Label htmlFor="corporate-account-name">Account name</Label>
                      <Input id="corporate-account-name" maxLength={80} value={accountName}
                        onChange={(event) => setAccountName(event.target.value)} placeholder="Payroll account" /></div>
                    <Button type="submit" disabled={!accountName.trim() || createAccountMutation.isPending}>
                      <Plus size={16} /> Open corporate account
                    </Button>
                  </form>
                  {createAccountMutation.isError && <p className="mt-3 text-sm text-red-700" role="alert">
                    {errorMessage(createAccountMutation.error, 'Corporate account could not be created.')}
                  </p>}
                </CardContent>
              </Card>

              <Card>
                <CardHeader><CardTitle><Users size={18} className="inline mr-2" />Manage members</CardTitle>
                  <CardDescription>Grant the minimum role needed for each job.</CardDescription></CardHeader>
                <CardContent className="grid gap-4">
                  <form className="grid gap-3" onSubmit={submitMember}>
                    <div className="grid gap-2"><Label htmlFor="member-email">Existing customer email</Label>
                      <Input id="member-email" type="email" value={memberEmail}
                        onChange={(event) => setMemberEmail(event.target.value)} placeholder="teammate@example.test" /></div>
                    <div className="grid gap-2"><Label htmlFor="member-role">Organization role</Label>
                      <select id="member-role" value={memberRole}
                        onChange={(event) => setMemberRole(event.target.value as OrganizationRole)}>
                        {roleOptions.map((option) => <option key={option.value} value={option.value}>{option.label}</option>)}
                      </select></div>
                    <Button type="submit" disabled={!memberEmail.trim() || addMemberMutation.isPending}>
                      <Plus size={16} /> Add member
                    </Button>
                  </form>
                  {addMemberMutation.isError && <p className="text-sm text-red-700" role="alert">
                    {errorMessage(addMemberMutation.error, 'Member could not be added.')}
                  </p>}
                  {revokeRoleMutation.isError && <p className="text-sm text-red-700" role="alert">
                    {errorMessage(revokeRoleMutation.error, 'Organization role could not be revoked.')}
                  </p>}
                  <div className="grid gap-3" aria-label="Organization members">
                    {(membersQuery.data ?? []).map((member) => (
                      <div key={member.customerId} className="flex flex-wrap items-center justify-between gap-2 border-t pt-3">
                        <div><p className="font-medium">{member.displayName}</p><p className="text-xs text-muted-foreground">{member.email}</p></div>
                        <div className="flex flex-wrap gap-2">{member.roles.map((role) => (
                          <span key={role} className="inline-flex items-center gap-1">
                            <Badge variant="outline">{role.replaceAll('_', ' ')}</Badge>
                            {isAdmin && member.customerId && <Button type="button" variant="ghost" size="icon-xs"
                              aria-label={`Revoke ${role.replaceAll('_', ' ')} from ${member.email}`}
                              title={`Revoke ${role.replaceAll('_', ' ')} from ${member.email}`}
                              disabled={revokeRoleMutation.isPending}
                              onClick={() => revokeRoleMutation.mutate({ customerId: member.customerId as string, role })}>
                              <X size={13} aria-hidden="true" />
                            </Button>}
                          </span>
                        ))}</div>
                      </div>
                    ))}
                  </div>
                </CardContent>
              </Card>
            </div>
          )}
        </>
      ) : null}
    </div>
  )
}
