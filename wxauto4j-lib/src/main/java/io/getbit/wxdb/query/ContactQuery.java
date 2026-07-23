package io.getbit.wxdb.query;

import io.getbit.wxdb.model.Contact;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;

/**
 * 联系人查询
 */
public class ContactQuery {

    private final String jdbcUrl;
    private final String derivedKey;

    public ContactQuery(String jdbcUrl, String derivedKey) {
        this.jdbcUrl = jdbcUrl;
        this.derivedKey = derivedKey;
    }

    /**
     * 查询所有联系人
     */
    public List<Contact> getAll() {
        return query("SELECT * FROM contact ORDER BY nick_name", new Object[]{});
    }

    /**
     * 按 username 查询
     */
    public Contact getByUsername(String username) {
        List<Contact> results = query(
                "SELECT * FROM contact WHERE username = ? LIMIT 1",
                new Object[]{username});
        return results.isEmpty() ? null : results.get(0);
    }

    /**
     * 按昵称搜索
     */
    public List<Contact> searchByNickName(String keyword) {
        return query(
                "SELECT * FROM contact WHERE nick_name LIKE ? ORDER BY nick_name",
                new Object[]{"%" + keyword + "%"});
    }

    /**
     * 按备注搜索
     */
    public List<Contact> searchByRemark(String keyword) {
        return query(
                "SELECT * FROM contact WHERE remark LIKE ? ORDER BY nick_name",
                new Object[]{"%" + keyword + "%"});
    }

    /**
     * 按昵称或备注搜索
     */
    public List<Contact> search(String keyword) {
        return query(
                "SELECT * FROM contact WHERE nick_name LIKE ? OR remark LIKE ? ORDER BY nick_name",
                new Object[]{"%" + keyword + "%", "%" + keyword + "%"});
    }

    /**
     * 查询所有群聊
     */
    public List<Contact> getChatrooms() {
        return query(
                "SELECT * FROM contact WHERE username LIKE '%@chatroom' ORDER BY nick_name",
                new Object[]{});
    }

    /**
     * 查询所有公众号
     */
    public List<Contact> getOfficialAccounts() {
        return query(
                "SELECT * FROM contact WHERE username LIKE 'gh_%' ORDER BY nick_name",
                new Object[]{});
    }

    /**
     * 查询联系人总数
     */
    public int count() {
        try (Connection conn = openConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT count(*) FROM contact")) {
            return rs.next() ? rs.getInt(1) : 0;
        } catch (SQLException e) {
            throw new RuntimeException("Contact count failed: " + e.getMessage(), e);
        }
    }

    private Connection openConnection() throws SQLException {
        return DriverManager.getConnection(jdbcUrl);
    }

    private List<Contact> query(String sql, Object[] params) {
        List<Contact> contacts = new ArrayList<>();
        try (Connection conn = openConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) {
                ps.setObject(i + 1, params[i]);
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    contacts.add(mapContact(rs));
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("Contact query failed: " + e.getMessage(), e);
        }
        return contacts;
    }

    private Contact mapContact(ResultSet rs) throws SQLException {
        Contact c = new Contact();
        c.setUsername(rs.getString("username"));
        c.setNickName(rs.getString("nick_name"));
        c.setRemark(rs.getString("remark"));
        c.setAlias(rs.getString("alias"));
        c.setSmallHeadImgUrl(rs.getString("small_head_url"));
        c.setBigHeadImgUrl(rs.getString("big_head_url"));
        return c;
    }
}
