import { Component, inject, signal } from '@angular/core';
import { AbstractControl, FormArray, FormBuilder, FormGroup, ReactiveFormsModule, ValidationErrors } from '@angular/forms';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { MatButtonModule } from '@angular/material/button';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatTooltipModule } from '@angular/material/tooltip';
import { firstValueFrom } from 'rxjs';
import { Api } from '../core/api.service';
import { formatQty, quantityError } from '../core/format';
import { ApiError, DocView, Page, PoDetail, PoItem, PoSummary } from '../core/models';
import { ErrorBanner } from '../shared/error-banner';
import { applyServerErrors } from '../shared/field-errors';
import { Lookup } from '../shared/lookup';
import { PageState } from '../shared/page-state';
import { Qty } from '../shared/qty';
import { StatusChip } from '../shared/status-chip';
import { CommandKey } from './command-key';

@Component({
  selector: 'sf-gr-form',
  imports: [ReactiveFormsModule, RouterLink, MatButtonModule, MatFormFieldModule, MatIconModule, MatInputModule,
    MatTooltipModule, ErrorBanner, Lookup, PageState, Qty, StatusChip],
  template: `
    <nav class="sf-crumbs" aria-label="Breadcrumb"><a routerLink="/goods-receipts">Goods receipts</a></nav>
    <header class="sf-head"><h1>Receive goods</h1></header>
    @if (!po()) {
      <section class="sf-panel" aria-labelledby="pick-h">
        <h2 id="pick-h">1. Choose a purchase order awaiting receipt</h2>
        <sf-error [error]="poError()" retryLabel="Retry" (retry)="loadReceivable()" />
        <sf-page-state [loading]="poLoading()" [empty]="!poLoading() && receivable().length === 0"
          emptyTitle="No purchase order is awaiting receipt" emptyHint="A supervisor must open a PO before goods can be received." />
        @if (receivable().length) {
          <div class="sf-table-scroll" role="region" aria-label="Receivable purchase orders" tabindex="0">
            <table class="sf-table"><thead><tr><th scope="col">PO</th><th scope="col">Supplier</th><th scope="col">Status</th>
              <th scope="col" class="num">Lines outstanding</th><th scope="col"><span class="sf-sr">Select</span></th></tr></thead>
              <tbody>@for (p of receivable(); track p.id) {
                <tr><td class="sf-code">{{ p.poNumber }}</td><td>{{ p.supplierName }}</td><td><sf-status [value]="p.status" /></td>
                  <td class="num">{{ p.outstandingLines }}</td>
                  <td><button mat-stroked-button type="button" (click)="selectPo(p.id)" [attr.aria-label]="'Receive against ' + p.poNumber">Select</button></td></tr>
              }</tbody></table>
          </div>
        }
      </section>
    } @else {
      @let p = po()!;
      <dl class="sf-facts">
        <div><dt>Purchase order</dt><dd><a class="sf-code" [routerLink]="['/purchase-orders', p.id]">{{ p.poNumber }}</a> <sf-status [value]="p.status" /></dd></div>
        <div><dt>Supplier</dt><dd>{{ p.supplierName }}</dd></div>
        <div><button mat-button type="button" (click)="changePo()">Change PO</button></div>
      </dl>
      <sf-error [error]="error()" [title]="error()?.status === 409 ? 'Receipt not posted — the PO changed' : ''" />
      <form [formGroup]="form" (ngSubmit)="submit()" novalidate class="sf-form">
        <div class="sf-form-row">
          <mat-form-field><mat-label>Delivery note / reference</mat-label><input matInput formControlName="reference" maxlength="60" /></mat-form-field>
          <mat-form-field class="sf-grow"><mat-label>Notes</mat-label><input matInput formControlName="notes" maxlength="500" /></mat-form-field>
        </div>
        <fieldset class="sf-lines"><legend>2. Quantities received and destination bin</legend>
          <p class="sf-muted">Leave a quantity empty to skip that item. Split an item over several bins with "Add bin".</p>
          @for (line of lines.controls; track line; let i = $index) {
            @let item = itemOf(line);
            <div class="sf-line" [formGroup]="asGroup(line)" [attr.data-testid]="'gr-line-' + item.materialCode">
              <div class="sf-line-item">
                <span class="sf-code">{{ item.materialCode }}</span>
                <span class="sf-sub">{{ item.materialName }}</span>
                <span class="sf-sub">Outstanding <sf-qty [value]="item.outstandingQuantity" [scale]="item.uomScale" /> {{ item.uomCode }}</span>
              </div>
              <mat-form-field class="sf-qty-field"><mat-label>Receive qty</mat-label>
                <input matInput formControlName="quantity" inputmode="decimal" class="num" [attr.aria-label]="'Receive quantity ' + item.materialCode" />
                <span matTextSuffix>{{ item.uomCode }}</span>
                @if (line.get('quantity')?.invalid) { <mat-error>{{ line.get('quantity')?.errors?.['server'] ?? line.get('quantity')?.errors?.['qty'] }}</mat-error> }
              </mat-form-field>
              <mat-form-field class="sf-grow"><mat-label>Destination bin</mat-label>
                <sf-lookup formControlName="locationId" kind="location" [ariaLabel]="'Destination bin ' + item.materialCode" />
                @if (line.get('locationId')?.invalid) { <mat-error>{{ line.get('locationId')?.errors?.['server'] ?? 'Choose a bin' }}</mat-error> }
              </mat-form-field>
              <button mat-icon-button type="button" (click)="split(i)" [attr.aria-label]="'Add bin for ' + item.materialCode" matTooltip="Add bin"><mat-icon>call_split</mat-icon></button>
              @if (isSplit(i)) {
                <button mat-icon-button type="button" (click)="lines.removeAt(i)" [attr.aria-label]="'Remove split line for ' + item.materialCode" matTooltip="Remove"><mat-icon>delete</mat-icon></button>
              }
            </div>
          }
          @if (form.errors?.['over']; as over) { <p class="sf-field-error" role="alert">{{ over }}</p> }
          @if (form.errors?.['none']) { <p class="sf-field-error" role="alert">Enter a quantity for at least one item.</p> }
        </fieldset>
        <div class="sf-actions">
          <a mat-button routerLink="/goods-receipts">Cancel</a>
          <button mat-flat-button type="submit" [disabled]="busy()">{{ busy() ? 'Posting…' : 'Post goods receipt' }}</button>
        </div>
      </form>
    }
  `,
})
export class GrForm {
  private api = inject(Api);
  private router = inject(Router);
  private fb = inject(FormBuilder);
  readonly po = signal<PoDetail | null>(null);
  readonly receivable = signal<PoSummary[]>([]);
  readonly poLoading = signal(false);
  readonly poError = signal<ApiError | null>(null);
  readonly error = signal<ApiError | null>(null);
  readonly busy = signal(false);
  private key = new CommandKey();
  readonly lines = this.fb.array<FormGroup>([]);
  readonly form = this.fb.group({ reference: [''], notes: [''], lines: this.lines }, { validators: (g) => this.totals(g) });

  constructor() {
    const id = inject(ActivatedRoute).snapshot.queryParamMap.get('po');
    if (id) {
      this.selectPo(Number(id));
    } else {
      this.loadReceivable();
    }
  }

  loadReceivable(): void {
    this.poLoading.set(true);
    this.poError.set(null);
    this.api.get<Page<PoSummary>>('/api/purchase-orders', { receivable: true, size: 50, sort: 'poNumber,asc' }).subscribe({
      next: (p) => { this.receivable.set(p.content); this.poLoading.set(false); },
      error: (e: ApiError) => { this.poError.set(e); this.poLoading.set(false); },
    });
  }

  selectPo(id: number): void {
    this.error.set(null);
    this.api.get<PoDetail>(`/api/purchase-orders/${id}`).subscribe({
      next: (p) => {
        if (p.status !== 'OPEN' && p.status !== 'PARTIALLY_RECEIVED') {
          this.poError.set({ status: 409, error: 'INVALID_STATE', message: `${p.poNumber} is ${p.status} and cannot be received.` });
          this.loadReceivable();
          return;
        }
        this.po.set(p);
        this.rebuild(p);
      },
      error: (e: ApiError) => { this.poError.set(e); this.loadReceivable(); },
    });
  }

  changePo(): void {
    this.po.set(null);
    this.loadReceivable();
  }

  private rebuild(p: PoDetail): void {
    const prev = this.lines.getRawValue() as { itemId: number; quantity: string; locationId: number | null }[];
    this.lines.clear();
    for (const item of p.items.filter((i) => Number(i.outstandingQuantity) > 0)) {
      const mine = prev.filter((l) => l.itemId === item.id);
      for (const l of mine.length ? mine : [{ itemId: item.id, quantity: '', locationId: null }]) {
        this.lines.push(this.line(item.id, l.quantity, l.locationId));
      }
    }
    this.form.updateValueAndValidity();
  }

  private line(itemId: number, quantity = '', locationId: number | null = null): FormGroup {
    return this.fb.group({
      itemId: [itemId],
      quantity: [quantity, (c: AbstractControl): ValidationErrors | null => {
        if (c.value === '' || c.value === null) {
          return null;
        }
        const e = quantityError(c.value, this.item(itemId)?.uomScale ?? 0);
        return e ? { qty: e } : null;
      }],
      locationId: [locationId, (c: AbstractControl): ValidationErrors | null =>
        c.parent && c.parent.get('quantity')?.value && !c.value ? { required: true } : null],
    });
  }

  private item(id: number): PoItem | undefined {
    return this.po()?.items.find((i) => i.id === id);
  }

  itemOf(c: AbstractControl): PoItem {
    return this.item(c.get('itemId')!.value)!;
  }

  asGroup(c: AbstractControl): FormGroup { return c as FormGroup; }

  isSplit(i: number): boolean {
    const id = this.lines.at(i).get('itemId')!.value;
    return this.lines.controls.findIndex((c) => c.get('itemId')!.value === id) !== i;
  }

  split(i: number): void {
    this.lines.insert(i + 1, this.line(this.lines.at(i).get('itemId')!.value));
  }

  private totals(g: AbstractControl): ValidationErrors | null {
    const ls = ((g.get('lines') as FormArray)?.getRawValue() ?? []) as { itemId: number; quantity: string }[];
    const filled = ls.filter((l) => l.quantity !== '' && l.quantity !== null && !isNaN(Number(l.quantity)));
    if (this.po() && filled.length === 0) {
      return { none: true };
    }
    const sums = new Map<number, number>();
    for (const l of filled) {
      sums.set(l.itemId, (sums.get(l.itemId) ?? 0) + Number(l.quantity));
    }
    for (const [id, sum] of sums) {
      const it = this.item(id);
      if (it && sum > Number(it.outstandingQuantity) + 1e-9) {
        return { over: `${it.materialCode}: total ${formatQty(sum, it.uomScale)} exceeds outstanding ${formatQty(it.outstandingQuantity, it.uomScale)} ${it.uomCode}` };
      }
    }
    return null;
  }

  async submit(): Promise<void> {
    this.lines.controls.forEach((c) => c.get('locationId')!.updateValueAndValidity());
    if (this.form.invalid) {
      this.form.markAllAsTouched();
      return;
    }
    const v = this.form.getRawValue();
    const p = this.po()!;
    const body = {
      purchaseOrderId: p.id, reference: v.reference || null, notes: v.notes || null,
      lines: (v.lines as { itemId: number; quantity: string; locationId: number }[])
        .filter((l) => l.quantity !== '' && l.quantity !== null)
        .map((l) => ({ purchaseOrderItemId: l.itemId, locationId: l.locationId, quantity: String(l.quantity).trim() })),
    };
    this.busy.set(true);
    this.error.set(null);
    try {
      const doc = await firstValueFrom(this.api.post<DocView>('/api/goods-receipts', body, this.key.for(body)));
      await this.router.navigate(['/goods-receipts', doc.id]);
    } catch (e) {
      const err = e as ApiError;
      this.error.set(err);
      applyServerErrors(this.form, remapLines(err, body.lines.length, this.lines.getRawValue() as { quantity: string }[]));
      if (err.status === 409 || err.status === 404) {
        this.key.reset();

        this.api.get<PoDetail>(`/api/purchase-orders/${p.id}`).subscribe((fresh) => {
          this.po.set(fresh);
          if (fresh.status === 'OPEN' || fresh.status === 'PARTIALLY_RECEIVED') {
            this.rebuild(fresh);
          }
        });
      }
    } finally {
      this.busy.set(false);
    }
  }
}

function remapLines(err: ApiError, _n: number, formLines: { quantity: string }[]): ApiError {
  const idx: number[] = [];
  formLines.forEach((l, i) => { if (l.quantity !== '' && l.quantity !== null) { idx.push(i); } });
  return {
    ...err,
    fieldErrors: (err.fieldErrors ?? []).map((f) => ({
      ...f, field: f.field.replace(/^lines\[(\d+)\]/, (_m, n) => `lines[${idx[Number(n)] ?? n}]`),
    })),
  };
}
