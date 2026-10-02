import { Component, inject } from '@angular/core';
import { FormBuilder, ReactiveFormsModule } from '@angular/forms';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { MatButtonModule } from '@angular/material/button';
import { MatCheckboxModule } from '@angular/material/checkbox';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatPaginatorModule } from '@angular/material/paginator';
import { MatSelectModule } from '@angular/material/select';
import { MatSortModule } from '@angular/material/sort';
import { Api } from '../core/api.service';
import { formatDateTime } from '../core/format';
import { BalanceRow, Warehouse } from '../core/models';
import { ErrorBanner } from '../shared/error-banner';
import { PageState } from '../shared/page-state';
import { PagedList } from '../shared/paged-list';
import { Qty } from '../shared/qty';

@Component({
  selector: 'sf-stock',
  imports: [ReactiveFormsModule, RouterLink, MatButtonModule, MatCheckboxModule, MatFormFieldModule, MatInputModule,
    MatPaginatorModule, MatSelectModule, MatSortModule, ErrorBanner, PageState, Qty],
  template: `
    <header class="sf-head"><h1>Stock on hand</h1>
      <p class="sf-muted">One row per material and bin. Quantities are maintained only by posted documents.</p></header>
    <form class="sf-filters" [formGroup]="f" (ngSubmit)="apply()" role="search" aria-label="Filter stock">
      <mat-form-field><mat-label>Search material or bin</mat-label><input matInput formControlName="q" /></mat-form-field>
      <mat-form-field><mat-label>Warehouse</mat-label>
        <mat-select formControlName="warehouseId"><mat-option [value]="null">All warehouses</mat-option>
          @for (w of warehouses; track w.id) { <mat-option [value]="w.id">{{ w.code }} · {{ w.name }}</mat-option> }
        </mat-select></mat-form-field>
      <mat-checkbox formControlName="includeZero">Include zero balances</mat-checkbox>
      <button mat-flat-button type="submit">Apply</button>
      @if (materialId) { <button mat-button type="button" (click)="clearMaterial()">Clear material filter</button> }
    </form>
    <sf-error [error]="list.error()" retryLabel="Retry" (retry)="list.load()" />
    <sf-page-state [loading]="list.loading()" [empty]="list.loadedOnce() && list.rows().length === 0"
      emptyTitle="No stock found" emptyHint="Stock appears after a goods receipt, transfer or approved adjustment is posted." />
    @if (list.rows().length) {
      <div class="sf-table-scroll" role="region" aria-label="Stock balances" tabindex="0">
        <table class="sf-table" matSort (matSortChange)="list.sortChange($event)">
          <thead><tr>
            <th scope="col" mat-sort-header="material">Material</th><th scope="col" mat-sort-header="location">Warehouse / bin</th>
            <th scope="col" class="num" mat-sort-header="quantity">On hand</th><th scope="col">UOM</th>
            <th scope="col" mat-sort-header="updatedAt">Last change</th><th scope="col"><span class="sf-sr">Actions</span></th></tr></thead>
          <tbody>
            @for (r of list.rows(); track r.id) {
              <tr [attr.data-testid]="'bal-' + r.materialCode + '-' + r.locationCode">
                <td><span class="sf-code">{{ r.materialCode }}</span><div class="sf-sub">{{ r.materialName }}</div></td>
                <td class="sf-code">{{ r.warehouseCode }}/{{ r.locationCode }}</td>
                <td class="num"><sf-qty [value]="r.quantity" [scale]="r.uomScale" /></td>
                <td>{{ r.uomCode }}</td>
                <td>{{ fmt(r.updatedAt) }}</td>
                <td><a routerLink="/movements" [queryParams]="{ materialId: r.materialId, locationId: r.locationId }">Ledger</a></td>
              </tr>
            }
          </tbody>
        </table>
      </div>
    }
    <mat-paginator [length]="list.total()" [pageIndex]="list.page" [pageSize]="list.size" [pageSizeOptions]="[20, 50, 100]"
      (page)="list.pageChange($event)" aria-label="Stock pages" />
  `,
})
export class Stock {
  private api = inject(Api);
  readonly list = new PagedList<BalanceRow>(this.api, '/api/inventory/balances');
  readonly fmt = formatDateTime;
  warehouses: Warehouse[] = [];
  materialId: string | null = inject(ActivatedRoute).snapshot.queryParamMap.get('materialId');
  readonly f = inject(FormBuilder).group({ q: [''], warehouseId: [null as number | null], includeZero: [false] });

  constructor() {
    this.api.get<Warehouse[]>('/api/warehouses').subscribe({ next: (w) => (this.warehouses = w) });
    this.apply();
  }

  apply(): void {
    const v = this.f.getRawValue();
    this.list.setFilters({ q: v.q, warehouseId: v.warehouseId, includeZero: v.includeZero, materialId: this.materialId });
  }

  clearMaterial(): void {
    this.materialId = null;
    this.apply();
  }
}
