package com.follarce.util;

import com.follarce.Constants;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * 网络工具类 —— 提供 HTTP 客户端功能。
 */
public final class NetworkUtil {

    private NetworkUtil() {}

    /**
     * HTTP GET 请求。
     */
    public static String httpGet(String url) {
        HttpURLConnection conn = null;
        try {
            URL obj = new URL(url);
            conn = (HttpURLConnection) obj.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(Constants.DEFAULT_TIMEOUT);
            conn.setReadTimeout(Constants.DEFAULT_TIMEOUT);

            int responseCode = conn.getResponseCode();
            String responseBody = readStream(conn.getInputStream());

            return "Response Code: " + responseCode + "\n" + responseBody;
        } catch (Exception e) {
            return new String[]{Constants.ERROR_MARKER, "HTTP GET failed: " + e.getMessage()}.toString();
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /**
     * HTTP POST 请求。
     */
    public static String httpPost(String url, String data) {
        HttpURLConnection conn = null;
        try {
            URL obj = new URL(url);
            conn = (HttpURLConnection) obj.openConnection();
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(Constants.DEFAULT_TIMEOUT);
            conn.setReadTimeout(Constants.DEFAULT_TIMEOUT);
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json");

            try (OutputStream os = conn.getOutputStream()) {
                byte[] input = data.getBytes(StandardCharsets.UTF_8);
                os.write(input, 0, input.length);
            }

            int responseCode = conn.getResponseCode();
            String responseBody = readStream(conn.getInputStream());

            return "Response Code: " + responseCode + "\n" + responseBody;
        } catch (Exception e) {
            return new String[]{Constants.ERROR_MARKER, "HTTP POST failed: " + e.getMessage()}.toString();
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private static String readStream(InputStream stream) throws IOException {
        StringBuilder response = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                response.append(line).append("\n");
            }
        }
        return response.toString().trim();
    }
}
