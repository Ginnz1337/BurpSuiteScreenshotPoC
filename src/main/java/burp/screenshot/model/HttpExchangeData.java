package burp.screenshot.model;

import java.util.Date;

public class HttpExchangeData {
    private String rawRequest;
    private String rawResponse;

    public HttpExchangeData() {
        this.rawRequest = "";
        this.rawResponse = "";
    }

    public static HttpExchangeData createSampleData() {
        HttpExchangeData d = new HttpExchangeData();

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
        return d;
    }

    public String getRawRequest() { return rawRequest; }
    public void setRawRequest(String rawRequest) { this.rawRequest = rawRequest; }

    public String getRawResponse() { return rawResponse; }
    public void setRawResponse(String rawResponse) { this.rawResponse = rawResponse; }
}
