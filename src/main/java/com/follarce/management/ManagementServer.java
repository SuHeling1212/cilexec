package com.follarce.management;

import com.follarce.Constants;
import com.follarce.process.ProcessRunner;
import com.follarce.util.FileUtil;
import com.follarce.util.JsonUtil;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** HTTP control plane. Process files remain the authority for all displayed state. */
public final class ManagementServer {
    private HttpServer server;

    public void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", Constants.MANAGEMENT_PORT), 0);
        server.createContext("/api/processes", this::processes);
        server.createContext("/", this::staticFile);
        server.start();
    }

    public void stop() { if (server != null) server.stop(0); }

    private void processes(HttpExchange exchange) throws IOException {
        String[] parts = exchange.getRequestURI().getPath().split("/");
        if (parts.length == 3) {
            List<Map<String, Object>> result = new ArrayList<>();
            for (String name : FileUtil.getListOfFileAndDirectory(Constants.SYSTEM_PROCESS_PATH).stream().map(m -> String.valueOf(m.get("name")).toString()).toList()) {
                if (!name.endsWith(".proc")) continue;
                try { result.add(summary(JsonUtil.parseToMap(FileUtil.read(Constants.SYSTEM_PROCESS_PATH + name)))); } catch (Exception ignored) {}
            }
            json(exchange, 200, result); return;
        }
        if (parts.length < 4) { json(exchange, 404, Map.of("error", "Not found")); return; }
        int pid;
        try { pid = Integer.parseInt(parts[3]); } catch (NumberFormatException e) { json(exchange, 400, Map.of("error", "Invalid PID")); return; }
        String path = Constants.SYSTEM_PROCESS_PATH + pid + ".proc";
        if (!FileUtil.exists(path)) { json(exchange, 404, Map.of("error", "Process not found")); return; }
        if (parts.length == 4 && "GET".equals(exchange.getRequestMethod())) { json(exchange, 200, JsonUtil.parseToMap(FileUtil.read(path))); return; }
        if (!"POST".equals(exchange.getRequestMethod()) || parts.length != 5) { json(exchange, 405, Map.of("error", "Unsupported operation")); return; }
        String operation = parts[4];
        if ("pause".equals(operation)) ProcessRunner.postMessage(pid, "Control.Paused", true);
        else if ("resume".equals(operation)) ProcessRunner.postMessage(pid, "Control.Paused", false);
        else if ("terminate".equals(operation)) ProcessRunner.terminateProcess(pid);
        else if ("variable".equals(operation)) {
            @SuppressWarnings("unchecked") Map<String, Object> input = JsonUtil.parseToMap(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            String name = String.valueOf(input.get("name"));
            if (!name.matches("[A-Za-z_][A-Za-z0-9_]*")) { json(exchange, 400, Map.of("error", "Invalid variable name")); return; }
            ProcessRunner.postMessage(pid, "Program.Data." + name, input.get("value"));
        } else if ("field".equals(operation)) {
            @SuppressWarnings("unchecked") Map<String, Object> input = JsonUtil.parseToMap(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            String field = String.valueOf(input.get("path"));
            if (!field.matches("[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)*")) {
                json(exchange, 400, Map.of("error", "Invalid field path")); return;
            }
            ProcessRunner.postMessage(pid, field, input.get("value"));
        } else { json(exchange, 404, Map.of("error", "Unknown operation")); return; }
        json(exchange, 200, Map.of("ok", true));
    }

    @SuppressWarnings("unchecked") private Map<String, Object> summary(Map<String, Object> process) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("pid", process.get("PID")); result.put("name", process.get("Name")); result.put("owner", process.get("Owner")); result.put("status", process.get("Status"));
        Map<String, Object> control = (Map<String, Object>) process.get("Control"); result.put("paused", control != null && Boolean.TRUE.equals(control.get("Paused")));
        Map<String, Object> program = (Map<String, Object>) process.get("Program"); if (program != null) { Map<String, Object> code = (Map<String, Object>) program.get("Code"); if (code != null) result.put("line", code.get("runningCodeLine")); }
        return result;
    }

    private void staticFile(HttpExchange exchange) throws IOException {
        String requested = exchange.getRequestURI().getPath();
        String resource = "/web" + ("/".equals(requested) ? "/index.html" : requested);
        try (InputStream input = getClass().getResourceAsStream(resource)) {
            if (input == null) { exchange.sendResponseHeaders(404, -1); return; }
            byte[] body = input.readAllBytes(); exchange.getResponseHeaders().set("Content-Type", resource.endsWith(".css") ? "text/css" : resource.endsWith(".js") ? "application/javascript" : "text/html; charset=utf-8"); exchange.sendResponseHeaders(200, body.length); exchange.getResponseBody().write(body);
        } finally { exchange.close(); }
    }
    private void json(HttpExchange x, int status, Object data) throws IOException { byte[] body = JsonUtil.toJson(data).getBytes(StandardCharsets.UTF_8); x.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8"); x.sendResponseHeaders(status, body.length); x.getResponseBody().write(body); x.close(); }
}
