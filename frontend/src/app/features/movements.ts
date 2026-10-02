import { Component, inject } from '@angular/core';
import { FormBuilder, ReactiveFormsModule } from '@angular/forms';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { MatButtonModule } from '@angular/material/button';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatPaginatorModule } from '@angular/material/paginator';
import { MatSelectModule } from '@angular/material/select';
import { Api } from '../core/api.service';
import { formatDateTime, humanize } from '../core/format';
import { MovementRow } from '../core/models';
import { ErrorBanner } from '../shared/error-banner';
import { PageState } from '../shared/page-state';
import { PagedList } from '../shared/paged-list';
import { Qty } from '../shared/qty';
import { docLink } from './doc-links';

const TYPES = ['OPENING_BALANCE', 'GOODS_RECEIPT', 'STOCK_ISSUE', 'TRANSFER', 'ADJUSTMENT', 'REVERSAL'];

@Component({
  selector: 'sf-movements',
  imports: [ReactiveFormsModule, RouterLink, MatButtonModule, MatFormFieldModule, MatInputModule, MatPaginatorModule,
    MatSelectModule, ErrorBanner, PageState, Qty],
  template: `
    <header class="sf-head"><h1>Movement ledger</h1>
      <p class="sf-muted">Immutable signed entries. Corrections appear as linked reversal movements, never as edits.</p></header>
    <form class="sf-filters" [formGroup]="f" (ngSubmit)="apply()" role="search" aria-label="Filter movements">
      <mat-form-field><mat-label>Search movement, document, material</mat-label><input matInput formControlName="q" /></mat-form-field>
      <mat-form-field><mat-label>Movement type</mat-label>
        <mat-select formControlName="movementType"><mat-option [value]="''">All types</mat-option>
          @for (t of types; track t) { <mat-option [value]="t">{{ h(t) }}</mat-option> }</mat-select></mat-form-field>
      <mat-form-field><mat-label>From date</mat-label><input matInput type="date" formControlName="from" /></mat-form-field>
      <mat-form-field><mat-label>To date</mat-label><input matInput type="date" formControlName="to" /></mat-form-field>
      <button mat-flat-button type="submit">Apply</button>
      @if (scoped) { <button mat-button type="button" (click)="clearScope()">Show all materials/bins</button> }
    </form>
    <sf-error [error]="list.error()" retryLabel="Retry" (retry)="list.load()" />
    <sf-page-state [loading]="list.loading()" [empty]="list.loadedOnce() && list.rows().length === 0"
      emptyTitle="No movements match" emptyHint="Change the filters, or post a document to create ledger entries." />
    @if (list.rows().length) {
      <div class="sf-table-scroll" role="region" aria-label="Ledger entries" tabindex="0">
        <table class="sf-table">
          <thead><tr><th scope="col">Movement</th><th scope="col">Type</th><th scope="col">Document</th>
            <th scope="col">Material</th><th scope="col">Warehouse / bin</th><th scope="col" class="num">Change</th>
            <th scope="col" class="num">Balance after</th><th scope="col">UOM</th><th scope="col">Posted</th></tr></thead>
          <tbody>
            @for (r of list.rows(); track r.movementId + '-' + r.lineNo) {
              <tr>
                <td class="sf-code">{{ r.movementNumber }}
                  @if (r.reversalOfMovementNumber) { <div class="sf-sub">reverses {{ r.reversalOfMovementNumber }}</div> }</td>
                <td>{{ h(r.movementType) }}</td>
                <td>@if (link(r.documentType, r.documentId); as l) { <a class="sf-code" [routerLink]="l">{{ r.documentNumber }}</a> }
                  @else { <span class="sf-code">{{ r.documentNumber }}</span> }</td>
                <td class="sf-code">{{ r.materialCode }}</td>
                <td class="sf-code">{{ r.location }}</td>
                <td class="num"><sf-qty [value]="r.quantityDelta" [scale]="r.uomScale" [signed]="true" /></td>
                <td class="num"><sf-qty [value]="r.balanceAfter" [scale]="r.uomScale" /></td>
                <td>{{ r.uomCode }}</td>
                <td>{{ fmt(r.postedAt) }}<div class="sf-sub">{{ r.postedBy }}</div></td>
              </tr>
            }
          </tbody>
        </table>
      </div>
    }
    <mat-paginator [length]="list.total()" [pageIndex]="list.page" [pageSize]="list.size" [pageSizeOptions]="[20, 50, 100]"
      (page)="list.pageChange($event)" aria-label="Ledger pages" />
  `,
})
export class Movements {
  private api = inject(Api);
  private qp = inject(ActivatedRoute).snapshot.queryParamMap;
  readonly list = new PagedList<MovementRow>(this.api, '/api/inventory/movements');
  readonly types = TYPES;
  readonly h = humanize;
  readonly fmt = formatDateTime;
  readonly link = docLink;
  scoped = this.qp.has('materialId') || this.qp.has('locationId');
  readonly f = inject(FormBuilder).nonNullable.group({ q: [this.qp.get('q') ?? ''], movementType: [''], from: [''], to: [''] });

  constructor() {
    this.apply();
  }

  apply(): void {
    const v = this.f.getRawValue();
    this.list.setFilters({
      ...v,
      materialId: this.scoped ? this.qp.get('materialId') : null,
      locationId: this.scoped ? this.qp.get('locationId') : null,
    });
  }

  clearScope(): void {
    this.scoped = false;
    this.apply();
  }
}
