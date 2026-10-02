import { Component, inject } from '@angular/core';
import { RouterLink } from '@angular/router';
import { Auth } from '../core/auth.service';

@Component({
  selector: 'sf-forbidden',
  imports: [RouterLink],
  template: `
    <section class="sf-page sf-narrow">
      <h1>Access not allowed</h1>
      <p>Your role <strong>{{ auth.role() }}</strong> cannot open this page. Ask an administrator if you need access.</p>
      <a routerLink="/dashboard">Back to dashboard</a>
    </section>
  `,
})
export class Forbidden {
  readonly auth = inject(Auth);
}
