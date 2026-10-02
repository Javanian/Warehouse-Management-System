import { Component, computed, input } from '@angular/core';
import { formatQty } from '../core/format';

@Component({
  selector: 'sf-qty',
  template: `<span class="sf-qty" [class.pos]="signed() && num() > 0" [class.neg]="signed() && num() < 0">{{ text() }}</span>`,
})
export class Qty {
  readonly value = input.required<number | string | null>();
  readonly scale = input(0);
  readonly signed = input(false);
  readonly num = computed(() => Number(this.value() ?? 0));
  readonly text = computed(() => formatQty(this.value(), this.scale(), this.signed()));
}
