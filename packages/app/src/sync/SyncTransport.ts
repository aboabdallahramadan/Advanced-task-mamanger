/**
 * SyncTransport — the engine's only outbound seam (contract C5). The production
 * impl wraps the refresh-wrapped MinimalClient (refreshClient.ts): one shared
 * 401→refresh + abort + Bearer middleware path for every replayed op (the SP2
 * invariant). Ops carry already-concrete paths (LocalDataClient resolved them at
 * enqueue), so we call the typed method with the literal path and NO openapi-fetch
 * `params` — openapi-fetch fetches a concrete path verbatim. pull/ensureInstances
 * bake their query string into the path for the same reason (see the R3-4
 * transport-mechanism note in the plan).
 */

import type { MinimalClient } from '../auth/refreshClient';
import type { SyncOp } from './types';
import type { SyncResponse, TaskSyncRow } from '../data/local/rows';

export interface SyncTransport {
  /** Replay one op. Returns {status, body}; THROWS on network failure / terminal 401. */
  send(op: SyncOp): Promise<{ status: number; body?: unknown }>;
  /**
   * GET /api/v1/sync?since=&cursor=&limit= — one page of remote changes. `cursor` is the client's
   * COMMITTED sync cursor (its real progress); the server keys the full-resync directive on it
   * (independently of the overlap-reduced `since`), and a from-0 re-pull passes cursor=0 so it is
   * never refused below the purge watermark.
   */
  pull(since: number, limit: number, cursor?: number): Promise<SyncResponse>;
  /** POST /api/v1/recurrence/ensure-instances?start=&end= — materialize instances. */
  ensureInstances(start: string, end: string): Promise<TaskSyncRow[]>;
}

type Result = { data?: unknown; error?: unknown; response: { status: number } };

export class HttpSyncTransport implements SyncTransport {
  constructor(private readonly client: MinimalClient) {}

  async send(op: SyncOp): Promise<{ status: number; body?: unknown }> {
    const init = op.body === undefined ? {} : { body: op.body };
    let res: Result;
    switch (op.method) {
      case 'POST':
        res = await this.client.POST(op.path, init);
        break;
      case 'PATCH':
        res = await this.client.PATCH(op.path, init);
        break;
      case 'PUT':
        res = await this.client.PUT(op.path, init);
        break;
      case 'DELETE':
        res = await this.client.DELETE(op.path, init);
        break;
    }
    return { status: res.response.status, body: res.error ?? res.data };
  }

  async pull(since: number, limit: number, cursor?: number): Promise<SyncResponse> {
    const res = (await this.client.GET(
      `/api/v1/sync?since=${since}&cursor=${cursor ?? since}&limit=${limit}`,
      {},
    )) as Result;
    if (res.error) throw res.error;
    return res.data as SyncResponse;
  }

  async ensureInstances(start: string, end: string): Promise<TaskSyncRow[]> {
    const res = (await this.client.POST(
      `/api/v1/recurrence/ensure-instances?start=${start}&end=${end}`,
      {},
    )) as Result;
    if (res.error) throw res.error;
    return (res.data as TaskSyncRow[]) ?? [];
  }
}
