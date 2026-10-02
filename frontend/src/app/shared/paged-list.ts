import { DestroyRef, inject, signal } from '@angular/core';
import { Subscription } from 'rxjs';
import { Api, Query } from '../core/api.service';
import { ApiError, Page } from '../core/models';

export class PagedList<T> {
  readonly rows = signal<T[]>([]);
  readonly total = signal(0);
  readonly loading = signal(false);
  readonly error = signal<ApiError | null>(null);
  readonly loadedOnce = signal(false);
  page = 0;
  size = 20;
  sort = '';
  filters: Query = {};
  private sub?: Subscription;

  constructor(private readonly api: Api, private readonly url: string, defaults?: { size?: number; sort?: string }) {
    this.size = defaults?.size ?? 20;
    this.sort = defaults?.sort ?? '';
    inject(DestroyRef).onDestroy(() => this.sub?.unsubscribe());
  }

  load(): void {
    this.sub?.unsubscribe();
    this.loading.set(true);
    this.error.set(null);
    this.sub = this.api
      .get<Page<T>>(this.url, { ...this.filters, page: this.page, size: this.size, sort: this.sort || undefined })
      .subscribe({
        next: (p) => {
          this.rows.set(p.content);
          this.total.set(p.totalElements);
          this.loading.set(false);
          this.loadedOnce.set(true);
        },
        error: (e: ApiError) => {
          this.error.set(e);
          this.loading.set(false);
        },
      });
  }

  setFilters(f: Query): void {
    this.filters = f;
    this.page = 0;
    this.load();
  }

  pageChange(e: { pageIndex: number; pageSize: number }): void {
    this.page = e.pageIndex;
    this.size = e.pageSize;
    this.load();
  }

  sortChange(e: { active: string; direction: string }): void {
    this.sort = e.direction ? `${e.active},${e.direction}` : '';
    this.page = 0;
    this.load();
  }
}
