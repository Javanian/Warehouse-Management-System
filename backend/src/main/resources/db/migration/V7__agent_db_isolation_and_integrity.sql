-- Goods receipt foreign key reference
ALTER TABLE document_drafts
    ADD COLUMN IF NOT EXISTS goods_receipt_id BIGINT UNIQUE REFERENCES goods_receipts (id);

ALTER TABLE audit_logs ALTER COLUMN entity_id TYPE VARCHAR(64);

-- Concurrency safe unique constraint for stock movements
ALTER TABLE stock_movements
    DROP CONSTRAINT IF EXISTS ux_stock_movements_doc_type_number;
ALTER TABLE stock_movements
    ADD CONSTRAINT ux_stock_movements_doc_type_number UNIQUE (document_type, document_number);

-- Immutability triggers for audit events
CREATE OR REPLACE FUNCTION forbid_audit_mutation() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'AUDIT_LOG_IMMUTABLE: Audit and tool events are append-only and cannot be modified or deleted'
        USING ERRCODE = 'restrict_violation';
END $$;

DROP TRIGGER IF EXISTS trg_agent_tool_calls_immutable ON agent_tool_calls;
CREATE TRIGGER trg_agent_tool_calls_immutable
    BEFORE UPDATE OR DELETE ON agent_tool_calls
    FOR EACH ROW EXECUTE FUNCTION forbid_audit_mutation();

DROP TRIGGER IF EXISTS trg_audit_logs_immutable ON audit_logs;
CREATE TRIGGER trg_audit_logs_immutable
    BEFORE UPDATE OR DELETE ON audit_logs
    FOR EACH ROW EXECUTE FUNCTION forbid_audit_mutation();

DROP TRIGGER IF EXISTS trg_agent_runs_no_delete ON agent_runs;
CREATE TRIGGER trg_agent_runs_no_delete
    BEFORE DELETE ON agent_runs
    FOR EACH ROW EXECUTE FUNCTION forbid_audit_mutation();

-- Domain views for restricted agent read access
CREATE OR REPLACE VIEW v_agent_purchase_orders AS
    SELECT po.id, po.po_number, s.code AS supplier_code, s.name AS supplier_name,
           po.po_date, po.expected_date, po.status
    FROM purchase_orders po
    JOIN suppliers s ON s.id = po.supplier_id;

CREATE OR REPLACE VIEW v_agent_purchase_order_items AS
    SELECT poi.id, poi.purchase_order_id, poi.line_no, m.code AS material_code,
           m.name AS material_name, poi.uom_code, poi.ordered_quantity, poi.received_quantity,
           (poi.ordered_quantity - poi.received_quantity) AS outstanding_quantity
    FROM purchase_order_items poi
    JOIN materials m ON m.id = poi.material_id;

CREATE OR REPLACE VIEW v_agent_materials AS
    SELECT id, code, name, description, uom_code AS base_uom, active
    FROM materials;

CREATE OR REPLACE VIEW v_agent_inventory_balances AS
    SELECT b.id, m.code AS material_code, w.code AS warehouse_code,
           sl.code AS location_code, b.quantity, b.updated_at
    FROM inventory_balances b
    JOIN materials m ON m.id = b.material_id
    JOIN storage_locations sl ON sl.id = b.location_id
    JOIN warehouses w ON w.id = sl.warehouse_id;

CREATE OR REPLACE VIEW v_agent_storage_locations AS
    SELECT sl.id, sl.code AS location_code, sl.name AS location_name,
           sl.location_type, w.code AS warehouse_code, sl.active
    FROM storage_locations sl
    JOIN warehouses w ON w.id = sl.warehouse_id;

-- Guard triggers against forged draft status or ownership
CREATE OR REPLACE FUNCTION check_agent_draft_insert() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF CURRENT_USER = 'stockflow_agent' THEN
        IF NEW.status IS NOT NULL AND NEW.status != 'DRAFT' THEN
            RAISE EXCEPTION 'AGENT_UNAUTHORIZED_DRAFT_STATUS: Agent role can only insert drafts with status DRAFT'
                USING ERRCODE = 'restrict_violation';
        END IF;
        IF NEW.reviewed_by IS NOT NULL OR NEW.reviewed_at IS NOT NULL OR NEW.human_decision IS NOT NULL
           OR NEW.posted_gr_document_number IS NOT NULL OR NEW.goods_receipt_id IS NOT NULL THEN
            RAISE EXCEPTION 'AGENT_UNAUTHORIZED_DRAFT_COLUMNS: Agent role cannot set review, human approval, or GR link columns'
                USING ERRCODE = 'restrict_violation';
        END IF;
        IF NEW.run_id IS NOT NULL THEN
            IF NOT EXISTS (SELECT 1 FROM agent_runs WHERE run_id = NEW.run_id) THEN
                RAISE EXCEPTION 'INVALID_RUN: run_id does not exist in agent_runs'
                    USING ERRCODE = 'restrict_violation';
            END IF;
        END IF;
    END IF;
    RETURN NEW;
END $$;

DROP TRIGGER IF EXISTS trg_agent_draft_insert ON document_drafts;
CREATE TRIGGER trg_agent_draft_insert
    BEFORE INSERT ON document_drafts
    FOR EACH ROW EXECUTE FUNCTION check_agent_draft_insert();

CREATE OR REPLACE FUNCTION check_agent_run_update() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF CURRENT_USER = 'stockflow_agent' THEN
        IF NEW.agent_id != OLD.agent_id OR NEW.triggered_by != OLD.triggered_by OR NEW.run_id != OLD.run_id THEN
            RAISE EXCEPTION 'AGENT_CANNOT_FORGE_RUN_OWNERSHIP: Agent cannot alter run identity or ownership'
                USING ERRCODE = 'restrict_violation';
        END IF;
    END IF;
    RETURN NEW;
END $$;

DROP TRIGGER IF EXISTS trg_agent_run_update ON agent_runs;
CREATE TRIGGER trg_agent_run_update
    BEFORE UPDATE ON agent_runs
    FOR EACH ROW EXECUTE FUNCTION check_agent_run_update();

-- Role permissions for stockflow_agent
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'stockflow_agent') THEN
        REVOKE ALL ON inventory_balances, stock_movements, stock_movement_entries,
                      purchase_orders, purchase_order_items, goods_receipts, goods_receipt_items,
                      materials, suppliers, warehouses, storage_locations, users, audit_logs
        FROM stockflow_agent;

        GRANT SELECT ON v_agent_purchase_orders, v_agent_purchase_order_items,
                        v_agent_materials, v_agent_inventory_balances,
                        v_agent_storage_locations
        TO stockflow_agent;

        REVOKE ALL ON document_drafts FROM stockflow_agent;

        GRANT SELECT ON document_drafts TO stockflow_agent;
        GRANT INSERT (draft_id, run_id, source_document_path, source_document_sha256,
                      detected_po_number, detected_delivery_note, detected_supplier_code,
                      detected_delivery_date, status, confidence_score, validation_outcome,
                      validation_reason, provenance, line_items)
        ON document_drafts TO stockflow_agent;

        GRANT SELECT, INSERT ON agent_tool_calls TO stockflow_agent;
        GRANT SELECT, INSERT, UPDATE (status, finished_at, latency_ms, error_reason, summary) ON agent_runs TO stockflow_agent;

        GRANT USAGE, SELECT ON SEQUENCE document_drafts_id_seq TO stockflow_agent;
        GRANT USAGE, SELECT ON SEQUENCE agent_runs_id_seq TO stockflow_agent;
        GRANT USAGE, SELECT ON SEQUENCE agent_tool_calls_id_seq TO stockflow_agent;
    END IF;
END $$;
