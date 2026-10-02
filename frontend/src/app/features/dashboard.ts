import { Component, inject, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { MatButtonModule } from '@angular/material/button';
import { Api } from '../core/api.service';
import { ApiError, DashboardData } from '../core/models';
import { formatDateTime, humanize } from '../core/format';
import { docLink } from './doc-links';
import { ErrorBanner } from '../shared/error-banner';
import { PageState } from '../shared/page-state';
import { Qty } from '../shared/qty';

@Component({
  selector: 'sf-dashboard',
  imports: [RouterLink, MatButtonModule, ErrorBanner, PageState, Qty],
  template: `
    <header class="sf-head">
      <h1>Dashboard</h1>
      @if (data(); as d) {
        <p class="sf-muted">Business date {{ d.businessDate }} ({{ d.timezone }}) · generated {{ fmt(d.generatedAt) }}</p>
      }
      <span class="sf-spacer"></span>
      <button mat-stroked-button type="button" (click)="load()">Refresh</button>
    </header>
    <sf-error [error]="error()" retryLabel="Retry" (retry)="load()" />
    <sf-page-state [loading]="loading()" />
    @if (data(); as d) {
      <section class="sf-metrics" aria-label="Today and open work">
        <a class="sf-metric" routerLink="/goods-receipts" title="Goods receipts posted today, excluding reversed">
          <span class="sf-metric-label">Receipts today</span><span class="sf-metric-value">{{ d.counts.receiptsToday }}</span></a>
        <a class="sf-metric" routerLink="/stock-issues" title="Stock issues posted today, excluding reversed">
          <span class="sf-metric-label">Issues today</span><span class="sf-metric-value">{{ d.counts.issuesToday }}</span></a>
        <a class="sf-metric" routerLink="/stock-transfers" title="Transfers posted today, excluding reversed">
          <span class="sf-metric-label">Transfers today</span><span class="sf-metric-value">{{ d.counts.transfersToday }}</span></a>
        <a class="sf-metric" routerLink="/purchase-orders" [queryParams]="{ receivable: true }"
           title="POs with status Open or Partially received">
          <span class="sf-metric-label">POs awaiting receipt</span><span class="sf-metric-value">{{ d.counts.openPurchaseOrders }}</span></a>
        <a class="sf-metric" routerLink="/adjustments" [queryParams]="{ status: 'PENDING' }" title="Adjustment requests awaiting decision"
           [attr.data-attention]="d.counts.pendingAdjustments > 0 || null">
          <span class="sf-metric-label">Adjustments pending</span><span class="sf-metric-value">{{ d.counts.pendingAdjustments }}</span></a>
        <a class="sf-metric" routerLink="/materials" [queryParams]="{ lowStock: true }"
           title="Active materials with minimum stock > 0 whose total on hand across all bins is below minimum"
           [attr.data-attention]="d.counts.lowStockMaterials > 0 || null">
          <span class="sf-metric-label">Below minimum stock</span>
          <span class="sf-metric-value">{{ d.counts.lowStockMaterials }} <small>of {{ d.counts.activeMaterials }} active</small></span></a>
      </section>

      <div class="sf-grid-2">
        <section class="sf-panel" aria-labelledby="low-h">
          <h2 id="low-h">Below minimum stock</h2>
          @if (d.lowStock.length === 0) {
            <p class="sf-empty-inline">No active material is below its minimum.</p>
          } @else {
            <div class="sf-table-scroll" role="region" aria-label="Low stock materials" tabindex="0">
              <table class="sf-table">
                <thead><tr><th scope="col">Material</th><th scope="col" class="num">On hand</th>
                  <th scope="col" class="num">Minimum</th><th scope="col" class="num">Shortage</th><th scope="col">UOM</th></tr></thead>
                <tbody>
                  @for (r of d.lowStock; track r.materialId) {
                    <tr>
                      <td><a [routerLink]="['/stock']" [queryParams]="{ materialId: r.materialId }" class="sf-code">{{ r.materialCode }}</a>
                        <div class="sf-sub">{{ r.materialName }}</div></td>
                      <td class="num"><sf-qty [value]="r.onHand" [scale]="r.uomScale" /></td>
                      <td class="num"><sf-qty [value]="r.minimumStock" [scale]="r.uomScale" /></td>
                      <td class="num neg"><sf-qty [value]="r.shortage" [scale]="r.uomScale" /></td>
                      <td>{{ r.uomCode }}</td>
                    </tr>
                  }
                </tbody>
              </table>
            </div>
          }
        </section>
        <section class="sf-panel" aria-labelledby="act-h">
          <h2 id="act-h">Posted documents, last 7 business days</h2>
          <div class="sf-table-scroll" role="region" aria-label="Daily activity" tabindex="0">
            <table class="sf-table">
              <thead><tr><th scope="col">Date</th><th scope="col" class="num">Receipts</th><th scope="col" class="num">Issues</th>
                <th scope="col" class="num">Transfers</th></tr></thead>
              <tbody>
                @for (a of d.activity; track a.businessDate) {
                  <tr><td class="sf-code">{{ a.businessDate }}</td><td class="num">{{ a.receipts }}</td>
                    <td class="num">{{ a.issues }}</td><td class="num">{{ a.transfers }}</td></tr>
                }
              </tbody>
            </table>
          </div>
        </section>
      </div>

      <section class="sf-panel" aria-labelledby="rec-h">
        <h2 id="rec-h">Latest movements</h2>
        @if (d.recent.length === 0) {
          <p class="sf-empty-inline">No movements have been posted yet.</p>
        } @else {
          <div class="sf-table-scroll" role="region" aria-label="Latest movements" tabindex="0">
            <table class="sf-table">
              <thead><tr><th scope="col">Movement</th><th scope="col">Type</th><th scope="col">Document</th>
                <th scope="col" class="num">Entries</th><th scope="col">Posted by</th><th scope="col">Posted at</th></tr></thead>
              <tbody>
                @for (m of d.recent; track m.movementId) {
                  <tr>
                    <td><a class="sf-code" routerLink="/movements" [queryParams]="{ q: m.movementNumber }">{{ m.movementNumber }}</a></td>
                    <td>{{ h(m.movementType) }}</td>
                    <td>
                      @if (link(m.documentType, m.documentId); as l) { <a class="sf-code" [routerLink]="l">{{ m.documentNumber }}</a> }
                      @else { <span class="sf-code">{{ m.documentNumber }}</span> }
                    </td>
                    <td class="num">{{ m.entryCount }}</td><td>{{ m.postedBy }}</td><td>{{ fmt(m.postedAt) }}</td>
                  </tr>
                }
              </tbody>
            </table>
          </div>
        }
      </section>
    }
  `,
})
export class Dashboard {
  private api = inject(Api);
  readonly data = signal<DashboardData | null>(null);
  readonly loading = signal(false);
  readonly error = signal<ApiError | null>(null);
  readonly fmt = formatDateTime;
  readonly h = humanize;
  readonly link = docLink;

  constructor() {
    this.load();
  }

  load(): void {
    this.loading.set(true);
    this.error.set(null);
    this.api.get<DashboardData>('/api/dashboard').subscribe({
      next: (d) => { this.data.set(d); this.loading.set(false); },
      error: (e: ApiError) => { this.error.set(e); this.loading.set(false); },
    });
  }
}
