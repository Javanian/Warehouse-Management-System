import { HttpClient, HttpErrorResponse, HttpHeaders, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable, catchError, throwError } from 'rxjs';
import { ApiError } from './models';

export type Query = Record<string, string | number | boolean | null | undefined>;

export function toApiError(e: unknown): ApiError {
  if (e instanceof HttpErrorResponse) {
    const b = e.error;
    if (b && typeof b === 'object' && typeof b.error === 'string') {
      return { ...b, status: e.status } as ApiError;
    }
    if (e.status === 0) {
      return { status: 0, error: 'NETWORK', message: 'Cannot reach the StockFlow server. Check that it is running.' };
    }
    return { status: e.status, error: 'HTTP_' + e.status, message: e.statusText || 'Unexpected server response' };
  }
  return { status: -1, error: 'CLIENT', message: String(e) };
}

export function newIdempotencyKey(): string {
  const c = globalThis.crypto;
  return c && 'randomUUID' in c ? c.randomUUID() : 'k' + Date.now().toString(36) + Math.random().toString(36).slice(2);
}

@Injectable({ providedIn: 'root' })
export class Api {
  private http = inject(HttpClient);

  private params(q?: Query): HttpParams {
    let p = new HttpParams();
    for (const [k, v] of Object.entries(q ?? {})) {
      if (v !== null && v !== undefined && v !== '') {
        p = p.set(k, String(v));
      }
    }
    return p;
  }

  get<T>(url: string, q?: Query): Observable<T> {
    return this.http.get<T>(url, { params: this.params(q) }).pipe(catchError((e) => throwError(() => toApiError(e))));
  }

  post<T>(url: string, body: unknown, idempotencyKey?: string): Observable<T> {
    const headers = idempotencyKey ? new HttpHeaders({ 'Idempotency-Key': idempotencyKey }) : undefined;
    return this.http.post<T>(url, body ?? {}, { headers }).pipe(catchError((e) => throwError(() => toApiError(e))));
  }

  put<T>(url: string, body: unknown): Observable<T> {
    return this.http.put<T>(url, body).pipe(catchError((e) => throwError(() => toApiError(e))));
  }
}
