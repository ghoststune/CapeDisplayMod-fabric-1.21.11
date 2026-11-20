package com.ghosttune.capedisplay.net;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * 极简 HTTP 客户端：统一 UA、超时与重定向策略。
 * 所有方法都是阻塞的，只能在后台线程调用。
 */
public final class CapeHttp {
    public static final String USER_AGENT = "capedisplay/0.1.1 (Minecraft Fabric client mod)";

    private static volatile HttpClient client;

    private CapeHttp() {
    }

    private static HttpClient client(int timeoutSeconds) {
        HttpClient c = client;
        if (c == null) {
            synchronized (CapeHttp.class) {
                c = client;
                if (c == null) {
                    c = HttpClient.newBuilder()
                            .connectTimeout(Duration.ofSeconds(Math.max(3, timeoutSeconds)))
                            .followRedirects(HttpClient.Redirect.NORMAL)
                            .build();
                    client = c;
                }
            }
        }
        return c;
    }

    public static HttpResult get(String url, int timeoutSeconds) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(Math.max(3, timeoutSeconds)))
                .header("User-Agent", USER_AGENT)
                .header("Accept", "*/*")
                .GET()
                .build();
        HttpResponse<byte[]> response = client(timeoutSeconds).send(request, HttpResponse.BodyHandlers.ofByteArray());
        return new HttpResult(response.statusCode(), response.body() == null ? new byte[0] : response.body());
    }

    public record HttpResult(int status, byte[] body) {
        public boolean ok() {
            return status >= 200 && status < 300;
        }

        public String text() {
            return new String(body, StandardCharsets.UTF_8);
        }
    }
}