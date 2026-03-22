package com.follarce.network;

import com.follarce.basicUtil.UserUtil;
import com.follarce.plugin.FunctionContext;
import com.follarce.plugin.FunctionInfo;
import com.follarce.plugin.FunctionProvider;

import java.util.List;
import java.util.Map;

/**
 * Socket function provider
 * Provides TCP/UDP socket functionality for script engine
 * 
 * Script API:
 * - socket.createServer(host, port, saveDir) - Create TCP server
 * - socket.accept(serverId, saveDir) - Accept client connection
 * - socket.connect(host, port, saveDir) - Connect to TCP server
 * - socket.send(socketId, data) - Send data
 * - socket.receive(socketId, saveDir) - Receive data to file
 * - socket.close(socketId) - Close socket
 * - socket.getInfo(socketId) - Get socket info
 * - socket.list() - List all sockets
 * - socket.createUdp(host, port, saveDir) - Create UDP socket
 * - socket.sendTo(socketId, host, port, data) - Send UDP packet
 */
public class SocketFunctionProvider implements FunctionProvider {

    @Override
    public Object call(String name, Object[] args, FunctionContext context) {
        switch (name) {
            // TCP Server
            case "socket.createServer":
                return handleCreateServer(args);
                
            case "socket.accept":
                return handleAccept(args);
                
            // TCP Client
            case "socket.connect":
                return handleConnect(args);
                
            // Common operations
            case "socket.send":
                return handleSend(args);
                
            case "socket.receive":
                return handleReceive(args);
                
            case "socket.close":
                return handleClose(args);
                
            case "socket.getInfo":
                return handleGetInfo(args);
                
            case "socket.list":
                return handleList();
                
            // UDP
            case "socket.createUdp":
                return handleCreateUdp(args);
                
            case "socket.sendTo":
                return handleSendTo(args);
                
            default:
                return null;
        }
    }
    
    /**
     * Get default save directory based on current user
     * Local user: /user/local/sockets/
     * Normal user: /user/{username}/sockets/
     */
    private String getDefaultSaveDir() {
        String currentUser = UserUtil.getCurrentUser();
        if ("local".equals(currentUser)) {
            return "/user/local/sockets/";
        } else {
            return "/user/" + currentUser + "/sockets/";
        }
    }
    
    /**
     * Handle socket.createServer(host, port, saveDir)
     * Creates a TCP server socket
     */
    private Object handleCreateServer(Object[] args) {
        // Parameter validation
        if (args.length < 2) {
            return error("INVALID_ARGUMENTS");
        }
        if (!(args[0] instanceof String)) {
            return error("HOST_MUST_BE_STRING");
        }
        if (!(args[1] instanceof Number)) {
            return error("PORT_MUST_BE_NUMBER");
        }
        
        String host = (String) args[0];
        int port = ((Number) args[1]).intValue();
        String saveDir = args.length >= 3 && args[2] instanceof String 
            ? (String) args[2] 
            : getDefaultSaveDir();
        
        // Validate host
        if (host.trim().isEmpty()) {
            return error("INVALID_HOST");
        }
        
        // Validate port
        if (port <= 0 || port > 65535) {
            return error("INVALID_PORT");
        }
        
        // Validate saveDir
        if (!(saveDir instanceof String) || saveDir.trim().isEmpty()) {
            return error("SAVE_DIR_MUST_BE_STRING");
        }
        
        return SocketUtil.createServer(host, port, saveDir);
    }
    
    /**
     * Handle socket.accept(serverId, saveDir)
     * Accepts a client connection on server socket
     */
    private Object handleAccept(Object[] args) {
        if (args.length < 1) {
            return error("INVALID_ARGUMENTS");
        }
        if (!(args[0] instanceof Number)) {
            return error("SOCKET_ID_MUST_BE_NUMBER");
        }
        
        int serverId = ((Number) args[0]).intValue();
        String saveDir = args.length >= 2 && args[1] instanceof String 
            ? (String) args[1] 
            : getDefaultSaveDir();
        
        return SocketUtil.accept(serverId, saveDir);
    }
    
    /**
     * Handle socket.connect(host, port, saveDir)
     * Connects to a TCP server
     */
    private Object handleConnect(Object[] args) {
        if (args.length < 2) {
            return error("INVALID_ARGUMENTS");
        }
        if (!(args[0] instanceof String)) {
            return error("HOST_MUST_BE_STRING");
        }
        if (!(args[1] instanceof Number)) {
            return error("PORT_MUST_BE_NUMBER");
        }
        
        String host = (String) args[0];
        int port = ((Number) args[1]).intValue();
        String saveDir = args.length >= 3 && args[2] instanceof String 
            ? (String) args[2] 
            : getDefaultSaveDir();
        
        // Validate host
        if (host.trim().isEmpty()) {
            return error("INVALID_HOST");
        }
        
        // Validate port
        if (port <= 0 || port > 65535) {
            return error("INVALID_PORT");
        }
        
        // Validate saveDir
        if (!(saveDir instanceof String) || saveDir.trim().isEmpty()) {
            return error("SAVE_DIR_MUST_BE_STRING");
        }
        
        return SocketUtil.connect(host, port, saveDir);
    }
    
    /**
     * Handle socket.send(socketId, data)
     * Sends data through socket
     */
    private Object handleSend(Object[] args) {
        if (args.length < 2) {
            return error("INVALID_ARGUMENTS");
        }
        if (!(args[0] instanceof Number)) {
            return error("SOCKET_ID_MUST_BE_NUMBER");
        }
        if (!(args[1] instanceof String)) {
            return error("DATA_MUST_BE_STRING");
        }
        
        int socketId = ((Number) args[0]).intValue();
        String data = (String) args[1];
        
        return SocketUtil.send(socketId, data);
    }
    
    /**
     * Handle socket.receive(socketId, saveDir)
     * Receives data from socket and saves to file
     */
    private Object handleReceive(Object[] args) {
        if (args.length < 1) {
            return error("INVALID_ARGUMENTS");
        }
        if (!(args[0] instanceof Number)) {
            return error("SOCKET_ID_MUST_BE_NUMBER");
        }
        
        int socketId = ((Number) args[0]).intValue();
        String saveDir = args.length >= 2 && args[1] instanceof String 
            ? (String) args[1] 
            : null;
        
        return SocketUtil.receive(socketId, saveDir);
    }
    
    /**
     * Handle socket.close(socketId)
     * Closes a socket
     */
    private Object handleClose(Object[] args) {
        if (args.length < 1) {
            return error("INVALID_ARGUMENTS");
        }
        if (!(args[0] instanceof Number)) {
            return error("SOCKET_ID_MUST_BE_NUMBER");
        }
        
        int socketId = ((Number) args[0]).intValue();
        
        return SocketUtil.close(socketId);
    }
    
    /**
     * Handle socket.getInfo(socketId)
     * Gets socket information
     */
    private Object handleGetInfo(Object[] args) {
        if (args.length < 1) {
            return error("INVALID_ARGUMENTS");
        }
        if (!(args[0] instanceof Number)) {
            return error("SOCKET_ID_MUST_BE_NUMBER");
        }
        
        int socketId = ((Number) args[0]).intValue();
        
        Map<String, Object> info = SocketUtil.getSocketInfo(socketId);
        if (info == null) {
            return error("SOCKET_DOES_NOT_EXIST");
        }
        
        return info;
    }
    
    /**
     * Handle socket.list()
     * Lists all sockets owned by current process
     */
    private Object handleList() {
        return SocketUtil.listSockets();
    }
    
    /**
     * Handle socket.createUdp(host, port, saveDir)
     * Creates a UDP socket
     */
    private Object handleCreateUdp(Object[] args) {
        if (args.length < 1) {
            return error("INVALID_ARGUMENTS");
        }
        if (!(args[0] instanceof String)) {
            return error("HOST_MUST_BE_STRING");
        }
        
        String host = (String) args[0];
        int port = args.length >= 2 && args[1] instanceof Number 
            ? ((Number) args[1]).intValue() 
            : 0;
        String saveDir = args.length >= 3 && args[2] instanceof String 
            ? (String) args[2] 
            : getDefaultSaveDir();
        
        // Validate host
        if (host.trim().isEmpty()) {
            return error("INVALID_HOST");
        }
        
        // Validate port
        if (port < 0 || port > 65535) {
            return error("INVALID_PORT");
        }
        
        // Validate saveDir
        if (!(saveDir instanceof String) || saveDir.trim().isEmpty()) {
            return error("SAVE_DIR_MUST_BE_STRING");
        }
        
        return SocketUtil.createUdpSocket(host, port, saveDir);
    }
    
    /**
     * Handle socket.sendTo(socketId, host, port, data)
     * Sends UDP packet to specific address
     */
    private Object handleSendTo(Object[] args) {
        if (args.length < 4) {
            return error("INVALID_ARGUMENTS");
        }
        if (!(args[0] instanceof Number)) {
            return error("SOCKET_ID_MUST_BE_NUMBER");
        }
        if (!(args[1] instanceof String)) {
            return error("HOST_MUST_BE_STRING");
        }
        if (!(args[2] instanceof Number)) {
            return error("PORT_MUST_BE_NUMBER");
        }
        if (!(args[3] instanceof String)) {
            return error("DATA_MUST_BE_STRING");
        }
        
        int socketId = ((Number) args[0]).intValue();
        String host = (String) args[1];
        int port = ((Number) args[2]).intValue();
        String data = (String) args[3];
        
        // Validate host
        if (host.trim().isEmpty()) {
            return error("INVALID_HOST");
        }
        
        // Validate port
        if (port <= 0 || port > 65535) {
            return error("INVALID_PORT");
        }
        
        return SocketUtil.sendTo(socketId, host, port, data);
    }
    
    private String[] error(String code) {
        return new String[]{"ERROR", code};
    }
    
    @Override
    public FunctionInfo[] getFunctions() {
        return new FunctionInfo[]{
            new FunctionInfo(
                "socket.createServer",
                "Create TCP server socket",
                new String[]{"host: string", "port: int", "saveDir: string (optional)"},
                "String[]",
                "Socket"
            ),
            new FunctionInfo(
                "socket.accept",
                "Accept client connection on server socket",
                new String[]{"serverId: int", "saveDir: string (optional)"},
                "String[]",
                "Socket"
            ),
            new FunctionInfo(
                "socket.connect",
                "Connect to TCP server",
                new String[]{"host: string", "port: int", "saveDir: string (optional)"},
                "String[]",
                "Socket"
            ),
            new FunctionInfo(
                "socket.send",
                "Send data through socket",
                new String[]{"socketId: int", "data: string"},
                "String[]",
                "Socket"
            ),
            new FunctionInfo(
                "socket.receive",
                "Receive data from socket and save to file",
                new String[]{"socketId: int", "saveDir: string (optional)"},
                "String[]",
                "Socket"
            ),
            new FunctionInfo(
                "socket.close",
                "Close socket",
                new String[]{"socketId: int"},
                "String[]",
                "Socket"
            ),
            new FunctionInfo(
                "socket.getInfo",
                "Get socket information",
                new String[]{"socketId: int"},
                "Map",
                "Socket"
            ),
            new FunctionInfo(
                "socket.list",
                "List all sockets owned by current process",
                new String[]{},
                "Map",
                "Socket"
            ),
            new FunctionInfo(
                "socket.createUdp",
                "Create UDP socket (port 0 for auto-assign)",
                new String[]{"host: string", "port: int (optional)", "saveDir: string (optional)"},
                "String[]",
                "Socket"
            ),
            new FunctionInfo(
                "socket.sendTo",
                "Send UDP packet to specific address",
                new String[]{"socketId: int", "host: string", "port: int", "data: string"},
                "String[]",
                "Socket"
            )
        };
    }
    
    @Override
    public String getProviderName() {
        return "SocketFunctionProvider";
    }
}
