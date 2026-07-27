/*
 * haven-usb-probe — Slice-2 reachability gate.
 *
 * A tiny guest-side client that connects to Haven's USB proxy on the abstract
 * unix socket "\0haven-usb", issues one GET_DESCRIPTORS request, and prints the
 * device descriptors it gets back. Its only job is to prove that a process
 * running inside the proot can reach the abstract socket the Android app binds
 * (proot shares the network namespace, so it should — this confirms it before
 * we invest in the full LD_PRELOAD/DllMap shim).
 *
 * Built per-ABI with the glibc/musl cross toolchain, mirroring the wayvnc shim
 * (see core/local/build-haven-usb.sh). Static where possible so it runs in any
 * distro's rootfs.
 *
 * Wire protocol (see UsbProxyProtocol.kt): frame = u32 len (BE) · u8 opcode ·
 * payload. GET_DESCRIPTORS opcode = 0x02, empty payload. Response payload =
 * i32 status (BE) followed by `status` descriptor bytes.
 */
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <stdint.h>
#include <sys/socket.h>
#include <sys/un.h>

#define OP_AUTH 0x01
#define OP_GET_DESCRIPTORS 0x02
#define AUTH_TOKEN_BYTES 32

static int read_full(int fd, void *buf, size_t n) {
    uint8_t *p = (uint8_t *)buf;
    size_t got = 0;
    while (got < n) {
        ssize_t r = read(fd, p + got, n - got);
        if (r <= 0) return -1;
        got += (size_t)r;
    }
    return 0;
}

static int write_full(int fd, const void *buf, size_t n) {
    const uint8_t *p = (const uint8_t *)buf;
    size_t put = 0;
    while (put < n) {
        ssize_t w = write(fd, p + put, n - put);
        if (w <= 0) return -1;
        put += (size_t)w;
    }
    return 0;
}

static uint32_t be32(uint32_t v) {
    return ((v & 0xFF) << 24) | ((v & 0xFF00) << 8) |
           ((v >> 8) & 0xFF00) | ((v >> 24) & 0xFF);
}

int main(void) {
    const char *sock_name = getenv("YYSLIDE_USB_SOCKET");
    const char *token_hex = getenv("YYSLIDE_USB_TOKEN");
    if (!sock_name || !token_hex || !*sock_name || strlen(token_hex) != AUTH_TOKEN_BYTES * 2) {
        fprintf(stderr, "missing YYSLIDE_USB_SOCKET/YYSLIDE_USB_TOKEN\n");
        return 2;
    }
    size_t sock_name_len = strlen(sock_name);
    if (sock_name_len >= sizeof(((struct sockaddr_un *)0)->sun_path) - 1) return 2;
    uint8_t token[AUTH_TOKEN_BYTES];
    for (size_t i = 0; i < AUTH_TOKEN_BYTES; i++) {
        unsigned int b;
        if (sscanf(token_hex + i * 2, "%2x", &b) != 1) return 2;
        token[i] = (uint8_t)b;
    }
    int fd = socket(AF_UNIX, SOCK_STREAM, 0);
    if (fd < 0) { perror("socket"); return 2; }

    struct sockaddr_un addr;
    memset(&addr, 0, sizeof(addr));
    addr.sun_family = AF_UNIX;
    /* Abstract namespace: leading NUL, then the name. */
    addr.sun_path[0] = '\0';
    memcpy(addr.sun_path + 1, sock_name, sock_name_len);
    socklen_t len = (socklen_t)(offsetof(struct sockaddr_un, sun_path) + 1 + sock_name_len);

    if (connect(fd, (struct sockaddr *)&addr, len) < 0) {
        perror("connect to USB broker");
        fprintf(stderr, "GATE FAIL: abstract socket not reachable from this process\n");
        close(fd);
        return 3;
    }
    fprintf(stderr, "connected to authenticated USB broker\n");

    uint8_t auth[4 + 1 + AUTH_TOKEN_BYTES];
    uint32_t auth_len = be32(1 + AUTH_TOKEN_BYTES);
    memcpy(auth, &auth_len, 4);
    auth[4] = OP_AUTH;
    memcpy(auth + 5, token, AUTH_TOKEN_BYTES);
    memset(token, 0, sizeof(token));
    if (write_full(fd, auth, sizeof(auth)) < 0) { perror("auth"); close(fd); return 4; }
    memset(auth, 0, sizeof(auth));

    /* GET_DESCRIPTORS frame: len=1, opcode. */
    uint8_t req[5];
    uint32_t blen = be32(1);
    memcpy(req, &blen, 4);
    req[4] = OP_GET_DESCRIPTORS;
    if (write_full(fd, req, sizeof(req)) < 0) { perror("write"); close(fd); return 4; }

    uint32_t rlen_be;
    if (read_full(fd, &rlen_be, 4) < 0) { fprintf(stderr, "no response frame\n"); close(fd); return 5; }
    uint32_t rlen = be32(rlen_be);
    if (rlen < 4 || rlen > (1u << 20)) { fprintf(stderr, "bad frame len %u\n", rlen); close(fd); return 6; }

    uint8_t *body = (uint8_t *)malloc(rlen);
    if (read_full(fd, body, rlen) < 0) { fprintf(stderr, "short body\n"); free(body); close(fd); return 7; }

    uint32_t status_be;
    memcpy(&status_be, body, 4);
    int32_t status = (int32_t)be32(status_be);
    printf("status=%d descriptor_bytes=%u\n", status, (status > 0) ? (uint32_t)(rlen - 4) : 0);
    if (status > 0) {
        for (uint32_t i = 0; i < rlen - 4 && i < 64; i++) printf("%02x ", body[4 + i]);
        printf("\n");
    }
    fprintf(stderr, "GATE PASS: reached the broker and read descriptors\n");
    free(body);
    close(fd);
    return (status > 0) ? 0 : 1;
}
