import { useEffect, useState, type ReactNode } from 'react';
import { Navigate } from 'react-router-dom';
import { describeError } from './api';
import { useAuth } from './auth';
import type { Role } from './types';

export const money = (n: number | null | undefined) => (n == null ? '-' : `${Number(n).toFixed(2)} RON`);
export const when = (iso: string | null) => (iso ? new Date(iso).toLocaleString() : '-');

/** Re-renders every {@code ms} milliseconds and returns the current time (for countdowns). */
export function useNow(ms = 1000): number {
  const [now, setNow] = useState(Date.now());
  useEffect(() => {
    const id = setInterval(() => setNow(Date.now()), ms);
    return () => clearInterval(id);
  }, [ms]);
  return now;
}

export function ErrorBox({ error }: { error: unknown }) {
  if (!error) return null;
  return <div className="box error">{describeError(error)}</div>;
}

export function InfoBox({ children }: { children: ReactNode }) {
  return children ? <div className="box info">{children}</div> : null;
}

export function Badge({ value }: { value: string }) {
  return <span className={`badge b-${value.toLowerCase()}`}>{value}</span>;
}

/** Shows the page only to the listed roles; everybody else is sent to the login page. */
export function RequireRole({ roles, children }: { roles?: Role[]; children: ReactNode }) {
  const { user, loading } = useAuth();
  if (loading) return <p>Loading...</p>;
  if (!user) return <Navigate to="/login" replace />;
  if (roles && !roles.includes(user.role)) {
    return <div className="box error">Your role ({user.role}) cannot open this page.</div>;
  }
  return <>{children}</>;
}

/** Runs a loader on mount (and when {@code deps} change) and keeps data, error and a reload function. */
export function useLoad<T>(loader: () => Promise<T>, deps: unknown[] = [], pollMs?: number) {
  const [data, setData] = useState<T | null>(null);
  const [error, setError] = useState<unknown>(null);
  const [tick, setTick] = useState(0);
  useEffect(() => {
    let cancelled = false;
    const run = () =>
      loader().then(
        (value) => {
          if (!cancelled) {
            setData(value);
            setError(null);
          }
        },
        (e) => {
          if (!cancelled) setError(e);
        },
      );
    run();
    const id = pollMs ? setInterval(run, pollMs) : undefined;
    return () => {
      cancelled = true;
      if (id) clearInterval(id);
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [...deps, tick]);
  return { data, error, reload: () => setTick((t) => t + 1) };
}
