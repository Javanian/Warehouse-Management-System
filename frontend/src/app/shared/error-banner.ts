import { Component, input, output } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { ApiError } from '../core/models';

@Component({
  selector: 'sf-error',
  imports: [MatButtonModule, MatIconModule],
  template: `
    @if (error(); as e) {
      <div class="sf-banner" [attr.data-kind]="e.status === 409 ? 'conflict' : 'error'" role="alert">
        <mat-icon aria-hidden="true">{{ e.status === 409 ? 'sync_problem' : 'error' }}</mat-icon>
        <div class="sf-banner-body">
          <strong>{{ title() || (e.status === 409 ? 'Conflict' : e.status === 403 ? 'Not allowed' : 'Request failed') }}</strong>
          <span>{{ e.message }}</span>
          <span class="sf-banner-meta">
            <code>{{ e.error }}</code>
            @if (e.requestId) { · request {{ e.requestId }} }
          </span>
        </div>
        @if (retryLabel()) {
          <button mat-stroked-button type="button" (click)="retry.emit()">{{ retryLabel() }}</button>
        }
      </div>
    }
  `,
})
export class ErrorBanner {
  readonly error = input<ApiError | null>(null);
  readonly title = input<string>('');
  readonly retryLabel = input<string>('');
  readonly retry = output<void>();
}
