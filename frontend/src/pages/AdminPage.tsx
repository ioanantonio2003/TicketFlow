import { useState, type FormEvent } from 'react';
import { api } from '../api';
import type { Role, User } from '../types';
import { ErrorBox, InfoBox } from '../ui';

export default function AdminPage() {
  const [userId, setUserId] = useState('');
  const [role, setRole] = useState<Role>('ORGANIZER');
  const [error, setError] = useState<unknown>(null);
  const [result, setResult] = useState<User | null>(null);

  async function submit(e: FormEvent) {
    e.preventDefault();
    setError(null);
    setResult(null);
    try {
      setResult(await api<User>(`/api/admin/users/${userId}/role`, { method: 'PATCH', body: { role } }));
    } catch (err) {
      setError(err);
    }
  }

  return (
    <>
      <h1>Admin</h1>
      <form className="card" onSubmit={submit}>
        <h3>Change a user's role</h3>
        <p className="muted">The user finds their id on the "My account" page. They must log in again to get the new role.</p>
        <div className="row">
          <label>User id<input value={userId} onChange={(e) => setUserId(e.target.value)} required /></label>
          <label>New role
            <select value={role} onChange={(e) => setRole(e.target.value as Role)}>
              <option>CUSTOMER</option><option>ORGANIZER</option><option>ADMIN</option>
            </select>
          </label>
          <button className="primary">Change role</button>
        </div>
        <ErrorBox error={error} />
        {result && <InfoBox>{result.email} is now {result.role}.</InfoBox>}
      </form>
    </>
  );
}
