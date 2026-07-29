package com.follarce.application;

import com.follarce.auth.Authorization;
import com.follarce.domain.auth.Capability;
import com.follarce.domain.port.TransactionContext;
import com.follarce.domain.vfs.StoredObject;
import com.follarce.domain.vfs.VfsNode;
import com.follarce.fcl.FclPath;
import com.follarce.fcl.FclRuntimeException;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Expands VFS source files at each include directive before compilation. */
final class FclSourceIncludes {
    private static final int MAX_DEPTH = 32;
    private static final long MAX_EXPANDED_BYTES = 16L * 1024 * 1024;
    private static final Pattern DIRECTIVE = Pattern.compile(
            "(?m)(^|[;{}\\n])([\\t ]*)include[\\t ]+\"((?:\\\\.|[^\"\\\\\\r\\n])*)\""
                    + "[\\t ]*(?=;|\\r?$|})");

    String expand(TransactionContext transaction, UUID ownerId, String source,
                  String workingDirectory) {
        Expansion expansion = new Expansion(transaction, ownerId);
        expansion.consume(source);
        return expansion.expand(source, FclPath.normalizeAbsolute(workingDirectory),
                new ArrayDeque<>());
    }

    private static final class Expansion {
        private final TransactionContext transaction;
        private final UUID ownerId;
        private long expandedBytes;

        private Expansion(TransactionContext transaction, UUID ownerId) {
            this.transaction = transaction;
            this.ownerId = ownerId;
        }

        private String expand(String source, String baseDirectory, Deque<String> stack) {
            Matcher matcher = DIRECTIVE.matcher(source);
            StringBuffer result = new StringBuffer(source.length());
            while (matcher.find()) {
                String requested = decode(matcher.group(3));
                String absolute = FclPath.resolve(baseDirectory, requested);
                if (stack.contains(absolute)) {
                    throw new FclRuntimeException("Circular include: "
                            + String.join(" -> ", stack) + " -> " + absolute);
                }
                if (stack.size() >= MAX_DEPTH) {
                    throw new FclRuntimeException("Include nesting exceeds " + MAX_DEPTH
                            + " files at " + absolute);
                }
                String included = readUtf8File(absolute);
                consume(included);
                stack.addLast(absolute);
                String expanded = expand(included, parent(absolute), stack);
                stack.removeLast();
                String separator = matcher.group(1);
                if (!separator.endsWith("\n") && !expanded.startsWith("\n")) {
                    separator += "\n";
                }
                if (!expanded.endsWith("\n")) expanded += "\n";
                matcher.appendReplacement(result, Matcher.quoteReplacement(separator + expanded));
            }
            matcher.appendTail(result);
            return result.toString();
        }

        private String readUtf8File(String path) {
            Authorization.require(transaction, ownerId, Capability.VFS_READ);
            VfsNode node = resolve(path).orElseThrow(() ->
                    new FclRuntimeException("Unknown include file: " + path));
            if (node.type() != VfsNode.Type.FILE) {
                throw new FclRuntimeException("include accepts a VFS file, not "
                        + node.type().name().toLowerCase() + ": " + path);
            }
            StoredObject object = transaction.vfs().findObject(
                    node.currentObjectHash().orElseThrow()).orElseThrow(() ->
                    new FclRuntimeException("Include file content is missing: " + path));
            if (object.mediaType().equals("application/vnd.sqlite3")
                    || path.toLowerCase(java.util.Locale.ROOT).endsWith(".db")) {
                throw new FclRuntimeException(
                        "include cannot load a package database; use import with its SHA-256");
            }
            if (object.byteSize() > MAX_EXPANDED_BYTES) {
                throw new FclRuntimeException("Include file exceeds 16 MiB: " + path);
            }
            ByteArrayOutputStream bytes = new ByteArrayOutputStream((int) object.byteSize());
            long offset = 0;
            while (offset < object.byteSize()) {
                int maximum = (int) Math.min(4L * 1024 * 1024, object.byteSize() - offset);
                byte[] chunk = transaction.vfs().readObjectRange(object.objectHash(), offset,
                        maximum);
                if (chunk.length == 0) {
                    throw new FclRuntimeException("Include file ended early: " + path);
                }
                bytes.writeBytes(chunk);
                offset += chunk.length;
            }
            try {
                return StandardCharsets.UTF_8.newDecoder()
                        .onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT)
                        .decode(ByteBuffer.wrap(bytes.toByteArray())).toString();
            } catch (CharacterCodingException failure) {
                throw new FclRuntimeException("Include file is not valid UTF-8 FCL: " + path);
            }
        }

        private Optional<VfsNode> resolve(String path) {
            Optional<VfsNode> current = transaction.vfs().findChild(ownerId,
                    Optional.empty(), "/");
            if (path.equals("/")) return current;
            for (String part : path.substring(1).split("/")) {
                if (current.isEmpty() || current.orElseThrow().type()
                        != VfsNode.Type.DIRECTORY) return Optional.empty();
                current = transaction.vfs().findChild(ownerId,
                        Optional.of(current.orElseThrow().nodeId()), part);
            }
            return current;
        }

        private void consume(String source) {
            expandedBytes = Math.addExact(expandedBytes,
                    source.getBytes(StandardCharsets.UTF_8).length);
            if (expandedBytes > MAX_EXPANDED_BYTES) {
                throw new FclRuntimeException("Expanded FCL source exceeds 16 MiB");
            }
        }
    }

    private static String parent(String path) {
        int separator = path.lastIndexOf('/');
        return separator <= 0 ? "/" : path.substring(0, separator);
    }

    private static String decode(String literal) {
        StringBuilder decoded = new StringBuilder(literal.length());
        for (int index = 0; index < literal.length(); index++) {
            char character = literal.charAt(index);
            if (character != '\\' || index + 1 >= literal.length()) {
                decoded.append(character);
                continue;
            }
            char escaped = literal.charAt(++index);
            decoded.append(switch (escaped) {
                case 'n' -> '\n';
                case 'r' -> '\r';
                case 't' -> '\t';
                case '"' -> '"';
                case '\\' -> '\\';
                default -> escaped;
            });
        }
        return decoded.toString();
    }
}
