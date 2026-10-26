import { useState, type FormEvent } from 'react';
import { Link, useNavigate, useSearchParams } from 'react-router-dom';
import { useAuth } from '../auth';
import { ErrorBox } from '../components/Common';

export function homeFor(role: string) {
  return role === 'STUDENT' ? '/' : role === 'ADMIN' ? '/admin' : '/manage';
}

// only allow redirects to our own pages, otherwise someone could send people to a random site after login
export function safeNext(next: string | null): string | null {
  return next && next.startsWith('/') && !next.startsWith('//') ? next : null;
}

export default function Login() {
  const { login } = useAuth();
  const nav = useNavigate();
  const [params] = useSearchParams();
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  async function submit(e: FormEvent) {
    e.preventDefault();
    setBusy(true);
    setError(null);
    try {
      const u = await login(email, password);
      nav(safeNext(params.get('next')) ?? homeFor(u.role));
    } catch (err) { setError((err as Error).message); } finally { setBusy(false); }
  }

  return (
    <div className="container auth-box">
      <form className="card" onSubmit={submit}>
        <h1>Log in</h1>
        <ErrorBox error={error} />
        <label className="field"><span>Email</span>
          <input type="email" autoComplete="email" required value={email} onChange={e => setEmail(e.target.value)} /></label>
        <label className="field"><span>Password</span>
          <input type="password" autoComplete="current-password" required value={password} onChange={e => setPassword(e.target.value)} /></label>
        <button className="btn btn-primary btn-block" disabled={busy}>{busy ? 'Logging in…' : 'Log in'}</button>
        <p className="small muted">No account? <Link to={`/register${params.get('next') ? `?next=${encodeURIComponent(params.get('next')!)}` : ''}`}>Sign up</Link></p>
      </form>
    </div>
  );
}
