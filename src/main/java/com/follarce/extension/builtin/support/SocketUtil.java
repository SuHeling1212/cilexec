package com.follarce.extension.builtin.support;

import com.follarce.kernel.Constants;
import com.follarce.kernel.log.Logger;

import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Socket 工具类 —— 管理 TCP Socket 连接（客户端和服务端）。
 */
public final class SocketUtil {

    private SocketUtil() {}

    private static final Map<Integer, SocketConnection> connections = new ConcurrentHashMap<>();
    private static final Map<Integer, ServerSocket> serverSockets = new ConcurrentHashMap<>();
    private static final AtomicInteger connIdCounter = new AtomicInteger(1);

    /**
     * Socket 连接信息。
     */
    public static class SocketConnection {
        public final int id;
        public final Socket socket;
        public final BufferedReader reader;
        public final PrintWriter writer;
        public final String host;
        public final int port;

        public SocketConnection(int id, Socket socket, String host, int port) throws IOException {
            this.id = id;
            this.socket = socket;
            this.host = host;
            this.port = port;
            this.reader = new BufferedReader(
                    new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            this.writer = new PrintWriter(
                    new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8), true);
        }
    }

    /**
     * 连接远程 Socket。
     */
    public static String socketConnect(String host, int port) {
        try {
            Socket socket = new Socket(host, port);
            int id = connIdCounter.getAndIncrement();
            SocketConnection conn = new SocketConnection(id, socket, host, port);
            connections.put(id, conn);
            Logger.info("Socket connected: id=" + id + " " + host + ":" + port);
            return String.valueOf(id);
        } catch (Exception e) {
            Logger.error("Socket connect failed: " + e.getMessage());
            return "ERROR: " + e.getMessage();
        }
    }

    /**
     * 通过 Socket 发送数据。
     */
    public static String socketSend(int connId, String data) {
        SocketConnection conn = connections.get(connId);
        if (conn == null) {
            return "ERROR: Connection not found: " + connId;
        }
        try {
            conn.writer.println(data);
            conn.writer.flush();
            return "Sent " + data.length() + " bytes";
        } catch (Exception e) {
            return "ERROR: Send failed: " + e.getMessage();
        }
    }

    /**
     * 从 Socket 接收数据。
     */
    public static String socketReceive(int connId) {
        SocketConnection conn = connections.get(connId);
        if (conn == null) {
            return "ERROR: Connection not found: " + connId;
        }
        try {
            conn.socket.setSoTimeout(Constants.DEFAULT_TIMEOUT);
            String line = conn.reader.readLine();
            return line != null ? line : "EOF";
        } catch (java.net.SocketTimeoutException e) {
            return "TIMEOUT";
        } catch (Exception e) {
            return "ERROR: Receive failed: " + e.getMessage();
        }
    }

    /**
     * 关闭 Socket 连接。
     */
    public static String socketClose(int connId) {
        SocketConnection conn = connections.remove(connId);
        if (conn == null) {
            return "ERROR: Connection not found: " + connId;
        }
        try {
            conn.socket.close();
            Logger.info("Socket closed: id=" + connId);
            return "Connection closed";
        } catch (Exception e) {
            return "ERROR: Close failed: " + e.getMessage();
        }
    }

    /**
     * 绑定监听端口（服务器）。
     */
    public static String socketBind(int port) {
        try {
            if (serverSockets.containsKey(port)) {
                return "ERROR: Port already bound: " + port;
            }
            ServerSocket serverSocket = new ServerSocket(port);
            serverSockets.put(port, serverSocket);
            Logger.info("Socket bound to port " + port);
            return "Bound to port " + port;
        } catch (Exception e) {
            return "ERROR: Bind failed: " + e.getMessage();
        }
    }

    /**
     * 接受连接（服务器阻塞）。
     */
    public static String socketAccept(int port) {
        ServerSocket serverSocket = serverSockets.get(port);
        if (serverSocket == null) {
            return "ERROR: Port not bound: " + port;
        }
        try {
            serverSocket.setSoTimeout(Constants.DEFAULT_TIMEOUT);
            Socket client = serverSocket.accept();
            int id = connIdCounter.getAndIncrement();
            SocketConnection conn = new SocketConnection(id, client,
                    client.getInetAddress().getHostAddress(), client.getPort());
            connections.put(id, conn);
            Logger.info("Socket accepted: id=" + id + " from " + conn.host + ":" + conn.port);
            return String.valueOf(id);
        } catch (java.net.SocketTimeoutException e) {
            return "TIMEOUT";
        } catch (Exception e) {
            return "ERROR: Accept failed: " + e.getMessage();
        }
    }

    /**
     * 获取所有活跃连接。
     */
    public static String getActiveConnections() {
        if (connections.isEmpty()) {
            return "No active connections";
        }
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<Integer, SocketConnection> entry : connections.entrySet()) {
            SocketConnection conn = entry.getValue();
            sb.append("ID=").append(entry.getKey())
                    .append(" ").append(conn.host).append(":").append(conn.port)
                    .append(" connected=").append(!conn.socket.isClosed())
                    .append("\n");
        }
        return sb.toString().trim();
    }
}
