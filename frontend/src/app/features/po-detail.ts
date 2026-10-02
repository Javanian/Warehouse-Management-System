import { Component, inject, input, signal } from '@angular/core';
import { Router, RouterLink } from '@angular/router';
import { MatButtonModule } from '@angular/material/button';
import { MatDialog } from '@angular/material/dialog';
import { MatIconModule } from '@angular/material/icon';
import { MatSnackBar } from '@angular/material/snack-bar';
import { firstValueFrom } from 'rxjs';
import { Api, newIdempotencyKey } from '../core/api.service';
import { Auth } from '../core/auth.service';
import { formatDateTime } from '../core/format';
import { ApiError, PoDetail as Po } from '../core/models';
import { ErrorBanner } from '../shared/error-banner';
import { PageState } from '../shared/page-state';
import { Qty } from '../shared/qty';
import { ReasonDialog, ReasonDialogData } from '../shared/reason-dialog';
import { StatusChip } from '../shared/status-chip';

@Component({
  selector: 'sf-po-detail',
  imports: [RouterLink, MatButtonModule, MatIconModule, ErrorBanner, PageState, Qty, StatusChip],
  template: `
    <nav class="sf-crumbs" aria-label="Breadcrumb"><a routerLink="/purchase-orders">Purchase orders</a></nav>
    <sf-error [error]="loadError()" retryLabel="Retry" (retry)="load()" />
    <sf-page-state [loading]="loading()" />
    @if (po(); as p) {
      <header class="sf-head">
        <h1><span class="sf-code">{{ p.poNumber }}</span></h1><sf-status [value]="p.status" />
        @if (p.demo) { <span class="sf-tag">demo data</span> }
        <span class="sf-spacer"></span>
        @if (receivable(p) && auth.can('post')) {
          <a mat-flat-button [routerLink]="['/goods-receipts/new']" [queryParams]="{ po: p.id }"><mat-icon>move_to_inbox</mat-icon> Receive goods</a>
        }
        @if (p.status === 'DRAFT' && auth.can('managePo')) {
          <a mat-stroked-button [routerLink]="['/purchase-orders', p.id, 'edit']"><mat-icon>edit</mat-icon> Edit</a>
          <button mat-flat-button type="button" (click)="open(p)" [disabled]="busy()"><mat-icon>lock_open</mat-icon> Open for receipt</button>
        }
        @if ((p.status === 'DRAFT' || receivable(p)) && auth.can('managePo')) {
          <button mat-stroked-button class="sf-danger-outline" type="button" (click)="cancel(p)" [disabled]="busy()">Cancel PO</button>
        }
      </header>
      <sf-error [error]="actionError()" />
      <dl class="sf-facts">
        <div><dt>Supplier</dt><dd>{{ p.supplierName }} <span class="sf-code sf-muted">{{ p.supplierCode }}</span></dd></div>
        <div><dt>PO date</dt><dd class="sf-code">{{ p.poDate }}</dd></div>
        <div><dt>Expected</dt><dd class="sf-code">{{ p.expectedDate ?? '—' }}</dd></div>
        <div><dt>Created</dt><dd>{{ p.createdBy }} · {{ fmt(p.createdAt) }}</dd></div>
        @if (p.openedAt) { <div><dt>Opened</dt><dd>{{ fmt(p.openedAt) }}</dd></div> }
        @if (p.cancelledAt) { <div><dt>Cancelled</dt><dd>{{ fmt(p.cancelledAt) }} — {{ p.cancelReason }}</dd></div> }
        @if (p.notes) { <div class="sf-wide"><dt>Notes</dt><dd>{{ p.notes }}</dd></div> }
      </dl>
      <section class="sf-panel" aria-labelledby="items-h">
        <h2 id="items-h">Items</h2>
        <div class="sf-table-scroll" role="region" aria-label="PO items" tabindex="0">
          <table class="sf-table">
            <thead><tr><th scope="col" class="num">#</th><th scope="col">Material</th><th scope="col" class="num">Ordered</th>
              <th scope="col" class="num">Received</th><th scope="col" class="num">Outstanding</th><th scope="col">UOM</th></tr></thead>
            <tbody>
              @for (i of p.items; track i.id) {
                <tr [attr.data-testid]="'po-item-' + i.materialCode">
                  <td class="num">{{ i.lineNo }}</td>
                  <td><span class="sf-code">{{ i.materialCode }}</span><div class="sf-sub">{{ i.materialName }}</div></td>
                  <td class="num"><sf-qty [value]="i.orderedQuantity" [scale]="i.uomScale" /></td>
                  <td class="num"><sf-qty [value]="i.receivedQuantity" [scale]="i.uomScale" /></td>
                  <td class="num" data-col="outstanding"><sf-qty [value]="i.outstandingQuantity" [scale]="i.uomScale" /></td>
                  <td>{{ i.uomCode }}</td>
                </tr>
              }
            </tbody>
          </table>
        </div>
      </section>
      <section class="sf-panel" aria-labelledby="gr-h">
        <h2 id="gr-h">Goods receipts</h2>
        @if (p.receipts.length === 0) {
          <p class="sf-empty-inline">No goods have been received against this PO.</p>
        } @else {
          <ul class="sf-linklist">
            @for (r of p.receipts; track r.id) {
              <li><a class="sf-code" [routerLink]="['/goods-receipts', r.id]">{{ r.documentNumber }}</a>
                <sf-status [value]="r.status" /> <span class="sf-muted">{{ r.postedBy }} · {{ fmt(r.postedAt) }}</span></li>
            }
          </ul>
        }
      </section>
    }
  `,
})
export class PoDetailPage {
  readonly id = input.required<string>();
  readonly auth = inject(Auth);
  private api = inject(Api);
  private dialog = inject(MatDialog);
  private snack = inject(MatSnackBar);
  readonly router = inject(Router);
  readonly po = signal<Po | null>(null);
  readonly loading = signal(false);
  readonly busy = signal(false);
  readonly loadError = signal<ApiError | null>(null);
  readonly actionError = signal<ApiError | null>(null);
  readonly fmt = formatDateTime;

  ngOnInit(): void {
    this.load();
  }

  receivable(p: Po): boolean {
    return p.status === 'OPEN' || p.status === 'PARTIALLY_RECEIVED';
  }

  load(): void {
    this.loading.set(true);
    this.loadError.set(null);
    this.api.get<Po>(`/api/purchase-orders/${this.id()}`).subscribe({
      next: (p) => { this.po.set(p); this.loading.set(false); },
      error: (e: ApiError) => { this.loadError.set(e); this.loading.set(false); },
    });
  }

  async open(p: Po): Promise<void> {
    await this.run(`/api/purchase-orders/${p.id}/open`, {}, 'PO opened for receipt');
  }

  async cancel(p: Po): Promise<void> {
    const data: ReasonDialogData = { title: `Cancel ${p.poNumber}?`, confirm: 'Cancel PO', label: 'Reason', required: true, danger: true,
      message: 'Received quantities stay in stock. Remaining outstanding quantities are closed and cannot be received.' };
    const reason = await firstValueFrom(this.dialog.open(ReasonDialog, { data, width: '480px' }).afterClosed());
    if (reason === undefined) {
      return;
    }
    await this.run(`/api/purchase-orders/${p.id}/cancel`, { reason }, 'PO cancelled');
  }

  private async run(url: string, body: unknown, ok: string): Promise<void> {
    this.busy.set(true);
    this.actionError.set(null);
    try {
      const p = await firstValueFrom(this.api.post<Po>(url, body, newIdempotencyKey()));
      this.po.set(p);
      this.snack.open(ok, 'Close', { duration: 4000 });
    } catch (e) {
      this.actionError.set(e as ApiError);
      if ((e as ApiError).status === 409) {
        this.load();
      }
    } finally {
      this.busy.set(false);
    }
  }
}
