import { Component, inject } from '@angular/core';
import { FormBuilder, ReactiveFormsModule } from '@angular/forms';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { MatButtonModule } from '@angular/material/button';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatPaginatorModule } from '@angular/material/paginator';
import { MatSelectModule } from '@angular/material/select';
import { MatSortModule } from '@angular/material/sort';
import { Api } from '../core/api.service';
import { Auth } from '../core/auth.service';
import { humanize } from '../core/format';
import { PoSummary } from '../core/models';
import { ErrorBanner } from '../shared/error-banner';
import { PageState } from '../shared/page-state';
import { PagedList } from '../shared/paged-list';
import { StatusChip } from '../shared/status-chip';

@Component({
  selector: 'sf-po-list',
  imports: [ReactiveFormsModule, RouterLink, MatButtonModule, MatFormFieldModule, MatIconModule, MatInputModule,
    MatPaginatorModule, MatSelectModule, MatSortModule, ErrorBanner, PageState, StatusChip],
  template: `
    <header class="sf-head"><h1>Purchase orders</h1><span class="sf-spacer"></span>
      @if (auth.can('managePo')) { <a mat-flat-button routerLink="/purchase-orders/new"><mat-icon>add</mat-icon> New PO</a> }
    </header>
    <form class="sf-filters" [formGroup]="f" (ngSubmit)="apply()" role="search" aria-label="Filter purchase orders">
      <mat-form-field><mat-label>Search PO number or supplier</mat-label><input matInput formControlName="q" /></mat-form-field>
      <mat-form-field><mat-label>Status</mat-label>
        <mat-select formControlName="status"><mat-option [value]="''">Any status</mat-option>
          @for (s of statuses; track s) { <mat-option [value]="s">{{ h(s) }}</mat-option> }</mat-select></mat-form-field>
      <mat-form-field><mat-label>Receivable</mat-label>
        <mat-select formControlName="receivable"><mat-option [value]="''">All</mat-option>
          <mat-option value="true">Awaiting receipt (open / partial)</mat-option></mat-select></mat-form-field>
      <button mat-flat-button type="submit">Apply</button>
    </form>
    <sf-error [error]="list.error()" retryLabel="Retry" (retry)="list.load()" />
    <sf-page-state [loading]="list.loading()" [empty]="list.loadedOnce() && list.rows().length === 0"
      emptyTitle="No purchase orders match" emptyHint="Supervisors create POs; operators receive goods against open POs." />
    @if (list.rows().length) {
      <div class="sf-table-scroll" role="region" aria-label="Purchase orders" tabindex="0">
        <table class="sf-table" matSort (matSortChange)="list.sortChange($event)">
          <thead><tr><th scope="col" mat-sort-header="poNumber">PO number</th><th scope="col" mat-sort-header="supplier">Supplier</th>
            <th scope="col" mat-sort-header="poDate">PO date</th><th scope="col" mat-sort-header="expectedDate">Expected</th>
            <th scope="col" mat-sort-header="status">Status</th><th scope="col" class="num">Lines</th>
            <th scope="col" class="num">Lines outstanding</th></tr></thead>
          <tbody>
            @for (p of list.rows(); track p.id) {
              <tr>
                <td><a class="sf-code" [routerLink]="['/purchase-orders', p.id]">{{ p.poNumber }}</a></td>
                <td>{{ p.supplierName }}<div class="sf-sub sf-code">{{ p.supplierCode }}</div></td>
                <td class="sf-code">{{ p.poDate }}</td><td class="sf-code">{{ p.expectedDate ?? '—' }}</td>
                <td><sf-status [value]="p.status" /></td><td class="num">{{ p.itemCount }}</td>
                <td class="num">{{ p.outstandingLines }}</td>
              </tr>
            }
          </tbody>
        </table>
      </div>
    }
    <mat-paginator [length]="list.total()" [pageIndex]="list.page" [pageSize]="list.size" [pageSizeOptions]="[20, 50, 100]"
      (page)="list.pageChange($event)" aria-label="Purchase order pages" />
  `,
})
export class PoList {
  readonly auth = inject(Auth);
  private api = inject(Api);
  private qp = inject(ActivatedRoute).snapshot.queryParamMap;
  readonly list = new PagedList<PoSummary>(this.api, '/api/purchase-orders');
  readonly statuses = ['DRAFT', 'OPEN', 'PARTIALLY_RECEIVED', 'COMPLETED', 'CANCELLED'];
  readonly h = humanize;
  readonly f = inject(FormBuilder).nonNullable.group({
    q: [''], status: [this.qp.get('status') ?? ''], receivable: [this.qp.get('receivable') ?? ''],
  });

  constructor() {
    this.apply();
  }

  apply(): void {
    this.list.setFilters(this.f.getRawValue());
  }
}
