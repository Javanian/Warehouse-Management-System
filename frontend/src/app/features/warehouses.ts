import { Component, inject, signal } from '@angular/core';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
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
import { ApiError, StorageLocation, Warehouse } from '../core/models';
import { ErrorBanner } from '../shared/error-banner';
import { applyServerErrors } from '../shared/field-errors';
import { PageState } from '../shared/page-state';
import { PagedList } from '../shared/paged-list';
import { StatusChip } from '../shared/status-chip';

const TYPES = ['BIN', 'RACK', 'FLOOR', 'STAGING', 'QUARANTINE'];

@Component({
  selector: 'sf-warehouses',
  imports: [ReactiveFormsModule, MatButtonModule, MatCheckboxModule, MatFormFieldModule, MatIconModule, MatInputModule,
    MatPaginatorModule, MatSelectModule, MatSortModule, ErrorBanner, PageState, StatusChip],
  template: `
    <header class="sf-head"><h1>Warehouses & storage locations</h1><span class="sf-spacer"></span>
      @if (auth.can('adminMaster')) {
        <button mat-stroked-button type="button" (click)="openWhCreate()"><mat-icon>add</mat-icon> New warehouse</button>
        <button mat-flat-button type="button" (click)="openLocCreate()"><mat-icon>add</mat-icon> New location / bin</button>
      }
    </header>
    <sf-error [error]="actionError()" />

    @if (whFormVisible()) {
      <section class="sf-panel sf-edit-panel">
        <h2>{{ whEditId ? 'Edit warehouse' : 'New warehouse' }}</h2>
        <form [formGroup]="wf" (ngSubmit)="saveWh()" novalidate class="sf-form">
          <div class="sf-form-row">
            <mat-form-field><mat-label>Code</mat-label><input matInput formControlName="code" maxlength="20" [readonly]="!!whEditId" />
              @if (wf.controls.code.invalid) { <mat-error>{{ wf.controls.code.errors?.['server'] ?? 'Code required (letters/numbers/hyphen)' }}</mat-error> }</mat-form-field>
            <mat-form-field class="sf-grow"><mat-label>Name</mat-label><input matInput formControlName="name" maxlength="120" required /></mat-form-field>
            @if (whEditId) { <mat-checkbox formControlName="active">Active</mat-checkbox> }
          </div>
          <mat-form-field class="sf-full"><mat-label>Description</mat-label><textarea matInput formControlName="description" rows="2" maxlength="500"></textarea></mat-form-field>
          <div class="sf-actions"><button mat-button type="button" (click)="whFormVisible.set(false)">Cancel</button>
            <button mat-flat-button type="submit" [disabled]="saving()">Save</button></div>
        </form>
      </section>
    }

    @if (locFormVisible()) {
      <section class="sf-panel sf-edit-panel">
        <h2>{{ locEditId ? 'Edit storage location' : 'New storage location' }}</h2>
        <form [formGroup]="lf" (ngSubmit)="saveLoc()" novalidate class="sf-form">
          <div class="sf-form-row">
            <mat-form-field><mat-label>Warehouse</mat-label>
              <mat-select formControlName="warehouseId" required>
                @for (w of warehouses(); track w.id) { <mat-option [value]="w.id">{{ w.code }} — {{ w.name }}</mat-option> }
              </mat-select>
            </mat-form-field>
            <mat-form-field><mat-label>Bin / location code</mat-label><input matInput formControlName="code" maxlength="30" [readonly]="!!locEditId" />
              @if (lf.controls.code.invalid) { <mat-error>{{ lf.controls.code.errors?.['server'] ?? 'Code required' }}</mat-error> }</mat-form-field>
            <mat-form-field><mat-label>Type</mat-label>
              <mat-select formControlName="locationType" required>
                @for (t of types; track t) { <mat-option [value]="t">{{ t }}</mat-option> }
              </mat-select>
            </mat-form-field>
          </div>
          <div class="sf-form-row">
            <mat-form-field class="sf-grow"><mat-label>Description</mat-label><input matInput formControlName="name" maxlength="120" /></mat-form-field>
            @if (locEditId) { <mat-checkbox formControlName="active">Active</mat-checkbox> }
          </div>
          <div class="sf-actions"><button mat-button type="button" (click)="locFormVisible.set(false)">Cancel</button>
            <button mat-flat-button type="submit" [disabled]="saving()">Save</button></div>
        </form>
      </section>
    }

    <section class="sf-panel">
      <h2>Warehouses</h2>
      <div class="sf-table-scroll" role="region" aria-label="Warehouses" tabindex="0">
        <table class="sf-table">
          <thead><tr><th scope="col">Code</th><th scope="col">Name</th><th scope="col" class="num">Locations</th>
            <th scope="col">Status</th>@if (auth.can('adminMaster')) { <th scope="col"><span class="sf-sr">Edit</span></th> }</tr></thead>
          <tbody>
            @for (w of warehouses(); track w.id) {
              <tr><td class="sf-code">{{ w.code }} @if (w.demo) { <span class="sf-tag">demo</span> }</td>
                <td>{{ w.name }}@if (w.description) { <div class="sf-sub">{{ w.description }}</div> }</td>
                <td class="num">{{ w.locationCount }}</td><td><sf-status [value]="w.active ? 'ACTIVE' : 'INACTIVE'" /></td>
                @if (auth.can('adminMaster')) { <td><button mat-button type="button" (click)="openWhEdit(w)">Edit</button></td> }</tr>
            }
          </tbody>
        </table>
      </div>
    </section>

    <section class="sf-panel">
      <h2>Storage locations / bins</h2>
      <form class="sf-filters" [formGroup]="ff" (ngSubmit)="applyLoc()" role="search" aria-label="Filter locations">
        <mat-form-field><mat-label>Warehouse</mat-label>
          <mat-select formControlName="warehouseId"><mat-option [value]="null">All</mat-option>
            @for (w of warehouses(); track w.id) { <mat-option [value]="w.id">{{ w.code }}</mat-option> }
          </mat-select></mat-form-field>
        <mat-form-field><mat-label>Search code, description</mat-label><input matInput formControlName="q" /></mat-form-field>
        <mat-checkbox formControlName="activeOnly">Active only</mat-checkbox>
        <button mat-flat-button type="submit">Apply</button>
      </form>
      <sf-error [error]="locList.error()" retryLabel="Retry" (retry)="locList.load()" />
      <sf-page-state [loading]="locList.loading()" [empty]="locList.loadedOnce() && locList.rows().length === 0"
        emptyTitle="No locations match" emptyHint="Add bins for inventory storage." />
      @if (locList.rows().length) {
        <div class="sf-table-scroll" role="region" aria-label="Locations" tabindex="0">
          <table class="sf-table" matSort (matSortChange)="locList.sortChange($event)">
            <thead><tr><th scope="col" mat-sort-header="warehouse">Warehouse</th><th scope="col" mat-sort-header="code">Bin code</th>
              <th scope="col" mat-sort-header="name">Description</th><th scope="col">Type</th><th scope="col" class="num">Stock lines</th>
              <th scope="col">Status</th>@if (auth.can('adminMaster')) { <th scope="col"><span class="sf-sr">Edit</span></th> }</tr></thead>
            <tbody>
              @for (l of locList.rows(); track l.id) {
                <tr [attr.data-testid]="'loc-' + l.warehouseCode + '-' + l.code">
                  <td class="sf-code">{{ l.warehouseCode }}</td><td class="sf-code">{{ l.code }}</td>
                  <td>{{ l.name ?? '—' }}</td><td>{{ l.locationType }}</td><td class="num">{{ l.stockLines }}</td>
                  <td><sf-status [value]="l.active && l.warehouseActive ? 'ACTIVE' : 'INACTIVE'" /></td>
                  @if (auth.can('adminMaster')) { <td><button mat-button type="button" (click)="openLocEdit(l)">Edit</button></td> }
                </tr>
              }
            </tbody>
          </table>
        </div>
      }
      <mat-paginator [length]="locList.total()" [pageIndex]="locList.page" [pageSize]="locList.size" [pageSizeOptions]="[20, 50, 100]"
        (page)="locList.pageChange($event)" aria-label="Location pages" />
    </section>
  `,
})
export class Warehouses {
  readonly auth = inject(Auth);
  private api = inject(Api);
  private fb = inject(FormBuilder);
  readonly warehouses = signal<Warehouse[]>([]);
  readonly locList = new PagedList<StorageLocation>(this.api, '/api/storage-locations');
  readonly types = TYPES;
  readonly whFormVisible = signal(false);
  readonly locFormVisible = signal(false);
  readonly saving = signal(false);
  readonly actionError = signal<ApiError | null>(null);
  whEditId: number | null = null;
  private whVersion: number | null = null;
  locEditId: number | null = null;
  private locVersion: number | null = null;

  readonly wf = this.fb.nonNullable.group({
    code: ['', [Validators.required, Validators.pattern(/^[A-Za-z0-9_-]+$/)]],
    name: ['', Validators.required],
    description: [''],
    active: [true],
  });
  readonly lf = this.fb.nonNullable.group({
    warehouseId: [null as number | null, Validators.required],
    code: ['', [Validators.required, Validators.pattern(/^[A-Za-z0-9._-]+$/)]],
    name: [''],
    locationType: ['BIN', Validators.required],
    active: [true],
  });
  readonly ff = this.fb.group({ warehouseId: [null as number | null], q: [''], activeOnly: [true] });

  constructor() {
    this.loadWh();
    this.applyLoc();
  }

  loadWh(): void {
    this.api.get<Warehouse[]>('/api/warehouses').subscribe((w) => this.warehouses.set(w));
  }

  applyLoc(): void {
    const v = this.ff.getRawValue();
    this.locList.setFilters({ warehouseId: v.warehouseId, q: v.q, active: v.activeOnly ? true : null });
  }

  openWhCreate(): void {
    this.whEditId = null;
    this.whVersion = null;
    this.wf.reset({ code: '', name: '', description: '', active: true });
    this.whFormVisible.set(true);
  }

  openWhEdit(w: Warehouse): void {
    this.whEditId = w.id;
    this.whVersion = w.version;
    this.wf.reset({ code: w.code, name: w.name, description: w.description ?? '', active: w.active });
    this.whFormVisible.set(true);
  }

  async saveWh(): Promise<void> {
    if (this.wf.invalid) { this.wf.markAllAsTouched(); return; }
    const v = this.wf.getRawValue();
    const body = { code: v.code.trim().toUpperCase(), name: v.name.trim(), description: v.description?.trim() || null, active: v.active, version: this.whVersion };
    this.saving.set(true);
    this.actionError.set(null);
    try {
      if (this.whEditId) {
        await firstValueFrom(this.api.put(`/api/warehouses/${this.whEditId}`, body));
      } else {
        await firstValueFrom(this.api.post('/api/warehouses', body));
      }
      this.whFormVisible.set(false);
      this.loadWh();
      this.locList.load();
    } catch (e) {
      const err = e as ApiError;
      this.actionError.set(err);
      applyServerErrors(this.wf, err);
    } finally { this.saving.set(false); }
  }

  openLocCreate(): void {
    this.locEditId = null;
    this.locVersion = null;
    this.lf.reset({ warehouseId: this.warehouses()[0]?.id ?? null, code: '', name: '', locationType: 'BIN', active: true });
    this.locFormVisible.set(true);
  }

  openLocEdit(l: StorageLocation): void {
    this.locEditId = l.id;
    this.locVersion = l.version;
    this.lf.reset({ warehouseId: l.warehouseId, code: l.code, name: l.name ?? '', locationType: l.locationType, active: l.active });
    this.locFormVisible.set(true);
  }

  async saveLoc(): Promise<void> {
    if (this.lf.invalid) { this.lf.markAllAsTouched(); return; }
    const v = this.lf.getRawValue();
    const body = { warehouseId: v.warehouseId, code: v.code.trim().toUpperCase(), name: v.name?.trim() || null, locationType: v.locationType, active: v.active, version: this.locVersion };
    this.saving.set(true);
    this.actionError.set(null);
    try {
      if (this.locEditId) {
        await firstValueFrom(this.api.put(`/api/storage-locations/${this.locEditId}`, body));
      } else {
        await firstValueFrom(this.api.post('/api/storage-locations', body));
      }
      this.locFormVisible.set(false);
      this.loadWh();
      this.locList.load();
    } catch (e) {
      const err = e as ApiError;
      this.actionError.set(err);
      applyServerErrors(this.lf, err);
    } finally { this.saving.set(false); }
  }
}
