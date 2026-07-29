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

static void restore_terminal(void) {
    if (terminal_saved) tcsetattr(STDIN_FILENO, TCSAFLUSH, &saved_terminal);
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
        if (index == 0 || buffer[index - 1] != '\r') {
            if (write_all(STDOUT_FILENO, "\r", 1) != 0) return -1;
        }
        if (write_all(STDOUT_FILENO, "\n", 1) != 0) return -1;
        start = index + 1;
    }
    return start == length || write_all(STDOUT_FILENO, buffer + start, length - start) == 0
            ? 0 : -1;
}

/* Ctrl-C is one out-of-band control request. The Runtime decides whether it
 * interrupts active FCL or cancels the editable prompt. */
static int forward_terminal_input(int socket_descriptor, const unsigned char *buffer,
                                  size_t length) {
    for (size_t index = 0; index < length; index++) {
        if (buffer[index] == 3) {
            if (write_all(socket_descriptor, "\0I\n", 3) != 0) return -1;
            continue;
        }
        if (write_all(socket_descriptor, buffer + index, 1) != 0) return -1;
    }
    return 0;
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

int main(int argument_count, char **arguments) {
    int probe = argument_count > 1 && strcmp(arguments[1], "--probe") == 0;
    const char *port_argument = probe
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
