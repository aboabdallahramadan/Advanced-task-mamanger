import createClient, { type Client } from 'openapi-fetch';
import type { paths } from './schema';

export interface TmapClientOptions {
  /** Base URL of the API, e.g. "https://api.tmap.app". */
  baseUrl: string;
  /** Returns the current JWT access token, or null when unauthenticated. */
  getAccessToken?: () => string | null | undefined;
  /** Forwarded to fetch; web clients pass "include" to send the refresh cookie. */
  credentials?: RequestCredentials;
}

export type TmapClient = Client<paths>;

/**
 * Creates a typed TMap API client. Attaches the Bearer access token (if any)
 * to every request via a middleware hook.
 */
export function createTmapClient(options: TmapClientOptions): TmapClient {
  const client = createClient<paths>({
    baseUrl: options.baseUrl,
    credentials: options.credentials,
  });

  if (options.getAccessToken) {
    client.use({
      onRequest({ request }) {
        const token = options.getAccessToken?.();
        if (token) {
          request.headers.set('Authorization', `Bearer ${token}`);
        }
        return request;
      },
    });
  }

  return client;
}
