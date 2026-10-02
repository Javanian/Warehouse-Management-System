import { Component, inject, signal } from '@angular/core';
import { AbstractControl, FormBuilder, ReactiveFormsModule, ValidationErrors, Validators } from '@angular/forms';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { MatButtonModule } from '@angular/material/button';
import { MatDialog } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatPaginatorModule } from '@angular/material/paginator';
import { MatSelectModule } from '@angular/material/select';
import { MatSortModule } from '@angular/material/sort';
import { firstValueFrom } from 'rxjs';
import { Api, newIdempotencyKey } from '../core/api.service';
import { Auth } from '../core/auth.service';
import { formatDateTime, quantityError } from '../core/format';
import { Adjustment, ApiError, Material } from '../core/models';
import { ErrorBanner } from '../shared/error-banner';
import { Lookup } from '../shared/lookup';
import { PageState } from '../shared/page-state';
import { PagedList } from '../shared/paged-list';
import { Qty } from '../shared/qty';
import { ReasonDialog, ReasonDialogData } from '../shared/reason-dialog';
import { StatusChip } from '../shared/status-chip';
import { CommandKey } from './command-key';

interface Snapshot { quantity: number; version: number; }

@Component({
  selector: 'sf-adjustments',
  imports: [ReactiveFormsModule, RouterLink, MatButtonModule, MatFormFieldModule, MatIconModule, MatInputModule,
    MatPaginatorModule, MatSelectModule, MatSortModule, ErrorBanner, Lookup, PageState, Qty, StatusChip],
  template: `
    <header class="sf-head"><h1>Physical-count adjustments</h1>
      <p class="sf-muted">Two-person rule: the requester cannot decide their own count. Approvals verify the stock version has not moved.</p>
    </header>
    <sf-error [error]="actionError()" [title]="actionError()?.status === 409 ? 'Decision not possible' : ''" />

    @if (auth.can('post')) {
      <details class="sf-disclosure" #reqBox [open]="showForm()">
        <summary (click)="showForm.set(!showForm())">
          <span><mat-icon>add</mat-icon> Request count adjustment</span>
        </summary>
        <form [formGroup]="rf" (ngSubmit)="submitRequest()" novalidate class="sf-form sf-compact">
          <div class="sf-form-row">
            <mat-form-field class="sf-grow"><mat-label>Material</mat-label>
              <sf-lookup formControlName="materialId" kind="material" ariaLabel="Material" (focusout)="materialChanged()" />
              @if (rf.controls.materialId.invalid) { <mat-error>Choose a material</mat-error> }
            </mat-form-field>
            <mat-form-field class="sf-grow"><mat-label>Bin</mat-label>
              <sf-lookup formControlName="locationId" kind="location" ariaLabel="Bin" (focusout)="fetchSnapshot()" />
              @if (rf.controls.locationId.invalid) { <mat-error>Choose a bin</mat-error> }
            </mat-form-field>
            <mat-form-field class="sf-qty-field"><mat-label>Physical count</mat-label>
              <input matInput formControlName="physicalQuantity" inputmode="decimal" class="num" />
              <span matTextSuffix>{{ material()?.uomCode ?? '' }}</span>
              @if (rf.controls.physicalQuantity.invalid) { <mat-error>{{ rf.controls.physicalQuantity.errors?.['server'] ?? rf.controls.physicalQuantity.errors?.['qty'] }}</mat-error> }
              @if (snapshot(); as s) { <mat-hint>Current system: {{ s.quantity }}</mat-hint> }
            </mat-form-field>
          </div>
          <div class="sf-form-row">
            <mat-form-field class="sf-grow"><mat-label>Reason for adjustment</mat-label>
              <input matInput formControlName="reason" maxlength="200" required />
              @if (rf.controls.reason.invalid) { <mat-error>Reason is required</mat-error> }
            </mat-form-field>
            <button mat-flat-button type="submit" [disabled]="requestBusy()">{{ requestBusy() ? 'Submitting…' : 'Submit request' }}</button>
          </div>
        </form>
      </details>
    }

    <form class="sf-filters" [formGroup]="ff" (ngSubmit)="apply()" role="search" aria-label="Filter adjustments">
      <mat-form-field><mat-label>Search document or material</mat-label><input matInput formControlName="q" /></mat-form-field>
      <mat-form-field><mat-label>Status</mat-label>
        <mat-select formControlName="status"><mat-option [value]="''">All</mat-option>
          <mat-option value="PENDING">Pending</mat-option><mat-option value="APPROVED">Approved</mat-option>
          <mat-option value="REJECTED">Rejected</mat-option></mat-select></mat-form-field>
      <button mat-flat-button type="submit">Apply</button>
    </form>
    <sf-error [error]="list.error()" retryLabel="Retry" (retry)="list.load()" />
    <sf-page-state [loading]="list.loading()" [empty]="list.loadedOnce() && list.rows().length === 0"
      emptyTitle="No adjustments match" emptyHint="Operators and supervisors request adjustments after physical cycle counts." />
    @if (list.rows().length) {
      <div class="sf-table-scroll" role="region" aria-label="Adjustments" tabindex="0">
        <table class="sf-table" matSort (matSortChange)="list.sortChange($event)">
          <thead><tr>
            <th scope="col" mat-sort-header="documentNumber">Document</th><th scope="col" mat-sort-header="status">Status</th>
            <th scope="col">Material</th><th scope="col">Bin</th>
            <th scope="col" class="num">System at count</th><th scope="col" class="num">Counted</th>
            <th scope="col" class="num">Delta</th><th scope="col">UOM</th>
            <th scope="col" mat-sort-header="requestedAt">Requested</th><th scope="col">Decision</th>
            <th scope="col"><span class="sf-sr">Actions</span></th></tr></thead>
          <tbody>
            @for (a of list.rows(); track a.id) {
              <tr [attr.data-testid]="'adj-' + a.documentNumber" [class.sf-stale-row]="a.stale">
                <td><span class="sf-code">{{ a.documentNumber }}</span></td>
                <td><sf-status [value]="a.status" />
                  @if (a.stale && a.status === 'PENDING') {
                    <span class="sf-tag warn" title="Stock moved since the count was taken. Approval will be rejected.">STALE</span>
                  }</td>
                <td><span class="sf-code">{{ a.materialCode }}</span><div class="sf-sub">{{ a.materialName }}</div></td>
                <td class="sf-code">{{ a.location }}</td>
                <td class="num"><sf-qty [value]="a.observedQuantity" [scale]="a.uomScale" /></td>
                <td class="num"><sf-qty [value]="a.physicalQuantity" [scale]="a.uomScale" /></td>
                <td class="num"><sf-qty [value]="a.delta" [scale]="a.uomScale" [signed]="true" /></td>
                <td>{{ a.uomCode }}</td>
                <td>{{ fmt(a.requestedAt) }}<div class="sf-sub">{{ a.requestedBy }}</div></td>
                <td>
                  @if (a.status !== 'PENDING') {
                    <span>{{ a.decidedBy }} · {{ fmt(a.decidedAt) }}</span>
                    @if (a.decisionNote) { <div class="sf-sub">{{ a.decisionNote }}</div> }
                    @if (a.movementNumber) { <div class="sf-sub"><a class="sf-code" routerLink="/movements" [queryParams]="{ q: a.movementNumber }">{{ a.movementNumber }}</a></div> }
                  } @else { <span class="sf-muted">Awaiting decision</span> }
                </td>
                <td>
                  @if (a.status === 'PENDING' && auth.can('decide')) {
                    @if (isOwn(a)) {
                      <span class="sf-muted" title="Requester cannot approve their own count">Self-decision blocked</span>
                    } @else {
                      <div class="sf-btn-group">
                        <button mat-stroked-button type="button" (click)="approve(a)" [disabled]="actionBusy()">Approve</button>
                        <button mat-button class="sf-danger-text" type="button" (click)="reject(a)" [disabled]="actionBusy()">Reject</button>
                      </div>
                    }
                  }
                </td>
              </tr>
            }
          </tbody>
        </table>
      </div>
    }
    <mat-paginator [length]="list.total()" [pageIndex]="list.page" [pageSize]="list.size" [pageSizeOptions]="[20, 50, 100]"
      (page)="list.pageChange($event)" aria-label="Adjustment pages" />
  `,
})
export class Adjustments {
  readonly auth = inject(Auth);
  private api = inject(Api);
  private dialog = inject(MatDialog);
  private qp = inject(ActivatedRoute).snapshot.queryParamMap;
  readonly list = new PagedList<Adjustment>(this.api, '/api/stock-adjustments', { sort: 'requestedAt,desc' });
  readonly fmt = formatDateTime;
  readonly showForm = signal(false);
  readonly material = signal<Material | null>(null);
  readonly snapshot = signal<Snapshot | null>(null);
  readonly requestBusy = signal(false);
  readonly actionBusy = signal(false);
  readonly actionError = signal<ApiError | null>(null);
  private requestKey = new CommandKey();
  private fb = inject(FormBuilder);
  readonly ff = this.fb.nonNullable.group({ q: [''], status: [this.qp.get('status') ?? ''] });
  readonly rf = this.fb.group({
    materialId: [null as number | null, Validators.required],
    locationId: [null as number | null, Validators.required],
    physicalQuantity: ['', (c: AbstractControl): ValidationErrors | null => {
      const e = quantityError(c.value, this.material()?.uomScale ?? 6, true);
      if (e) { return { qty: e }; }
      const snap = this.snapshot();
      if (snap && Number(c.value) === Number(snap.quantity)) { return { qty: 'Count matches system balance (delta is 0)' }; }
      return null;
    }],
    reason: ['', Validators.required],
  });

  constructor() {
    this.apply();
  }

  isOwn(a: Adjustment): boolean {
    return this.auth.me()?.id === a.requestedById;
  }

  apply(): void {
    this.list.setFilters(this.ff.getRawValue());
  }

  materialChanged(): void {
    const id = this.rf.controls.materialId.value;
    if (!id) { this.material.set(null); return; }
    this.api.get<Material>(`/api/materials/${id}`).subscribe((m) => {
      this.material.set(m);
      this.rf.controls.physicalQuantity.updateValueAndValidity();
    });
    this.fetchSnapshot();
  }

  fetchSnapshot(): void {
    const m = this.rf.controls.materialId.value;
    const l = this.rf.controls.locationId.value;
    if (!m || !l) { this.snapshot.set(null); return; }
    this.api.get<Snapshot>('/api/stock-adjustments/count-snapshot', { materialId: m, locationId: l }).subscribe((s) => {
      this.snapshot.set(s);
      this.rf.controls.physicalQuantity.updateValueAndValidity();
    });
  }

  async submitRequest(): Promise<void> {
    if (this.rf.invalid || !this.snapshot()) {
      this.rf.markAllAsTouched();
      return;
    }
    const v = this.rf.getRawValue();
    const snap = this.snapshot()!;
    const body = {
      materialId: v.materialId, locationId: v.locationId,
      physicalQuantity: String(v.physicalQuantity).trim(),
      observedQuantity: snap.quantity, observedVersion: snap.version,
      reason: v.reason!.trim(),
    };
    this.requestBusy.set(true);
    this.actionError.set(null);
    try {
      await firstValueFrom(this.api.post<Adjustment>('/api/stock-adjustments', body, this.requestKey.for(body)));
      this.rf.reset({ materialId: null, locationId: null, physicalQuantity: '', reason: '' });
      this.snapshot.set(null);
      this.showForm.set(false);
      this.list.load();
    } catch (e) {
      const err = e as ApiError;
      this.actionError.set(err);
      if (err.status === 409) {
        this.requestKey.reset();
        this.fetchSnapshot();
      }
    } finally {
      this.requestBusy.set(false);
    }
  }

  async approve(a: Adjustment): Promise<void> {
    const data: ReasonDialogData = {
      title: `Approve ${a.documentNumber}?`, confirm: 'Approve & post balance change', label: 'Approval note (optional)', required: false,
      message: `Delta ${a.delta > 0 ? '+' : ''}${a.delta} ${a.uomCode} will be posted to ${a.location}. The check verifies stock version is still ${a.observedVersion}.`,
    };
    const note = await firstValueFrom(this.dialog.open(ReasonDialog, { data, width: '480px' }).afterClosed());
    if (note === undefined) { return; }
    await this.decide(`/api/stock-adjustments/${a.id}/approve`, { note });
  }

  async reject(a: Adjustment): Promise<void> {
    const data: ReasonDialogData = {
      title: `Reject ${a.documentNumber}?`, confirm: 'Reject request', label: 'Rejection reason', required: true, danger: true,
      message: 'No stock balance or ledger entries are modified.',
    };
    const note = await firstValueFrom(this.dialog.open(ReasonDialog, { data, width: '480px' }).afterClosed());
    if (note === undefined) { return; }
    await this.decide(`/api/stock-adjustments/${a.id}/reject`, { note });
  }

  private async decide(url: string, body: unknown): Promise<void> {
    this.actionBusy.set(true);
    this.actionError.set(null);
    try {
      await firstValueFrom(this.api.post(url, body, newIdempotencyKey()));
      this.list.load();
    } catch (e) {
      this.actionError.set(e as ApiError);
      this.list.load();
    } finally {
      this.actionBusy.set(false);
    }
  }
}
