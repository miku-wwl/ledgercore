import { QueryClient, QueryClientProvider, useQuery, useQueryClient } from '@tanstack/react-query'
import { BrowserRouter, Navigate, Route, Routes } from 'react-router-dom'
import { AlertCircle, Landmark } from 'lucide-react'
import { Alert, AlertDescription, AlertTitle } from '@/components/ui/alert'
import { Button } from '@/components/ui/button'
import { Skeleton } from '@/components/ui/skeleton'
import { BankLayout } from '@/components/BankLayout'
import { getCurrentCustomer, type Customer } from '@/api/client'
import { AccountPage } from '@/pages/AccountPage'
import { AuthPage } from '@/pages/AuthPage'
import { DashboardPage } from '@/pages/DashboardPage'
import { HistoryPage } from '@/pages/HistoryPage'
import { TransferPage } from '@/pages/TransferPage'
import './App.css'

const queryClient = new QueryClient({
  defaultOptions: {
    queries: { staleTime: 20_000, retry: 1, refetchOnWindowFocus: false },
    mutations: { retry: false },
  },
})

function SessionGate() {
  const queryClient = useQueryClient()
  const session = useQuery({ queryKey: ['me'], queryFn: getCurrentCustomer, retry: false, staleTime: 30_000 })

  function authenticated(customer: Customer) {
    queryClient.setQueryData(['me'], customer)
    void queryClient.invalidateQueries({ queryKey: ['accounts'] })
  }

  if (session.isLoading) return <main className="boot-screen"><div className="boot-brand"><span className="brand-mark"><Landmark size={20} /></span> LedgerCore</div><Skeleton className="boot-skeleton" /><Skeleton className="boot-skeleton short" /></main>
  if (session.isError) return <main className="boot-screen"><Alert variant="destructive"><AlertCircle size={18} /><div><AlertTitle>LedgerCore is unavailable</AlertTitle><AlertDescription>We could not check your session. The API may still be starting.</AlertDescription></div></Alert><Button onClick={() => void session.refetch()}>Try again</Button></main>
  if (!session.data) return <AuthPage onAuthenticated={authenticated} />

  return (
    <Routes>
      <Route element={<BankLayout customer={session.data} />}>
        <Route path="/" element={<DashboardPage />} />
        <Route path="/transfer" element={<TransferPage />} />
        <Route path="/history" element={<HistoryPage />} />
        <Route path="/accounts/:accountId" element={<AccountPage />} />
        <Route path="*" element={<Navigate to="/" replace />} />
      </Route>
    </Routes>
  )
}

function App() {
  return <QueryClientProvider client={queryClient}><BrowserRouter><SessionGate /></BrowserRouter></QueryClientProvider>
}

export default App
