import { Component, input } from '@angular/core';
import { MatProgressBarModule } from '@angular/material/progress-bar';

@Component({
  selector: 'sf-page-state',
  imports: [MatProgressBarModule],
  template: `
    <div class="sf-loading-slot" aria-live="polite">
      @if (loading()) {
        <mat-progress-bar mode="indeterminate" aria-label="Loading"></mat-progress-bar>
      }
    </div>
    @if (!loading() && empty()) {
      <div class="sf-empty" role="status">
        <strong>{{ emptyTitle() }}</strong>
        <span>{{ emptyHint() }}</span>
      </div>
    }
  `,
})
export class PageState {
  readonly loading = input(false);
  readonly empty = input(false);
  readonly emptyTitle = input('Nothing here yet');
  readonly emptyHint = input('');
}
