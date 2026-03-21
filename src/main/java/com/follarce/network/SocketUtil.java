package com.follarce.network;

import com.follarce.basicUtil.FileUtil;
import com.follarce.basicUtil.JsonUtil;
import com.follarce.basicUtil.Logger;
import com.follarce.basicUtil.TimeUtil;
import com.follarce.basicUtil.UserUtil;
import com.follarce.process.ProcessFunc;

import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Socket utility class
 * Provides TCP/UDP socket functionality for Java API calls
 * 
 * Usage example:
 * <pre>
 * // Create TCP server
 * String[] result = SocketUtil.createServer("127.0.0.1", 8080, "/user/local/sockets/");
 * 
 * // Connect to TCP server
 * String[] result = SocketUtil.connect("127.0.0.1", 8080, "/user/local/sockets/");
 * 
 * // Send data
 * String[] result = SocketUtil.send(1, "Hello World");
 * 
 * // Receive data (saved to file)
 * String[] result = SocketUtil.receive(1, "/user/local/data/");
 * 
 * // Close socket
 * String[] result = SocketUtil.close(1);
 * </pre>
 */
public class SocketUtil {

    private static final String SOCKET_DIR = "/system/sockets/";
    private static final int DEFAULT_TIMEOUT = 10000; // 10 seconds
    private static final int BUFFER_SIZE = 8192; // 8KB buffer
    
    // Socket registry: socketId -> SocketInfo
    private static final Map<Integer, SocketInfo> sockets = new ConcurrentHashMap<>();
    private static final AtomicInteger socketIdGenerator = new AtomicInteger(1);
    
    /**
     * Socket information holder
     */
    private static class SocketInfo {
        int id;
        int ownerPid;
        String type; // "tcp_server", "tcp_client", "udp"
        String host;
        int port;
        Socket socket;
        ServerSocket serverSocket;
        DatagramSocket datagramSocket;
        String saveDir; // Directory to save received data
        boolean isRunning;
        Thread receiveThread;
        
        SocketInfo(int id, int ownerPid, String type, String host, int port, String saveDir) {
            this.id = id;
            this.ownerPid = ownerPid;
            this.type = type;
            this.host = host;
            this.port = port;
            this.saveDir = saveDir;
            this.isRunning = true;
        }
    }
    
    /**
     * Initialize socket system
     */
    public static void init() {
        // Ensure socket directory exists
        String[] listResult = FileUtil.getListOfFileAndDirectory(SOCKET_DIR);
        if (!listResult[0].equals("SUCCESS")) {
            FileUtil.createDirectory("/system/", "sockets");
        }
        Logger.info("Socket system initialized");
    }
    
    /**
     * Create TCP server socket
     * 
     * @param host Bind address (e.g., "127.0.0.1" or "0.0.0.0")
     * @param port Port number
     * @param saveDir Directory to save received data files
     * @return String[] ["SUCCESS", socketId] or ["ERROR", errorCode]
     */
    public static String[] createServer(String host, int port, String saveDir) {
        // Validate parameters
        if (host == null || host.trim().isEmpty()) {
            return new String[]{"ERROR", "INVALID_HOST"};
        }
        if (port <= 0 || port > 65535) {
            return new String[]{"ERROR", "INVALID_PORT"};
        }
        if (saveDir == null || saveDir.trim().isEmpty()) {
            return new String[]{"ERROR", "INVALID_SAVE_DIR"};
        }
        
        // Check directory permission
        if (!UserUtil.checkFilePermission(saveDir, "write")) {
            return new String[]{"ERROR", "INSUFFICIENT_PERMISSION"};
        }
        
        // Ensure save directory exists
        String[] dirResult = ensureDirectory(saveDir);
        if (!dirResult[0].equals("SUCCESS")) {
            return dirResult;
        }
        
        try {
            ServerSocket serverSocket = new ServerSocket();
            serverSocket.bind(new InetSocketAddress(host, port));
            serverSocket.setSoTimeout(1000); // 1 second timeout for accept
            
            int socketId = socketIdGenerator.getAndIncrement();
            int currentPid = ProcessFunc.getPID();
            
            SocketInfo info = new SocketInfo(socketId, currentPid, "tcp_server", host, port, saveDir);
            info.serverSocket = serverSocket;
            sockets.put(socketId, info);
            
            // Save socket info to file
            saveSocketInfo(info);
            
            Logger.info("Created TCP server socket " + socketId + " on " + host + ":" + port);
            return new String[]{"SUCCESS", String.valueOf(socketId)};
            
        } catch (BindException e) {
            Logger.error("Port already in use: " + port);
            return new String[]{"ERROR", "PORT_IN_USE"};
        } catch (IOException e) {
            Logger.error("Failed to create server socket: " + e.getMessage());
            return new String[]{"ERROR", "CREATE_SOCKET_FAILED"};
        }
    }
    
    /**
     * Accept client connection (for server sockets)
     * 
     * @param serverSocketId Server socket ID
     * @param saveDir Directory to save received data
     * @return String[] ["SUCCESS", clientSocketId] or ["ERROR", errorCode]
     */
    public static String[] accept(int serverSocketId, String saveDir) {
        SocketInfo serverInfo = sockets.get(serverSocketId);
        if (serverInfo == null) {
            return new String[]{"ERROR", "SOCKET_DOES_NOT_EXIST"};
        }
        
        // Check ownership
        int currentPid = ProcessFunc.getPID();
        if (serverInfo.ownerPid != currentPid && !UserUtil.isLocal()) {
            return new String[]{"ERROR", "INSUFFICIENT_PERMISSION"};
        }
        
        if (!"tcp_server".equals(serverInfo.type)) {
            return new String[]{"ERROR", "NOT_SERVER_SOCKET"};
        }
        
        try {
            Socket clientSocket = serverInfo.serverSocket.accept();
            
            int clientSocketId = socketIdGenerator.getAndIncrement();
            String clientHost = clientSocket.getInetAddress().getHostAddress();
            int clientPort = clientSocket.getPort();
            
            SocketInfo clientInfo = new SocketInfo(clientSocketId, currentPid, "tcp_client", clientHost, clientPort, saveDir);
            clientInfo.socket = clientSocket;
            sockets.put(clientSocketId, clientInfo);
            
            // Start receive thread
            startReceiveThread(clientInfo);
            
            // Save socket info
            saveSocketInfo(clientInfo);
            
            Logger.info("Accepted client connection " + clientSocketId + " from " + clientHost + ":" + clientPort);
            return new String[]{"SUCCESS", String.valueOf(clientSocketId)};
            
        } catch (SocketTimeoutException e) {
            return new String[]{"ERROR", "ACCEPT_TIMEOUT"};
        } catch (IOException e) {
            Logger.error("Failed to accept connection: " + e.getMessage());
            return new String[]{"ERROR", "ACCEPT_FAILED"};
        }
    }
    
    /**
     * Connect to TCP server
     * 
     * @param host Server address
     * @param port Server port
     * @param saveDir Directory to save received data
     * @return String[] ["SUCCESS", socketId] or ["ERROR", errorCode]
     */
    public static String[] connect(String host, int port, String saveDir) {
        // Validate parameters
        if (host == null || host.trim().isEmpty()) {
            return new String[]{"ERROR", "INVALID_HOST"};
        }
        if (port <= 0 || port > 65535) {
            return new String[]{"ERROR", "INVALID_PORT"};
        }
        if (saveDir == null || saveDir.trim().isEmpty()) {
            return new String[]{"ERROR", "INVALID_SAVE_DIR"};
        }
        
        // Check directory permission
        if (!UserUtil.checkFilePermission(saveDir, "write")) {
            return new String[]{"ERROR", "INSUFFICIENT_PERMISSION"};
        }
        
        // Ensure save directory exists
        String[] dirResult = ensureDirectory(saveDir);
        if (!dirResult[0].equals("SUCCESS")) {
            return dirResult;
        }
        
        try {
            Socket socket = new Socket();
            socket.connect(new InetSocketAddress(host, port), DEFAULT_TIMEOUT);
            
            int socketId = socketIdGenerator.getAndIncrement();
            int currentPid = ProcessFunc.getPID();
            
            SocketInfo info = new SocketInfo(socketId, currentPid, "tcp_client", host, port, saveDir);
            info.socket = socket;
            sockets.put(socketId, info);
            
            // Start receive thread
            startReceiveThread(info);
            
            // Save socket info
            saveSocketInfo(info);
            
            Logger.info("Connected to " + host + ":" + port + " with socket " + socketId);
            return new String[]{"SUCCESS", String.valueOf(socketId)};
            
        } catch (UnknownHostException e) {
            Logger.error("Unknown host: " + host);
            return new String[]{"ERROR", "UNKNOWN_HOST"};
        } catch (ConnectException e) {
            Logger.error("Connection refused: " + host + ":" + port);
            return new String[]{"ERROR", "CONNECTION_REFUSED"};
        } catch (SocketTimeoutException e) {
            Logger.error("Connection timeout: " + host + ":" + port);
            return new String[]{"ERROR", "CONNECTION_TIMEOUT"};
        } catch (IOException e) {
            Logger.error("Failed to connect: " + e.getMessage());
            return new String[]{"ERROR", "CONNECT_FAILED"};
        }
    }
    
    /**
     * Send data through socket
     * 
     * @param socketId Socket ID
     * @param data Data to send (string)
     * @return String[] ["SUCCESS", null] or ["ERROR", errorCode]
     */
    public static String[] send(int socketId, String data) {
        if (data == null) {
            return new String[]{"ERROR", "INVALID_DATA"};
        }
        
        SocketInfo info = sockets.get(socketId);
        if (info == null) {
            return new String[]{"ERROR", "SOCKET_DOES_NOT_EXIST"};
        }
        
        // Check ownership
        int currentPid = ProcessFunc.getPID();
        if (info.ownerPid != currentPid && !UserUtil.isLocal()) {
            return new String[]{"ERROR", "INSUFFICIENT_PERMISSION"};
        }
        
        if (!info.isRunning) {
            return new String[]{"ERROR", "SOCKET_CLOSED"};
        }
        
        try {
            if ("tcp_client".equals(info.type) && info.socket != null) {
                OutputStream out = info.socket.getOutputStream();
                out.write(data.getBytes("UTF-8"));
                out.flush();
                return new String[]{"SUCCESS", null};
            } else if ("udp".equals(info.type) && info.datagramSocket != null) {
                // UDP send
                byte[] buffer = data.getBytes("UTF-8");
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length, 
                    InetAddress.getByName(info.host), info.port);
                info.datagramSocket.send(packet);
                return new String[]{"SUCCESS", null};
            } else {
                return new String[]{"ERROR", "INVALID_SOCKET_TYPE"};
            }
        } catch (IOException e) {
            Logger.error("Failed to send data: " + e.getMessage());
            return new String[]{"ERROR", "SEND_FAILED"};
        }
    }
    
    /**
     * Send data through socket (binary)
     * 
     * @param socketId Socket ID
     * @param data Data to send (byte array)
     * @return String[] ["SUCCESS", null] or ["ERROR", errorCode]
     */
    public static String[] sendBytes(int socketId, byte[] data) {
        if (data == null) {
            return new String[]{"ERROR", "INVALID_DATA"};
        }
        
        SocketInfo info = sockets.get(socketId);
        if (info == null) {
            return new String[]{"ERROR", "SOCKET_DOES_NOT_EXIST"};
        }
        
        // Check ownership
        int currentPid = ProcessFunc.getPID();
        if (info.ownerPid != currentPid && !UserUtil.isLocal()) {
            return new String[]{"ERROR", "INSUFFICIENT_PERMISSION"};
        }
        
        if (!info.isRunning) {
            return new String[]{"ERROR", "SOCKET_CLOSED"};
        }
        
        try {
            if ("tcp_client".equals(info.type) && info.socket != null) {
                OutputStream out = info.socket.getOutputStream();
                out.write(data);
                out.flush();
                return new String[]{"SUCCESS", null};
            } else {
                return new String[]{"ERROR", "INVALID_SOCKET_TYPE"};
            }
        } catch (IOException e) {
            Logger.error("Failed to send data: " + e.getMessage());
            return new String[]{"ERROR", "SEND_FAILED"};
        }
    }
    
    /**
     * Receive data from socket (blocking, single receive)
     * Saves received data to file
     * 
     * @param socketId Socket ID
     * @param saveDir Directory to save received data (optional, uses socket's default if null)
     * @return String[] ["SUCCESS", filename] or ["ERROR", errorCode]
     */
    public static String[] receive(int socketId, String saveDir) {
        SocketInfo info = sockets.get(socketId);
        if (info == null) {
            return new String[]{"ERROR", "SOCKET_DOES_NOT_EXIST"};
        }
        
        // Check ownership
        int currentPid = ProcessFunc.getPID();
        if (info.ownerPid != currentPid && !UserUtil.isLocal()) {
            return new String[]{"ERROR", "INSUFFICIENT_PERMISSION"};
        }
        
        if (!info.isRunning) {
            return new String[]{"ERROR", "SOCKET_CLOSED"};
        }
        
        String targetDir = saveDir != null ? saveDir : info.saveDir;
        if (targetDir == null || targetDir.trim().isEmpty()) {
            return new String[]{"ERROR", "INVALID_SAVE_DIR"};
        }
        
        // Ensure directory exists
        String[] dirResult = ensureDirectory(targetDir);
        if (!dirResult[0].equals("SUCCESS")) {
            return dirResult;
        }
        
        try {
            if ("tcp_client".equals(info.type) && info.socket != null) {
                InputStream in = info.socket.getInputStream();
                
                // Generate filename with timestamp
                String filename = generateDataFilename(info.id);
                String filePath = targetDir + filename;
                
                // Read data and save to file
                String realPath = FileUtil.getVfsRoot() + filePath.replace('/', java.io.File.separatorChar);
                FileOutputStream fos = new FileOutputStream(realPath);
                
                byte[] buffer = new byte[BUFFER_SIZE];
                int bytesRead;
                int totalBytes = 0;
                
                // Set socket timeout for this read
                info.socket.setSoTimeout(5000); // 5 second timeout
                
                try {
                    while ((bytesRead = in.read(buffer)) != -1) {
                        fos.write(buffer, 0, bytesRead);
                        totalBytes += bytesRead;
                        
                        // Break if no more data available (non-blocking check)
                        if (in.available() == 0) {
                            break;
                        }
                    }
                } catch (SocketTimeoutException e) {
                    // Timeout is OK, return what we have
                }
                
                fos.close();
                
                if (totalBytes == 0) {
                    // Delete empty file
                    new File(realPath).delete();
                    return new String[]{"ERROR", "NO_DATA_RECEIVED"};
                }
                
                Logger.info("Received " + totalBytes + " bytes from socket " + socketId + " to " + filename);
                return new String[]{"SUCCESS", filename};
                
            } else if ("udp".equals(info.type) && info.datagramSocket != null) {
                // UDP receive
                byte[] buffer = new byte[BUFFER_SIZE];
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                
                info.datagramSocket.setSoTimeout(5000);
                info.datagramSocket.receive(packet);
                
                // Save to file
                String filename = generateDataFilename(info.id);
                String filePath = targetDir + filename;
                String realPath = FileUtil.getVfsRoot() + filePath.replace('/', java.io.File.separatorChar);
                
                FileOutputStream fos = new FileOutputStream(realPath);
                fos.write(packet.getData(), 0, packet.getLength());
                fos.close();
                
                Logger.info("Received UDP packet " + packet.getLength() + " bytes from socket " + socketId);
                return new String[]{"SUCCESS", filename};
                
            } else {
                return new String[]{"ERROR", "INVALID_SOCKET_TYPE"};
            }
            
        } catch (SocketTimeoutException e) {
            return new String[]{"ERROR", "RECEIVE_TIMEOUT"};
        } catch (IOException e) {
            Logger.error("Failed to receive data: " + e.getMessage());
            return new String[]{"ERROR", "RECEIVE_FAILED"};
        }
    }
    
    /**
     * Close socket
     * 
     * @param socketId Socket ID
     * @return String[] ["SUCCESS", null] or ["ERROR", errorCode]
     */
    public static String[] close(int socketId) {
        SocketInfo info = sockets.get(socketId);
        if (info == null) {
            return new String[]{"ERROR", "SOCKET_DOES_NOT_EXIST"};
        }
        
        // Check ownership
        int currentPid = ProcessFunc.getPID();
        if (info.ownerPid != currentPid && !UserUtil.isLocal()) {
            return new String[]{"ERROR", "INSUFFICIENT_PERMISSION"};
        }
        
        return closeSocketInternal(socketId, info);
    }
    
    /**
     * Internal close socket method
     */
    private static String[] closeSocketInternal(int socketId, SocketInfo info) {
        info.isRunning = false;
        
        // Stop receive thread
        if (info.receiveThread != null && info.receiveThread.isAlive()) {
            info.receiveThread.interrupt();
        }
        
        try {
            if (info.socket != null && !info.socket.isClosed()) {
                info.socket.close();
            }
            if (info.serverSocket != null && !info.serverSocket.isClosed()) {
                info.serverSocket.close();
            }
            if (info.datagramSocket != null && !info.datagramSocket.isClosed()) {
                info.datagramSocket.close();
            }
        } catch (IOException e) {
            Logger.error("Error closing socket: " + e.getMessage());
        }
        
        sockets.remove(socketId);
        
        // Remove socket info file
        FileUtil.removeFile(SOCKET_DIR + socketId + ".json");
        
        Logger.info("Closed socket " + socketId);
        return new String[]{"SUCCESS", null};
    }
    
    /**
     * Get socket info
     * 
     * @param socketId Socket ID
     * @return Map with socket info, or null if not found
     */
    public static Map<String, Object> getSocketInfo(int socketId) {
        SocketInfo info = sockets.get(socketId);
        if (info == null) {
            return null;
        }
        
        Map<String, Object> result = new HashMap<>();
        result.put("id", info.id);
        result.put("ownerPid", info.ownerPid);
        result.put("type", info.type);
        result.put("host", info.host);
        result.put("port", info.port);
        result.put("saveDir", info.saveDir);
        result.put("isRunning", info.isRunning);
        
        return result;
    }
    
    /**
     * List all sockets owned by current process
     * 
     * @return Map<socketId, socketInfo>
     */
    public static Map<Integer, Map<String, Object>> listSockets() {
        int currentPid = ProcessFunc.getPID();
        Map<Integer, Map<String, Object>> result = new HashMap<>();
        
        for (Map.Entry<Integer, SocketInfo> entry : sockets.entrySet()) {
            SocketInfo info = entry.getValue();
            if (info.ownerPid == currentPid || UserUtil.isLocal()) {
                result.put(entry.getKey(), getSocketInfo(entry.getKey()));
            }
        }
        
        return result;
    }
    
    /**
     * Create UDP socket
     * 
     * @param host Bind address
     * @param port Port number (0 for auto-assign)
     * @param saveDir Directory to save received data
     * @return String[] ["SUCCESS", socketId] or ["ERROR", errorCode]
     */
    public static String[] createUdpSocket(String host, int port, String saveDir) {
        // Validate parameters
        if (host == null || host.trim().isEmpty()) {
            return new String[]{"ERROR", "INVALID_HOST"};
        }
        if (port < 0 || port > 65535) {
            return new String[]{"ERROR", "INVALID_PORT"};
        }
        if (saveDir == null || saveDir.trim().isEmpty()) {
            return new String[]{"ERROR", "INVALID_SAVE_DIR"};
        }
        
        // Check directory permission
        if (!UserUtil.checkFilePermission(saveDir, "write")) {
            return new String[]{"ERROR", "INSUFFICIENT_PERMISSION"};
        }
        
        // Ensure save directory exists
        String[] dirResult = ensureDirectory(saveDir);
        if (!dirResult[0].equals("SUCCESS")) {
            return dirResult;
        }
        
        try {
            DatagramSocket datagramSocket;
            if (port == 0) {
                datagramSocket = new DatagramSocket();
            } else {
                datagramSocket = new DatagramSocket(port, InetAddress.getByName(host));
            }
            datagramSocket.setSoTimeout(1000);
            
            int socketId = socketIdGenerator.getAndIncrement();
            int currentPid = ProcessFunc.getPID();
            int actualPort = datagramSocket.getLocalPort();
            
            SocketInfo info = new SocketInfo(socketId, currentPid, "udp", host, actualPort, saveDir);
            info.datagramSocket = datagramSocket;
            sockets.put(socketId, info);
            
            // Start receive thread for UDP
            startUdpReceiveThread(info);
            
            // Save socket info
            saveSocketInfo(info);
            
            Logger.info("Created UDP socket " + socketId + " on " + host + ":" + actualPort);
            return new String[]{"SUCCESS", String.valueOf(socketId)};
            
        } catch (BindException e) {
            Logger.error("Port already in use: " + port);
            return new String[]{"ERROR", "PORT_IN_USE"};
        } catch (IOException e) {
            Logger.error("Failed to create UDP socket: " + e.getMessage());
            return new String[]{"ERROR", "CREATE_SOCKET_FAILED"};
        }
    }
    
    /**
     * Send UDP packet to specific address
     * 
     * @param socketId UDP socket ID
     * @param host Target host
     * @param port Target port
     * @param data Data to send
     * @return String[] ["SUCCESS", null] or ["ERROR", errorCode]
     */
    public static String[] sendTo(int socketId, String host, int port, String data) {
        if (host == null || host.trim().isEmpty()) {
            return new String[]{"ERROR", "INVALID_HOST"};
        }
        if (port <= 0 || port > 65535) {
            return new String[]{"ERROR", "INVALID_PORT"};
        }
        if (data == null) {
            return new String[]{"ERROR", "INVALID_DATA"};
        }
        
        SocketInfo info = sockets.get(socketId);
        if (info == null) {
            return new String[]{"ERROR", "SOCKET_DOES_NOT_EXIST"};
        }
        
        // Check ownership
        int currentPid = ProcessFunc.getPID();
        if (info.ownerPid != currentPid && !UserUtil.isLocal()) {
            return new String[]{"ERROR", "INSUFFICIENT_PERMISSION"};
        }
        
        if (!"udp".equals(info.type)) {
            return new String[]{"ERROR", "NOT_UDP_SOCKET"};
        }
        
        try {
            byte[] buffer = data.getBytes("UTF-8");
            DatagramPacket packet = new DatagramPacket(buffer, buffer.length, 
                InetAddress.getByName(host), port);
            info.datagramSocket.send(packet);
            return new String[]{"SUCCESS", null};
        } catch (IOException e) {
            Logger.error("Failed to send UDP packet: " + e.getMessage());
            return new String[]{"ERROR", "SEND_FAILED"};
        }
    }
    
    /**
     * Start TCP receive thread
     */
    private static void startReceiveThread(SocketInfo info) {
        info.receiveThread = new Thread(() -> {
            try {
                InputStream in = info.socket.getInputStream();
                byte[] buffer = new byte[BUFFER_SIZE];
                
                while (info.isRunning && !info.socket.isClosed()) {
                    try {
                        int available = in.available();
                        if (available > 0) {
                            // Auto-save received data to file
                            String filename = generateDataFilename(info.id);
                            String filePath = info.saveDir + filename;
                            String realPath = FileUtil.getVfsRoot() + filePath.replace('/', java.io.File.separatorChar);
                            
                            FileOutputStream fos = new FileOutputStream(realPath);
                            int bytesRead;
                            int totalBytes = 0;
                            
                            while ((bytesRead = in.read(buffer)) != -1) {
                                fos.write(buffer, 0, bytesRead);
                                totalBytes += bytesRead;
                                
                                if (in.available() == 0) {
                                    break;
                                }
                            }
                            
                            fos.close();
                            Logger.info("Auto-saved " + totalBytes + " bytes from socket " + info.id + " to " + filename);
                        }
                        
                        Thread.sleep(100);
                    } catch (InterruptedException e) {
                        // Thread interrupted
                        break;
                    } catch (SocketException e) {
                        // Socket closed
                        break;
                    } catch (IOException e) {
                        Logger.error("Receive error on socket " + info.id + ": " + e.getMessage());
                        break;
                    }
                }
            } catch (IOException e) {
                Logger.error("Failed to start receive thread for socket " + info.id);
            }
        });
        
        info.receiveThread.setDaemon(true);
        info.receiveThread.start();
    }
    
    /**
     * Start UDP receive thread
     */
    private static void startUdpReceiveThread(SocketInfo info) {
        info.receiveThread = new Thread(() -> {
            byte[] buffer = new byte[BUFFER_SIZE];
            
            while (info.isRunning && !info.datagramSocket.isClosed()) {
                try {
                    DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                    info.datagramSocket.receive(packet);
                    
                    // Save to file
                    String filename = generateDataFilename(info.id);
                    String filePath = info.saveDir + filename;
                    String realPath = FileUtil.getVfsRoot() + filePath.replace('/', java.io.File.separatorChar);
                    
                    FileOutputStream fos = new FileOutputStream(realPath);
                    fos.write(packet.getData(), 0, packet.getLength());
                    fos.close();
                    
                    Logger.info("Auto-saved UDP packet " + packet.getLength() + " bytes from socket " + info.id);
                    
                } catch (SocketTimeoutException e) {
                    // Continue loop
                } catch (IOException e) {
                    if (info.isRunning) {
                        Logger.error("UDP receive error on socket " + info.id + ": " + e.getMessage());
                    }
                    break;
                }
            }
        });
        
        info.receiveThread.setDaemon(true);
        info.receiveThread.start();
    }
    
    /**
     * Save socket info to file
     */
    private static void saveSocketInfo(SocketInfo info) {
        Map<String, Object> socketData = new HashMap<>();
        socketData.put("id", info.id);
        socketData.put("ownerPid", info.ownerPid);
        socketData.put("type", info.type);
        socketData.put("host", info.host);
        socketData.put("port", info.port);
        socketData.put("saveDir", info.saveDir);
        socketData.put("isRunning", info.isRunning);
        socketData.put("created", TimeUtil.getTime());
        
        FileUtil.createFile(SOCKET_DIR, info.id + ".json");
        FileUtil.write(SOCKET_DIR + info.id + ".json", JsonUtil.toJson(socketData));
    }
    
    /**
     * Generate unique filename for received data
     */
    private static String generateDataFilename(int socketId) {
        int[] time = TimeUtil.getTime();
        return String.format("socket_%d_%04d%02d%02d_%02d%02d%02d_%03d.dat",
            socketId, time[0], time[1], time[2], time[3], time[4], time[5], time[6]);
    }
    
    /**
     * Ensure directory exists
     */
    private static String[] ensureDirectory(String path) {
        String[] listResult = FileUtil.getListOfFileAndDirectory(path);
        if (listResult[0].equals("SUCCESS")) {
            return new String[]{"SUCCESS", null};
        }
        
        // Try to create directory recursively
        String normalized = path;
        if (!normalized.endsWith("/")) {
            normalized = normalized + "/";
        }
        
        String[] parts = normalized.split("/");
        String currentPath = "/";
        
        for (String part : parts) {
            if (part.isEmpty()) continue;
            
            currentPath = currentPath + part + "/";
            
            String[] checkResult = FileUtil.getListOfFileAndDirectory(currentPath);
            if (!checkResult[0].equals("SUCCESS")) {
                String parentPath = currentPath.substring(0, currentPath.lastIndexOf(part));
                String[] createResult = FileUtil.createDirectory(parentPath, part);
                if (!createResult[0].equals("SUCCESS")) {
                    return createResult;
                }
            }
        }
        
        return new String[]{"SUCCESS", null};
    }
    
    /**
     * Clean up sockets when process exits
     * 
     * @param pid Process ID
     */
    public static void onProcessExit(int pid) {
        List<Integer> toRemove = new ArrayList<>();
        
        for (Map.Entry<Integer, SocketInfo> entry : sockets.entrySet()) {
            if (entry.getValue().ownerPid == pid) {
                toRemove.add(entry.getKey());
            }
        }
        
        for (Integer socketId : toRemove) {
            SocketInfo info = sockets.get(socketId);
            if (info != null) {
                closeSocketInternal(socketId, info);
            }
        }
        
        if (!toRemove.isEmpty()) {
            Logger.info("Cleaned up " + toRemove.size() + " sockets for PID " + pid);
        }
    }
    
    /**
     * Function dispatch for script engine
     */
    public static Object call(String name, Object[] args) {
        switch (name) {
            case "socket.createServer":
                if (args.length < 2 || !(args[0] instanceof String) || !(args[1] instanceof Number)) {
                    return new String[]{"ERROR", "INVALID_ARGUMENTS"};
                }
                String saveDir = args.length >= 3 && args[2] instanceof String ? (String) args[2] : "/user/local/sockets/";
                return createServer((String) args[0], ((Number) args[1]).intValue(), saveDir);
                
            case "socket.connect":
                if (args.length < 2 || !(args[0] instanceof String) || !(args[1] instanceof Number)) {
                    return new String[]{"ERROR", "INVALID_ARGUMENTS"};
                }
                saveDir = args.length >= 3 && args[2] instanceof String ? (String) args[2] : "/user/local/sockets/";
                return connect((String) args[0], ((Number) args[1]).intValue(), saveDir);
                
            case "socket.accept":
                if (args.length < 1 || !(args[0] instanceof Number)) {
                    return new String[]{"ERROR", "INVALID_ARGUMENTS"};
                }
                saveDir = args.length >= 2 && args[1] instanceof String ? (String) args[1] : "/user/local/sockets/";
                return accept(((Number) args[0]).intValue(), saveDir);
                
            case "socket.send":
                if (args.length < 2 || !(args[0] instanceof Number) || !(args[1] instanceof String)) {
                    return new String[]{"ERROR", "INVALID_ARGUMENTS"};
                }
                return send(((Number) args[0]).intValue(), (String) args[1]);
                
            case "socket.receive":
                if (args.length < 1 || !(args[0] instanceof Number)) {
                    return new String[]{"ERROR", "INVALID_ARGUMENTS"};
                }
                saveDir = args.length >= 2 && args[1] instanceof String ? (String) args[1] : null;
                return receive(((Number) args[0]).intValue(), saveDir);
                
            case "socket.close":
                if (args.length < 1 || !(args[0] instanceof Number)) {
                    return new String[]{"ERROR", "INVALID_ARGUMENTS"};
                }
                return close(((Number) args[0]).intValue());
                
            case "socket.getInfo":
                if (args.length < 1 || !(args[0] instanceof Number)) {
                    return new String[]{"ERROR", "INVALID_ARGUMENTS"};
                }
                return getSocketInfo(((Number) args[0]).intValue());
                
            case "socket.list":
                return listSockets();
                
            case "socket.createUdp":
                if (args.length < 1 || !(args[0] instanceof String)) {
                    return new String[]{"ERROR", "INVALID_ARGUMENTS"};
                }
                int port = args.length >= 2 && args[1] instanceof Number ? ((Number) args[1]).intValue() : 0;
                saveDir = args.length >= 3 && args[2] instanceof String ? (String) args[2] : "/user/local/sockets/";
                return createUdpSocket((String) args[0], port, saveDir);
                
            case "socket.sendTo":
                if (args.length < 4 || !(args[0] instanceof Number) || !(args[1] instanceof String) 
                    || !(args[2] instanceof Number) || !(args[3] instanceof String)) {
                    return new String[]{"ERROR", "INVALID_ARGUMENTS"};
                }
                return sendTo(((Number) args[0]).intValue(), (String) args[1], 
                    ((Number) args[2]).intValue(), (String) args[3]);
                
            default:
                return null;
        }
    }
}
