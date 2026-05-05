package com.github.derakula;

import org.json.JSONException;
import org.json.JSONObject;

public class VpnProfile {
    public String id;           // UUID
    public String name;
    public String clientId;
    public String clientSecret;
    public String projectId;
    public String refreshToken;
    public String listenAddr;
    public String folderId;
    public int    refreshRateMs;
    public int    flushRateMs;
    public String targetIp;
    public String sni;
    public String hostHeader;

    // ── Defaults ──────────────────────────────────────────────
    public VpnProfile() {
        id             = java.util.UUID.randomUUID().toString();
        name           = "New Profile";
        listenAddr     = "127.0.0.1:1080";
        refreshRateMs  = 100;
        flushRateMs    = 300;
        targetIp       = "216.239.38.120:443";
        sni            = "google.com";
        hostHeader     = "www.googleapis.com";
    }

    // ── Serialize ─────────────────────────────────────────────
    public JSONObject toJson() throws JSONException {
        JSONObject o = new JSONObject();
        o.put("id",             id);
        o.put("name",           name);
        o.put("client_id",      clientId      != null ? clientId      : "");
        o.put("client_secret",  clientSecret  != null ? clientSecret  : "");
        o.put("project_id",     projectId     != null ? projectId     : "");
        o.put("refresh_token",  refreshToken  != null ? refreshToken  : "");
        o.put("listen_addr",    listenAddr    != null ? listenAddr    : "127.0.0.1:1080");
        o.put("folder_id",      folderId      != null ? folderId      : "");
        o.put("refresh_rate",   refreshRateMs);
        o.put("flush_rate",     flushRateMs);
        o.put("target_ip",      targetIp      != null ? targetIp      : "");
        o.put("sni",            sni           != null ? sni           : "");
        o.put("host_header",    hostHeader    != null ? hostHeader    : "");
        return o;
    }

    public static VpnProfile fromJson(JSONObject o) throws JSONException {
        VpnProfile p    = new VpnProfile();
        p.id            = o.optString("id",            p.id);
        p.name          = o.optString("name",          p.name);
        p.clientId      = o.optString("client_id",     "");
        p.clientSecret  = o.optString("client_secret", "");
        p.projectId     = o.optString("project_id",    "");
        p.refreshToken  = o.optString("refresh_token", "");
        p.listenAddr    = o.optString("listen_addr",   "127.0.0.1:1080");
        p.folderId      = o.optString("folder_id",     "");
        p.refreshRateMs = o.optInt   ("refresh_rate",  100);
        p.flushRateMs   = o.optInt   ("flush_rate",    300);
        p.targetIp      = o.optString("target_ip",     "");
        p.sni           = o.optString("sni",           "");
        p.hostHeader    = o.optString("host_header",   "");
        return p;
    }

    // ── Share Link (xvpn://base64) ────────────────────────────
    public String toShareLink() {
        try {
            JSONObject o = toJson();
            o.remove("id"); // id is regenerated on import
            String json = o.toString();
            String b64  = android.util.Base64.encodeToString(
                    json.getBytes("UTF-8"), android.util.Base64.NO_WRAP);
            return "xvpn://" + b64;
        } catch (Exception e) { return ""; }
    }

    public static VpnProfile fromShareLink(String link) throws Exception {
        if (link == null || link.trim().isEmpty()) throw new Exception("Empty link");
        link = link.trim();
        if (link.startsWith("xvpn://")) link = link.substring(7);
        byte[]     bytes = android.util.Base64.decode(link, android.util.Base64.NO_WRAP);
        JSONObject o     = new JSONObject(new String(bytes, "UTF-8"));
        VpnProfile p     = fromJson(o);
        p.id = java.util.UUID.randomUUID().toString(); // new id on import
        return p;
    }

    // ── Build JSON strings for Androidlib ────────────────────
    public String buildCredentialsJSON() {
        return "{\"installed\":{"
                + "\"client_id\":\""     + esc(clientId)     + "\","
                + "\"project_id\":\""    + esc(projectId)    + "\","
                + "\"auth_uri\":\"https://accounts.google.com/o/oauth2/auth\","
                + "\"token_uri\":\"https://oauth2.googleapis.com/token\","
                + "\"auth_provider_x509_cert_url\":\"https://www.googleapis.com/oauth2/v1/certs\","
                + "\"client_secret\":\"" + esc(clientSecret) + "\","
                + "\"redirect_uris\":[\"http://localhost\"]}}";
    }

    public String buildTokenJSON() {
        return "{\"refresh_token\":\"" + esc(refreshToken) + "\"}";
    }

    public String buildConfigJSON() {
        return "{\n"
                + "  \"listen_addr\": \""      + listenAddr  + "\",\n"
                + "  \"storage_type\": \"google\",\n"
                + "  \"google_folder_id\": \"" + folderId    + "\",\n"
                + "  \"refresh_rate_ms\": "    + refreshRateMs + ",\n"
                + "  \"flush_rate_ms\": "      + flushRateMs   + ",\n"
                + "  \"transport\": {\n"
                + "    \"TargetIP\": \""       + targetIp    + "\",\n"
                + "    \"SNI\": \""            + sni         + "\",\n"
                + "    \"HostHeader\": \""     + hostHeader  + "\"\n"
                + "  }\n}";
    }

    private String esc(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}