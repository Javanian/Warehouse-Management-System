import { Component, inject, signal } from '@angular/core';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatCheckboxModule } from '@angular/material/checkbox';
import { MatDialog } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatPaginatorModule } from '@angular/material/paginator';
import { MatSelectModule } from '@angular/material/select';
import { MatSortModule } from '@angular/material/sort';
import { firstValueFrom } from 'rxjs';
import { Api } from '../core/api.service';
import { Auth } from '../core/auth.service';
import { formatDateTime } from '../core/format';
import { ApiError, Role, UserRow } from '../core/models';
import { ErrorBanner } from '../shared/error-banner';
import { applyServerErrors } from '../shared/field-errors';
import { PageState } from '../shared/page-state';
import { PagedList } from '../shared/paged-list';
import { ReasonDialog, ReasonDialogData } from '../shared/reason-dialog';
import { StatusChip } from '../shared/status-chip';

const ROLES: Role[] = ['ADMIN', 'SUPERVISOR', 'OPERATOR', 'VIEWER'];

@Component({
  selector: 'sf-users',
  imports: [ReactiveFormsModule, MatButtonModule, MatCheckboxModule, MatFormFieldModule, MatIconModule, MatInputModule,
    MatPaginatorModule, MatSelectModule, MatSortModule, ErrorBanner, PageState, StatusChip],
  template: `
    <header class="sf-head"><h1>Users & roles</h1><span class="sf-spacer"></span>
      <button mat-flat-button type="button" (click)="openCreate()"><mat-icon>add</mat-icon> New user</button>
    </header>
    <sf-error [error]="actionError()" />

    @if (formVisible()) {
      <section class="sf-panel sf-edit-panel">
        <h2>{{ editId ? 'Edit user ' + editUsername : 'New user' }}</h2>
        <form [formGroup]="form" (ngSubmit)="save()" novalidate class="sf-form">
          <div class="sf-form-row">
            @if (!editId) {
              <mat-form-field><mat-label>Username</mat-label><input matInput formControlName="username" maxlength="50" required />
                @if (form.controls.username.invalid) { <mat-error>{{ form.controls.username.errors?.['server'] ?? 'Username required (3-50 chars)' }}</mat-error> }</mat-form-field>
              <mat-form-field><mat-label>Email</mat-label><input matInput type="email" formControlName="email" maxlength="120" required />
                @if (form.controls.email.invalid) { <mat-error>{{ form.controls.email.errors?.['server'] ?? 'Valid email required' }}</mat-error> }</mat-form-field>
              <mat-form-field><mat-label>Initial password</mat-label><input matInput type="password" formControlName="password" required />
                @if (form.controls.password.invalid) { <mat-error>Minimum 8 characters</mat-error> }</mat-form-field>
            }
            <mat-form-field class="sf-grow"><mat-label>Full name</mat-label><input matInput formControlName="fullName" maxlength="120" required /></mat-form-field>
            <mat-form-field><mat-label>Role</mat-label>
              <mat-select formControlName="role" required>
                @for (r of roles; track r) { <mat-option [value]="r">{{ r }}</mat-option> }
              </mat-select>
            </mat-form-field>
            @if (editId) { <mat-checkbox formControlName="active">Active</mat-checkbox> }
          </div>
          <div class="sf-actions"><button mat-button type="button" (click)="formVisible.set(false)">Cancel</button>
            <button mat-flat-button type="submit" [disabled]="saving()">Save</button></div>
        </form>
      </section>
    }

    <form class="sf-filters" [formGroup]="ff" (ngSubmit)="apply()" role="search" aria-label="Filter users">
      <mat-form-field><mat-label>Search username, email, name</mat-label><input matInput formControlName="q" /></mat-form-field>
      <mat-form-field><mat-label>Role</mat-label>
        <mat-select formControlName="role"><mat-option [value]="''">All</mat-option>
          @for (r of roles; track r) { <mat-option [value]="r">{{ r }}</mat-option> }</mat-select></mat-form-field>
      <button mat-flat-button type="submit">Apply</button>
    </form>
    <sf-error [error]="list.error()" retryLabel="Retry" (retry)="list.load()" />
    <sf-page-state [loading]="list.loading()" [empty]="list.loadedOnce() && list.rows().length === 0" emptyTitle="No users match" />
    @if (list.rows().length) {
      <div class="sf-table-scroll" role="region" aria-label="Users" tabindex="0">
        <table class="sf-table" matSort (matSortChange)="list.sortChange($event)">
          <thead><tr><th scope="col" mat-sort-header="username">Username</th><th scope="col" mat-sort-header="fullName">Full name</th>
            <th scope="col">Email</th><th scope="col" mat-sort-header="role">Role</th><th scope="col">Status</th>
            <th scope="col" mat-sort-header="createdAt">Created</th><th scope="col"><span class="sf-sr">Actions</span></th></tr></thead>
          <tbody>
            @for (u of list.rows(); track u.id) {
              <tr [attr.data-testid]="'user-' + u.username">
                <td class="sf-code">{{ u.username }} @if (u.demo) { <span class="sf-tag">demo</span> }</td>
                <td>{{ u.fullName }}</td><td>{{ u.email }}</td><td><span class="sf-role">{{ u.role }}</span></td>
                <td><sf-status [value]="u.active ? 'ACTIVE' : 'INACTIVE'" /></td><td>{{ fmt(u.createdAt) }}</td>
                <td><div class="sf-btn-group">
                  <button mat-button type="button" (click)="openEdit(u)">Edit</button>
                  <button mat-button type="button" (click)="resetPassword(u)">Reset pwd</button></div></td>
              </tr>
            }
          </tbody>
        </table>
      </div>
    }
    <mat-paginator [length]="list.total()" [pageIndex]="list.page" [pageSize]="list.size" [pageSizeOptions]="[20, 50, 100]"
      (page)="list.pageChange($event)" aria-label="User pages" />
  `,
})
export class Users {
  readonly auth = inject(Auth);
  private api = inject(Api);
  private dialog = inject(MatDialog);
  private fb = inject(FormBuilder);
  readonly list = new PagedList<UserRow>(this.api, '/api/users');
  readonly roles = ROLES;
  readonly fmt = formatDateTime;
  readonly formVisible = signal(false);
  readonly saving = signal(false);
  readonly actionError = signal<ApiError | null>(null);
  editId: number | null = null;
  editUsername = '';
  private version: number | null = null;
  readonly ff = this.fb.group({ q: [''], role: [''] });
  readonly form = this.fb.nonNullable.group({
    username: ['', [Validators.required, Validators.pattern(/^[A-Za-z0-9._-]{3,50}$/)]],
    email: ['', [Validators.required, Validators.email]],
    password: ['', [Validators.required, Validators.minLength(8)]],
    fullName: ['', Validators.required],
    role: ['OPERATOR' as Role, Validators.required],
    active: [true],
  });

  constructor() { this.apply(); }
  apply(): void {
    const v = this.ff.getRawValue();
    this.list.setFilters({ q: v.q, role: v.role || null });
  }

  openCreate(): void {
    this.editId = null;
    this.editUsername = '';
    this.version = null;
    this.form.reset({ username: '', email: '', password: '', fullName: '', role: 'OPERATOR', active: true });
    this.formVisible.set(true);
  }

  openEdit(u: UserRow): void {
    this.editId = u.id;
    this.editUsername = u.username;
    this.version = u.version;
    this.form.reset({ username: u.username, email: u.email, password: '', fullName: u.fullName, role: u.role, active: u.active });
    this.formVisible.set(true);
  }

  async save(): Promise<void> {
    if (this.editId) {
      this.form.controls.username.clearValidators();
      this.form.controls.email.clearValidators();
      this.form.controls.password.clearValidators();
    }
    if (this.form.invalid) { this.form.markAllAsTouched(); return; }
    const v = this.form.getRawValue();
    this.saving.set(true);
    this.actionError.set(null);
    try {
      if (this.editId) {
        await firstValueFrom(this.api.put(`/api/users/${this.editId}`, { fullName: v.fullName.trim(), role: v.role, active: v.active, version: this.version }));
      } else {
        await firstValueFrom(this.api.post('/api/users', { username: v.username.trim(), email: v.email.trim(), fullName: v.fullName.trim(), role: v.role, password: v.password }));
      }
      this.formVisible.set(false);
      this.list.load();
    } catch (e) {
      const err = e as ApiError;
      this.actionError.set(err);
      applyServerErrors(this.form, err);
    } finally { this.saving.set(false); }
  }

  async resetPassword(u: UserRow): Promise<void> {
    const data: ReasonDialogData = {
      title: `Reset password for ${u.username}?`, confirm: 'Set password', label: 'New password (min 8 characters)', required: true,
      message: 'The user session will be invalidated on their next request.',
    };
    const pwd = await firstValueFrom(this.dialog.open(ReasonDialog, { data, width: '480px' }).afterClosed());
    if (!pwd) { return; }
    if (pwd.length < 8) {
      this.actionError.set({ status: 400, error: 'VALIDATION_ERROR', message: 'Password must be at least 8 characters' });
      return;
    }
    this.actionError.set(null);
    try {
      await firstValueFrom(this.api.post(`/api/users/${u.id}/reset-password`, { password: pwd }));
    } catch (e) { this.actionError.set(e as ApiError); }
  }
}
