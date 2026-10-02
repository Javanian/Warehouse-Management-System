import { Component, inject, signal } from '@angular/core';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatCheckboxModule } from '@angular/material/checkbox';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatPaginatorModule } from '@angular/material/paginator';
import { MatSortModule } from '@angular/material/sort';
import { firstValueFrom } from 'rxjs';
import { Api } from '../core/api.service';
import { Auth } from '../core/auth.service';
import { ApiError, Supplier } from '../core/models';
import { ErrorBanner } from '../shared/error-banner';
import { applyServerErrors } from '../shared/field-errors';
import { PageState } from '../shared/page-state';
import { PagedList } from '../shared/paged-list';
import { StatusChip } from '../shared/status-chip';

@Component({
  selector: 'sf-suppliers',
  imports: [ReactiveFormsModule, MatButtonModule, MatCheckboxModule, MatFormFieldModule, MatIconModule, MatInputModule,
    MatPaginatorModule, MatSortModule, ErrorBanner, PageState, StatusChip],
  template: `
    <header class="sf-head"><h1>Suppliers</h1><span class="sf-spacer"></span>
      @if (auth.can('adminMaster')) {
        <button mat-flat-button type="button" (click)="openCreate()"><mat-icon>add</mat-icon> New supplier</button>
      }
    </header>
    <sf-error [error]="actionError()" />

    @if (formVisible()) {
      <section class="sf-panel sf-edit-panel">
        <h2>{{ editId ? 'Edit supplier' : 'New supplier' }}</h2>
        <form [formGroup]="form" (ngSubmit)="save()" novalidate class="sf-form">
          <div class="sf-form-row">
            <mat-form-field><mat-label>Code</mat-label><input matInput formControlName="code" maxlength="30" [readonly]="!!editId" />
              @if (form.controls.code.invalid) { <mat-error>{{ form.controls.code.errors?.['server'] ?? 'Code required' }}</mat-error> }</mat-form-field>
            <mat-form-field class="sf-grow"><mat-label>Name</mat-label><input matInput formControlName="name" maxlength="120" required /></mat-form-field>
            @if (editId) { <mat-checkbox formControlName="active">Active</mat-checkbox> }
          </div>
          <div class="sf-actions"><button mat-button type="button" (click)="formVisible.set(false)">Cancel</button>
            <button mat-flat-button type="submit" [disabled]="saving()">Save</button></div>
        </form>
      </section>
    }

    <form class="sf-filters" [formGroup]="ff" (ngSubmit)="apply()" role="search" aria-label="Filter suppliers">
      <mat-form-field><mat-label>Search code, name</mat-label><input matInput formControlName="q" /></mat-form-field>
      <mat-checkbox formControlName="activeOnly">Active only</mat-checkbox>
      <button mat-flat-button type="submit">Apply</button>
    </form>
    <sf-error [error]="list.error()" retryLabel="Retry" (retry)="list.load()" />
    <sf-page-state [loading]="list.loading()" [empty]="list.loadedOnce() && list.rows().length === 0"
      emptyTitle="No suppliers match" emptyHint="Suppliers are required when creating purchase orders." />
    @if (list.rows().length) {
      <div class="sf-table-scroll" role="region" aria-label="Suppliers" tabindex="0">
        <table class="sf-table" matSort (matSortChange)="list.sortChange($event)">
          <thead><tr><th scope="col" mat-sort-header="code">Code</th><th scope="col" mat-sort-header="name">Name</th>
            <th scope="col">Status</th>@if (auth.can('adminMaster')) { <th scope="col"><span class="sf-sr">Edit</span></th> }</tr></thead>
          <tbody>
            @for (s of list.rows(); track s.id) {
              <tr [attr.data-testid]="'sup-' + s.code">
                <td><span class="sf-code">{{ s.code }}</span> @if (s.demo) { <span class="sf-tag">demo</span> }</td>
                <td>{{ s.name }}</td><td><sf-status [value]="s.active ? 'ACTIVE' : 'INACTIVE'" /></td>
                @if (auth.can('adminMaster')) { <td><button mat-button type="button" (click)="openEdit(s)">Edit</button></td> }
              </tr>
            }
          </tbody>
        </table>
      </div>
    }
    <mat-paginator [length]="list.total()" [pageIndex]="list.page" [pageSize]="list.size" [pageSizeOptions]="[20, 50, 100]"
      (page)="list.pageChange($event)" aria-label="Supplier pages" />
  `,
})
export class Suppliers {
  readonly auth = inject(Auth);
  private api = inject(Api);
  private fb = inject(FormBuilder);
  readonly list = new PagedList<Supplier>(this.api, '/api/suppliers');
  readonly formVisible = signal(false);
  readonly saving = signal(false);
  readonly actionError = signal<ApiError | null>(null);
  editId: number | null = null;
  private version: number | null = null;
  readonly ff = this.fb.group({ q: [''], activeOnly: [true] });
  readonly form = this.fb.nonNullable.group({
    code: ['', [Validators.required, Validators.pattern(/^[A-Za-z0-9._-]+$/)]],
    name: ['', Validators.required],
    active: [true],
  });

  constructor() { this.apply(); }
  apply(): void {
    const v = this.ff.getRawValue();
    this.list.setFilters({ q: v.q, active: v.activeOnly ? true : null });
  }

  openCreate(): void {
    this.editId = null;
    this.version = null;
    this.form.reset({ code: '', name: '', active: true });
    this.formVisible.set(true);
  }

  openEdit(s: Supplier): void {
    this.editId = s.id;
    this.version = s.version;
    this.form.reset({ code: s.code, name: s.name, active: s.active });
    this.formVisible.set(true);
  }

  async save(): Promise<void> {
    if (this.form.invalid) { this.form.markAllAsTouched(); return; }
    const v = this.form.getRawValue();
    const body = { code: v.code.trim().toUpperCase(), name: v.name.trim(), active: v.active, version: this.version };
    this.saving.set(true);
    this.actionError.set(null);
    try {
      if (this.editId) {
        await firstValueFrom(this.api.put(`/api/suppliers/${this.editId}`, body));
      } else {
        await firstValueFrom(this.api.post('/api/suppliers', body));
      }
      this.formVisible.set(false);
      this.list.load();
    } catch (e) {
      const err = e as ApiError;
      this.actionError.set(err);
      applyServerErrors(this.form, err);
    } finally { this.saving.set(false); }
  }
}
