export type DocKind = 'goods-receipts' | 'stock-issues' | 'stock-transfers';

export const DOC_KINDS: Record<DocKind, { title: string; single: string; icon: string; emptyHint: string }> = {
  'goods-receipts': { title: 'Goods receipts', single: 'Goods receipt', icon: 'move_to_inbox',
    emptyHint: 'Receive goods from an open purchase order to create a receipt.' },
  'stock-issues': { title: 'Stock issues', single: 'Stock issue', icon: 'outbox',
    emptyHint: 'Issue stock to production, maintenance or internal use to create an issue document.' },
  'stock-transfers': { title: 'Transfers', single: 'Transfer', icon: 'swap_horiz',
    emptyHint: 'Move stock between bins or warehouses to create a transfer document.' },
};

export const ISSUE_REASONS = ['PRODUCTION', 'MAINTENANCE', 'INTERNAL_USE', 'SCRAP', 'SAMPLE'];
