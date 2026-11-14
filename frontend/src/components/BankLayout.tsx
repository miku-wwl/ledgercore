import { useMutation, useQueryClient } from '@tanstack/react-query'
import { NavLink, Outlet, useNavigate } from 'react-router-dom'
import { Activity, ArrowLeftRight, Building2, LayoutDashboard, Landmark, LogOut, WalletCards, Banknote } from 'lucide-react'
import { Button } from '@/components/ui/button'
import { signOut, type Customer } from '@/api/client'

export function BankLayout({ customer }: { customer: Customer }) {
  const queryClient = useQueryClient()
  const navigate = useNavigate()
  const logout = useMutation({
    mutationFn: signOut,
    onSuccess: () => {
      queryClient.setQueryData(['me'], null)
      void queryClient.removeQueries({ predicate: (query) => query.queryKey[0] !== 'me' })
      navigate('/', { replace: true })
    },
  })

  const links = [
    { to: '/', label: 'Overview', icon: LayoutDashboard, end: true },
    { to: '/transfer', label: 'Transfer', icon: ArrowLeftRight },
    { to: '/history', label: 'History', icon: Activity },
    { to: '/organizations', label: 'Organizations', icon: Building2 },
    { to: '/payroll', label: 'Payroll', icon: Banknote },
  ]

  return (
    <div className="app-shell">
      <aside className="sidebar">
        <NavLink to="/" className="brand-lockup" aria-label="LedgerCore home">
          <span className="brand-mark"><Landmark size={19} aria-hidden="true" /></span>
          <span>LedgerCore</span>
        </NavLink>
        <div className="simulation-tag"><span className="status-dot" /> SIMULATION MODE</div>
        <nav className="side-nav" aria-label="Main navigation">
          <p className="nav-caption">YOUR BANK</p>
          {links.map(({ to, label, icon: Icon, end }) => (
            <NavLink key={to} to={to} end={end} className={({ isActive }) => `nav-link${isActive ? ' active' : ''}`}>
              <Icon size={18} aria-hidden="true" /> <span>{label}</span>
            </NavLink>
          ))}
        </nav>
        <div className="sidebar-bottom">
          <div className="learning-note"><span className="learning-icon"><WalletCards size={17} /></span><div><strong>Ledger learning</strong><small>Every movement has a record.</small></div></div>
          <div className="user-row">
            <span className="avatar" aria-hidden="true">{customer.displayName.slice(0, 1).toUpperCase()}</span>
            <span className="user-label"><strong>{customer.displayName}</strong><small>{customer.email}</small></span>
            <Button variant="ghost" size="icon" aria-label="Sign out" title="Sign out" disabled={logout.isPending} onClick={() => logout.mutate()}>
              <LogOut size={17} aria-hidden="true" />
            </Button>
          </div>
        </div>
      </aside>
      <div className="main-column">
        <header className="topbar">
          <span className="topbar-title">Personal banking</span>
          <span className="topbar-user">Signed in as <strong>{customer.displayName}</strong></span>
        </header>
        <main className="main-content"><Outlet /></main>
      </div>
      <nav className="mobile-nav" aria-label="Mobile navigation">
        {links.map(({ to, label, icon: Icon, end }) => (
          <NavLink key={to} to={to} end={end} className={({ isActive }) => `mobile-nav-link${isActive ? ' active' : ''}`}>
            <Icon size={19} aria-hidden="true" /><span>{label}</span>
          </NavLink>
        ))}
      </nav>
    </div>
  )
}
