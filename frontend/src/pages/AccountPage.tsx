import { api } from '../api';
import type { User } from '../types';
import { ErrorBox, useLoad } from '../ui';

export default function AccountPage() {
  const { data, error } = useLoad(() => api<User>('/api/users/me'));
  return (
    <>
      <h1>My account</h1>
      <ErrorBox error={error} />
      {data && (
        <div className="card">
          <p><b>User id:</b> {data.id} <span className="muted">(an admin needs this id to change your role)</span></p>
          <p><b>Email:</b> {data.email}</p>
          <p><b>Name:</b> {data.displayName}</p>
          <p><b>Role:</b> {data.role}</p>
          <p className="muted">
            After an admin changes your role, log out and log in again: the role is part of the access token.
          </p>
        </div>
      )}
    </>
  );
}
