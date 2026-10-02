# StockFlow: Warehouse Management System

StockFlow is a warehouse management application built as a modular monolith. It provides inventory tracking, purchase order receipt management, immutable ledger posting, physical count adjustment workflows, and role-based access control.

## Stack & Architecture

- **Backend**: Java 21, Spring Boot MVC, Spring JDBC (`JdbcClient`), Flyway migrations, PostgreSQL 16.
- **Frontend**: Angular 22, Angular Material, IBM Plex Sans & Mono typography.
- **Architecture**: Modular monolith with package-by-feature structure. A single `InventoryPostingService` acts as the writer for inventory balances and immutable movement ledgers with row-level locking.
- **Security**: Same-origin session-cookie authentication with CSRF tokens (`XSRF-TOKEN`). URL and method-level RBAC (`ADMIN`, `SUPERVISOR`, `OPERATOR`, `VIEWER`).
- **Data Integrity**: Concurrency-safe document numbering, idempotent request execution (`Idempotency-Key`), two-person rule for stock adjustments to prevent self-approval, and linked document reversals.

## Features

1. **Identity & RBAC**:
   - Authentication, logout, brute-force protection, instant session deactivation for disabled accounts.
   - Roles: `ADMIN` (master data, users), `SUPERVISOR` (PO management, adjustments, reversals, audit), `OPERATOR` (receiving, issues, transfers), `VIEWER` (read-only queries).
2. **Master Data**:
   - Materials with exact UOM scaling and minimum stock alert thresholds.
   - Warehouses and storage locations/bins (BIN, RACK, FLOOR, STAGING, QUARANTINE).
   - Suppliers with reference tracking.
3. **Inbound Logistics (PO & Goods Receipt)**:
   - Purchase order lifecycle: `DRAFT -> OPEN -> PARTIALLY_RECEIVED -> COMPLETED / CANCELLED`.
   - Partial Goods Receipt directly to multiple storage bins against open PO lines.
   - Guard against over-receipt beyond outstanding quantities.
4. **Stock Operations**:
   - Stock Issue for production, maintenance, internal use, scrap, or samples.
   - Stock Transfer between bins/warehouses with atomic credit/debit and total quantity conservation.
   - Negative balance prevention and lock ordering `(material_id, location_id)` to avoid deadlocks.
5. **Physical-Count Adjustments**:
   - Cycle-count request with system count snapshot versioning.
   - Stale count detection: if stock moves after count is recorded, approval is rejected.
   - Anti-self-approval enforcement at application and database constraint levels.
6. **Full-Document Reversals**:
   - Reversal for Goods Receipts, Stock Issues, and Stock Transfers.
   - Linked reversal movements with inverted quantities and duplicate reversal protection.
   - Restores PO received quantities when a Goods Receipt is reversed.
7. **Dashboard & Reconciliation**:
   - Active materials, low-stock alerts, posted document activity, open PO tracking.
   - Automated reconciliation view ensuring balance equals net signed movement ledger entries.

## Local Development & Setup

### Prerequisites
- JDK 21+
- Node.js 22+ and npm
- PostgreSQL 16

### Starting PostgreSQL (Port: 55432)
```powershell
.\scripts\db-start.ps1
```

### Running Backend
```powershell
.\scripts\mvn.ps1 -Goals "spring-boot:run"
```
Or via Maven wrapper:
```bash
cd backend
./mvnw spring-boot:run
```

### Running Frontend
```bash
cd frontend
npm install
npm start
```
Frontend runs at `http://localhost:4200` (proxied to backend at `http://localhost:8085`).
When packaged, the Spring Boot backend directly serves the compiled frontend on `http://localhost:8085`.

## Automated Tests

### Backend Integration Tests (Real PostgreSQL)
```powershell
.\scripts\mvn.ps1 -Goals "test"
```
Includes:
- HTTP Authorization matrix and RBAC enforcement.
- Concurrent issue races (80 and 50 against balance 100).
- Concurrent partial receipts against outstanding limits.
- Transfer atomicity and rollback verification.
- Two-person physical count approval and anti-self-approval checks.
- Duplicate reversal and idempotency replay tests.

### Frontend Unit Tests (Vitest)
```bash
cd frontend
npm test -- --watch=false
```

## Demo Credentials

When running with `stockflow.demo.seed=true`:
| Username | Password | Role |
|---|---|---|
| `admin` | `Demo#2026` | `ADMIN` |
| `supervisor` | `Demo#2026` | `SUPERVISOR` |
| `operator` | `Demo#2026` | `OPERATOR` |
| `viewer` | `Demo#2026` | `VIEWER` |

## Author
- Javanian <teesanfajar@gmail.com>
