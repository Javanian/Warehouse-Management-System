package com.stockflow.identity;

import com.stockflow.common.PageQuery;
import com.stockflow.common.SqlFilter;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class UserRepository {

    private static final String COLS = "id, username, email, full_name, password_hash, role, active, demo, created_at,"
            + " updated_at, version";
    private final JdbcClient jdbc;

    public UserRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<UserRecord> findByLogin(String login) {
        return jdbc.sql("select " + COLS + " from users where lower(username) = lower(:l) or lower(email) = lower(:l)")
                .param("l", login.trim()).query(UserRecord.class).optional();
    }

    public Optional<UserRecord> findById(long id) {
        return jdbc.sql("select " + COLS + " from users where id = :id").param("id", id).query(UserRecord.class)
                .optional();
    }

    public List<UserRecord> page(SqlFilter f, PageQuery q) {
        return jdbc.sql("select " + COLS + " from users" + f.where() + " order by " + q.orderBy()
                        + " limit :limit offset :offset")
                .params(f.params()).param("limit", q.size()).param("offset", q.offset())
                .query(UserRecord.class).list();
    }

    public long count(SqlFilter f) {
        return jdbc.sql("select count(*) from users" + f.where()).params(f.params()).query(Long.class).single();
    }

    public long insert(String username, String email, String fullName, String hash, Role role, boolean demo) {
        return jdbc.sql("insert into users (username, email, full_name, password_hash, role, demo)"
                        + " values (:u, :e, :n, :h, :r, :d) returning id")
                .param("u", username).param("e", email).param("n", fullName).param("h", hash)
                .param("r", role.name()).param("d", demo).query(Long.class).single();
    }

    public int update(long id, long version, String fullName, Role role, boolean active) {
        return jdbc.sql("update users set full_name = :n, role = :r, active = :a, updated_at = now(),"
                        + " version = version + 1 where id = :id and version = :v")
                .param("n", fullName).param("r", role.name()).param("a", active).param("id", id).param("v", version)
                .update();
    }

    public int updatePassword(long id, String hash) {
        return jdbc.sql("update users set password_hash = :h, updated_at = now(), version = version + 1 where id = :id")
                .param("h", hash).param("id", id).update();
    }

    public long countActiveAdmins() {
        return jdbc.sql("select count(*) from users where role = 'ADMIN' and active").query(Long.class).single();
    }
}
