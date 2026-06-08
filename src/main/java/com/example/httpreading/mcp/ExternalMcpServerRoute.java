package com.example.httpreading.mcp;

public class ExternalMcpServerRoute {
    private final boolean useMcp;
    private final String serverName;
    private final String reason;

    public ExternalMcpServerRoute(boolean useMcp, String serverName, String reason) {
        this.useMcp = useMcp;
        this.serverName = serverName == null ? "" : serverName.trim();
        this.reason = reason == null ? "" : reason.trim();
    }

    public static ExternalMcpServerRoute use(String serverName, String reason) {
        return new ExternalMcpServerRoute(true, serverName, reason);
    }

    public static ExternalMcpServerRoute skip(String reason) {
        return new ExternalMcpServerRoute(false, "", reason);
    }

    public boolean isUseMcp() {
        return useMcp;
    }

    public String getServerName() {
        return serverName;
    }

    public String getReason() {
        return reason;
    }
}
