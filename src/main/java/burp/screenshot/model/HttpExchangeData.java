package burp.screenshot.model;

import java.text.SimpleDateFormat;
import java.util.Date;

public class HttpExchangeData {
    private String url;
    private String rawRequest;
    private String rawResponse;
    private String httpMethod;
    private String httpVersion;
    private int statusCode;
    private String statusReason;
    private String timestampFormatted;
    private long responseSizeBytes;
    private long responseDurationMs;

    public HttpExchangeData() {
        this.url = "";
        this.rawRequest = "";
        this.rawResponse = "";
        this.httpMethod = "GET";
        this.httpVersion = "HTTP/1.1";
        this.statusCode = 200;
        this.statusReason = "OK";
        this.timestampFormatted = new SimpleDateFormat("M/d/yyyy, h:mm:ss a").format(new Date());
        this.responseSizeBytes = 0;
        this.responseDurationMs = 0;
    }

    public static HttpExchangeData createSampleData() {
        HttpExchangeData d = new HttpExchangeData();
        d.setUrl("https://demo.example.com/api/v1/user/profile?id=1337");
        d.setHttpMethod("GET");
        d.setHttpVersion("HTTP/1.1");
        d.setStatusCode(200);
        d.setStatusReason("OK");
        d.setTimestampFormatted(new SimpleDateFormat("M/d/yyyy, h:mm:ss a").format(new Date()));
        d.setResponseDurationMs(142);

        String sampleReq = "GET /api/v1/user/profile?id=1337 HTTP/1.1\r\n" +
                "Host: demo.example.com\r\n" +
                "User-Agent: Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36\r\n" +
                "Accept: text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8\r\n" +
                "Accept-Language: en-US,en;q=0.5\r\n" +
                "Accept-Encoding: gzip, deflate\r\n" +
                "Authorization: Bearer eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.s3cr3t_t0k3n_v4lu3\r\n" +
                "Cookie: session_id=74a9f8bc23190e21a8de9;\r\n" +
                "Connection: close\r\n\r\n";

        String sampleRes = "HTTP/1.1 200 OK\r\n" +
                "Server: nginx/1.24.0\r\n" +
                "Date: " + new Date().toString() + "\r\n" +
                "Content-Type: application/json; charset=utf-8\r\n" +
                "Connection: close\r\n" +
                "Content-Length: 189\r\n\r\n" +
                "{\n" +
                "  \"status\": \"success\",\n" +
                "  \"data\": {\n" +
                "    \"user_id\": 1337,\n" +
                "    \"username\": \"pentester_admin\",\n" +
                "    \"email\": \"admin@internal.corp\",\n" +
                "    \"role\": \"SUPERADMIN\",\n" +
                "    \"api_key\": \"sec_live_994a32b1f8c7e4\"\n" +
                "  }\n" +
                "}";

        d.setRawRequest(sampleReq);
        d.setRawResponse(sampleRes);
        d.setResponseSizeBytes(sampleRes.getBytes().length);
        return d;
    }

    public String getUrl() { return url; }
    public void setUrl(String url) { this.url = url; }

    public String getRawRequest() { return rawRequest; }
    public void setRawRequest(String rawRequest) { this.rawRequest = rawRequest; }

    public String getRawResponse() { return rawResponse; }
    public void setRawResponse(String rawResponse) { this.rawResponse = rawResponse; }

    public String getHttpMethod() { return httpMethod; }
    public void setHttpMethod(String httpMethod) { this.httpMethod = httpMethod; }

    public String getHttpVersion() { return httpVersion; }
    public void setHttpVersion(String httpVersion) { this.httpVersion = httpVersion; }

    public int getStatusCode() { return statusCode; }
    public void setStatusCode(int statusCode) { this.statusCode = statusCode; }

    public String getStatusReason() { return statusReason; }
    public void setStatusReason(String statusReason) { this.statusReason = statusReason; }

    public String getTimestampFormatted() { return timestampFormatted; }
    public void setTimestampFormatted(String timestampFormatted) { this.timestampFormatted = timestampFormatted; }

    public long getResponseSizeBytes() { return responseSizeBytes; }
    public void setResponseSizeBytes(long responseSizeBytes) { this.responseSizeBytes = responseSizeBytes; }

    public long getResponseDurationMs() { return responseDurationMs; }
    public void setResponseDurationMs(long responseDurationMs) { this.responseDurationMs = responseDurationMs; }
}
