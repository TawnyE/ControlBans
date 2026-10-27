package ret.tawny.controlbans.storage.dao;

import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;

/**
 * Centralizes common SQL query construction for the PunishmentDao to eliminate
 * duplication across getRecentPunishments, getPunishmentHistory, and getPunishmentById.
 */
public final class PunishmentQueryBuilder {

    private static final String COMMON_COLS = "t.id, t.punishment_id, t.uuid, t.ip, t.reason, t.banned_by_uuid, t.banned_by_name, t.removed_by_uuid, t.removed_by_name, t.removed_by_date, t.time, t.until, t.server_origin, t.silent, t.ipban, t.active, t.template";

    private static final String KICK_COLS = "t.id, t.punishment_id, t.uuid, t.ip, t.reason, t.banned_by_uuid, t.banned_by_name, NULL AS removed_by_uuid, NULL AS removed_by_name, 0 AS removed_by_date, t.time, -1 AS until, t.server_origin, t.silent, 0 AS ipban, 0 AS active, NULL AS template";

    private static final String VOICEMUTE_COLS = "t.id, t.punishment_id, t.uuid, NULL AS ip, t.reason, t.banned_by_uuid, t.banned_by_name, t.removed_by_uuid, t.removed_by_name, t.removed_by_date, t.time, t.until, t.server_origin, t.silent, 0 AS ipban, t.active, NULL AS template";

    // Joins the LATEST history row per uuid via a grouped derived table instead of a
    // per-row correlated subquery (same semantics, but evaluated once per query).
    private static final String LATEST_HISTORY_JOIN =
            " LEFT JOIN (SELECT uuid, MAX(id) AS id FROM controlbans_history GROUP BY uuid) hh ON hh.uuid = t.uuid" +
            " LEFT JOIN controlbans_history h ON h.id = hh.id";

    private PunishmentQueryBuilder() {}

    /**
     * Builds a query that searches all punishment tables by a common condition.
     * @param condition the WHERE clause condition (e.g., "t.uuid = ?"), without leading WHERE
     * @param orderBy the ordering clause, without leading ORDER BY
     * @param limit whether to append a LIMIT ? clause
     * @return the constructed SQL string
     */
    public static String buildUnionQuery(String condition, String orderBy, boolean limit) {
        StringBuilder sql = new StringBuilder();
        sql.append("SELECT ").append(COMMON_COLS).append(", h.name as target_name, 'BAN' as type_ref FROM controlbans_bans t" + LATEST_HISTORY_JOIN);
        if (condition != null && !condition.isEmpty()) {
            sql.append(" WHERE ").append(condition);
        }
        sql.append(" UNION ALL ");
        sql.append("SELECT ").append(COMMON_COLS).append(", h.name as target_name, 'MUTE' as type_ref FROM controlbans_mutes t" + LATEST_HISTORY_JOIN);
        if (condition != null && !condition.isEmpty()) {
            sql.append(" WHERE ").append(condition);
        }
        sql.append(" UNION ALL ");
        sql.append("SELECT ").append(COMMON_COLS).append(", h.name as target_name, 'WARN' as type_ref FROM controlbans_warnings t" + LATEST_HISTORY_JOIN);
        if (condition != null && !condition.isEmpty()) {
            sql.append(" WHERE ").append(condition);
        }
        sql.append(" UNION ALL ");
        sql.append("SELECT ").append(KICK_COLS).append(", h.name as target_name, 'KICK' as type_ref FROM controlbans_kicks t" + LATEST_HISTORY_JOIN);
        if (condition != null && !condition.isEmpty()) {
            sql.append(" WHERE ").append(condition);
        }
        sql.append(" UNION ALL ");
        sql.append("SELECT ").append(VOICEMUTE_COLS).append(", h.name as target_name, 'VOICEMUTE' as type_ref FROM controlbans_voicemutes t" + LATEST_HISTORY_JOIN);
        if (condition != null && !condition.isEmpty()) {
            sql.append(" WHERE ").append(condition);
        }

        if (orderBy != null && !orderBy.isEmpty()) {
            sql.append(" ORDER BY ").append(orderBy);
        }
        if (limit) {
            sql.append(" LIMIT ?");
        }
        return sql.toString();
    }

    /**
     * Builds a query that searches all punishment tables by punishment ID.
     */
    public static String buildPunishmentByIdQuery() {
        StringBuilder sql = new StringBuilder();
        sql.append("SELECT ").append(COMMON_COLS).append(", h.name as target_name, 'BAN' as type_ref FROM controlbans_bans t" + LATEST_HISTORY_JOIN + " WHERE t.punishment_id = ?");
        sql.append(" UNION ALL ");
        sql.append("SELECT ").append(COMMON_COLS).append(", h.name as target_name, 'MUTE' as type_ref FROM controlbans_mutes t" + LATEST_HISTORY_JOIN + " WHERE t.punishment_id = ?");
        sql.append(" UNION ALL ");
        sql.append("SELECT ").append(COMMON_COLS).append(", h.name as target_name, 'WARN' as type_ref FROM controlbans_warnings t" + LATEST_HISTORY_JOIN + " WHERE t.punishment_id = ?");
        sql.append(" UNION ALL ");
        sql.append("SELECT ").append(KICK_COLS).append(", h.name as target_name, 'KICK' as type_ref FROM controlbans_kicks t" + LATEST_HISTORY_JOIN + " WHERE t.punishment_id = ?");
        sql.append(" UNION ALL ");
        sql.append("SELECT ").append(VOICEMUTE_COLS).append(", h.name as target_name, 'VOICEMUTE' as type_ref FROM controlbans_voicemutes t" + LATEST_HISTORY_JOIN + " WHERE t.punishment_id = ?");
        return sql.toString();
    }

    /**
     * Builds the lowercase column-label set for a ResultSet. Call once per query
     * execution (before iterating rows) and thread the set through row mapping —
     * identity-hash-result-set caching is unsafe because identity hash codes are
     * recycled after GC.
     */
    public static java.util.Set<String> columnNames(ResultSet rs) throws SQLException {
        ResultSetMetaData metaData = rs.getMetaData();
        int count = metaData.getColumnCount();
        java.util.Set<String> cols = new java.util.HashSet<>(count * 2);
        for (int i = 1; i <= count; i++) {
            String label = metaData.getColumnLabel(i);
            String name = metaData.getColumnName(i);
            if (label != null) cols.add(label.toLowerCase(java.util.Locale.ROOT));
            if (name != null) cols.add(name.toLowerCase(java.util.Locale.ROOT));
        }
        return cols;
    }
}
