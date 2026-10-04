package com.stockflow.dataquality;

import com.stockflow.audit.AuditService;
import com.stockflow.common.ApiException;
import com.stockflow.common.PageQuery;
import com.stockflow.common.PageResponse;
import com.stockflow.common.SqlFilter;
import com.stockflow.identity.Actor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Service
public class DataQualityService {

    public record QualityIssue(
            long id,
            String issueKey,
            String ruleCode,
            String ruleName,
            String severity,
            String entityType,
            long entityId,
            String entityCode,
            String entityName,
            String detectedDetail,
            String recommendedAction,
            String status,
            Long resolvedBy,
            Instant resolvedAt,
            String resolutionNotes,
            Instant createdAt
    ) {}

    public record QualitySummary(
            long totalOpen,
            long highSeverity,
            long mediumSeverity,
            long lowSeverity,
            Map<String, Long> countByRule
    ) {}

    public record ResolveRequest(
            String decision, // RESOLVED or IGNORED
            String notes
    ) {}

    private final JdbcClient jdbc;
    private final AuditService audit;

    public DataQualityService(JdbcClient jdbc, AuditService audit) {
        this.jdbc = jdbc;
        this.audit = audit;
    }

    @Transactional(propagation = Propagation.REQUIRED)
    public int scanCatalog() {
        int createdCount = 0;

        // Rule 1: Inactive materials with positive balance
        record InactiveRow(long id, String code, String name, double totalQty) {}
        List<InactiveRow> inactives = jdbc.sql("select m.id, m.code, m.name, sum(b.quantity) as total_qty "
                + "from materials m join inventory_balances b on b.material_id = m.id "
                + "where m.active = false group by m.id, m.code, m.name having sum(b.quantity) > 0")
                .query(InactiveRow.class)
                .list();

        for (InactiveRow r : inactives) {
            String key = "RULE-DQ-01:MAT:" + r.id();
            createdCount += upsertIssue(key, "RULE-DQ-01", "Inactive Material With Positive Stock",
                    "HIGH", "MATERIAL", r.id(), r.code(), r.name(),
                    "Material is deactivated but still holds " + r.totalQty() + " units in inventory.",
                    "Reactivate material in catalog or issue physical stock before deactivation.");
        }

        // Rule 2: Missing or trivial description
        record DescRow(long id, String code, String name, String description) {}
        List<DescRow> badDesc = jdbc.sql("select id, code, name, description from materials "
                + "where description is null or length(trim(description)) < 5 or trim(description) = code")
                .query(DescRow.class)
                .list();

        for (DescRow r : badDesc) {
            String key = "RULE-DQ-02:MAT:" + r.id();
            createdCount += upsertIssue(key, "RULE-DQ-02", "Missing or Trivial Description",
                    "LOW", "MATERIAL", r.id(), r.code(), r.name(),
                    "Description is empty or duplicates code: '" + (r.description() != null ? r.description() : "NULL") + "'.",
                    "Enrich material master data with complete technical specifications and standard naming.");
        }

        // Rule 3: Zero minimum stock on active items
        record MinStockRow(long id, String code, String name) {}
        List<MinStockRow> zeroMin = jdbc.sql("select id, code, name from materials "
                + "where active = true and minimum_stock = 0")
                .query(MinStockRow.class)
                .list();

        for (MinStockRow r : zeroMin) {
            String key = "RULE-DQ-03:MAT:" + r.id();
            createdCount += upsertIssue(key, "RULE-DQ-03", "Zero Minimum Safety Stock",
                    "MEDIUM", "MATERIAL", r.id(), r.code(), r.name(),
                    "Active material has safety minimum stock set to 0, risking unmonitored stockout.",
                    "Evaluate reorder point and set minimum safety threshold.");
        }

        // Rule 4: Suspicious near-SKU variants
        record NearSkuRow(long id1, String code1, String name1, long id2, String code2, String name2) {}
        List<NearSkuRow> nearSkus = jdbc.sql("select m1.id as id1, m1.code as code1, m1.name as name1, "
                + "m2.id as id2, m2.code as code2, m2.name as name2 "
                + "from materials m1 join materials m2 on m1.id < m2.id "
                + "where (m1.code like m2.code || '-%' or m2.code like m1.code || '-%' "
                + "   or m1.code like m2.code || '_%' or m2.code like m1.code || '_%')")
                .query(NearSkuRow.class)
                .list();

        for (NearSkuRow r : nearSkus) {
            String key = "RULE-DQ-04:MAT:" + r.id1() + ":" + r.id2();
            createdCount += upsertIssue(key, "RULE-DQ-04", "Suspicious Near-SKU Variant",
                    "MEDIUM", "MATERIAL", r.id1(), r.code1(), r.name1(),
                    "Codes '" + r.code1() + "' and '" + r.code2() + "' differ only by suffix/delimiter. Risk of picking confusion.",
                    "Verify if item is a legitimate variant or an unintentional duplicate entry.");
        }

        // Rule 5: Inactive supplier with open purchase orders
        record InactiveSupRow(long id, String code, String name, long openPoCount) {}
        List<InactiveSupRow> inactiveSups = jdbc.sql("select s.id, s.code, s.name, count(p.id) as open_po_count "
                + "from suppliers s join purchase_orders p on p.supplier_id = s.id "
                + "where s.active = false and p.status in ('OPEN', 'PARTIALLY_RECEIVED') "
                + "group by s.id, s.code, s.name")
                .query(InactiveSupRow.class)
                .list();

        for (InactiveSupRow r : inactiveSups) {
            String key = "RULE-DQ-05:SUP:" + r.id();
            createdCount += upsertIssue(key, "RULE-DQ-05", "Inactive Supplier With Active POs",
                    "HIGH", "SUPPLIER", r.id(), r.code(), r.name(),
                    "Supplier is inactive but has " + r.openPoCount() + " open or partially received purchase orders.",
                    "Cancel pending orders or reactivate supplier in master registry.");
        }

        return createdCount;
    }

    private int upsertIssue(String key, String ruleCode, String ruleName, String severity,
                            String entityType, long entityId, String entityCode, String entityName,
                            String detail, String action) {
        return jdbc.sql("insert into master_quality_issues (issue_key, rule_code, rule_name, severity, entity_type, "
                + "entity_id, entity_code, entity_name, detected_detail, recommended_action, status) "
                + "values (:k, :rc, :rn, :sev, :et, :eid, :ec, :en, :det, :act, 'OPEN') "
                + "on conflict (issue_key) do update set detected_detail = excluded.detected_detail, "
                + "entity_name = excluded.entity_name where master_quality_issues.status = 'OPEN'")
                .param("k", key)
                .param("rc", ruleCode)
                .param("rn", ruleName)
                .param("sev", severity)
                .param("et", entityType)
                .param("eid", entityId)
                .param("ec", entityCode)
                .param("en", entityName)
                .param("det", detail)
                .param("act", action)
                .update();
    }

    public PageResponse<QualityIssue> listIssues(String status, String severity, String ruleCode, PageQuery page) {
        SqlFilter f = new SqlFilter()
                .add("status = :status", "status", status)
                .add("severity = :severity", "severity", severity)
                .add("rule_code = :rule", "rule", ruleCode);

        String from = " from master_quality_issues" + f.where();
        long total = jdbc.sql("select count(*)" + from).params(f.params()).query(Long.class).single();

        List<QualityIssue> rows = jdbc.sql("select id, issue_key, rule_code, rule_name, severity, entity_type, entity_id, "
                + "entity_code, entity_name, detected_detail, recommended_action, status, resolved_by, resolved_at, "
                + "resolution_notes, created_at" + from + " order by " + page.orderBy() + " limit :limit offset :offset")
                .params(f.params())
                .param("limit", page.size())
                .param("offset", page.offset())
                .query(QualityIssue.class)
                .list();

        return PageResponse.of(rows, page, total);
    }

    public QualitySummary getSummary() {
        long openTotal = jdbc.sql("select count(*) from master_quality_issues where status = 'OPEN'")
                .query(Long.class).single();
        long high = jdbc.sql("select count(*) from master_quality_issues where status = 'OPEN' and severity = 'HIGH'")
                .query(Long.class).single();
        long med = jdbc.sql("select count(*) from master_quality_issues where status = 'OPEN' and severity = 'MEDIUM'")
                .query(Long.class).single();
        long low = jdbc.sql("select count(*) from master_quality_issues where status = 'OPEN' and severity = 'LOW'")
                .query(Long.class).single();

        record RuleStat(String ruleCode, long count) {}
        List<RuleStat> byRule = jdbc.sql("select rule_code, count(*) as count from master_quality_issues "
                + "where status = 'OPEN' group by rule_code")
                .query(RuleStat.class)
                .list();

        java.util.Map<String, Long> ruleMap = new java.util.LinkedHashMap<>();
        for (RuleStat s : byRule) {
            ruleMap.put(s.ruleCode(), s.count());
        }

        return new QualitySummary(openTotal, high, med, low, ruleMap);
    }

    @Transactional(propagation = Propagation.REQUIRED)
    public QualityIssue resolveIssue(Actor actor, String issueKey, ResolveRequest req) {
        String dec = req.decision() != null ? req.decision().toUpperCase() : "RESOLVED";
        if (!List.of("RESOLVED", "IGNORED").contains(dec)) {
            throw ApiException.badRequest("VALIDATION_ERROR", "Decision must be RESOLVED or IGNORED");
        }
        int rows = jdbc.sql("update master_quality_issues set status = :st, resolved_by = :by, resolved_at = now(), "
                + "resolution_notes = :notes where issue_key = :k and status = 'OPEN'")
                .param("st", dec)
                .param("by", actor.id())
                .param("notes", req.notes() != null ? req.notes().trim() : null)
                .param("k", issueKey)
                .update();

        if (rows == 0) {
            throw ApiException.notFound("Open issue " + issueKey);
        }

        audit.record(actor, "DATA_QUALITY_RESOLVE", "MASTER_QUALITY_ISSUE", issueKey,
                Map.of("decision", dec, "notes", req.notes() != null ? req.notes() : ""));

        return jdbc.sql("select id, issue_key, rule_code, rule_name, severity, entity_type, entity_id, "
                + "entity_code, entity_name, detected_detail, recommended_action, status, resolved_by, resolved_at, "
                + "resolution_notes, created_at from master_quality_issues where issue_key = :k")
                .param("k", issueKey)
                .query(QualityIssue.class)
                .single();
    }
}
