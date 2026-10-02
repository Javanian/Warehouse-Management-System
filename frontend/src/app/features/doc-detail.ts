import { Component, inject, input, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { MatButtonModule } from '@angular/material/button';
import { MatDialog } from '@angular/material/dialog';
import { MatIconModule } from '@angular/material/icon';
import { MatSnackBar } from '@angular/material/snack-bar';
import { firstValueFrom } from 'rxjs';
import { Api, newIdempotencyKey } from '../core/api.service';
import { Auth } from '../core/auth.service';
import { formatDateTime, humanize } from '../core/format';
import { ApiError, DocView } from '../core/models';
import { ErrorBanner } from '../shared/error-banner';
import { PageState } from '../shared/page-state';
import { Qty } from '../shared/qty';
import { ReasonDialog, ReasonDialogData } from '../shared/reason-dialog';
import { StatusChip } from '../shared/status-chip';
import { DOC_KINDS, DocKind } from './doc-kinds';

@Component({
  selector: 'sf-doc-detail',
  imports: [RouterLink, MatButtonModule, MatIconModule, ErrorBanner, PageState, Qty, StatusChip],
  template: `
    <nav class="sf-crumbs" aria-label="Breadcrumb"><a [routerLink]="'/' + kind()">{{ meta().title }}</a></nav>
    <sf-error [error]="loadError()" retryLabel="Retry" (retry)="load()" />
    <sf-page-state [loading]="loading()" />
    @if (doc(); as d) {
      <header class="sf-head">
        <h1><span class="sf-code">{{ d.documentNumber }}</span></h1><sf-status [value]="d.status" />
        <span class="sf-spacer"></span>
        @if (d.status === 'POSTED' && auth.can('reverse')) {
          <button mat-stroked-button class="sf-danger-outline" type="button" (click)="reverse(d)" [disabled]="busy()">
            <mat-icon>undo</mat-icon> Reverse document</button>
        }
      </header>
      <sf-error [error]="actionError()" [title]="actionError()?.status === 409 ? 'Reversal not possible' : ''" />
      <dl class="sf-facts">
        <div><dt>Type</dt><dd>{{ meta().single }}</dd></div>
        @if (d.poNumber) { <div><dt>Purchase order</dt><dd><a class="sf-code" [routerLink]="['/purchase-orders', d.purchaseOrderId]">{{ d.poNumber }}</a></dd></div> }
        @if (d.reasonCode) { <div><dt>Reason</dt><dd>{{ h(d.reasonCode) }}</dd></div> }
        <div><dt>Reference</dt><dd class="sf-wrap">{{ d.reference ?? '—' }}</dd></div>
        <div><dt>Posted</dt><dd>{{ d.postedBy }} · {{ fmt(d.postedAt) }}</dd></div>
        <div><dt>Business date</dt><dd class="sf-code">{{ d.businessDate }}</dd></div>
        <div><dt>Movement</dt><dd><a class="sf-code" routerLink="/movements" [queryParams]="{ q: d.movementNumber }">{{ d.movementNumber }}</a></dd></div>
        @if (d.notes) { <div class="sf-wide"><dt>Notes</dt><dd>{{ d.notes }}</dd></div> }
      </dl>
      @if (d.status === 'REVERSED') {
        <div class="sf-banner" data-kind="info" role="note" data-testid="reversal-info">
          <mat-icon aria-hidden="true">undo</mat-icon>
          <div class="sf-banner-body"><strong>Reversed by {{ d.reversalDocumentNumber }}</strong>
            <span>{{ d.reversedBy }} · {{ fmt(d.reversedAt) }} — {{ d.reversalReason }}</span>
            <span>Reversal movement <a class="sf-code" routerLink="/movements" [queryParams]="{ q: d.reversalMovementNumber }">{{ d.reversalMovementNumber }}</a></span></div>
        </div>
      }
      <section class="sf-panel" aria-labelledby="lines-h">
        <h2 id="lines-h">Lines</h2>
        <div class="sf-table-scroll" role="region" aria-label="Document lines" tabindex="0">
          <table class="sf-table">
            <thead><tr><th scope="col" class="num">#</th><th scope="col">Material</th>
              @if (kind() !== 'goods-receipts') { <th scope="col">From</th> }
              @if (kind() !== 'stock-issues') { <th scope="col">To</th> }
              <th scope="col" class="num">Quantity</th><th scope="col">UOM</th></tr></thead>
            <tbody>
              @for (l of d.lines; track l.lineNo) {
                <tr><td class="num">{{ l.lineNo }}</td>
                  <td><span class="sf-code">{{ l.materialCode }}</span><div class="sf-sub">{{ l.materialName }}</div></td>
                  @if (kind() !== 'goods-receipts') { <td class="sf-code">{{ l.fromLocation }}</td> }
                  @if (kind() !== 'stock-issues') { <td class="sf-code">{{ l.toLocation }}</td> }
                  <td class="num"><sf-qty [value]="l.quantity" [scale]="l.uomScale" /></td><td>{{ l.uomCode }}</td></tr>
              }
            </tbody>
          </table>
        </div>
      </section>
    }
  `,
})
export class DocDetail {
  readonly kind = input.required<DocKind>();
  readonly id = input.required<string>();
  readonly auth = inject(Auth);
  private api = inject(Api);
  private dialog = inject(MatDialog);
  private snack = inject(MatSnackBar);
  readonly doc = signal<DocView | null>(null);
  readonly loading = signal(false);
  readonly busy = signal(false);
  readonly loadError = signal<ApiError | null>(null);
  readonly actionError = signal<ApiError | null>(null);
  readonly fmt = formatDateTime;
  readonly h = humanize;
  meta = () => DOC_KINDS[this.kind()];
  private reverseKey = newIdempotencyKey();

  ngOnInit(): void {
    this.load();
  }

  load(): void {
    this.loading.set(true);
    this.loadError.set(null);
    this.api.get<DocView>(`/api/${this.kind()}/${this.id()}`).subscribe({
      next: (d) => { this.doc.set(d); this.loading.set(false); },
      error: (e: ApiError) => { this.loadError.set(e); this.loading.set(false); },
    });
  }

  async reverse(d: DocView): Promise<void> {
    const data: ReasonDialogData = {
      title: `Reverse ${d.documentNumber}?`, confirm: 'Post reversal', label: 'Reversal reason', required: true, danger: true,
      message: 'A linked reversal movement with opposite quantities is posted. The original document stays in history. '
        + (this.kind() === 'goods-receipts' ? 'The PO received quantity is reduced accordingly.' : ''),
    };
    const reason = await firstValueFrom(this.dialog.open(ReasonDialog, { data, width: '480px' }).afterClosed());
    if (reason === undefined) {
      return;
    }
    this.busy.set(true);
    this.actionError.set(null);
    try {
      const r = await firstValueFrom(this.api.post<DocView>(`/api/${this.kind()}/${d.id}/reverse`, { reason }, this.reverseKey));
      this.doc.set(r);
      this.snack.open(`${d.documentNumber} reversed by ${r.reversalDocumentNumber}`, 'Close', { duration: 5000 });
    } catch (e) {
      const err = e as ApiError;
      this.actionError.set(err);
      if (err.status === 409) {
        this.reverseKey = newIdempotencyKey();
        this.load();
      }
    } finally {
      this.busy.set(false);
    }
  }
}
