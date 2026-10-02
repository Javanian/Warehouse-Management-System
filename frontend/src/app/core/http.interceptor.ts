import { HttpErrorResponse, HttpInterceptorFn } from '@angular/common/http';
import { inject } from '@angular/core';
import { catchError, throwError } from 'rxjs';
import { Auth } from './auth.service';

export const sessionInterceptor: HttpInterceptorFn = (req, next) => {
  const auth = inject(Auth);
  return next(req).pipe(
    catchError((e: unknown) => {
      if (e instanceof HttpErrorResponse && e.status === 401 && !req.url.includes('/api/auth/')) {
        const code = (e.error && e.error.error) || 'UNAUTHENTICATED';
        auth.sessionEnded(code, (e.error && e.error.message) || 'Session ended');
      }
      return throwError(() => e);
    }),
  );
};
