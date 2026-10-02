import { HttpClient } from '@angular/common/http';
import { Injectable, computed, inject, signal } from '@angular/core';
import { Router } from '@angular/router';
import { firstValueFrom } from 'rxjs';
import { Api } from './api.service';
import { Me, Role } from './models';

export type Capability =
  | 'viewAll' | 'post' | 'managePo' | 'decide' | 'reverse' | 'adminMaster' | 'adminUsers' | 'viewAudit';

const MATRIX: Record<Capability, Role[]> = {
  viewAll: ['ADMIN', 'SUPERVISOR', 'OPERATOR', 'VIEWER'],
  post: ['ADMIN', 'SUPERVISOR', 'OPERATOR'],
  managePo: ['ADMIN', 'SUPERVISOR'],
  decide: ['ADMIN', 'SUPERVISOR'],
  reverse: ['ADMIN', 'SUPERVISOR'],
  adminMaster: ['ADMIN'],
  adminUsers: ['ADMIN'],
  viewAudit: ['ADMIN', 'SUPERVISOR'],
};

export function roleCan(role: Role | undefined, cap: Capability): boolean {
  return !!role && MATRIX[cap].includes(role);
}

@Injectable({ providedIn: 'root' })
export class Auth {
  private api = inject(Api);
  private http = inject(HttpClient);
  private router = inject(Router);
  readonly me = signal<Me | null>(null);
  readonly loaded = signal(false);
  readonly sessionNotice = signal<string | null>(null);
  readonly role = computed(() => this.me()?.role);

  can(cap: Capability): boolean {
    return roleCan(this.role(), cap);
  }

  async refresh(): Promise<Me | null> {
    try {
      await firstValueFrom(this.http.get('/api/auth/csrf'));
      const me = await firstValueFrom(this.api.get<Me>('/api/auth/me'));
      this.me.set(me);
    } catch {
      this.me.set(null);
    } finally {
      this.loaded.set(true);
    }
    return this.me();
  }

  async login(username: string, password: string): Promise<Me> {
    await firstValueFrom(this.http.get('/api/auth/csrf'));
    const me = await firstValueFrom(this.api.post<Me>('/api/auth/login', { username, password }));

    await firstValueFrom(this.http.get('/api/auth/csrf'));
    this.sessionNotice.set(null);
    this.me.set(me);
    return me;
  }

  async logout(): Promise<void> {
    try {
      await firstValueFrom(this.api.post('/api/auth/logout', {}));
    } finally {
      this.me.set(null);
      await this.router.navigateByUrl('/login');
    }
  }

  sessionEnded(code: string, message: string): void {
    if (!this.me()) {
      return;
    }
    this.me.set(null);
    this.sessionNotice.set(code === 'ACCOUNT_DISABLED' ? message : 'Your session ended. Please sign in again.');
    void this.router.navigate(['/login'], { queryParams: { returnUrl: this.router.url } });
  }
}
