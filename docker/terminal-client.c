#define _DEFAULT_SOURCE

#include <arpa/inet.h>
#include <errno.h>
#include <poll.h>
#include <signal.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/socket.h>
#include <termios.h>
#include <unistd.h>

static struct termios saved_terminal;
static int terminal_saved;
static volatile sig_atomic_t resized = 1;
static volatile sig_atomic_t stopping;
static int output_ended_with_cr;

/* Residue from a crashed full-screen session (alternate screen, mouse/paste/focus
 * reporting) must never survive a client connect or disconnect: it hides the primary
 * screen, floods the REPL with raw mouse bytes, and disables native text selection. */
static const char TERMINAL_RESET[] =
        "\033[?1049l\033[?1002l\033[?1006l\033[?2004l\033[?1004l\033[?25h";

static int write_all(int descriptor, const void *buffer, size_t length);

static void restore_terminal(void) {
    if (terminal_saved) {
        (void) write_all(STDOUT_FILENO, TERMINAL_RESET, sizeof(TERMINAL_RESET) - 1);
        tcsetattr(STDIN_FILENO, TCSAFLUSH, &saved_terminal);
    }
}

static void on_resize(int signal_number) {
    (void) signal_number;
    resized = 1;
}

static void on_stop(int signal_number) {
    (void) signal_number;
    stopping = 1;
}

static int write_all(int descriptor, const void *buffer, size_t length) {
    const unsigned char *cursor = buffer;
    while (length > 0) {
        ssize_t written = write(descriptor, cursor, length);
        if (written < 0) {
            if (errno == EINTR) continue;
            return -1;
        }
        cursor += written;
        length -= (size_t) written;
    }
    return 0;
}

/* cfmakeraw disables the terminal driver's ONLCR conversion.  Translate only lone LFs
 * received from the Runtime so PrintWriter.println() starts the following line at column 0. */
static int write_terminal_output(const unsigned char *buffer, size_t length) {
    size_t start = 0;
    for (size_t index = 0; index < length; index++) {
        if (buffer[index] != '\n') continue;
        if (index > start && write_all(STDOUT_FILENO, buffer + start, index - start) != 0) {
            return -1;
        }
        int preceded_by_cr = index > 0 ? buffer[index - 1] == '\r' : output_ended_with_cr;
        if (!preceded_by_cr) {
            if (write_all(STDOUT_FILENO, "\r", 1) != 0) return -1;
        }
        if (write_all(STDOUT_FILENO, "\n", 1) != 0) return -1;
        start = index + 1;
    }
    if (start != length
            && write_all(STDOUT_FILENO, buffer + start, length - start) != 0) return -1;
    if (length > 0) output_ended_with_cr = buffer[length - 1] == '\r';
    return 0;
}

/* Ctrl-C is one out-of-band control request. The Runtime decides whether it
 * interrupts active FCL or cancels the editable prompt. */
static int forward_terminal_input(int socket_descriptor, const unsigned char *buffer,
                                  size_t length) {
    size_t start = 0;
    for (size_t index = 0; index < length; index++) {
        if (buffer[index] != 3) continue;
        if (index > start
                && write_all(socket_descriptor, buffer + start, index - start) != 0) return -1;
        if (write_all(socket_descriptor, "\0I\n", 3) != 0) return -1;
        start = index + 1;
    }
    return start == length
            || write_all(socket_descriptor, buffer + start, length - start) == 0 ? 0 : -1;
}

static int send_size(int socket_descriptor) {
    struct winsize dimensions;
    if (ioctl(STDIN_FILENO, TIOCGWINSZ, &dimensions) != 0
            || dimensions.ws_row == 0 || dimensions.ws_col == 0) return 0;
    char frame[64];
    frame[0] = '\0';
    int payload_length = snprintf(frame + 1, sizeof(frame) - 1, "S %u %u\n",
            dimensions.ws_row, dimensions.ws_col);
    int length = payload_length < 0 ? -1 : payload_length + 1;
    return length > 1 && (size_t) length < sizeof(frame)
            ? write_all(socket_descriptor, frame, (size_t) length) : -1;
}

static int connect_runtime(int port) {
    int descriptor = socket(AF_INET, SOCK_STREAM, 0);
    if (descriptor < 0) return -1;
    struct sockaddr_in address;
    memset(&address, 0, sizeof(address));
    address.sin_family = AF_INET;
    address.sin_port = htons((unsigned short) port);
    address.sin_addr.s_addr = htonl(INADDR_LOOPBACK);
    if (connect(descriptor, (struct sockaddr *) &address, sizeof(address)) != 0) {
        close(descriptor);
        return -1;
    }
    return descriptor;
}

static int send_field(int descriptor, const unsigned char *value, size_t length) {
    char header[32];
    int header_length = snprintf(header, sizeof(header), "%zu\n", length);
    return header_length > 0 && (size_t) header_length < sizeof(header)
            && write_all(descriptor, header, (size_t) header_length) == 0
            && write_all(descriptor, value, length) == 0 ? 0 : -1;
}

static int receive_headless_response(int runtime) {
    unsigned char tail[64];
    size_t tail_length = 0;
    unsigned char received[8192];
    while (1) {
        ssize_t count = read(runtime, received, sizeof(received));
        if (count < 0) {
            if (errno == EINTR) continue;
            return 74;
        }
        if (count == 0) break;
        size_t incoming = (size_t) count;
        if (tail_length + incoming <= sizeof(tail)) {
            memcpy(tail + tail_length, received, incoming);
            tail_length += incoming;
            continue;
        }
        size_t flush = tail_length + incoming - sizeof(tail);
        if (flush <= tail_length) {
            if (write_all(STDOUT_FILENO, tail, flush) != 0) return 74;
            memmove(tail, tail + flush, tail_length - flush);
            tail_length -= flush;
            memcpy(tail + tail_length, received, incoming);
            tail_length += incoming;
        } else {
            if (tail_length > 0 && write_all(STDOUT_FILENO, tail, tail_length) != 0) return 74;
            size_t received_flush = flush - tail_length;
            if (received_flush > 0
                    && write_all(STDOUT_FILENO, received, received_flush) != 0) return 74;
            memcpy(tail, received + received_flush, incoming - received_flush);
            tail_length = incoming - received_flush;
        }
    }

    for (size_t index = 0; index + 5 <= tail_length; index++) {
        if (tail[index] != 0 || tail[index + 1] != 'R' || tail[index + 2] != ' ') continue;
        if (tail[tail_length - 1] != '\n') continue;
        char status_text[16];
        size_t status_length = tail_length - index - 4;
        if (status_length == 0 || status_length >= sizeof(status_text)) continue;
        memcpy(status_text, tail + index + 3, status_length);
        status_text[status_length] = '\0';
        char *end = NULL;
        long status = strtol(status_text, &end, 10);
        if (end == NULL || *end != '\0' || status < 0 || status > 255) continue;
        if (index > 0 && write_all(STDOUT_FILENO, tail, index) != 0) return 74;
        return (int) status;
    }
    if (tail_length > 0) write_all(STDOUT_FILENO, tail, tail_length);
    fprintf(stderr, "headless response ended without a status frame\n");
    return 74;
}

static int check_http_health(int runtime, const char *kind) {
    char request[160];
    int length = snprintf(request, sizeof(request),
            "GET /health/%s HTTP/1.1\r\nHost: 127.0.0.1\r\nConnection: close\r\n\r\n", kind);
    if (length < 1 || (size_t) length >= sizeof(request)
            || write_all(runtime, request, (size_t) length) != 0) return 74;
    shutdown(runtime, SHUT_WR);
    char response[32];
    ssize_t count;
    do {
        count = read(runtime, response, sizeof(response) - 1);
    } while (count < 0 && errno == EINTR);
    if (count < 12) return 69;
    response[count] = '\0';
    return (strncmp(response, "HTTP/1.1 200", 12) == 0
            || strncmp(response, "HTTP/1.0 200", 12) == 0) ? 0 : 69;
}

/* Headless stdin is: one password line followed by the exact FCL source. Neither value is
 * placed in argv or the environment, so host process listings cannot disclose them. */
static int run_headless(int runtime, const char *context, const char *username) {
    const size_t maximum = 4U * 1024U * 1024U + 4097U;
    /* Keep one sentinel byte so an input exactly at the documented limit is accepted while
     * an oversized stream is detected without waiting for an additional allocation. */
    const size_t capacity = maximum + 1U;
    unsigned char *input = malloc(capacity);
    if (input == NULL) return 70;
    size_t used = 0;
    while (used < capacity) {
        ssize_t count = read(STDIN_FILENO, input + used, capacity - used);
        if (count < 0) {
            if (errno == EINTR) continue;
            memset(input, 0, used);
            free(input);
            return 74;
        }
        if (count == 0) break;
        used += (size_t) count;
    }
    if (used > maximum) {
        fprintf(stderr, "headless input exceeds 4 MiB\n");
        memset(input, 0, used);
        free(input);
        return 64;
    }
    unsigned char *separator = memchr(input, '\n', used);
    if (separator == NULL) {
        fprintf(stderr, "headless password line is missing\n");
        memset(input, 0, used);
        free(input);
        return 64;
    }
    size_t password_length = (size_t) (separator - input);
    if (password_length > 0 && input[password_length - 1] == '\r') password_length--;
    size_t source_offset = (size_t) (separator - input) + 1;
    size_t source_length = used - source_offset;
    if (password_length > 4096) {
        fprintf(stderr, "headless password exceeds 4096 bytes\n");
        memset(input, 0, used);
        free(input);
        return 64;
    }

    int failed = write_all(runtime, "\0M HEADLESS\n", 12) != 0
            || send_field(runtime, (const unsigned char *) context, strlen(context)) != 0
            || send_field(runtime, (const unsigned char *) username, strlen(username)) != 0
            || send_field(runtime, input, password_length) != 0
            || send_field(runtime, input + source_offset, source_length) != 0;
    memset(input, 0, used);
    free(input);
    if (failed) return 74;
    shutdown(runtime, SHUT_WR);

    return receive_headless_response(runtime);
}

int main(int argument_count, char **arguments) {
    int probe = argument_count > 1 && strcmp(arguments[1], "--probe") == 0;
    int headless = argument_count > 1 && strcmp(arguments[1], "--headless") == 0;
    int health = argument_count > 1 && strcmp(arguments[1], "--health") == 0;
    if (headless && argument_count != 5) {
        fprintf(stderr, "usage: cilexec-terminal-client --headless <port> <context> <username>\n");
        return 64;
    }
    if (health && (argument_count != 4
            || (strcmp(arguments[2], "live") != 0 && strcmp(arguments[2], "ready") != 0))) {
        fprintf(stderr, "usage: cilexec-terminal-client --health <live|ready> <port>\n");
        return 64;
    }
    const char *port_argument = headless ? arguments[2] : health ? arguments[3] : probe
            ? (argument_count > 2 ? arguments[2] : "8022")
            : (argument_count > 1 ? arguments[1] : "8022");
    char *end = NULL;
    long parsed = strtol(port_argument, &end, 10);
    if (parsed < 1 || parsed > 65535 || (end != NULL && *end != '\0')) {
        fprintf(stderr, "invalid terminal port\n");
        return 64;
    }
    int runtime = connect_runtime((int) parsed);
    if (runtime < 0) {
        perror("cannot connect to CilExec Runtime");
        return 69;
    }
    if (probe) {
        close(runtime);
        return 0;
    }
    if (health) {
        int status = check_http_health(runtime, arguments[2]);
        close(runtime);
        return status;
    }
    if (headless) {
        signal(SIGPIPE, SIG_IGN);
        int status = run_headless(runtime, arguments[3], arguments[4]);
        close(runtime);
        return status;
    }

    if (tcgetattr(STDIN_FILENO, &saved_terminal) != 0) {
        perror("terminal input is not a TTY");
        close(runtime);
        return 64;
    }
    terminal_saved = 1;
    atexit(restore_terminal);
    struct termios raw = saved_terminal;
    cfmakeraw(&raw);
    if (tcsetattr(STDIN_FILENO, TCSAFLUSH, &raw) != 0) {
        perror("cannot enter raw terminal mode");
        close(runtime);
        return 70;
    }

    signal(SIGPIPE, SIG_IGN);
    signal(SIGWINCH, on_resize);
    signal(SIGTERM, on_stop);
    signal(SIGHUP, on_stop);
    if (write_all(STDOUT_FILENO, TERMINAL_RESET, sizeof(TERMINAL_RESET) - 1) != 0) {
        close(runtime);
        return 70;
    }
    if (write_all(runtime, "\0M INTERACTIVE\n", 15) != 0) {
        close(runtime);
        return 74;
    }
    unsigned char buffer[8192];
    int input_open = 1;
    while (!stopping) {
        if (resized) {
            resized = 0;
            if (send_size(runtime) != 0) break;
        }
        struct pollfd descriptors[2] = {
                { .fd = STDIN_FILENO, .events = input_open ? POLLIN : 0 },
                { .fd = runtime, .events = POLLIN }
        };
        int ready = poll(descriptors, 2, -1);
        if (ready < 0) {
            if (errno == EINTR) continue;
            break;
        }
        if (input_open && (descriptors[0].revents & (POLLIN | POLLHUP))) {
            ssize_t count = read(STDIN_FILENO, buffer, sizeof(buffer));
            if (count <= 0) {
                input_open = 0;
                shutdown(runtime, SHUT_WR);
            } else if (forward_terminal_input(runtime, buffer, (size_t) count) != 0) {
                break;
            }
        }
        if (descriptors[1].revents & (POLLIN | POLLHUP)) {
            ssize_t count = read(runtime, buffer, sizeof(buffer));
            if (count <= 0) break;
            if (write_terminal_output(buffer, (size_t) count) != 0) break;
        }
        if (descriptors[1].revents & (POLLERR | POLLNVAL)) break;
    }
    close(runtime);
    return 0;
}
