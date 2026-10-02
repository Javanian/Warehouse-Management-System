import { Component, inject, signal } from '@angular/core';
import { AbstractControl, FormBuilder, ReactiveFormsModule, ValidationErrors, Validators } from '@angular/forms';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { MatButtonModule } from '@angular/material/button';
import { MatCheckboxModule } from '@angular/material/checkbox';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatPaginatorModule } from '@angular/material/paginator';
import { MatSelectModule } from '@angular/material/select';
import { MatSortModule } from '@angular/material/sort';
import { firstValueFrom } from 'rxjs';
import { Api } from '../core/api.service';
import { Auth } from '../core/auth.service';
import { formatDateTime, quantityError } from '../core/format';
import { ApiError, Material, Uom } from '../core/models';
import { ErrorBanner } from '../shared/error-banner';
import { applyServerErrors } from '../shared/field-errors';
import { PageState } from '../shared/page-state';
import { PagedList } from '../shared/paged-list';
import { Qty } from '../shared/qty';
import { StatusChip } from '../shared/status-chip';

@Component({
  selector: 'sf-materials',
  imports: [ReactiveFormsModule, MatButtonModule, MatCheckboxModule, MatFormFieldModule, MatIconModule,
    MatInputModule, MatPaginatorModule, MatSelectModule, MatSortModule, ErrorBanner, PageState, Qty, StatusChip],
  template: `
    <header class="sf-head"><h1>Materials</h1><span class="sf-spacer"></span>
      @if (auth.can('adminMaster')) {
        <button mat-flat-button type="button" (click)="openCreate()"><mat-icon>add</mat-icon> New material</button>
      }
    </header>
    <sf-error [error]="actionError()" />

    @if (editing()) {
      <section class="sf-panel sf-edit-panel" aria-labelledby="form-h">
        <h2 id="form-h">{{ editId ? 'Edit material ' + editCode : 'New material' }}</h2>
        <form [formGroup]="form" (ngSubmit)="save()" novalidate class="sf-form">
          <div class="sf-form-row">
            <mat-form-field><mat-label>Code</mat-label>
              <input matInput formControlName="code" maxlength="30" [readonly]="!!editId" />
              @if (form.controls.code.invalid) { <mat-error>{{ form.controls.code.errors?.['server'] ?? 'Code must be uppercase letters/numbers/dots/hyphens' }}</mat-error> }
            </mat-form-field>
            <mat-form-field class="sf-grow"><mat-label>Name</mat-label>
              <input matInput formControlName="name" maxlength="120" required />
              @if (form.controls.name.invalid) { <mat-error>Name is required</mat-error> }
            </mat-form-field>
            <mat-form-field><mat-label>Category</mat-label>
              <mat-select formControlName="category">
                <mat-option [value]="null">None</mat-option>
                @for (c of categories; track c) { <mat-option [value]="c">{{ c }}</mat-option> }
              </mat-select>
            </mat-form-field>
          </div>
          <div class="sf-form-row">
            <mat-form-field><mat-label>Unit of measure</mat-label>
              <mat-select formControlName="uomCode" required [disabled]="uomLocked()">
                @for (u of uoms; track u.code) { <mat-option [value]="u.code">{{ u.code }} — {{ u.name }} (scale {{ u.scale }})</mat-option> }
              </mat-select>
              @if (uomLocked()) { <mat-hint>Cannot change UOM after stock or PO reference exists</mat-hint> }
            </mat-form-field>
            <mat-form-field class="sf-qty-field"><mat-label>Minimum stock</mat-label>
              <input matInput formControlName="minimumStock" inputmode="decimal" class="num" />
              @if (form.controls.minimumStock.invalid) { <mat-error>{{ form.controls.minimumStock.errors?.['server'] ?? form.controls.minimumStock.errors?.['qty'] }}</mat-error> }
            </mat-form-field>
            @if (editId) { <mat-checkbox formControlName="active">Active</mat-checkbox> }
          </div>
          <mat-form-field class="sf-full"><mat-label>Description</mat-label><textarea matInput formControlName="description" rows="2" maxlength="500"></textarea></mat-form-field>
          <div class="sf-actions">
            <button mat-button type="button" (click)="cancelEdit()">Cancel</button>
            <button mat-flat-button type="submit" [disabled]="saving()">{{ saving() ? 'Saving…' : 'Save' }}</button>
          </div>
        </form>
      </section>
    }

    <form class="sf-filters" [formGroup]="ff" (ngSubmit)="apply()" role="search" aria-label="Filter materials">
      <mat-form-field><mat-label>Search code, name, description</mat-label><input matInput formControlName="q" /></mat-form-field>
      <mat-form-field><mat-label>Category</mat-label>
        <mat-select formControlName="category"><mat-option [value]="''">All</mat-option>
          @for (c of categories; track c) { <mat-option [value]="c">{{ c }}</mat-option> }</mat-select></mat-form-field>
      <mat-checkbox formControlName="lowStock">Below minimum stock only</mat-checkbox>
      <mat-checkbox formControlName="activeOnly">Active only</mat-checkbox>
      <button mat-flat-button type="submit">Apply</button>
    </form>
    <sf-error [error]="list.error()" retryLabel="Retry" (retry)="list.load()" />
    <sf-page-state [loading]="list.loading()" [empty]="list.loadedOnce() && list.rows().length === 0"
      emptyTitle="No materials match" emptyHint="Create materials to begin tracking warehouse stock." />
    @if (list.rows().length) {
      <div class="sf-table-scroll" role="region" aria-label="Materials" tabindex="0">
        <table class="sf-table" matSort (matSortChange)="list.sortChange($event)">
          <thead><tr>
            <th scope="col" mat-sort-header="code">Code</th><th scope="col" mat-sort-header="name">Name</th>
            <th scope="col" mat-sort-header="category">Category</th><th scope="col">UOM</th>
            <th scope="col" class="num" mat-sort-header="totalQuantity">Total on hand</th>
            <th scope="col" class="num">Minimum</th><th scope="col">Status</th>
            @if (auth.can('adminMaster')) { <th scope="col"><span class="sf-sr">Edit</span></th> }
          </tr></thead>
          <tbody>
            @for (m of list.rows(); track m.id) {
              <tr [attr.data-testid]="'mat-' + m.code">
                <td><span class="sf-code">{{ m.code }}</span>
                  @if (m.demo) { <span class="sf-tag">demo</span> }</td>
                <td>{{ m.name }}@if (m.description) { <div class="sf-sub">{{ m.description }}</div> }</td>
                <td>{{ m.category ?? '—' }}</td><td>{{ m.uomCode }}</td>
                <td class="num"><sf-qty [value]="m.totalQuantity" [scale]="m.uomScale" /></td>
                <td class="num"><sf-qty [value]="m.minimumStock" [scale]="m.uomScale" /></td>
                <td>
                  <sf-status [value]="m.active ? 'ACTIVE' : 'INACTIVE'" />
                  @if (m.lowStock) { <span class="sf-tag warn">LOW STOCK</span> }
                </td>
                @if (auth.can('adminMaster')) {
                  <td><button mat-button type="button" (click)="openEdit(m)">Edit</button></td>
                }
              </tr>
            }
          </tbody>
        </table>
      </div>
    }
    <mat-paginator [length]="list.total()" [pageIndex]="list.page" [pageSize]="list.size" [pageSizeOptions]="[20, 50, 100]"
      (page)="list.pageChange($event)" aria-label="Material pages" />
  `,
})
export class Materials {
  readonly auth = inject(Auth);
  private api = inject(Api);
  private qp = inject(ActivatedRoute).snapshot.queryParamMap;
  readonly list = new PagedList<Material>(this.api, '/api/materials');
  categories: string[] = [];
  uoms: Uom[] = [];
  readonly editing = signal(false);
  readonly saving = signal(false);
  readonly uomLocked = signal(false);
  readonly actionError = signal<ApiError | null>(null);
  editId: number | null = null;
  editCode = '';
  private version: number | null = null;
  private fb = inject(FormBuilder);
  readonly ff = this.fb.group({
    q: [''], category: [''], activeOnly: [true], lowStock: [this.qp.get('lowStock') === 'true'],
  });
  readonly form = this.fb.nonNullable.group({
    code: ['', [Validators.required, Validators.pattern(/^[A-Za-z0-9._-]+$/)]],
    name: ['', Validators.required],
    description: [''],
    category: [null as string | null],
    uomCode: ['', Validators.required],
    minimumStock: ['0', (c: AbstractControl): ValidationErrors | null => {
      const u = this.uoms.find((x) => x.code === this.form?.get('uomCode')?.value);
      const e = quantityError(c.value, u ? u.scale : 6, true);
      return e ? { qty: e } : null;
    }],
    active: [true],
  });

  constructor() {
    this.api.get<string[]>('/api/material-categories').subscribe((c) => (this.categories = c));
    this.api.get<Uom[]>('/api/uoms').subscribe((u) => (this.uoms = u));
    this.apply();
  }

  apply(): void {
    const v = this.ff.getRawValue();
    this.list.setFilters({ q: v.q, category: v.category, active: v.activeOnly ? true : null, lowStock: v.lowStock || null });
  }

  openCreate(): void {
    this.editId = null;
    this.editCode = '';
    this.version = null;
    this.uomLocked.set(false);
    this.form.reset({ code: '', name: '', description: '', category: null, uomCode: 'PC', minimumStock: '0', active: true });
    this.form.controls.uomCode.enable();
    this.editing.set(true);
  }

  openEdit(m: Material): void {
    this.editId = m.id;
    this.editCode = m.code;
    this.version = m.version;
    this.uomLocked.set(m.referenced);
    this.form.reset({
      code: m.code, name: m.name, description: m.description ?? '', category: m.category,
      uomCode: m.uomCode, minimumStock: String(m.minimumStock), active: m.active,
    });
    if (m.referenced) { this.form.controls.uomCode.disable(); } else { this.form.controls.uomCode.enable(); }
    this.editing.set(true);
  }

  cancelEdit(): void {
    this.editing.set(false);
  }

  async save(): Promise<void> {
    if (this.form.invalid) {
      this.form.markAllAsTouched();
      return;
    }
    const v = this.form.getRawValue();
    const body = {
      code: v.code.trim().toUpperCase(), name: v.name.trim(), description: v.description?.trim() || null,
      category: v.category || null, uomCode: v.uomCode, minimumStock: String(v.minimumStock).trim(),
      active: v.active, version: this.version,
    };
    this.saving.set(true);
    this.actionError.set(null);
    try {
      if (this.editId) {
        await firstValueFrom(this.api.put(`/api/materials/${this.editId}`, body));
      } else {
        await firstValueFrom(this.api.post('/api/materials', body));
      }
      this.editing.set(false);
      this.list.load();
    } catch (e) {
      const err = e as ApiError;
      this.actionError.set(err);
      applyServerErrors(this.form, err);
    } finally {
      this.saving.set(false);
    }
  }
}
