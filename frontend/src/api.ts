import type { AuthResponse, User } from './types';

/** A problem answer of the backend (RFC 9457): title, detail and, for validation, the list of fields. */
export class ApiError extends Error {
  constructor(
    public status: number,
    public title: string,
    public detail: string,
    public errors: { field: string; message: string }[] = [],
  ) {
    super(detail || title);
  }
}

// The access token lives only in memory (a page reload loses it). The refresh token is kept in sessionStorage so a
// reload of the tab keeps the session; it is gone when the tab is closed.
const REFRESH_KEY = 'tf.refresh';
let accessToken: string | null = null;
let onSession: ((user: User | null) => void) | null = null;

export const session = {
  get hasRefreshToken() {
    return sessionStorage.getItem(REFRESH_KEY) !== null;
  },
  store(auth: AuthResponse) {
    accessToken = auth.accessToken;
    sessionStorage.setItem(REFRESH_KEY, auth.refreshToken);
  },
  clear() {
    accessToken = null;
    sessionStorage.removeItem(REFRESH_KEY);
  },
  /** The auth context registers itself here to learn about a refreshed or lost session. */
  listen(listener: (user: User | null) => void) {
    onSession = listener;
  },
};

let refreshing: Promise<boolean> | null = null;

/** Exchanges the refresh token for new tokens. Parallel callers share one request (the refresh token rotates). */
export function refreshSession(): Promise<boolean> {
  const token = sessionStorage.getItem(REFRESH_KEY);
  if (!token) return Promise.resolve(false);
  if (!refreshing) {
    refreshing = (async () => {
      const res = await fetch('/api/auth/refresh', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ refreshToken: token }),
      });
      if (!res.ok) {
        session.clear();
        onSession?.(null);
        return false;
      }
      const auth = (await res.json()) as AuthResponse;
      session.store(auth);
      onSession?.(auth.user);
      return true;
    })().finally(() => {
      refreshing = null;
    });
  }
  return refreshing;
}

interface Options {
  method?: string;
  body?: unknown;
  /** false for the login/register/refresh calls: no token, no automatic refresh. */
  auth?: boolean;
  headers?: Record<string, string>;
}

async function send(path: string, options: Options, canRetry: boolean): Promise<Response> {
  const headers: Record<string, string> = { ...options.headers };
  if (options.body !== undefined) headers['Content-Type'] = 'application/json';
  if (options.auth !== false && accessToken) headers['Authorization'] = `Bearer ${accessToken}`;
  const res = await fetch(path, {
    method: options.method ?? 'GET',
    headers,
    body: options.body === undefined ? undefined : JSON.stringify(options.body),
  });
  if (res.status === 401 && canRetry && options.auth !== false && (await refreshSession())) {
    return send(path, options, false); // the access token had expired: retry once with the new one
  }
  return res;
}

async function toError(res: Response): Promise<ApiError> {
  try {
    const body = await res.json();
    return new ApiError(res.status, body.title ?? 'Error', body.detail ?? '', body.errors ?? []);
  } catch {
    return new ApiError(res.status, 'Error', `The server answered ${res.status}.`);
  }
}

export async function api<T>(path: string, options: Options = {}): Promise<T> {
  const res = await send(path, options, true);
  if (!res.ok) throw await toError(res);
  const text = await res.text();
  return (text ? JSON.parse(text) : undefined) as T;
}

/** For images (QR codes): they need the Authorization header, so an <img src> cannot load them directly. */
export async function apiBlobUrl(path: string): Promise<string> {
  const res = await send(path, {}, true);
  if (!res.ok) throw await toError(res);
  return URL.createObjectURL(await res.blob());
}

export function describeError(e: unknown): string {
  if (e instanceof ApiError) {
    const fields = e.errors.map((f) => `${f.field}: ${f.message}`).join('; ');
    return `${e.status} ${e.title}${e.detail ? ' - ' + e.detail : ''}${fields ? ' (' + fields + ')' : ''}`;
  }
  return e instanceof Error ? e.message : String(e);
}
