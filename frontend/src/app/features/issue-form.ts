import { Component, inject, signal } from '@angular/core';
import { AbstractControl, FormArray, FormBuilder, FormGroup, ReactiveFormsModule, ValidationErrors, Validators } from '@angular/forms';
import { Router, RouterLink } from '@angular/router';
import { MatButtonModule } from '@angular/material/button';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { MatTooltipModule } from '@angular/material/tooltip';
import { firstValueFrom } from 'rxjs';
import { Api } from '../core/api.service';
import { formatQty, humanize, quantityError } from '../core/format';
import { ApiError, BalanceRow, DocView, Material } from '../core/models';
import { ErrorBanner } from '../shared/error-banner';
import { applyServerErrors } from '../shared/field-errors';
import { Lookup } from '../shared/lookup';
import { CommandKey } from './command-key';
import { ISSUE_REASONS } from './doc-kinds';

interface LineState { material?: Material; stock: BalanceRow[]; }

@Component({
  selector: 'sf-move-form',
  imports: [ReactiveFormsModule, RouterLink, MatButtonModule, MatFormFieldModule, MatIconModule, MatInputModule,
    MatSelectModule, MatTooltipModule, ErrorBanner, Lookup],
  template: `
    <nav class="sf-crumbs" aria-label="Breadcrumb"><a [routerLink]="base">{{ transfer ? 'Transfers' : 'Stock issues' }}</a></nav>
    <header class="sf-head"><h1>{{ transfer ? 'New transfer' : 'Issue stock' }}</h1></header>
    <sf-error [error]="error()" [title]="error()?.status === 409 ? 'Not posted — stock changed' : ''" />
    <form [formGroup]="form" (ngSubmit)="submit()" novalidate class="sf-form">
      <div class="sf-form-row">
        @if (!transfer) {
          <mat-form-field><mat-label>Reason</mat-label>
            <mat-select formControlName="reasonCode" required>
              @for (r of reasons; track r) { <mat-option [value]="r">{{ h(r) }}</mat-option> }
            </mat-select>
            @if (form.controls.reasonCode.invalid) { <mat-error>Choose a reason</mat-error> }
          </mat-form-field>
        }
        <mat-form-field><mat-label>{{ transfer ? 'Reference' : 'Work order / reference' }}</mat-label>
          <input matInput formControlName="reference" maxlength="60" />
          @if (form.controls.reference.invalid) { <mat-error>{{ form.controls.reference.errors?.['server'] ?? 'Reference is required for issues' }}</mat-error> }
        </mat-form-field>
        <mat-form-field class="sf-grow"><mat-label>Notes</mat-label><input matInput formControlName="notes" maxlength="500" /></mat-form-field>
      </div>
      <fieldset class="sf-lines"><legend>Lines</legend>
        @for (line of lines.controls; track line; let i = $index) {
          <div class="sf-line" [formGroup]="asGroup(line)" [attr.data-testid]="'move-line-' + i">
            <span class="sf-line-no" aria-hidden="true">{{ i + 1 }}</span>
            <mat-form-field class="sf-grow"><mat-label>Material</mat-label>
              <sf-lookup formControlName="materialId" kind="material" [ariaLabel]="'Material line ' + (i + 1)" (focusout)="materialChanged(i)" />
              @if (line.get('materialId')?.invalid) { <mat-error>{{ line.get('materialId')?.errors?.['server'] ?? 'Choose a material' }}</mat-error> }
            </mat-form-field>
            <mat-form-field class="sf-grow"><mat-label>{{ transfer ? 'From bin' : 'Bin' }}</mat-label>
              <mat-select [formControlName]="transfer ? 'fromLocationId' : 'locationId'" [attr.aria-label]="'Source bin line ' + (i + 1)">
                @for (b of state[i]?.stock ?? []; track b.locationId) {
                  <mat-option [value]="b.locationId">{{ b.warehouseCode }}/{{ b.locationCode }} — {{ qty(b.quantity, b.uomScale) }} {{ b.uomCode }}</mat-option>
                }
                @if ((state[i]?.stock ?? []).length === 0) { <mat-option disabled>No stock for this material</mat-option> }
              </mat-select>
              @if (srcCtrl(line)?.invalid) { <mat-error>{{ srcCtrl(line)?.errors?.['server'] ?? 'Choose a bin with stock' }}</mat-error> }
            </mat-form-field>
            @if (transfer) {
              <mat-form-field class="sf-grow"><mat-label>To bin</mat-label>
                <sf-lookup formControlName="toLocationId" kind="location" [ariaLabel]="'Destination bin line ' + (i + 1)" />
                @if (line.get('toLocationId')?.invalid) { <mat-error>{{ line.get('toLocationId')?.errors?.['server'] ?? line.get('toLocationId')?.errors?.['same'] ?? 'Choose a destination bin' }}</mat-error> }
              </mat-form-field>
            }
            <mat-form-field class="sf-qty-field"><mat-label>Quantity</mat-label>
              <input matInput formControlName="quantity" inputmode="decimal" class="num" [attr.aria-label]="'Quantity line ' + (i + 1)" />
              <span matTextSuffix>{{ state[i]?.material?.uomCode ?? '' }}</span>
              @if (line.get('quantity')?.invalid) { <mat-error>{{ line.get('quantity')?.errors?.['server'] ?? line.get('quantity')?.errors?.['qty'] }}</mat-error> }
              @if (available(i) !== null) { <mat-hint>Available {{ available(i) }}</mat-hint> }
            </mat-form-field>
            <button mat-icon-button type="button" (click)="remove(i)" [disabled]="lines.length === 1"
                    [attr.aria-label]="'Remove line ' + (i + 1)" matTooltip="Remove line"><mat-icon>delete</mat-icon></button>
          </div>
        }
        <button mat-stroked-button type="button" (click)="add()"><mat-icon>add</mat-icon> Add line</button>
      </fieldset>
      <div class="sf-actions">
        <a mat-button [routerLink]="base">Cancel</a>
        <button mat-flat-button type="submit" [disabled]="busy()">{{ busy() ? 'Posting…' : transfer ? 'Post transfer' : 'Post issue' }}</button>
      </div>
    </form>
  `,
})
export class MoveForm {
  private api = inject(Api);
  private router = inject(Router);
  private fb = inject(FormBuilder);
  readonly transfer = this.router.url.startsWith('/stock-transfers');
  readonly base = this.transfer ? '/stock-transfers' : '/stock-issues';
  readonly reasons = ISSUE_REASONS;
  readonly h = humanize;
  readonly busy = signal(false);
  readonly error = signal<ApiError | null>(null);
  state: LineState[] = [];
  private key = new CommandKey();
  readonly lines = this.fb.array<FormGroup>([]);
  readonly form = this.fb.group({
    reasonCode: ['', this.transfer ? [] : [Validators.required]],
    reference: ['', this.transfer ? [] : [Validators.required]],
    notes: [''],
    lines: this.lines,
  });

  constructor() {
    this.add();
  }

  qty(v: number, s: number): string { return formatQty(v, s); }
  asGroup(c: AbstractControl): FormGroup { return c as FormGroup; }
  srcCtrl(line: AbstractControl): AbstractControl | null { return line.get(this.transfer ? 'fromLocationId' : 'locationId'); }

  add(): void {
    const i = this.lines.length;
    const g: FormGroup = this.fb.group({
      materialId: [null as number | null, Validators.required],
      [this.transfer ? 'fromLocationId' : 'locationId']: [null as number | null, Validators.required],
      ...(this.transfer ? { toLocationId: [null as number | null, Validators.required] } : {}),
      quantity: ['', (c: AbstractControl): ValidationErrors | null => {
        const idx = this.lines.controls.indexOf(c.parent as FormGroup);
        const st = this.state[idx];
        const e = quantityError(c.value, st?.material?.uomScale ?? 6);
        if (e) { return { qty: e }; }
        const src = st?.stock.find((b) => b.locationId === this.srcCtrl(c.parent!)?.value);
        return src && Number(c.value) > Number(src.quantity) ? { qty: `Only ${formatQty(src.quantity, src.uomScale)} ${src.uomCode} available` } : null;
      }],
    });
    if (this.transfer) {
      g.get('toLocationId')!.addValidators((c) => c.value && c.value === g.get('fromLocationId')?.value ? { same: 'Destination must differ from source' } : null);
    }
    g.get(this.transfer ? 'fromLocationId' : 'locationId')!.valueChanges.subscribe(() => g.get('quantity')!.updateValueAndValidity({ emitEvent: false }));
    this.lines.push(g);
    this.state[i] = { stock: [] };
  }

  remove(i: number): void {
    this.lines.removeAt(i);
    this.state.splice(i, 1);
  }

  available(i: number): string | null {
    const st = this.state[i];
    const src = st?.stock.find((b) => b.locationId === this.srcCtrl(this.lines.at(i))?.value);
    return src ? `${formatQty(src.quantity, src.uomScale)} ${src.uomCode}` : null;
  }

  materialChanged(i: number): void {
    const id = this.lines.at(i).get('materialId')?.value as number | null;
    if (!id) {
      this.state[i] = { stock: [] };
      return;
    }
    if (this.state[i]?.material?.id === id) {
      return;
    }
    this.loadStock(i, id);
  }

  private loadStock(i: number, materialId: number): void {
    this.api.get<Material>(`/api/materials/${materialId}`).subscribe((m) => {
      this.state[i] = { ...this.state[i], material: m };
      this.lines.at(i).get('quantity')!.updateValueAndValidity();
    });
    this.api.get<BalanceRow[]>(`/api/inventory/materials/${materialId}/locations`).subscribe((rows) => {
      this.state[i] = { ...this.state[i], stock: rows };
      const src = this.srcCtrl(this.lines.at(i));
      if (src?.value && !rows.some((r) => r.locationId === src.value)) {
        src.setValue(null);
      }
      this.lines.at(i).get('quantity')!.updateValueAndValidity();
    });
  }

  async submit(): Promise<void> {
    if (this.form.invalid) {
      this.form.markAllAsTouched();
      return;
    }
    const v = this.form.getRawValue();
    const lines = (v.lines as Record<string, unknown>[]).map((l) => ({ ...l, quantity: String(l['quantity']).trim() }));
    const body = this.transfer
      ? { reference: v.reference || null, notes: v.notes || null, lines }
      : { reasonCode: v.reasonCode, reference: v.reference, notes: v.notes || null, lines };
    this.busy.set(true);
    this.error.set(null);
    try {
      const doc = await firstValueFrom(this.api.post<DocView>(`/api${this.base}`, body, this.key.for(body)));
      await this.router.navigate([this.base, doc.id]);
    } catch (e) {
      const err = e as ApiError;
      this.error.set(err);
      applyServerErrors(this.form, err);
      if (err.status === 409) {

        this.key.reset();
        this.lines.controls.forEach((c, i) => {
          const id = c.get('materialId')?.value;
          if (id) { this.loadStock(i, id); }
        });
      }
    } finally {
      this.busy.set(false);
    }
  }
}
