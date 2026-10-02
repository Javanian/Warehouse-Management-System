export type Role = 'ADMIN' | 'SUPERVISOR' | 'OPERATOR' | 'VIEWER';

export interface Me { id: number; username: string; fullName: string; role: Role; }

export interface Page<T> { content: T[]; page: number; size: number; totalElements: number; totalPages: number; }

export interface FieldError { field: string; message: string; }
export interface ApiError {
  status: number; error: string; message: string; path?: string; requestId?: string;
  fieldErrors?: FieldError[]; details?: Record<string, unknown>;
}

export interface Uom { code: string; name: string; scale: number; }
export interface Material {
  id: number; code: string; name: string; description: string | null; category: string | null; uomCode: string;
  uomScale: number; minimumStock: number; active: boolean; demo: boolean; totalQuantity: number; lowStock: boolean;
  referenced: boolean; version: number;
}
export interface Supplier { id: number; code: string; name: string; active: boolean; demo: boolean; version: number; }
export interface Warehouse {
  id: number; code: string; name: string; description: string | null; active: boolean; demo: boolean;
  locationCount: number; version: number;
}
export interface StorageLocation {
  id: number; warehouseId: number; warehouseCode: string; code: string; name: string | null; locationType: string;
  active: boolean; warehouseActive: boolean; demo: boolean; stockLines: number; version: number;
}
export interface PoSummary {
  id: number; poNumber: string; supplierId: number; supplierCode: string; supplierName: string; poDate: string;
  expectedDate: string | null; status: PoStatus; itemCount: number; outstandingLines: number; demo: boolean; version: number;
}
export type PoStatus = 'DRAFT' | 'OPEN' | 'PARTIALLY_RECEIVED' | 'COMPLETED' | 'CANCELLED';
export interface PoItem {
  id: number; lineNo: number; materialId: number; materialCode: string; materialName: string; uomCode: string;
  uomScale: number; orderedQuantity: number; receivedQuantity: number; outstandingQuantity: number;
}
export interface PoReceipt { id: number; documentNumber: string; status: string; postedAt: string; postedBy: string; }
export interface PoDetail {
  id: number; poNumber: string; supplierId: number; supplierCode: string; supplierName: string; poDate: string;
  expectedDate: string | null; status: PoStatus; notes: string | null; cancelReason: string | null; createdBy: string;
  createdAt: string; openedAt: string | null; cancelledAt: string | null; demo: boolean; version: number;
  items: PoItem[]; receipts: PoReceipt[];
}
export interface DocLine {
  lineNo: number; materialId: number; materialCode: string; materialName: string; uomCode: string; uomScale: number;
  fromLocationId: number | null; fromLocation: string | null; toLocationId: number | null; toLocation: string | null;
  quantity: number; purchaseOrderItemId: number | null;
}
export interface DocView {
  id: number; type: string; documentNumber: string; status: 'POSTED' | 'REVERSED'; reasonCode: string | null;
  reference: string | null; notes: string | null; purchaseOrderId: number | null; poNumber: string | null;
  postedBy: string; postedAt: string; businessDate: string; movementNumber: string;
  reversalMovementNumber: string | null; reversalDocumentNumber: string | null; reversedBy: string | null;
  reversedAt: string | null; reversalReason: string | null; lines: DocLine[];
}
export interface DocSummary {
  id: number; documentNumber: string; status: string; reference: string | null; reasonCode: string | null;
  poNumber: string | null; lineCount: number; postedBy: string; postedAt: string; businessDate: string;
}
export interface BalanceRow {
  id: number; materialId: number; materialCode: string; materialName: string; uomCode: string; uomScale: number;
  locationId: number; locationCode: string; warehouseId: number; warehouseCode: string; quantity: number;
  version: number; updatedAt: string;
}
export interface MovementRow {
  movementId: number; movementNumber: string; movementType: string; documentType: string; documentId: number | null;
  documentNumber: string; reversalOfMovementNumber: string | null; postedAt: string; businessDate: string;
  postedBy: string; lineNo: number; materialId: number; materialCode: string; uomCode: string; uomScale: number;
  locationId: number; location: string; quantityDelta: number; balanceAfter: number;
}
export interface Adjustment {
  id: number; documentNumber: string; status: 'PENDING' | 'APPROVED' | 'REJECTED'; materialId: number;
  materialCode: string; materialName: string; uomCode: string; uomScale: number; locationId: number; location: string;
  physicalQuantity: number; observedQuantity: number; observedVersion: number; delta: number; currentQuantity: number;
  currentVersion: number; stale: boolean; reason: string; requestedById: number; requestedBy: string;
  requestedAt: string; decidedBy: string | null; decidedAt: string | null; decisionNote: string | null;
  movementNumber: string | null; version: number;
}
export interface DashboardData {
  businessDate: string; timezone: string; generatedAt: string;
  counts: { activeMaterials: number; lowStockMaterials: number; receiptsToday: number; issuesToday: number;
    transfersToday: number; pendingAdjustments: number; openPurchaseOrders: number; };
  lowStock: { materialId: number; materialCode: string; materialName: string; uomCode: string; uomScale: number;
    onHand: number; minimumStock: number; shortage: number; }[];
  activity: { businessDate: string; receipts: number; issues: number; transfers: number; }[];
  recent: { movementId: number; movementNumber: string; movementType: string; documentType: string;
    documentId: number | null; documentNumber: string; postedAt: string; postedBy: string; entryCount: number; }[];
}
export interface UserRow {
  id: number; username: string; email: string; fullName: string; role: Role; active: boolean; demo: boolean;
  createdAt: string; version: number;
}
export interface AuditEntry {
  id: number; occurredAt: string; actorId: number | null; actorName: string | null; action: string; entityType: string;
  entityId: string | null; requestId: string | null; ipAddress: string | null; details: string | null;
}
