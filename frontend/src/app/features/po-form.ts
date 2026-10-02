import { Component, inject, input, signal } from '@angular/core';
import { AbstractControl, FormArray, FormBuilder, FormGroup, ReactiveFormsModule, ValidationErrors, Validators } from '@angular/forms';
import { Router, RouterLink } from '@angular/router';
import { MatButtonModule } from '@angular/material/button';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatTooltipModule } from '@angular/material/tooltip';
import { firstValueFrom } from 'rxjs';
import { Api, newIdempotencyKey } from '../core/api.service';
import { quantityError } from '../core/format';
import { ApiError, Material, PoDetail } from '../core/models';
import { ErrorBanner } from '../shared/error-banner';
import { applyServerErrors } from '../shared/field-errors';
import { Lookup } from '../shared/lookup';

@Component({
  selector: 'sf-po-form',
  imports: [ReactiveFormsModule, RouterLink, MatButtonModule, MatFormFieldModule, MatIconModule, MatInputModule,
    MatTooltipModule, ErrorBanner, Lookup],
  template: `
    <nav class="sf-crumbs" aria-label="Breadcrumb"><a routerLink="/purchase-orders">Purchase orders</a></nav>
    <header class="sf-head"><h1>{{ id() ? 'Edit draft PO' : 'New purchase order' }}</h1></header>
    <sf-error [error]="error()" [retryLabel]="error()?.status === 409 ? 'Reload latest' : ''" (retry)="reload()" />
    <form [formGroup]="form" (ngSubmit)="save()" novalidate class="sf-form">
      <div class="sf-form-row">
        <mat-form-field><mat-label>Supplier</mat-label>
          <sf-lookup formControlName="supplierId" kind="supplier" ariaLabel="Supplier" />
          @if (form.controls.supplierId.invalid) { <mat-error>{{ err(form.controls.supplierId, 'Choose a supplier from the list') }}</mat-error> }
        </mat-form-field>
        <mat-form-field><mat-label>PO date</mat-label><input matInput type="date" formControlName="poDate" required />
          @if (form.controls.poDate.invalid) { <mat-error>{{ err(form.controls.poDate, 'PO date is required') }}</mat-error> }</mat-form-field>
        <mat-form-field><mat-label>Expected date</mat-label><input matInput type="date" formControlName="expectedDate" />
          @if (form.controls.expectedDate.invalid) { <mat-error>{{ err(form.controls.expectedDate, '') }}</mat-error> }</mat-form-field>
      </div>
      <mat-form-field class="sf-full"><mat-label>Notes</mat-label><textarea matInput formControlName="notes" rows="2" maxlength="1000"></textarea></mat-form-field>

      <fieldset class="sf-lines">
        <legend>Items</legend>
        @for (line of items.controls; track line; let i = $index) {
          <div class="sf-line" [formGroup]="asGroup(line)">
            <span class="sf-line-no" aria-hidden="true">{{ i + 1 }}</span>
            <mat-form-field class="sf-grow"><mat-label>Material (line {{ i + 1 }})</mat-label>
              <sf-lookup formControlName="materialId" kind="material" [ariaLabel]="'Material line ' + (i + 1)" (focusout)="syncUom(i)" />
              @if (line.get('materialId')?.invalid) { <mat-error>{{ err(line.get('materialId')!, 'Choose a material') }}</mat-error> }
            </mat-form-field>
            <mat-form-field class="sf-qty-field"><mat-label>Ordered qty</mat-label>
              <input matInput formControlName="orderedQuantity" inputmode="decimal" class="num" />
              <span matTextSuffix>{{ uomOf(i) }}</span>
              @if (line.get('orderedQuantity')?.invalid) { <mat-error>{{ err(line.get('orderedQuantity')!, '') }}</mat-error> }
            </mat-form-field>
            <button mat-icon-button type="button" (click)="removeLine(i)" [disabled]="items.length === 1"
                    [attr.aria-label]="'Remove line ' + (i + 1)" matTooltip="Remove line"><mat-icon>delete</mat-icon></button>
          </div>
        }
        <button mat-stroked-button type="button" (click)="addLine()"><mat-icon>add</mat-icon> Add item</button>
      </fieldset>
      <div class="sf-actions">
        <a mat-button [routerLink]="id() ? ['/purchase-orders', id()] : ['/purchase-orders']">Cancel</a>
        <button mat-flat-button type="submit" [disabled]="busy()">{{ busy() ? 'Saving…' : 'Save draft' }}</button>
      </div>
    </form>
  `,
})
export class PoForm {
  readonly id = input<string>();
  private api = inject(Api);
  private router = inject(Router);
  private fb = inject(FormBuilder);
  readonly busy = signal(false);
  readonly error = signal<ApiError | null>(null);
  private scales = new Map<number, Material>();
  private version: number | null = null;
  private key = newIdempotencyKey();
  readonly form = this.fb.group({
    supplierId: [null as number | null, Validators.required],
    poDate: [new Date().toISOString().slice(0, 10), Validators.required],
    expectedDate: [''],
    notes: [''],
    items: this.fb.array([this.line()]),
  });
  get items(): FormArray { return this.form.controls.items as FormArray; }

  ngOnInit(): void {
    if (this.id()) {
      this.reload();
    }
  }

  reload(): void {
    this.error.set(null);
    this.api.get<PoDetail>(`/api/purchase-orders/${this.id()}`).subscribe({
      next: (p) => {
        this.version = p.version;
        this.items.clear();
        for (const i of p.items) {
          this.scales.set(i.materialId, { id: i.materialId, uomCode: i.uomCode, uomScale: i.uomScale } as Material);
          this.items.push(this.line(i.materialId, String(i.orderedQuantity)));
        }
        this.form.patchValue({ supplierId: p.supplierId, poDate: p.poDate, expectedDate: p.expectedDate ?? '', notes: p.notes ?? '' });
      },
      error: (e: ApiError) => this.error.set(e),
    });
  }

  private line(materialId: number | null = null, qty = ''): FormGroup {
    return this.fb.group({
      materialId: [materialId, Validators.required],
      orderedQuantity: [qty, (c: AbstractControl): ValidationErrors | null => {
        const m = this.scales.get(c.parent?.get('materialId')?.value);
        const e = quantityError(c.value, m ? m.uomScale : 6);
        return e ? { qty: e } : null;
      }],
    });
  }

  asGroup(c: AbstractControl): FormGroup { return c as FormGroup; }
  addLine(): void { this.items.push(this.line()); }
  removeLine(i: number): void { this.items.removeAt(i); }

  uomOf(i: number): string {
    return this.scales.get(this.items.at(i).get('materialId')?.value)?.uomCode ?? '';
  }

  syncUom(i: number): void {
    const id = this.items.at(i).get('materialId')?.value;
    if (id && !this.scales.has(id)) {
      this.api.get<Material>(`/api/materials/${id}`).subscribe((m) => {
        this.scales.set(id, m);
        this.items.at(i).get('orderedQuantity')?.updateValueAndValidity();
      });
    } else {
      this.items.at(i).get('orderedQuantity')?.updateValueAndValidity();
    }
  }

  err(c: AbstractControl, fallback: string): string {
    return c.errors?.['server'] ?? c.errors?.['qty'] ?? fallback;
  }

  async save(): Promise<void> {
    this.items.controls.forEach((_, i) => this.syncUom(i));
    if (this.form.invalid) {
      this.form.markAllAsTouched();
      return;
    }
    const v = this.form.getRawValue();
    const body = {
      supplierId: v.supplierId, poDate: v.poDate, expectedDate: v.expectedDate || null, notes: v.notes || null,
      items: (v.items as { materialId: number; orderedQuantity: string }[]).map((i) => ({ materialId: i.materialId, orderedQuantity: i.orderedQuantity.trim() })),
      version: this.version,
    };
    this.busy.set(true);
    this.error.set(null);
    try {
      const p = this.id()
        ? await firstValueFrom(this.api.put<PoDetail>(`/api/purchase-orders/${this.id()}`, body))
        : await firstValueFrom(this.api.post<PoDetail>('/api/purchase-orders', body, this.key));
      await this.router.navigate(['/purchase-orders', p.id]);
    } catch (e) {
      const err = e as ApiError;
      applyServerErrors(this.form, err);
      this.error.set(err);
    } finally {
      this.busy.set(false);
    }
  }
}
