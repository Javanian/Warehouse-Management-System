-- V9: Hardening SECURITY DEFINER audit trigger against temporary-table shadowing and search_path hijacking
-- Fixes CVE-like vulnerability where stockflow_agent creates temporary tables (users, agent_runs)
-- to shadow canonical authentication tables during trigger evaluation.

-- Lock search_path explicitly to public (pg_temp is excluded from search_path during trigger execution)
CREATE OR REPLACE FUNCTION public.check_agent_audit_insert() RETURNS TRIGGER
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public
AS $$
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

        -- 2. Validate actor identity: must not be null, must exist in public.users, must have role AGENT
        IF NEW.actor_id IS NULL THEN
            RAISE EXCEPTION 'AGENT_INVALID_ACTOR: actor_id cannot be null for agent audit'
                USING ERRCODE = 'restrict_violation';
        END IF;

        -- Fully schema-qualified query to canonical public.users table
        SELECT u.role, u.username INTO v_role, v_username
        FROM public.users u
        WHERE u.id = NEW.actor_id;

        IF v_role IS NULL THEN
            RAISE EXCEPTION 'AGENT_INVALID_ACTOR: actor_id % does not exist', NEW.actor_id
                USING ERRCODE = 'restrict_violation';
        END IF;

        IF v_role != 'AGENT' THEN
            RAISE EXCEPTION 'AGENT_FORBIDDEN_AUDIT_ACTOR: actor_id % has role %, not AGENT', NEW.actor_id, v_role
                USING ERRCODE = 'restrict_violation';
        END IF;

        -- 3. Actor name must strictly match the registered agent username in public.users
        IF NEW.actor_name IS NULL OR NEW.actor_name != v_username THEN
            RAISE EXCEPTION 'AGENT_IDENTITY_MISMATCH: actor_name % does not match registered username %', NEW.actor_name, v_username
                USING ERRCODE = 'restrict_violation';
        END IF;

        -- 4. Check run ownership if entity is an AGENT_RUN or entity_id is a run ID
        IF NEW.entity_type = 'AGENT_RUN' OR (NEW.entity_id IS NOT NULL AND NEW.entity_id LIKE 'RUN-%') THEN
            SELECT r.agent_id INTO v_run_owner
            FROM public.agent_runs r
            WHERE r.run_id = NEW.entity_id;

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
            FROM public.document_drafts d
            JOIN public.agent_runs r ON r.run_id = d.run_id
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
                SELECT r.agent_id INTO v_run_owner
                FROM public.agent_runs r
                WHERE r.run_id = v_run_id;

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
END;
$$;

-- Revoke CREATE privilege on schema public from stockflow_agent if role exists
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'stockflow_agent') THEN
        REVOKE CREATE ON SCHEMA public FROM stockflow_agent;
    END IF;
END $$;
