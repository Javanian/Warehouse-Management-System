import { Component } from '@angular/core';
import { RouterLink } from '@angular/router';

@Component({
  selector: 'sf-not-found',
  imports: [RouterLink],
  template: `<section class="sf-page sf-narrow"><h1>Page not found</h1><p>This address does not exist.</p>
    <a routerLink="/dashboard">Back to dashboard</a></section>`,
})
export class NotFound {}
