// wrapper around fetch for talking to the backend.
// locally VITE_API_URL is empty and vite forwards /api to spring boot (see vite.config.ts)
const BASE = (import.meta.env.VITE_API_URL as string | undefined)?.replace(/\/$/, '') ?? '';
const TOKEN_KEY = 'readysetrecruit.token';

export class ApiError extends Error {
  constructor(public status: number, message: string) { super(message); }
}

export function getToken(): string | null {
  try { return localStorage.getItem(TOKEN_KEY); } catch { return null; }
}
export function setToken(token: string | null) {
  try {
    if (token) localStorage.setItem(TOKEN_KEY, token);
    else localStorage.removeItem(TOKEN_KEY);
  } catch { /* storage unavailable */ }
}

export async function api<T>(path: string, options: { method?: string; body?: unknown; raw?: string } = {}): Promise<T> {
  const headers: Record<string, string> = {};
  const token = getToken();
  if (token) headers['Authorization'] = `Bearer ${token}`;
  let body: string | undefined;
  if (options.raw !== undefined) { headers['Content-Type'] = 'text/csv'; body = options.raw; }
  else if (options.body !== undefined) { headers['Content-Type'] = 'application/json'; body = JSON.stringify(options.body); }

  let res: Response;
  try {
    res = await fetch(BASE + path, { method: options.method ?? 'GET', headers, body });
  } catch {
    throw new ApiError(0, "Can't reach the server. If it was asleep, give it a minute and try again.");
  }
  if (res.status === 401 && token) setToken(null);
  const text = await res.text();
  const data = text ? safeJson(text) : null;
  if (!res.ok) {
    const msg = (data && typeof data === 'object' && 'error' in data) ? String((data as { error: unknown }).error)
      : res.status === 401 ? 'Please log in' : res.status === 403 ? "You don't have access to that" : `Error ${res.status}`;
    throw new ApiError(res.status, msg);
  }
  return data as T;
}

function safeJson(t: string): unknown {
  try { return JSON.parse(t); } catch { return t; }
}
