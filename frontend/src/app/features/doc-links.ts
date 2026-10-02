export function docLink(documentType: string | null | undefined, documentId: number | null | undefined): unknown[] | null {
  if (!documentType || documentId === null || documentId === undefined) {
    return null;
  }
  switch (documentType) {
    case 'GOODS_RECEIPT': return ['/goods-receipts', documentId];
    case 'STOCK_ISSUE': return ['/stock-issues', documentId];
    case 'STOCK_TRANSFER': return ['/stock-transfers', documentId];
    case 'STOCK_ADJUSTMENT': return ['/adjustments', documentId];
    default: return null;
  }
}
