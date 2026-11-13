import { useState, type FormEvent } from 'react'
import { ArrowUpRight, Landmark, LockKeyhole, ShieldCheck } from 'lucide-react'
import { Alert, AlertDescription, AlertTitle } from '@/components/ui/alert'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { ApiError, register, signIn, type Customer } from '@/api/client'

type AuthPageProps = { onAuthenticated: (customer: Customer) => void }

export function AuthPage({ onAuthenticated }: AuthPageProps) {
  const [mode, setMode] = useState<'signin' | 'register'>('signin')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')

  async function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    setError('')
    setBusy(true)
    const form = new FormData(event.currentTarget)
    const email = String(form.get('email') ?? '').trim()
    const password = String(form.get('password') ?? '')
    try {
      const customer = mode === 'register'
        ? await register({ email, password, displayName: String(form.get('displayName') ?? '').trim() })
        : await signIn({ email, password })
      onAuthenticated(customer)
    } catch (cause) {
      setError(cause instanceof ApiError ? cause.message : 'We could not reach LedgerCore. Try again shortly.')
    } finally {
      setBusy(false)
    }
  }

  return (
    <main className="auth-page">
      <section className="auth-story" aria-label="LedgerCore introduction">
        <div className="brand-lockup brand-lockup-light">
          <span className="brand-mark"><Landmark size={21} aria-hidden="true" /></span>
          <span>LedgerCore</span>
        </div>
        <div className="story-copy">
          <p className="eyebrow eyebrow-light">A banking simulation</p>
          <h1>Money should move with a clear story.</h1>
          <p>Explore accounts, double-entry records and reliable transfers in a safe NZD-only learning environment.</p>
        </div>
        <div className="story-proof">
          <span><ShieldCheck size={18} aria-hidden="true" /> Every transfer leaves a balanced record</span>
          <span><LockKeyhole size={18} aria-hidden="true" /> Your accounts stay behind your sign-in</span>
        </div>
        <div className="story-orbit orbit-one" aria-hidden="true" />
        <div className="story-orbit orbit-two" aria-hidden="true" />
      </section>

      <section className="auth-panel">
        <div className="auth-panel-head">
          <p className="eyebrow">Welcome to your demo bank</p>
          <h2>{mode === 'signin' ? 'Sign in to continue' : 'Create your account'}</h2>
          <p className="muted">{mode === 'signin' ? 'Your accounts and activity are stored in the local LedgerCore database.' : 'A new customer starts with no accounts. You can open one after signing in.'}</p>
        </div>

        {error && <Alert variant="destructive" role="alert"><AlertTitle>Could not sign you in</AlertTitle><AlertDescription>{error}</AlertDescription></Alert>}

        <form className="auth-form" onSubmit={handleSubmit}>
          {mode === 'register' && <div className="form-field">
            <Label htmlFor="displayName">Your name</Label>
            <Input id="displayName" name="displayName" autoComplete="name" maxLength={100} required />
          </div>}
          <div className="form-field">
            <Label htmlFor="email">Email</Label>
            <Input id="email" name="email" type="email" autoComplete="email" maxLength={254} required />
          </div>
          <div className="form-field">
            <div className="label-row"><Label htmlFor="password">Password</Label>{mode === 'register' && <span className="field-hint">12 characters or more</span>}</div>
            <Input id="password" name="password" type="password" autoComplete={mode === 'signin' ? 'current-password' : 'new-password'} minLength={mode === 'register' ? 12 : undefined} maxLength={128} required />
          </div>
          <Button className="auth-submit" type="submit" disabled={busy}>
            {busy ? 'Working…' : mode === 'signin' ? 'Sign in' : 'Create account'}
            {!busy && <ArrowUpRight size={17} aria-hidden="true" />}
          </Button>
        </form>

        <p className="auth-switch">
          {mode === 'signin' ? 'New to LedgerCore?' : 'Already have a profile?'}{' '}
          <button type="button" onClick={() => { setMode(mode === 'signin' ? 'register' : 'signin'); setError('') }}>
            {mode === 'signin' ? 'Create an account' : 'Sign in'}
          </button>
        </p>
        <p className="auth-disclaimer">This is simulated money only. No bank, payment network or real funds are connected.</p>
      </section>
    </main>
  )
}
