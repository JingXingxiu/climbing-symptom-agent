package org.javaup.gateway.config;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

/** Local, single-instance protection; these defaults are demonstration limits, not measured capacity. */
@Component
@Validated
@ConfigurationProperties(prefix = "gateway.sentinel")
public class GatewaySentinelProperties {

    private boolean enabled;

    @Min(1)
    private int agentAdvanceQps = 2;

    @Min(1)
    private int imageUploadQps = 5;

    @NotNull
    private String dashboard = "";

    @Min(1)
    @Max(65535)
    private int transportPort = 8719;

    @NotNull
    private String clientIp = "";

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public int getAgentAdvanceQps() {
        return agentAdvanceQps;
    }

    public void setAgentAdvanceQps(int agentAdvanceQps) {
        this.agentAdvanceQps = agentAdvanceQps;
    }

    public int getImageUploadQps() {
        return imageUploadQps;
    }

    public void setImageUploadQps(int imageUploadQps) {
        this.imageUploadQps = imageUploadQps;
    }

    public String getDashboard() {
        return dashboard;
    }

    public void setDashboard(String dashboard) {
        this.dashboard = dashboard;
    }

    public int getTransportPort() {
        return transportPort;
    }

    public void setTransportPort(int transportPort) {
        this.transportPort = transportPort;
    }

    public String getClientIp() {
        return clientIp;
    }

    public void setClientIp(String clientIp) {
        this.clientIp = clientIp;
    }
}
