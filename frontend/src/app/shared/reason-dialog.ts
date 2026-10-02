import { Component, inject } from '@angular/core';
import { FormControl, ReactiveFormsModule, Validators } from '@angular/forms';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatButtonModule } from '@angular/material/button';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';

export interface ReasonDialogData {
  title: string;
  message: string;
  confirm: string;
  label: string;
  required: boolean;
  danger?: boolean;
}

@Component({
  selector: 'sf-reason-dialog',
  imports: [MatDialogModule, MatButtonModule, MatFormFieldModule, MatInputModule, ReactiveFormsModule],
  template: `
    <h2 mat-dialog-title>{{ data.title }}</h2>
    <mat-dialog-content>
      <p>{{ data.message }}</p>
      <mat-form-field class="sf-full">
        <mat-label>{{ data.label }}</mat-label>
        <textarea matInput [formControl]="text" rows="3" maxlength="500" cdkFocusInitial></textarea>
        @if (text.hasError('required')) {
          <mat-error>{{ data.label }} is required</mat-error>
        }
      </mat-form-field>
    </mat-dialog-content>
    <mat-dialog-actions align="end">
      <button mat-button type="button" mat-dialog-close>Cancel</button>
      <button mat-flat-button type="button" [class.sf-danger]="data.danger" (click)="ok()">{{ data.confirm }}</button>
    </mat-dialog-actions>
  `,
})
export class ReasonDialog {
  readonly data = inject<ReasonDialogData>(MAT_DIALOG_DATA);
  private ref = inject(MatDialogRef<ReasonDialog, string>);
  readonly text = new FormControl('', this.data.required ? [Validators.required, Validators.maxLength(500)] : []);

  ok(): void {
    if (this.text.invalid) {
      this.text.markAsTouched();
      return;
    }
    this.ref.close((this.text.value ?? '').trim());
  }
}
