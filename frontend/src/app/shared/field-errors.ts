import { AbstractControl, FormGroup } from '@angular/forms';
import { ApiError } from '../core/models';

export function applyServerErrors(form: FormGroup, err: ApiError): string[] {
  const unmatched: string[] = [];
  for (const fe of err.fieldErrors ?? []) {
    const path = fe.field.replace(/\[(\d+)\]/g, '.$1');
    const ctrl: AbstractControl | null = form.get(path);
    if (ctrl) {
      ctrl.setErrors({ ...(ctrl.errors ?? {}), server: fe.message });
      ctrl.markAsTouched();
    } else {
      unmatched.push(`${fe.field}: ${fe.message}`);
    }
  }
  return unmatched;
}
