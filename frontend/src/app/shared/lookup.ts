import { Component, DestroyRef, forwardRef, inject, input, signal } from '@angular/core';
import { ControlValueAccessor, FormControl, NG_VALUE_ACCESSOR, ReactiveFormsModule } from '@angular/forms';
import { MatAutocompleteModule, MatAutocompleteSelectedEvent } from '@angular/material/autocomplete';
import { MatInputModule } from '@angular/material/input';
import { debounceTime, distinctUntilChanged, switchMap, of, catchError } from 'rxjs';
import { Api, Query } from '../core/api.service';
import { Page } from '../core/models';

export interface LookupOption { id: number; label: string; sub?: string; raw: unknown; }
export type LookupKind = 'material' | 'location' | 'supplier';

const CONFIG: Record<LookupKind, { url: string; map: (r: any) => LookupOption; extra: Query }> = {
  material: {
    url: '/api/materials',
    extra: { active: true },
    map: (m) => ({ id: m.id, label: m.code, sub: `${m.name} · ${m.uomCode}`, raw: m }),
  },
  location: {
    url: '/api/storage-locations',
    extra: { active: true },
    map: (l) => ({ id: l.id, label: `${l.warehouseCode}/${l.code}`, sub: l.name ?? l.locationType, raw: l }),
  },
  supplier: {
    url: '/api/suppliers',
    extra: { active: true },
    map: (s) => ({ id: s.id, label: s.code, sub: s.name, raw: s }),
  },
};

@Component({
  selector: 'sf-lookup',
  imports: [MatAutocompleteModule, MatInputModule, ReactiveFormsModule],
  providers: [{ provide: NG_VALUE_ACCESSOR, useExisting: forwardRef(() => Lookup), multi: true }],
  template: `
    <input matInput [formControl]="text" [matAutocomplete]="auto" [attr.aria-label]="ariaLabel()"
           [placeholder]="placeholder()" (blur)="onBlur()" autocomplete="off" spellcheck="false" />
    <mat-autocomplete #auto="matAutocomplete" (optionSelected)="pick($event)" [displayWith]="display">
      @for (o of options(); track o.id) {
        <mat-option [value]="o"><span class="sf-code">{{ o.label }}</span> <small class="sf-muted">{{ o.sub }}</small></mat-option>
      }
      @if (!searching() && options().length === 0 && text.value) {
        <mat-option disabled>No match</mat-option>
      }
    </mat-autocomplete>
  `,
  host: { class: 'sf-lookup' },
})
export class Lookup implements ControlValueAccessor {
  readonly kind = input.required<LookupKind>();
  readonly ariaLabel = input('');
  readonly placeholder = input('Type to search');
  readonly filter = input<Query>({});
  readonly options = signal<LookupOption[]>([]);
  readonly searching = signal(false);
  readonly text = new FormControl<string | LookupOption>('');
  selected: LookupOption | null = null;
  private onChange: (v: number | null) => void = () => {};
  private onTouched: () => void = () => {};
  private api = inject(Api);

  constructor() {
    this.text.valueChanges
      .pipe(
        debounceTime(200),
        distinctUntilChanged(),
        switchMap((v) => {
          if (typeof v !== 'string') {
            return of(null);
          }
          if (this.selected && v !== this.selected.label) {
            this.selected = null;
            this.onChange(null);
          }
          this.searching.set(true);
          const c = CONFIG[this.kind()];
          return this.api.get<Page<unknown>>(c.url, { ...c.extra, ...this.filter(), q: v, size: 15 }).pipe(catchError(() => of(null)));
        }),
      )
      .subscribe((p) => {
        this.searching.set(false);
        if (p) {
          this.options.set(p.content.map(CONFIG[this.kind()].map));
        }
      });
    inject(DestroyRef);
  }

  display = (v: LookupOption | string | null): string => (v && typeof v === 'object' ? v.label : (v ?? ''));

  pick(e: MatAutocompleteSelectedEvent): void {
    this.selected = e.option.value as LookupOption;
    this.onChange(this.selected.id);
  }

  onBlur(): void {
    this.onTouched();
  }

  preset(o: LookupOption | null): void {
    this.selected = o;
    this.text.setValue(o ?? '', { emitEvent: false });
  }

  writeValue(v: number | null): void {
    if (v === null || v === undefined) {
      this.selected = null;
      this.text.setValue('', { emitEvent: false });
    } else if (!this.selected || this.selected.id !== v) {
      const c = CONFIG[this.kind()];
      this.api.get<any>(`${c.url}/${v}`).subscribe({ next: (r) => this.preset(c.map(r)), error: () => {} });
    }
  }
  registerOnChange(fn: (v: number | null) => void): void { this.onChange = fn; }
  registerOnTouched(fn: () => void): void { this.onTouched = fn; }
  setDisabledState(d: boolean): void { if (d) { this.text.disable(); } else { this.text.enable(); } }
}
