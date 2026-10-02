import { BreakpointObserver } from '@angular/cdk/layout';
import { Component, computed, inject } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatSidenavModule } from '@angular/material/sidenav';
import { MatToolbarModule } from '@angular/material/toolbar';
import { MatTooltipModule } from '@angular/material/tooltip';
import { map } from 'rxjs';
import { Auth, Capability } from '../core/auth.service';

interface NavItem { path: string; label: string; icon: string; cap: Capability; group: string; }

const NAV: NavItem[] = [
  { path: '/dashboard', label: 'Dashboard', icon: 'dashboard', cap: 'viewAll', group: 'Overview' },
  { path: '/stock', label: 'Stock on hand', icon: 'inventory_2', cap: 'viewAll', group: 'Overview' },
  { path: '/movements', label: 'Movement ledger', icon: 'receipt_long', cap: 'viewAll', group: 'Overview' },
  { path: '/purchase-orders', label: 'Purchase orders', icon: 'shopping_cart', cap: 'viewAll', group: 'Inbound' },
  { path: '/goods-receipts', label: 'Goods receipts', icon: 'move_to_inbox', cap: 'viewAll', group: 'Inbound' },
  { path: '/stock-issues', label: 'Stock issues', icon: 'outbox', cap: 'viewAll', group: 'Stock' },
  { path: '/stock-transfers', label: 'Transfers', icon: 'swap_horiz', cap: 'viewAll', group: 'Stock' },
  { path: '/adjustments', label: 'Adjustments', icon: 'fact_check', cap: 'viewAll', group: 'Stock' },
  { path: '/materials', label: 'Materials', icon: 'category', cap: 'viewAll', group: 'Master data' },
  { path: '/warehouses', label: 'Warehouses & bins', icon: 'warehouse', cap: 'viewAll', group: 'Master data' },
  { path: '/suppliers', label: 'Suppliers', icon: 'local_shipping', cap: 'viewAll', group: 'Master data' },
  { path: '/users', label: 'Users', icon: 'group', cap: 'adminUsers', group: 'Administration' },
  { path: '/audit', label: 'Audit log', icon: 'history', cap: 'viewAudit', group: 'Administration' },
];

@Component({
  selector: 'sf-shell',
  imports: [RouterOutlet, RouterLink, RouterLinkActive, MatSidenavModule, MatToolbarModule, MatButtonModule, MatIconModule, MatTooltipModule],
  template: `
    <a class="sf-skip" href="#main">Skip to content</a>
    <mat-toolbar class="sf-topbar">
      @if (!wide()) {
        <button mat-icon-button type="button" (click)="nav.toggle()" aria-label="Toggle navigation" matTooltip="Navigation">
          <mat-icon>menu</mat-icon>
        </button>
      }
      <span class="sf-product">StockFlow</span>
      <span class="sf-spacer"></span>
      @if (auth.me(); as me) {
        <span class="sf-user" data-testid="current-user">
          <span class="sf-user-name">{{ me.fullName }}</span>
          <span class="sf-role">{{ me.role }}</span>
        </span>
        <button mat-stroked-button type="button" (click)="auth.logout()" data-testid="logout">
          <mat-icon>logout</mat-icon> Sign out
        </button>
      }
    </mat-toolbar>
    <mat-sidenav-container class="sf-container">
      <mat-sidenav #nav [mode]="wide() ? 'side' : 'over'" [opened]="wide()" class="sf-nav">
        <nav aria-label="Main">
          @for (g of groups(); track g.name) {
            <h2 class="sf-nav-group">{{ g.name }}</h2>
            <ul>
              @for (i of g.items; track i.path) {
                <li>
                  <a [routerLink]="i.path" routerLinkActive="active" ariaCurrentWhenActive="page"
                     (click)="wide() || nav.close()">
                    <mat-icon aria-hidden="true">{{ i.icon }}</mat-icon><span>{{ i.label }}</span>
                  </a>
                </li>
              }
            </ul>
          }
        </nav>
      </mat-sidenav>
      <mat-sidenav-content>
        <main id="main" tabindex="-1" class="sf-main"><router-outlet /></main>
      </mat-sidenav-content>
    </mat-sidenav-container>
  `,
})
export class Shell {
  readonly auth = inject(Auth);
  readonly wide = toSignal(inject(BreakpointObserver).observe('(min-width: 1280px)').pipe(map((s) => s.matches)), {
    initialValue: true,
  });
  readonly groups = computed(() => {
    const items = NAV.filter((i) => this.auth.can(i.cap));
    const names = [...new Set(items.map((i) => i.group))];
    return names.map((name) => ({ name, items: items.filter((i) => i.group === name) }));
  });
}
