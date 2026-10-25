package cc.hrva.urlshortener.dto.stats;

import java.time.LocalDate;
import java.util.List;

public record UsersStatsResponse(
        int days,
        LocalDate from,
        LocalDate to,
        StackedSeries signups,
        UserInventory now,
        long activeLast7Days,
        long activeLast30Days,
        ApiKeyCounts apiKeys,
        McpCounts mcp) {

    public record UserInventory(
            long total,
            long active,
            long deactivated,
            long admins,
            long emailVerified,
            long twoFactorEnabled,
            List<CountEntry> byProvider) {}

    public record ApiKeyCounts(long active, long expired, long callsUsed) {}

    public record McpCounts(long connectedUsers, long registeredClients) {}

}
