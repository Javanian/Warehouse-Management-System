import { Component, inject, signal } from '@angular/core';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { ActivatedRoute, Router } from '@angular/router';
import { MatButtonModule } from '@angular/material/button';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { Auth } from '../core/auth.service';
import { ApiError } from '../core/models';
import { ErrorBanner } from '../shared/error-banner';

@Component({
  selector: 'sf-login',
  imports: [ReactiveFormsModule, MatButtonModule, MatFormFieldModule, MatInputModule, ErrorBanner],
  template: `
    <main class="sf-login">
      <form [formGroup]="form" (ngSubmit)="submit()" class="sf-login-card" aria-labelledby="login-title" novalidate>
        <p class="sf-product">StockFlow</p>
        <h1 id="login-title">Sign in</h1>
        @if (auth.sessionNotice(); as n) {
          <div class="sf-banner" data-kind="info" role="status">{{ n }}</div>
        }
        <sf-error [error]="error()" title="Sign-in failed" />
        <mat-form-field>
          <mat-label>Username or email</mat-label>
          <input matInput formControlName="username" autocomplete="username" required />
          @if (form.controls.username.hasError('required')) { <mat-error>Enter your username or email</mat-error> }
        </mat-form-field>
        <mat-form-field>
          <mat-label>Password</mat-label>
          <input matInput type="password" formControlName="password" autocomplete="current-password" required />
          @if (form.controls.password.hasError('required')) { <mat-error>Enter your password</mat-error> }
        </mat-form-field>
        <button mat-flat-button type="submit" [disabled]="busy()">{{ busy() ? 'Signing in…' : 'Sign in' }}</button>
      </form>
    </main>
  `,
})
export class Login {
  readonly auth = inject(Auth);
  private router = inject(Router);
  private route = inject(ActivatedRoute);
  readonly busy = signal(false);
  readonly error = signal<ApiError | null>(null);
  readonly form = inject(FormBuilder).nonNullable.group({
    username: ['', Validators.required],
    password: ['', Validators.required],
  });

  async submit(): Promise<void> {
    if (this.form.invalid) {
      this.form.markAllAsTouched();
      return;
    }
    this.busy.set(true);
    this.error.set(null);
    try {
      const v = this.form.getRawValue();
      await this.auth.login(v.username.trim(), v.password);
      const back = this.route.snapshot.queryParamMap.get('returnUrl');
      await this.router.navigateByUrl(back && back.startsWith('/') && !back.startsWith('/login') ? back : '/dashboard');
    } catch (e) {
      this.error.set(e as ApiError);
      this.form.controls.password.reset('');
    } finally {
      this.busy.set(false);
    }
  }
}
