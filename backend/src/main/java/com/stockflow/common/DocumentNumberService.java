package com.stockflow.common;

import java.time.LocalDate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DocumentNumberService {

    private final JdbcClient jdbc;

    public DocumentNumberService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public String next(String docType, LocalDate businessDate) {
        int year = businessDate.getYear();
        jdbc.sql("insert into document_counters (doc_type, year, next_value) values (:t, :y, 1) on conflict do nothing")
                .param("t", docType).param("y", year).update();
        long value = jdbc.sql("update document_counters set next_value = next_value + 1 where doc_type = :t and year = :y"
                        + " returning next_value - 1")
                .param("t", docType).param("y", year).query(Long.class).single();
        return String.format("%s-%d-%06d", docType, year, value);
    }
}
