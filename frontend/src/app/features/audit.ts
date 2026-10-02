import { Component, inject } from '@angular/core';
import { FormBuilder, ReactiveFormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatPaginatorModule } from '@angular/material/paginator';
import { MatSortModule } from '@angular/material/sort';
import { Api } from '../core/api.service';
import { formatDateTime } from '../core/format';
import { AuditEntry } from '../core/models';
import { ErrorBanner } from '../shared/error-banner';
import { PageState } from '../shared/page-state';
import { PagedList } from '../shared/paged-list';

@Component({
  selector: 'sf-audit',
  imports: [ReactiveFormsModule, MatButtonModule, MatFormFieldModule, MatInputModule, MatPaginatorModule, MatSortModule, ErrorBanner, PageState],
  template: `
    <header class="sf-head"><h1>Audit log</h1><p class="sf-muted">Append-only log of security events and business transactions.</p></header>
    <form class="sf-filters" [formGroup]="f" (ngSubmit)="apply()" role="search" aria-label="Filter audit logs">
      <mat-form-field><mat-label>Search action, user, entity</mat-label><input matInput formControlName="q" /></mat-form-field>
      <mat-form-field><mat-label>Action</mat-label><input matInput formControlName="action" placeholder="e.g. POST_REVERSAL" /></mat-form-field>
      <mat-form-field><mat-label>Entity type</mat-label><input matInput formControlName="entityType" placeholder="e.g. GOODS_RECEIPT" /></mat-form-field>
      <mat-form-field><mat-label>From date</mat-label><input matInput type="date" formControlName="from" /></mat-form-field>
      <mat-form-field><mat-label>To date</mat-label><input matInput type="date" formControlName="to" /></mat-form-field>
      <button mat-flat-button type="submit">Apply</button>
    </form>
    <sf-error [error]="list.error()" retryLabel="Retry" (retry)="list.load()" />
    <sf-page-state [loading]="list.loading()" [empty]="list.loadedOnce() && list.rows().length === 0" emptyTitle="No audit entries match" />
    @if (list.rows().length) {
      <div class="sf-table-scroll" role="region" aria-label="Audit log entries" tabindex="0">
        <table class="sf-table" matSort (matSortChange)="list.sortChange($event)">
          <thead><tr><th scope="col" mat-sort-header="occurredAt">Timestamp</th><th scope="col">Actor</th>
            <th scope="col" mat-sort-header="action">Action</th><th scope="col">Entity</th><th scope="col">Entity ID</th>
            <th scope="col">IP address</th><th scope="col">Details</th></tr></thead>
          <tbody>
            @for (a of list.rows(); track a.id) {
              <tr>
                <td>{{ fmt(a.occurredAt) }}</td><td>{{ a.actorName ?? 'system' }}</td>
                <td class="sf-code">{{ a.action }}</td><td class="sf-code">{{ a.entityType }}</td>
                <td class="sf-code">{{ a.entityId ?? '—' }}</td><td class="sf-code">{{ a.ipAddress ?? '—' }}</td>
                <td class="sf-sub sf-code sf-wrap">{{ a.details }}</td>
              </tr>
            }
          </tbody>
        </table>
      </div>
    }
    <mat-paginator [length]="list.total()" [pageIndex]="list.page" [pageSize]="list.size" [pageSizeOptions]="[20, 50, 100]"
      (page)="list.pageChange($event)" aria-label="Audit pages" />
  `,
})
export class Audit {
  private api = inject(Api);
  readonly list = new PagedList<AuditEntry>(this.api, '/api/audit-logs');
  readonly fmt = formatDateTime;
  readonly f = inject(FormBuilder).nonNullable.group({ q: [''], action: [''], entityType: [''], from: [''], to: [''] });
  constructor() { this.apply(); }
  apply(): void { this.list.setFilters(this.f.getRawValue()); }
}
