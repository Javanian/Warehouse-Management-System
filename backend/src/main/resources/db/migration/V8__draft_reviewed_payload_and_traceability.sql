-- Store reviewed payload and human modification history
ALTER TABLE document_drafts
    ADD COLUMN IF NOT EXISTS reviewed_payload JSONB,
    ADD COLUMN IF NOT EXISTS review_notes TEXT,
    ADD COLUMN IF NOT EXISTS payload_hash VARCHAR(64);

CREATE OR REPLACE FUNCTION check_agent_draft_insert() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF CURRENT_USER = 'stockflow_agent' OR SESSION_USER = 'stockflow_agent' THEN
        IF NEW.status IS NOT NULL AND NEW.status != 'DRAFT' THEN
            RAISE EXCEPTION 'AGENT_UNAUTHORIZED_DRAFT_STATUS: Agent role can only insert drafts with status DRAFT'
                USING ERRCODE = 'restrict_violation';
        END IF;
        IF NEW.reviewed_by IS NOT NULL OR NEW.reviewed_at IS NOT NULL OR NEW.human_decision IS NOT NULL
           OR NEW.posted_gr_document_number IS NOT NULL OR NEW.goods_receipt_id IS NOT NULL
           OR NEW.reviewed_payload IS NOT NULL OR NEW.review_notes IS NOT NULL OR NEW.payload_hash IS NOT NULL THEN
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
    FOR EACH ROW
    EXECUTE FUNCTION check_agent_draft_insert();

CREATE OR REPLACE FUNCTION check_agent_audit_insert() RETURNS TRIGGER LANGUAGE plpgsql SECURITY DEFINER AS $$
DECLARE
    v_role VARCHAR(20);
    v_username VARCHAR(50);
    v_run_owner BIGINT;
    v_run_id TEXT;
BEGIN
    IF CURRENT_USER = 'stockflow_agent' OR SESSION_USER = 'stockflow_agent' THEN
        -- 1. Narrow allowed actions strictly to agent lifecycle and tool calls
        IF NEW.action NOT IN ('AGENT_CREATE_DRAFT', 'AGENT_TOOL_CALL', 'AGENT_TOOL_DENIED', 'AGENT_RUN_START', 'AGENT_RUN_FINISH') THEN
            RAISE EXCEPTION 'AGENT_UNAUTHORIZED_AUDIT_ACTION: Agent role cannot record audit action %', NEW.action
                USING ERRCODE = 'restrict_violation';
        END IF;

        -- 2. Validate actor identity: must not be null, must exist in users, must have role AGENT
        IF NEW.actor_id IS NULL THEN
            RAISE EXCEPTION 'AGENT_INVALID_ACTOR: actor_id cannot be null for agent audit'
                USING ERRCODE = 'restrict_violation';
        END IF;

        SELECT role, username INTO v_role, v_username FROM users WHERE id = NEW.actor_id;
        IF v_role IS NULL THEN
            RAISE EXCEPTION 'AGENT_INVALID_ACTOR: actor_id % does not exist', NEW.actor_id
                USING ERRCODE = 'restrict_violation';
        END IF;

        IF v_role != 'AGENT' THEN
            RAISE EXCEPTION 'AGENT_FORBIDDEN_AUDIT_ACTOR: actor_id % has role %, not AGENT', NEW.actor_id, v_role
                USING ERRCODE = 'restrict_violation';
        END IF;

        -- 3. Actor name must strictly match the registered agent username
        IF NEW.actor_name IS NULL OR NEW.actor_name != v_username THEN
            RAISE EXCEPTION 'AGENT_IDENTITY_MISMATCH: actor_name % does not match registered username %', NEW.actor_name, v_username
                USING ERRCODE = 'restrict_violation';
        END IF;

        -- 4. Check run ownership if entity is an AGENT_RUN or entity_id is a run ID
        IF NEW.entity_type = 'AGENT_RUN' OR (NEW.entity_id IS NOT NULL AND NEW.entity_id LIKE 'RUN-%') THEN
            SELECT agent_id INTO v_run_owner FROM agent_runs WHERE run_id = NEW.entity_id;
            IF v_run_owner IS NULL THEN
                RAISE EXCEPTION 'AGENT_AUDIT_INVALID_RUN: run_id % does not exist in agent_runs', NEW.entity_id
                    USING ERRCODE = 'restrict_violation';
            END IF;
            IF v_run_owner != NEW.actor_id THEN
                RAISE EXCEPTION 'AGENT_AUDIT_RUN_OWNERSHIP_MISMATCH: actor % does not own run %', NEW.actor_id, NEW.entity_id
                    USING ERRCODE = 'restrict_violation';
            END IF;
        END IF;

        -- Check draft ownership if entity is a DOCUMENT_DRAFT
        IF NEW.entity_type = 'DOCUMENT_DRAFT' AND NEW.entity_id IS NOT NULL THEN
            SELECT r.agent_id INTO v_run_owner
            FROM document_drafts d
            JOIN agent_runs r ON r.run_id = d.run_id
            WHERE d.draft_id = NEW.entity_id;
            IF v_run_owner IS NOT NULL AND v_run_owner != NEW.actor_id THEN
                RAISE EXCEPTION 'AGENT_AUDIT_DRAFT_OWNERSHIP_MISMATCH: actor % does not own draft %', NEW.actor_id, NEW.entity_id
                    USING ERRCODE = 'restrict_violation';
            END IF;
        END IF;

        -- Also check runId in details if present
        IF NEW.details IS NOT NULL AND NEW.details ? 'runId' THEN
            v_run_id := NEW.details ->> 'runId';
            IF v_run_id IS NOT NULL AND v_run_id != 'NONE' AND v_run_id != 'UNKNOWN' THEN
                SELECT agent_id INTO v_run_owner FROM agent_runs WHERE run_id = v_run_id;
                IF v_run_owner IS NULL THEN
                    RAISE EXCEPTION 'AGENT_AUDIT_INVALID_RUN: runId % in details does not exist', v_run_id
                        USING ERRCODE = 'restrict_violation';
                END IF;
                IF v_run_owner != NEW.actor_id THEN
                    RAISE EXCEPTION 'AGENT_AUDIT_RUN_OWNERSHIP_MISMATCH: actor % does not own run % in details', NEW.actor_id, v_run_id
                        USING ERRCODE = 'restrict_violation';
                END IF;
            END IF;
        END IF;
    END IF;
    RETURN NEW;
END $$;

DROP TRIGGER IF EXISTS trg_agent_audit_insert ON audit_logs;
CREATE TRIGGER trg_agent_audit_insert
    BEFORE INSERT ON audit_logs
    FOR EACH ROW
    EXECUTE FUNCTION check_agent_audit_insert();

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'stockflow_agent') THEN
        -- Revoke all permissions on audit_logs, then grant only narrow append-only column insert
        REVOKE ALL ON audit_logs FROM stockflow_agent;
        GRANT INSERT (actor_id, actor_name, action, entity_type, entity_id, request_id, ip_address, details) ON audit_logs TO stockflow_agent;
        GRANT USAGE, SELECT ON SEQUENCE audit_logs_id_seq TO stockflow_agent;
    END IF;
END $$;
