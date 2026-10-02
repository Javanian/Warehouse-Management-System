import { Component, computed, input } from '@angular/core';
import { STATUS_TONE, humanize } from '../core/format';

@Component({
  selector: 'sf-status',
  template: `<span class="sf-chip" [attr.data-tone]="tone()" [attr.data-status]="value()">{{ label() }}</span>`,
})
export class StatusChip {
  readonly value = input.required<string>();
  readonly tone = computed(() => STATUS_TONE[this.value()] ?? 'muted');
  readonly label = computed(() => humanize(this.value()));
}
