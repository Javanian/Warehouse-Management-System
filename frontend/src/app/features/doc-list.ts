import { Component, inject, input } from '@angular/core';
import { FormBuilder, ReactiveFormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { MatButtonModule } from '@angular/material/button';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatPaginatorModule } from '@angular/material/paginator';
import { MatSelectModule } from '@angular/material/select';
import { MatSortModule } from '@angular/material/sort';
import { Api } from '../core/api.service';
import { Auth } from '../core/auth.service';
import { formatDateTime, humanize } from '../core/format';
import { DocSummary } from '../core/models';
import { ErrorBanner } from '../shared/error-banner';
import { PageState } from '../shared/page-state';
import { PagedList } from '../shared/paged-list';
import { StatusChip } from '../shared/status-chip';
import { DOC_KINDS, DocKind } from './doc-kinds';

@Component({
  selector: 'sf-doc-list',
  imports: [ReactiveFormsModule, RouterLink, MatButtonModule, MatFormFieldModule, MatIconModule, MatInputModule,
    MatPaginatorModule, MatSelectModule, MatSortModule, ErrorBanner, PageState, StatusChip],
  template: `
    <header class="sf-head"><h1>{{ meta.title }}</h1><span class="sf-spacer"></span>
      @if (auth.can('post')) {
        <a mat-flat-button [routerLink]="['/' + kind(), 'new']"><mat-icon>{{ meta.icon }}</mat-icon> New {{ meta.single.toLowerCase() }}</a>
      }
    </header>
    <form class="sf-filters" [formGroup]="f" (ngSubmit)="apply()" role="search" [attr.aria-label]="'Filter ' + meta.title">
      <mat-form-field><mat-label>Search document or reference</mat-label><input matInput formControlName="q" /></mat-form-field>
      <mat-form-field><mat-label>Status</mat-label><mat-select formControlName="status">
        <mat-option [value]="''">Any</mat-option><mat-option value="POSTED">Posted</mat-option><mat-option value="REVERSED">Reversed</mat-option>
      </mat-select></mat-form-field>
      <mat-form-field><mat-label>From date</mat-label><input matInput type="date" formControlName="from" /></mat-form-field>
      <mat-form-field><mat-label>To date</mat-label><input matInput type="date" formControlName="to" /></mat-form-field>
      <button mat-flat-button type="submit">Apply</button>
    </form>
    <sf-error [error]="list.error()" retryLabel="Retry" (retry)="list.load()" />
    <sf-page-state [loading]="list.loading()" [empty]="list.loadedOnce() && list.rows().length === 0"
      [emptyTitle]="'No ' + meta.title.toLowerCase() + ' match'" [emptyHint]="meta.emptyHint" />
    @if (list.rows().length) {
      <div class="sf-table-scroll" role="region" [attr.aria-label]="meta.title" tabindex="0">
        <table class="sf-table" matSort (matSortChange)="list.sortChange($event)">
          <thead><tr><th scope="col" mat-sort-header="documentNumber">Document</th><th scope="col" mat-sort-header="status">Status</th>
            @if (kind() === 'goods-receipts') { <th scope="col">PO</th> }
            @if (kind() === 'stock-issues') { <th scope="col">Reason</th> }
            <th scope="col">Reference</th><th scope="col" class="num">Lines</th>
            <th scope="col" mat-sort-header="businessDate">Business date</th><th scope="col" mat-sort-header="postedAt">Posted</th></tr></thead>
          <tbody>
            @for (d of list.rows(); track d.id) {
              <tr>
                <td><a class="sf-code" [routerLink]="['/' + kind(), d.id]">{{ d.documentNumber }}</a></td>
                <td><sf-status [value]="d.status" /></td>
                @if (kind() === 'goods-receipts') { <td class="sf-code">{{ d.poNumber }}</td> }
                @if (kind() === 'stock-issues') { <td>{{ h(d.reasonCode) }}</td> }
                <td class="sf-wrap">{{ d.reference ?? '—' }}</td><td class="num">{{ d.lineCount }}</td>
                <td class="sf-code">{{ d.businessDate }}</td><td>{{ fmt(d.postedAt) }}<div class="sf-sub">{{ d.postedBy }}</div></td>
              </tr>
            }
          </tbody>
        </table>
      </div>
    }
    <mat-paginator [length]="list.total()" [pageIndex]="list.page" [pageSize]="list.size" [pageSizeOptions]="[20, 50, 100]"
      (page)="list.pageChange($event)" [attr.aria-label]="meta.title + ' pages'" />
  `,
})
export class DocList {
  readonly kind = input.required<DocKind>();
  readonly auth = inject(Auth);
  private api = inject(Api);
  list!: PagedList<DocSummary>;
  meta = DOC_KINDS['goods-receipts'];
  readonly fmt = formatDateTime;
  readonly h = humanize;
  readonly f = inject(FormBuilder).nonNullable.group({ q: [''], status: [''], from: [''], to: [''] });
  private readonly init = (() => {

  })();

  constructor() {
    this.list = new PagedList<DocSummary>(this.api, '');
  }

  ngOnInit(): void {
    this.meta = DOC_KINDS[this.kind()];
    (this.list as unknown as { url: string }).url = '/api/' + this.kind();
    this.apply();
  }

  apply(): void {
    this.list.setFilters(this.f.getRawValue());
  }
}
