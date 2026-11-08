import { useState, type FormEvent } from 'react';
import { useNavigate } from 'react-router-dom';
import { useAuth } from '../auth';
import { ErrorBox } from '../ui';

export default function LoginPage() {
  const { login, register, user } = useAuth();
  const navigate = useNavigate();
  const [mode, setMode] = useState<'login' | 'register'>('login');
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [displayName, setDisplayName] = useState('');
  const [error, setError] = useState<unknown>(null);
  const [busy, setBusy] = useState(false);

  async function submit(e: FormEvent) {
    e.preventDefault();
    setBusy(true);
    setError(null);
    try {
      if (mode === 'login') await login(email, password);
      else await register(email, password, displayName);
      navigate('/');
    } catch (err) {
      setError(err);
    } finally {
      setBusy(false);
    }
  }

  function fill(e: string, p: string) {
    setMode('login');
    setEmail(e);
    setPassword(p);
  }

  return (
    <div style={{ maxWidth: 420 }}>
      <h1>{mode === 'login' ? 'Log in' : 'Create an account'}</h1>
      {user && <div className="box info">You are logged in as {user.email}. Logging in again replaces the session.</div>}
      <form className="card" onSubmit={submit}>
        <div className="row">
          <label>Email<input type="email" value={email} onChange={(e) => setEmail(e.target.value)} required /></label>
        </div>
        {mode === 'register' && (
          <div className="row">
            <label>Display name<input value={displayName} onChange={(e) => setDisplayName(e.target.value)} required /></label>
          </div>
        )}
        <div className="row">
          <label>Password {mode === 'register' && '(8-72 characters)'}
            <input type="password" value={password} onChange={(e) => setPassword(e.target.value)} required />
          </label>
        </div>
        <ErrorBox error={error} />
        <button className="primary" disabled={busy}>{mode === 'login' ? 'Log in' : 'Register and log in'}</button>{' '}
        <button type="button" onClick={() => setMode(mode === 'login' ? 'register' : 'login')}>
          {mode === 'login' ? 'I need an account' : 'I have an account'}
        </button>
      </form>
      <div className="card">
        <b>Demo accounts</b> <span className="muted">(only with the backend in the <code>dev</code> profile)</span>
        <div className="row">
          <button onClick={() => fill('customer@ticketflow.local', 'customer123')}>Customer</button>
          <button onClick={() => fill('organizer@ticketflow.local', 'organizer123')}>Organizer</button>
          <button onClick={() => fill('admin@ticketflow.local', 'admin12345')}>Admin</button>
        </div>
        <p className="muted">New accounts are always customers. An admin can make a user an organizer (Admin page).</p>
      </div>
    </div>
  );
}
