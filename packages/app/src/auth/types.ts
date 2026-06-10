// packages/app/src/auth/types.ts
// Canonical auth types + ProblemDetails parsing for the SP2 clients.
// AuthTokenResponse name/shape is fixed by the SP2 shared contract — do not rename.

export interface AuthUser {
  id: string;
  email: string;
  timeZoneId: string;
}

export interface AuthTokenResponse {
  accessToken: string;
  expiresIn: number;
  user: AuthUser;
}

/** RFC 9457 ProblemDetails (matches the API's HttpValidationProblemDetails). */
export interface ProblemDetails {
  type?: string | null;
  title?: string | null;
  status?: number | null;
  detail?: string | null;
  instance?: string | null;
  errors?: Record<string, string[]>;
}

/**
 * Runtime guard: narrows an unknown response body to AuthTokenResponse.
 * Defends the auth store against an untyped/regen-lagging OpenAPI body.
 */
export function unwrapAuth(body: unknown): AuthTokenResponse {
  const b = body as Partial<AuthTokenResponse> | null | undefined;
  if (
    !b ||
    typeof b.accessToken !== 'string' ||
    typeof b.expiresIn !== 'number' ||
    !b.user ||
    typeof b.user.id !== 'string' ||
    typeof b.user.email !== 'string' ||
    typeof b.user.timeZoneId !== 'string'
  ) {
    throw new Error('Malformed auth response from server');
  }
  return {
    accessToken: b.accessToken,
    expiresIn: b.expiresIn,
    user: { id: b.user.id, email: b.user.email, timeZoneId: b.user.timeZoneId },
  };
}

/** True for a ProblemDetails-shaped object (has title or errors). */
export function isProblemDetails(x: unknown): x is ProblemDetails {
  if (!x || typeof x !== 'object') return false;
  const p = x as Record<string, unknown>;
  return 'title' in p || 'errors' in p || 'detail' in p || 'status' in p;
}

/**
 * Flattens a ProblemDetails into a single human-readable message for a form banner.
 * Prefers field errors (joined), then detail, then title, then a fallback.
 */
export function problemToMessage(p: ProblemDetails | null | undefined, fallback: string): string {
  if (!p) return fallback;
  if (p.errors) {
    const msgs = Object.values(p.errors).flat().filter(Boolean);
    if (msgs.length) return msgs.join(' ');
  }
  if (p.detail) return p.detail;
  if (p.title) return p.title;
  return fallback;
}
