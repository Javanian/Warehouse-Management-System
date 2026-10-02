import { newIdempotencyKey } from '../core/api.service';

export class CommandKey {
  private key = newIdempotencyKey();
  private payload: string | null = null;

  for(body: unknown): string {
    const p = JSON.stringify(body);
    if (this.payload !== null && this.payload !== p) {
      this.key = newIdempotencyKey();
    }
    this.payload = p;
    return this.key;
  }

  reset(): void {
    this.key = newIdempotencyKey();
    this.payload = null;
  }
}
