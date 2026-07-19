package com.follarce.util;

import com.follarce.Constants;
import com.follarce.function.UnknownEffectOutcomeException;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;

/**
 * 网络工具类 —— 提供 HTTP 客户端功能。
 */
public final class NetworkUtil {

    private NetworkUtil() {
    }

    /**
     * HTTP GET 请求。
     */
    public static String httpGet(String url) {
        HttpURLConnection conn = null;

        try {
            conn = openHttpConnection(url);
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(Constants.DEFAULT_TIMEOUT);
            conn.setReadTimeout(Constants.DEFAULT_TIMEOUT);

            int responseCode = conn.getResponseCode();

            InputStream responseStream = responseCode >= 400
                    ? conn.getErrorStream()
                    : conn.getInputStream();

            String responseBody = responseStream != null
                    ? readStream(responseStream)
                    : "(no response body)";

            return "Response Code: " + responseCode + "\n" + responseBody;
        } catch (Exception e) {
            return "ERROR: HTTP GET failed: " + e.getMessage();
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    /**
     * HTTP POST 请求。
     */
    public static String httpPost(String url, String data) {
        return httpPost(url, data, null);
    }

    /**
     * HTTP POST 请求。
     *
     * @param url            请求地址
     * @param data           JSON 请求体
     * @param idempotencyKey 可选的幂等键
     */
    public static String httpPost(
            String url,
            String data,
            String idempotencyKey
    ) {
        HttpURLConnection conn = null;
        boolean dispatchStarted = false;

        try {
            conn = openHttpConnection(url);
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(Constants.DEFAULT_TIMEOUT);
            conn.setReadTimeout(Constants.DEFAULT_TIMEOUT);
            conn.setDoOutput(true);
            conn.setRequestProperty(
                    "Content-Type",
                    "application/json; charset=UTF-8"
            );

            if (idempotencyKey != null && !idempotencyKey.isBlank()) {
                conn.setRequestProperty(
                        "Idempotency-Key",
                        idempotencyKey
                );
            }

            byte[] input = data.getBytes(StandardCharsets.UTF_8);
            conn.setFixedLengthStreamingMode(input.length);

            /*
             * getOutputStream() 可能触发请求发送，因此从调用它之前开始，
             * POST 的最终结果就可能无法可靠判断。
             */
            dispatchStarted = true;

            try (OutputStream os = conn.getOutputStream()) {
                os.write(input);
            }

            int responseCode = conn.getResponseCode();

            InputStream responseStream = responseCode >= 400
                    ? conn.getErrorStream()
                    : conn.getInputStream();

            String responseBody = responseStream != null
                    ? readStream(responseStream)
                    : "(no response body)";

            return "Response Code: " + responseCode + "\n" + responseBody;
        } catch (Exception e) {
            if (dispatchStarted) {
                throw new UnknownEffectOutcomeException(
                        "HTTP POST outcome is unknown: " + e.getMessage(),
                        e
                );
            }

            return "ERROR: HTTP POST failed: " + e.getMessage();
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    /**
     * 创建 HTTP 或 HTTPS 连接。
     */
    private static HttpURLConnection openHttpConnection(String url)
            throws IOException {

        if (url == null || url.isBlank()) {
            throw new IllegalArgumentException(
                    "URL cannot be null or blank"
            );
        }

        final URI uri;

        try {
            uri = URI.create(url);
        } catch (IllegalArgumentException e) {
            throw new IOException("Invalid URL: " + url, e);
        }

        String scheme = uri.getScheme();

        if (!"http".equalsIgnoreCase(scheme)
                && !"https".equalsIgnoreCase(scheme)) {
            throw new IOException(
                    "Unsupported URL scheme: " + scheme
            );
        }

        return (HttpURLConnection) uri.toURL().openConnection();
    }

    /**
     * 使用 UTF-8 读取响应流。
     */
    private static String readStream(InputStream stream)
            throws IOException {

        StringBuilder response = new StringBuilder();

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(
                        stream,
                        StandardCharsets.UTF_8
                )
        )) {
            String line;

            while ((line = reader.readLine()) != null) {
                response.append(line).append('\n');
            }
        }

        return response.toString().trim();
    }
}