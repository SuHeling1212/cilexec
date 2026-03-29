package com.follarce.plugin;

import com.follarce.basicUtil.FileUtil;
import com.follarce.basicUtil.Logger;
import com.follarce.basicUtil.UserUtil;
import com.follarce.plugin.FunctionContext;
import com.follarce.plugin.FunctionInfo;
import com.follarce.plugin.FunctionProvider;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;

/**
 * Network function provider
 * Provides HTTP download functionality
 */
public class NetworkFunctionProvider implements FunctionProvider {

    private static final int DEFAULT_TIMEOUT = 10000; // 10 seconds
    private static final int MAX_REDIRECTS = 5;
    private static final int BUFFER_SIZE = 8192; // 8KB buffer

    @Override
    public Object call(String name, Object[] args, FunctionContext context) {
        switch (name) {
            case "webget":
                return handleWebGet(args);
            default:
                return null;
        }
    }

    /**
     * Handle webget function
     * Downloads file from URL to specified directory
     * Filename is automatically extracted from URL
     *
     * Usage: webget(url, saveDir) or webget(url, saveDir, timeout)
     *
     * @param args [url, saveDir] or [url, saveDir, timeout]
     * @return String[] ["SUCCESS", filename] or ["ERROR", errorCode]
     */
    private Object handleWebGet(Object[] args) {
        // Check parameter count
        if (args.length < 2) {
            return new String[]{"ERROR", "INVALID_ARGUMENTS"};
        }

        // Check URL parameter type
        if (!(args[0] instanceof String)) {
            return new String[]{"ERROR", "URL_MUST_BE_STRING"};
        }

        // Check saveDir parameter type
        if (!(args[1] instanceof String)) {
            return new String[]{"ERROR", "SAVE_DIR_MUST_BE_STRING"};
        }

        String urlString = (String) args[0];
        String saveDir = (String) args[1];

        // Validate URL is not empty
        if (urlString.trim().isEmpty()) {
            return new String[]{"ERROR", "INVALID_URL"};
        }

        // Validate saveDir is not empty
        if (saveDir.trim().isEmpty()) {
            return new String[]{"ERROR", "INVALID_SAVE_DIR"};
        }

        // Parse timeout if provided
        int timeout = DEFAULT_TIMEOUT;
        if (args.length >= 3) {
            if (!(args[2] instanceof Number)) {
                return new String[]{"ERROR", "TIMEOUT_MUST_BE_NUMBER"};
            }
            timeout = ((Number) args[2]).intValue();
            if (timeout <= 0) {
                return new String[]{"ERROR", "INVALID_TIMEOUT"};
            }
        }

        try {
            return downloadToFile(urlString, saveDir, timeout, 0);
        } catch (Exception e) {
            Logger.error("Webget failed: " + e.getMessage());
            return new String[]{"ERROR", "DOWNLOAD_FAILED"};
        }
    }

    /**
     * Extract filename from URL
     *
     * @param urlString URL string
     * @return filename or null if cannot extract
     */
    private String extractFilenameFromUrl(String urlString) {
        try {
            URL url = new URI(urlString).toURL();
            String path = url.getPath();

            if (path == null || path.isEmpty() || path.equals("/")) {
                return "index.html";
            }

            // Remove trailing slash
            if (path.endsWith("/")) {
                path = path.substring(0, path.length() - 1);
            }

            // Get last path component
            int lastSlash = path.lastIndexOf('/');
            String filename;
            if (lastSlash >= 0) {
                filename = path.substring(lastSlash + 1);
            } else {
                filename = path;
            }

            // If filename is empty, use default
            if (filename.isEmpty()) {
                return "index.html";
            }

            // Remove query parameters if any
            int queryIndex = filename.indexOf('?');
            if (queryIndex > 0) {
                filename = filename.substring(0, queryIndex);
            }

            // Remove fragment if any
            int fragmentIndex = filename.indexOf('#');
            if (fragmentIndex > 0) {
                filename = filename.substring(0, fragmentIndex);
            }

            // If still empty after cleanup, use default
            if (filename.isEmpty()) {
                return "index.html";
            }

            return filename;
        } catch (MalformedURLException | URISyntaxException e) {
            return null;
        }
    }

    /**
     * Download file from URL and save to specified directory
     *
     * @param urlString URL to download
     * @param saveDir Directory to save the file
     * @param timeout Connection timeout in milliseconds
     * @param redirectCount Current redirect count
     * @return String[] ["SUCCESS", filename] or ["ERROR", errorCode]
     */
    private String[] downloadToFile(String urlString, String saveDir, int timeout, int redirectCount) {
        // Extract filename from URL
        String fileName = extractFilenameFromUrl(urlString);
        if (fileName == null || fileName.isEmpty()) {
            return new String[]{"ERROR", "CANNOT_EXTRACT_FILENAME"};
        }

        // Check redirect limit
        if (redirectCount > MAX_REDIRECTS) {
            return new String[]{"ERROR", "TOO_MANY_REDIRECTS"};
        }

        // Normalize saveDir
        String dirPath = saveDir;
        if (!dirPath.endsWith("/")) {
            dirPath = dirPath + "/";
        }

        // Check if directory exists
        String[] dirResult = FileUtil.getListOfFileAndDirectory(dirPath);
        if (!dirResult[0].equals("SUCCESS")) {
            // Directory doesn't exist, try to create it
            String[] createDirResult = createDirectoryRecursive(dirPath);
            if (!createDirResult[0].equals("SUCCESS")) {
                return createDirResult;
            }
        }

        // Check directory permission (write permission required)
        if (!UserUtil.checkFilePermission(dirPath, "write")) {
            return new String[]{"ERROR", "INSUFFICIENT_PERMISSION"};
        }

        // Check if directory is locked
        String[] lockCheck = checkDirectoryLock(dirPath);
        if (lockCheck != null) {
            return lockCheck;
        }

        HttpURLConnection connection = null;
        InputStream inputStream = null;
        FileOutputStream outputStream = null;

        try {
            // Validate and create URL
            URL url;
            try {
                url = new URI(urlString).toURL();
            } catch (MalformedURLException | URISyntaxException e) {
                return new String[]{"ERROR", "INVALID_URL"};
            }

            // Open connection
            if (url.getProtocol().equals("https")) {
                // Create SSL context that trusts all certificates
                try {
                    javax.net.ssl.SSLContext sslContext = javax.net.ssl.SSLContext.getInstance("TLS");
                    sslContext.init(null, new javax.net.ssl.TrustManager[]{new javax.net.ssl.X509TrustManager() {
                        public java.security.cert.X509Certificate[] getAcceptedIssuers() { return null; }
                        public void checkClientTrusted(java.security.cert.X509Certificate[] certs, String authType) { }
                        public void checkServerTrusted(java.security.cert.X509Certificate[] certs, String authType) { }
                    }}, new java.security.SecureRandom());
                    
                    javax.net.ssl.HttpsURLConnection.setDefaultSSLSocketFactory(sslContext.getSocketFactory());
                    javax.net.ssl.HttpsURLConnection.setDefaultHostnameVerifier((hostname, session) -> true);
                } catch (Exception e) {
                    Logger.error("SSL initialization failed: " + e.getMessage());
                    // Continue without SSL trust management
                }
            }
            
            connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(timeout);
            connection.setReadTimeout(timeout);
            connection.setInstanceFollowRedirects(false);
            connection.setRequestProperty("User-Agent", "CilExec/1.0");

            // Connect and get response
            connection.connect();
            int responseCode = connection.getResponseCode();

            // Handle redirects (3xx status codes)
            if (responseCode >= 300 && responseCode < 400) {
                String location = connection.getHeaderField("Location");
                if (location != null && !location.isEmpty()) {
                    // Resolve relative URLs
                    if (!location.startsWith("http://") && !location.startsWith("https://")) {
                        try {
                            URL baseUrl = connection.getURL();
                            location = baseUrl.toURI().resolve(location).toString();
                        } catch (URISyntaxException e) {
                            Logger.error("Invalid redirect URL: " + e.getMessage());
                            return new String[]{"ERROR", "INVALID_REDIRECT"};
                        }
                    }
                    Logger.info("Following redirect to: " + location);
                    return downloadToFile(location, saveDir, timeout, redirectCount + 1);
                }
            }

            // Check for HTTP errors
            if (responseCode >= 400) {
                switch (responseCode) {
                    case 404:
                        return new String[]{"ERROR", "RESOURCE_NOT_FOUND"};
                    case 403:
                        return new String[]{"ERROR", "ACCESS_FORBIDDEN"};
                    case 401:
                        return new String[]{"ERROR", "UNAUTHORIZED"};
                    case 500:
                    case 502:
                    case 503:
                    case 504:
                        return new String[]{"ERROR", "SERVER_ERROR"};
                    default:
                        return new String[]{"ERROR", "HTTP_ERROR_" + responseCode};
                }
            }

            // Check for successful response
            if (responseCode != HttpURLConnection.HTTP_OK) {
                return new String[]{"ERROR", "HTTP_ERROR_" + responseCode};
            }

            // Create file
            String[] createResult = FileUtil.createFile(dirPath, fileName);
            if (!createResult[0].equals("SUCCESS") && !createResult[1].equals("FILE_EXIST")) {
                return createResult;
            }

            // Check if file exists and get its size for resume
            String realPath = FileUtil.getVfsRoot() + dirPath + fileName;
            java.io.File targetFile = new java.io.File(realPath);
            long downloadedSize = 0;
            boolean resume = false;

            if (targetFile.exists()) {
                downloadedSize = targetFile.length();
                if (downloadedSize > 0) {
                    resume = true;
                    Logger.info("Resuming download from " + downloadedSize + " bytes for " + fileName);
                }
            }

            // Open connection with range request if resuming
            if (resume) {
                connection = (HttpURLConnection) url.openConnection();
                connection.setRequestMethod("GET");
                connection.setConnectTimeout(timeout);
                connection.setReadTimeout(timeout);
                connection.setInstanceFollowRedirects(false);
                connection.setRequestProperty("User-Agent", "CilExec/1.0");
                connection.setRequestProperty("Range", "bytes=" + downloadedSize + "-");
                connection.connect();
                responseCode = connection.getResponseCode();

                // Check if server supports range requests
                if (responseCode != HttpURLConnection.HTTP_PARTIAL) {
                    Logger.warn("Server does not support range requests, starting download from beginning");
                    resume = false;
                    // Reopen connection without range header
                    connection.disconnect();
                    connection = (HttpURLConnection) url.openConnection();
                    connection.setRequestMethod("GET");
                    connection.setConnectTimeout(timeout);
                    connection.setReadTimeout(timeout);
                    connection.setInstanceFollowRedirects(false);
                    connection.setRequestProperty("User-Agent", "CilExec/1.0");
                    connection.connect();
                    responseCode = connection.getResponseCode();
                }
            }

            // Check for HTTP errors after resume attempt
            if (responseCode >= 400) {
                switch (responseCode) {
                    case 404:
                        return new String[]{"ERROR", "RESOURCE_NOT_FOUND"};
                    case 403:
                        return new String[]{"ERROR", "ACCESS_FORBIDDEN"};
                    case 401:
                        return new String[]{"ERROR", "UNAUTHORIZED"};
                    case 500:
                    case 502:
                    case 503:
                    case 504:
                        return new String[]{"ERROR", "SERVER_ERROR"};
                    default:
                        return new String[]{"ERROR", "HTTP_ERROR_" + responseCode};
                }
            }

            // Download and save content using binary stream
            inputStream = connection.getInputStream();
            // Open file in append mode if resuming, otherwise overwrite
            outputStream = new FileOutputStream(realPath, resume);

            byte[] buffer = new byte[BUFFER_SIZE];
            int bytesRead;
            while ((bytesRead = inputStream.read(buffer)) != -1) {
                outputStream.write(buffer, 0, bytesRead);
            }
            outputStream.flush();

            if (resume) {
                Logger.info("Successfully resumed download of " + fileName + " to: " + dirPath);
            } else {
                Logger.info("Successfully downloaded " + fileName + " to: " + dirPath);
            }
            return new String[]{"SUCCESS", fileName};

        } catch (java.net.SocketTimeoutException e) {
            Logger.error("Connection timeout: " + urlString);
            return new String[]{"ERROR", "CONNECTION_TIMEOUT"};
        } catch (java.net.UnknownHostException e) {
            Logger.error("Unknown host: " + urlString);
            return new String[]{"ERROR", "UNKNOWN_HOST"};
        } catch (java.net.ConnectException e) {
            Logger.error("Connection refused: " + urlString);
            return new String[]{"ERROR", "CONNECTION_REFUSED"};
        } catch (IOException e) {
            Logger.error("IO error downloading from " + urlString + ": " + e.getMessage());
            return new String[]{"ERROR", "IO_ERROR"};
        } finally {
            // Close resources
            if (outputStream != null) {
                try {
                    outputStream.close();
                } catch (IOException e) {
                    // Ignore
                }
            }
            if (inputStream != null) {
                try {
                    inputStream.close();
                } catch (IOException e) {
                    // Ignore
                }
            }
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    /**
     * Check if directory is locked
     */
    private String[] checkDirectoryLock(String dirPath) {
        String root = FileUtil.getVfsRoot();
        String normalized = dirPath;
        if (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        String realPath = root + normalized.replace('/', java.io.File.separatorChar);
        java.io.File dir = new java.io.File(realPath);

        if (!dir.exists() || !dir.isDirectory()) {
            return null;
        }

        java.io.File metaFile = new java.io.File(dir, ".META");
        if (!metaFile.exists()) {
            return null;
        }

        try {
            String fullContent = new String(java.nio.file.Files.readAllBytes(metaFile.toPath()),
                                            java.nio.charset.StandardCharsets.UTF_8);
            String[] metaResult = FileUtil.extractMetaContent(fullContent);

            if (metaResult[0].equals("SUCCESS")) {
                Object metaObj = com.follarce.basicUtil.JsonUtil.readJson(metaResult[1]);
                if (metaObj instanceof java.util.Map) {
                    java.util.Map<String, Object> metaMap = (java.util.Map<String, Object>) metaObj;
                    java.util.Map<String, Object> locked = (java.util.Map<String, Object>) metaMap.get("locked");
                    if (locked != null) {
                        Boolean isLocked = (Boolean) locked.get("isLocked");
                        if (isLocked != null && isLocked) {
                            return new String[]{"ERROR", "DIRECTORY_IS_LOCKED"};
                        }
                    }
                }
            }
        } catch (IOException e) {
            Logger.error("Failed to check directory lock status: " + e.getMessage());
        }
        return null;
    }

    /**
     * Recursively create directory path
     */
    private String[] createDirectoryRecursive(String path) {
        if (path == null || path.isEmpty() || path.equals("/")) {
            return new String[]{"SUCCESS", null};
        }

        // Normalize path
        String normalized = path;
        if (!normalized.endsWith("/")) {
            normalized = normalized + "/";
        }

        // Build path components
        String[] parts = normalized.split("/");
        String currentPath = "/";

        for (String part : parts) {
            if (part.isEmpty()) continue;

            currentPath = currentPath + part + "/";

            // Check if directory exists
            String[] listResult = FileUtil.getListOfFileAndDirectory(currentPath);
            if (!listResult[0].equals("SUCCESS")) {
                // Check parent directory permission before creating
                String parentPath = currentPath.substring(0, currentPath.lastIndexOf(part));
                if (!parentPath.equals("/")) {
                    if (!UserUtil.checkFilePermission(parentPath, "write")) {
                        return new String[]{"ERROR", "INSUFFICIENT_PERMISSION"};
                    }
                }

                // Directory doesn't exist, create it
                String[] createResult = FileUtil.createDirectory(parentPath, part);
                if (!createResult[0].equals("SUCCESS")) {
                    return createResult;
                }
            }
        }

        return new String[]{"SUCCESS", null};
    }

    @Override
    public FunctionInfo[] getFunctions() {
        return new FunctionInfo[]{
            new FunctionInfo(
                "webget",
                "Download file from URL to specified directory (filename auto-extracted from URL)",
                new String[]{"url: string", "saveDir: string", "timeout: int (optional, default 10000ms)"},
                "String[]",
                "Network"
            )
        };
    }

    @Override
    public String getProviderName() {
        return "NetworkFunctionProvider";
    }
}
