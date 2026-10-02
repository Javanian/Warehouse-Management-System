import { inject } from '@angular/core';
import { CanActivateFn, Router } from '@angular/router';
import { Auth, Capability } from './auth.service';

export const authGuard: CanActivateFn = async (_route, state) => {
  const auth = inject(Auth);
  const router = inject(Router);
  if (!auth.loaded() || !auth.me()) {
    await auth.refresh();
  }
  return auth.me() ? true : router.createUrlTree(['/login'], { queryParams: { returnUrl: state.url } });
};

export function capabilityGuard(cap: Capability): CanActivateFn {
  return () => {
    const auth = inject(Auth);
    const router = inject(Router);
    return auth.can(cap) ? true : router.createUrlTree(['/forbidden']);
  };
}
