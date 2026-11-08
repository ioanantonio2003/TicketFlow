import { createContext, useContext, useEffect, useState, type ReactNode } from 'react';
import { api, refreshSession, session } from './api';
import type { AuthResponse, User } from './types';

interface AuthState {
  user: User | null;
  /** true until we know whether a stored refresh token still gives a session */
  loading: boolean;
  login(email: string, password: string): Promise<void>;
  register(email: string, password: string, displayName: string): Promise<void>;
  logout(): Promise<void>;
}

const AuthContext = createContext<AuthState | null>(null);

export function AuthProvider({ children }: { children: ReactNode }) {
  const [user, setUser] = useState<User | null>(null);
  const [loading, setLoading] = useState(session.hasRefreshToken);

  useEffect(() => {
    session.listen(setUser);
    if (session.hasRefreshToken) {
      refreshSession().finally(() => setLoading(false)); // restores the session after a page reload
    }
  }, []);

  async function login(email: string, password: string) {
    const auth = await api<AuthResponse>('/api/auth/login', { method: 'POST', body: { email, password }, auth: false });
    session.store(auth);
    setUser(auth.user);
  }

  async function register(email: string, password: string, displayName: string) {
    await api('/api/auth/register', { method: 'POST', body: { email, password, displayName }, auth: false });
    await login(email, password);
  }

  async function logout() {
    const refreshToken = sessionStorage.getItem('tf.refresh');
    try {
      if (refreshToken) await api('/api/auth/logout', { method: 'POST', body: { refreshToken }, auth: false });
    } finally {
      session.clear();
      setUser(null);
    }
  }

  return <AuthContext.Provider value={{ user, loading, login, register, logout }}>{children}</AuthContext.Provider>;
}

export function useAuth(): AuthState {
  const context = useContext(AuthContext);
  if (!context) throw new Error('useAuth must be used inside AuthProvider');
  return context;
}
