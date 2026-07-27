/*
 * libhaven_usb.so — Haven's guest USB interposition shim (Slice 3, HID/hidraw).
 *
 * LD_PRELOADed into a native guest app (or, in Slice 4, DllMap'd for Mono),
 * this fakes a /dev/hidraw* device backed by Haven's USB proxy. The app opens
 * what looks like a normal hidraw node and does the usual ioctl/read/write; the
 * shim translates those into UsbProxyProtocol requests over the abstract socket
 * \0haven-usb, which the Android UsbBroker fulfils against the real device.
 * No real device node, no /dev/bus/usb, no root.
 *
 * Interposed: open/open64/openat/openat64 (match /dev/hidraw*), ioctl
 * (HIDIOCGRDESCSIZE/GRDESC/GRAWINFO/GRAWNAME), read, write, close, poll.
 * Everything else (and any fd we don't own) falls through to the real libc via
 * dlsym(RTLD_NEXT) — so DllMap'ing all of "libc" to this .so is safe.
 *
 * Built per-ABI with the glibc/musl cross toolchain (see build-haven-usb.sh),
 * _FORTIFY_SOURCE off so it loads under both glibc and musl.
 */
#define _GNU_SOURCE
#include <dlfcn.h>
#include <errno.h>
#include <fcntl.h>
#include <poll.h>
#include <stdarg.h>
#include <stdint.h>
#include <limits.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <pthread.h>
#include <sys/eventfd.h>
#include <sys/ioctl.h>
#include <sys/socket.h>
#include <sys/un.h>

/* ---- hidraw ioctl ABI (define locally to avoid header skew on musl) ------ */
#define HID_MAX_DESCRIPTOR_SIZE 4096
struct haven_hidraw_report_descriptor { uint32_t size; uint8_t value[HID_MAX_DESCRIPTOR_SIZE]; };
struct haven_hidraw_devinfo { uint32_t bustype; int16_t vendor; int16_t product; };
#define HIDIOCGRDESCSIZE  _IOR('H', 0x01, int)
#define HIDIOCGRDESC      _IOR('H', 0x02, struct haven_hidraw_report_descriptor)
#define HIDIOCGRAWINFO    _IOR('H', 0x03, struct haven_hidraw_devinfo)
#define HIDIOCGRAWNAME(len) _IOC(_IOC_READ, 'H', 0x04, len)
#define HIDIOCGRAWPHYS(len) _IOC(_IOC_READ, 'H', 0x05, len)
#define BUS_USB 0x03

/* ---- proxy protocol (mirror UsbProxyProtocol.kt) ------------------------- */
#define OP_AUTH 0x01
#define OP_GET_DESCRIPTORS 0x02
#define OP_CONTROL 0x03
#define OP_BULK 0x04
#define OP_CLOSE 0x05
#define USB_DT_REPORT 0x22
#define MAX_MANAGED 16
#define MAX_FRAME (1u << 20)
#define AUTH_TOKEN_BYTES 32

/* Per-open device state. The managed fd we hand back IS the socket fd. */
struct managed {
    int fd;                 /* socket fd, -1 = slot free */
    int hid_iface;          /* HID interface number */
    int ep_in, ep_out;      /* interrupt endpoint addresses (0 = none) */
    int16_t vendor, product;
    uint32_t report_desc_len;
    uint8_t report_desc[HID_MAX_DESCRIPTOR_SIZE];
};

static struct managed g_managed[MAX_MANAGED];
static pthread_mutex_t g_lock = PTHREAD_MUTEX_INITIALIZER;

/* ---- real libc symbols --------------------------------------------------- */
static int (*real_open)(const char *, int, ...);
static int (*real_open64)(const char *, int, ...);
static int (*real_openat)(int, const char *, int, ...);
static int (*real_openat64)(int, const char *, int, ...);
static ssize_t (*real_read)(int, void *, size_t);
static ssize_t (*real_write)(int, const void *, size_t);
static int (*real_close)(int);
static int (*real_ioctl)(int, unsigned long, ...);
static int (*real_poll)(struct pollfd *, nfds_t, int);

static int g_debug = 0;
#define DBG(...) do { if (g_debug) { fprintf(stderr, "haven-usb: " __VA_ARGS__); fflush(stderr); } } while (0)

/* Resolve a real libc symbol. RTLD_NEXT works when we're LD_PRELOADed (Slice 3,
 * loaded first). Under Mono DllMap (Slice 4) the shim is dlopen'd late, so
 * RTLD_NEXT can't see libc (it only searches libraries loaded AFTER us) — fall
 * back to an explicit libc handle. RTLD_DEFAULT is avoided: it would resolve
 * back to our own interposer and recurse. */
static void *g_libc = NULL;
static void *resolve_real(const char *name) {
    void *p = dlsym(RTLD_NEXT, name);
    if (p) return p;
    if (!g_libc) {
        g_libc = dlopen("libc.so.6", RTLD_NOW | RTLD_NOLOAD);   /* glibc, already loaded */
        if (!g_libc) g_libc = dlopen("libc.so.6", RTLD_NOW);
        if (!g_libc) g_libc = dlopen("libc.so", RTLD_NOW);      /* musl / fallback */
    }
    return g_libc ? dlsym(g_libc, name) : NULL;
}

static void init_reals(void) {
    static int done = 0;
    if (done) return;
    g_debug = getenv("HAVEN_USB_DEBUG") != NULL;
    /* Free-slot sentinel is -1, but the static table is zero-initialized
     * (fd 0 = a valid fd). Mark every slot free before first use. */
    for (int i = 0; i < MAX_MANAGED; i++) g_managed[i].fd = -1;
    real_open = resolve_real("open");
    real_open64 = resolve_real("open64");
    real_openat = resolve_real("openat");
    real_openat64 = resolve_real("openat64");
    real_read = resolve_real("read");
    real_write = resolve_real("write");
    real_close = resolve_real("close");
    real_ioctl = resolve_real("ioctl");
    real_poll = resolve_real("poll");
    done = 1;
}

/* ---- low-level socket framing ------------------------------------------- */
static int io_full(int fd, void *buf, size_t n, int writing) {
    uint8_t *p = buf; size_t done = 0;
    while (done < n) {
        ssize_t r = writing ? real_write(fd, p + done, n - done)
                            : real_read(fd, p + done, n - done);
        if (r <= 0) return -1;
        done += (size_t)r;
    }
    return 0;
}
static uint32_t be32(uint32_t v) {
    return ((v & 0xFF) << 24) | ((v & 0xFF00) << 8) | ((v >> 8) & 0xFF00) | ((v >> 24) & 0xFF);
}

static int proxy_connect(void) {
    const char *sock_name = getenv("YYSLIDE_USB_SOCKET");
    const char *token_hex = getenv("YYSLIDE_USB_TOKEN");
    if (!sock_name || !token_hex || !*sock_name || strlen(token_hex) != AUTH_TOKEN_BYTES * 2) {
        errno = EACCES;
        return -1;
    }
    size_t sock_name_len = strlen(sock_name);
    if (sock_name_len == 0 || sock_name_len >= sizeof(((struct sockaddr_un *)0)->sun_path) - 1) {
        errno = ENAMETOOLONG;
        return -1;
    }
    uint8_t token[AUTH_TOKEN_BYTES];
    for (size_t i = 0; i < AUTH_TOKEN_BYTES; i++) {
        unsigned int b;
        if (sscanf(token_hex + (i * 2), "%2x", &b) != 1) { errno = EACCES; return -1; }
        token[i] = (uint8_t)b;
    }
    int fd = socket(AF_UNIX, SOCK_STREAM, 0);
    if (fd < 0) return -1;
    struct sockaddr_un addr;
    memset(&addr, 0, sizeof(addr));
    addr.sun_family = AF_UNIX;
    addr.sun_path[0] = '\0';
    memcpy(addr.sun_path + 1, sock_name, sock_name_len);
    socklen_t len = offsetof(struct sockaddr_un, sun_path) + 1 + sock_name_len;
    if (connect(fd, (struct sockaddr *)&addr, len) < 0) { real_close(fd); return -1; }
    uint32_t auth_len = be32(1 + AUTH_TOKEN_BYTES);
    uint8_t auth_body[1 + AUTH_TOKEN_BYTES];
    auth_body[0] = OP_AUTH;
    memcpy(auth_body + 1, token, AUTH_TOKEN_BYTES);
    memset(token, 0, sizeof(token));
    if (io_full(fd, &auth_len, sizeof(auth_len), 1) < 0 ||
        io_full(fd, auth_body, sizeof(auth_body), 1) < 0) {
        memset(auth_body, 0, sizeof(auth_body));
        real_close(fd);
        errno = EACCES;
        return -1;
    }
    memset(auth_body, 0, sizeof(auth_body));
    return fd;
}

/* Send a framed request (opcode + payload), read a framed response into *out
 * (caller frees). Returns status (>=0 bytes / <0 error), out_len set. */
static int proxy_request(int fd, const uint8_t *body, uint32_t body_len, uint8_t **out, uint32_t *out_len) {
    if (!body || body_len == 0 || body_len > MAX_FRAME) { errno = EINVAL; return -1; }
    uint32_t flen = be32(body_len);
    if (io_full(fd, &flen, 4, 1) < 0) return -1;
    if (io_full(fd, (void *)body, body_len, 1) < 0) return -1;
    uint32_t rlen_be;
    if (io_full(fd, &rlen_be, 4, 0) < 0) return -1;
    uint32_t rlen = be32(rlen_be);
    if (rlen < 4 || rlen > MAX_FRAME) return -1;
    uint8_t *resp = malloc(rlen);
    if (!resp) return -1;
    if (io_full(fd, resp, rlen, 0) < 0) { free(resp); return -1; }
    uint32_t st_be; memcpy(&st_be, resp, 4);
    int32_t status = (int32_t)be32(st_be);
    if (status < -1 || (status > 0 && (uint32_t)status != rlen - 4)) {
        free(resp); errno = EPROTO; return -1;
    }
    if (out && status > 0) {
        *out = malloc(rlen - 4);
        if (!*out) { free(resp); errno = ENOMEM; return -1; }
        memcpy(*out, resp + 4, rlen - 4);
        *out_len = rlen - 4;
    } else if (out) { *out = NULL; *out_len = 0; }
    free(resp);
    return status;
}

static int proxy_get_descriptors(int fd, uint8_t **out, uint32_t *out_len) {
    uint8_t op = OP_GET_DESCRIPTORS;
    return proxy_request(fd, &op, 1, out, out_len);
}

static int proxy_control(int fd, int rt, int req, int val, int idx, int len, uint8_t **out, uint32_t *out_len) {
    if (len < 0 || len > 65535) { errno = EINVAL; return -1; }
    /* opcode(1) rt(1) req(1) value(2) index(2) length(2) timeout(4) = 13 bytes */
    uint8_t body[13];
    body[0] = OP_CONTROL;
    body[1] = (uint8_t)rt;
    body[2] = (uint8_t)req;
    body[3] = (uint8_t)(val >> 8); body[4] = (uint8_t)val;        /* u16 BE */
    body[5] = (uint8_t)(idx >> 8); body[6] = (uint8_t)idx;
    body[7] = (uint8_t)(len >> 8); body[8] = (uint8_t)len;
    uint32_t t = be32(1000); memcpy(body + 9, &t, 4);             /* u32 BE timeout */
    return proxy_request(fd, body, sizeof(body), out, out_len);
}

static int proxy_bulk(int fd, int ep, const uint8_t *data, int data_len, int read_len,
                      uint8_t **out, uint32_t *out_len) {
    if (data_len < 0 || read_len < 0 || data_len > (int)(MAX_FRAME - 10) ||
        read_len > (int)(MAX_FRAME - 10)) { errno = EINVAL; return -1; }
    uint32_t blen = 1 + 1 + 4 + 4 + (uint32_t)(data ? data_len : 0);
    uint8_t *body = malloc(blen);
    if (!body) { errno = ENOMEM; return -1; }
    body[0] = OP_BULK;
    body[1] = (uint8_t)ep;
    uint32_t l = be32((uint32_t)read_len); memcpy(body + 2, &l, 4);
    uint32_t t = be32(1000); memcpy(body + 6, &t, 4);
    if (data && data_len) memcpy(body + 10, data, data_len);
    int rc = proxy_request(fd, body, blen, out, out_len);
    free(body);
    return rc;
}

/* ---- descriptor parsing: find HID interface, endpoints, report-desc len -- */
static void parse_descriptors(struct managed *m, const uint8_t *d, uint32_t n) {
    m->hid_iface = 0; m->ep_in = 0; m->ep_out = 0; m->report_desc_len = 0;
    if (n >= 12) { m->vendor = (int16_t)(d[8] | (d[9] << 8)); m->product = (int16_t)(d[10] | (d[11] << 8)); }
    uint32_t i = 0; int cur_iface = -1, in_hid = 0;
    while (i + 2 <= n) {
        uint8_t blen = d[i], btype = d[i + 1];
        if (blen < 2 || i + blen > n) break;
        if (btype == 0x04) {                 /* INTERFACE */
            cur_iface = d[i + 2];
            in_hid = (d[i + 5] == 0x03);     /* bInterfaceClass == HID */
            if (in_hid) m->hid_iface = cur_iface;
        } else if (btype == 0x21 && in_hid) { /* HID descriptor */
            /* report descriptor length at offset 7..8 (first class desc) */
            if (i + 8 < n) m->report_desc_len = d[i + 7] | (d[i + 8] << 8);
        } else if (btype == 0x05 && in_hid) { /* ENDPOINT on the HID iface */
            uint8_t addr = d[i + 2];
            if (addr & 0x80) { if (!m->ep_in) m->ep_in = addr; }
            else { if (!m->ep_out) m->ep_out = addr; }
        }
        i += blen;
    }
}

static struct managed *find(int fd) {
    for (int i = 0; i < MAX_MANAGED; i++) if (g_managed[i].fd == fd) return &g_managed[i];
    return NULL;
}

static int is_hidraw_path(const char *path) {
    return path && strncmp(path, "/dev/hidraw", 11) == 0;
}

/* Open the proxy, fetch + parse descriptors, fetch the HID report descriptor,
 * and register a managed slot. Returns the socket fd, or -1 (errno set). */
static int open_managed(void) {
    init_reals();
    int fd = proxy_connect();
    if (fd < 0) { errno = ENOENT; return -1; }

    uint8_t *desc = NULL; uint32_t desc_len = 0;
    if (proxy_get_descriptors(fd, &desc, &desc_len) <= 0) { real_close(fd); errno = EIO; return -1; }

    pthread_mutex_lock(&g_lock);
    struct managed *m = find(-1);
    if (!m) { pthread_mutex_unlock(&g_lock); free(desc); real_close(fd); errno = EMFILE; return -1; }
    memset(m, 0, sizeof(*m));
    m->fd = fd;
    parse_descriptors(m, desc, desc_len);
    DBG("descs: len=%u iface=%d ep_in=0x%x ep_out=0x%x vid=%04x pid=%04x report_len=%u\n",
        desc_len, m->hid_iface, m->ep_in, m->ep_out, (uint16_t)m->vendor, (uint16_t)m->product, m->report_desc_len);
    free(desc);

    /* Fetch the HID report descriptor via control GET_DESCRIPTOR(0x22). */
    if (m->report_desc_len > 0 && m->report_desc_len <= HID_MAX_DESCRIPTOR_SIZE) {
        uint8_t *rd = NULL; uint32_t rd_len = 0;
        int want = m->report_desc_len;
        int rc = proxy_control(fd, 0x81, 0x06, (USB_DT_REPORT << 8), m->hid_iface,
                               want, &rd, &rd_len);
        DBG("report ctrl: want=%d rc=%d rd_len=%u first=%02x%02x\n",
            want, rc, rd_len, rd && rd_len > 0 ? rd[0] : 0, rd && rd_len > 1 ? rd[1] : 0);
        if (rc > 0 && rd) {
            uint32_t cp = rd_len < (uint32_t)want ? rd_len : (uint32_t)want;
            memcpy(m->report_desc, rd, cp);
            m->report_desc_len = cp;
        }
        free(rd);
    }
    pthread_mutex_unlock(&g_lock);
    return fd;
}

/* ===================================================================== */
/* libudev fakes (Slice 4) — DllMap'd from libudev.so.0/.1 for Mono apps  */
/* (HidSharp). We synthesise a single hidraw device backed by the proxy:  */
/* enumeration returns it, sysattr returns its VID/PID/strings, and the   */
/* hotplug monitor is a non-crashing fd that never fires. The devnode is  */
/* /dev/hidraw0, which the DllMap'd libc open() above routes to the proxy. */
/* ===================================================================== */

/* Cached device identity, fetched once over the proxy. */
static struct {
    int probed;            /* attempted a fetch */
    int present;           /* a device answered */
    char idVendor[8];      /* "268b" */
    char idProduct[8];     /* "0433" */
    char manufacturer[128];
    char product[128];
    char serial[128];
} g_dev;

/* Decode a USB string descriptor (UTF-16LE after a 2-byte header) into ASCII. */
static void decode_usb_string(const uint8_t *d, uint32_t n, char *out, size_t outsz) {
    out[0] = '\0';
    if (n < 2 || d[1] != 0x03) return;
    size_t j = 0;
    for (uint32_t i = 2; i + 1 < n && j + 1 < outsz; i += 2) {
        uint16_t c = d[i] | (d[i + 1] << 8);
        out[j++] = (c >= 0x20 && c < 0x7f) ? (char)c : '?';
    }
    out[j] = '\0';
}

/* Connect to the proxy, read the device descriptor for VID/PID + string
 * indices, then fetch the string descriptors. Best-effort: strings may stay
 * empty. Sets g_dev.present iff a device answered. Idempotent. */
static void ensure_dev_info(void) {
    if (g_dev.probed) return;
    g_dev.probed = 1;
    init_reals();
    int fd = proxy_connect();
    if (fd < 0) { DBG("udev: no proxy/device\n"); return; }

    uint8_t *desc = NULL; uint32_t desc_len = 0;
    if (proxy_get_descriptors(fd, &desc, &desc_len) <= 0 || !desc || desc_len < 18) {
        free(desc); real_close(fd); DBG("udev: GET_DESCRIPTORS failed\n"); return;
    }
    uint16_t vid = desc[8] | (desc[9] << 8);
    uint16_t pid = desc[10] | (desc[11] << 8);
    uint8_t iMan = desc[14], iProd = desc[15], iSer = desc[16];
    free(desc);
    snprintf(g_dev.idVendor, sizeof(g_dev.idVendor), "%04x", vid);
    snprintf(g_dev.idProduct, sizeof(g_dev.idProduct), "%04x", pid);
    g_dev.present = 1;

    struct { uint8_t idx; char *out; size_t sz; } strs[] = {
        { iMan, g_dev.manufacturer, sizeof(g_dev.manufacturer) },
        { iProd, g_dev.product, sizeof(g_dev.product) },
        { iSer, g_dev.serial, sizeof(g_dev.serial) },
    };
    for (size_t k = 0; k < 3; k++) {
        if (strs[k].idx == 0) continue;
        uint8_t *s = NULL; uint32_t sl = 0;
        int rc = proxy_control(fd, 0x80, 0x06, (0x03 << 8) | strs[k].idx, 0x0409, 255, &s, &sl);
        if (rc > 0 && s) decode_usb_string(s, sl, strs[k].out, strs[k].sz);
        free(s);
    }
    DBG("udev: device %s:%s manuf='%s' prod='%s' serial='%s'\n",
        g_dev.idVendor, g_dev.idProduct, g_dev.manufacturer, g_dev.product, g_dev.serial);

    uint8_t op = OP_CLOSE;
    proxy_request(fd, &op, 1, NULL, NULL);
    real_close(fd);
}

/* Opaque handles — distinct non-null sentinels HidSharp passes back to us. */
static int g_udev_obj, g_enum_obj, g_hid_dev, g_usb_dev, g_list_entry, g_monitor;
static int g_monitor_fd = -1;

void *udev_new(void) { ensure_dev_info(); return &g_udev_obj; }
void *udev_ref(void *u) { return u; }
void *udev_unref(void *u) { (void)u; return NULL; }

void *udev_enumerate_new(void *u) { (void)u; return &g_enum_obj; }
void *udev_enumerate_ref(void *e) { return e; }
void *udev_enumerate_unref(void *e) { (void)e; return NULL; }
int udev_enumerate_add_match_subsystem(void *e, const char *s) { (void)e; (void)s; return 0; }
int udev_enumerate_add_match_parent(void *e, void *p) { (void)e; (void)p; return 0; }
int udev_enumerate_scan_devices(void *e) { (void)e; return 0; }
void *udev_enumerate_get_list_entry(void *e) {
    (void)e; ensure_dev_info();
    return g_dev.present ? &g_list_entry : NULL;   /* exactly one device, or none */
}
void *udev_list_entry_get_next(void *le) { (void)le; return NULL; }   /* single entry */
const char *udev_list_entry_get_name(void *le) { (void)le; return "/sys/class/hidraw/hidraw0"; }

void *udev_device_new_from_syspath(void *u, const char *p) { (void)u; (void)p; return &g_hid_dev; }
void *udev_device_new_from_subsystem_sysname(void *u, const char *s, const char *n) {
    (void)u; (void)s; (void)n; return &g_hid_dev;
}
void *udev_device_ref(void *d) { return d; }
void *udev_device_unref(void *d) { (void)d; return NULL; }
const char *udev_device_get_devnode(void *d) { (void)d; return "/dev/hidraw0"; }
const char *udev_device_get_syspath(void *d) { (void)d; return "/sys/class/hidraw/hidraw0"; }
int udev_device_get_is_initialized(void *d) { (void)d; return 1; }
void *udev_device_get_parent(void *d) { (void)d; return &g_usb_dev; }
void *udev_device_get_parent_with_subsystem_devtype(void *d, const char *s, const char *t) {
    (void)d; (void)s; (void)t; return &g_usb_dev;
}
const char *udev_device_get_sysattr_value(void *d, const char *attr) {
    (void)d; ensure_dev_info();
    if (!attr) return NULL;
    if (!strcmp(attr, "idVendor")) return g_dev.idVendor[0] ? g_dev.idVendor : NULL;
    if (!strcmp(attr, "idProduct")) return g_dev.idProduct[0] ? g_dev.idProduct : NULL;
    if (!strcmp(attr, "manufacturer")) return g_dev.manufacturer[0] ? g_dev.manufacturer : NULL;
    if (!strcmp(attr, "product")) return g_dev.product[0] ? g_dev.product : NULL;
    if (!strcmp(attr, "serial")) return g_dev.serial[0] ? g_dev.serial : NULL;
    return NULL;
}

void *udev_monitor_new_from_netlink(void *u, const char *name) {
    (void)u; (void)name;
    /* A real, never-readable fd so HidSharp's monitor thread blocks harmlessly
     * instead of throwing (the udev_monitor_new_from_netlink failure that
     * killed EScribe's GUI). eventfd with no writes never polls readable. */
    if (g_monitor_fd < 0) g_monitor_fd = eventfd(0, EFD_CLOEXEC);
    return &g_monitor;
}
int udev_monitor_filter_add_match_subsystem_devtype(void *m, const char *s, const char *t) {
    (void)m; (void)s; (void)t; return 0;
}
int udev_monitor_enable_receiving(void *m) { (void)m; return 0; }
int udev_monitor_get_fd(void *m) { (void)m; return g_monitor_fd; }
void *udev_monitor_receive_device(void *m) { (void)m; return NULL; }
void *udev_monitor_unref(void *m) { (void)m; return NULL; }

/* ---- interposers --------------------------------------------------------- */
static int open_common(const char *path) {
    if (is_hidraw_path(path)) {
        int fd = open_managed();
        return fd; /* errno already set on failure */
    }
    return -2; /* sentinel: not ours, fall through */
}

int open(const char *path, int flags, ...) {
    init_reals();
    int r = open_common(path);
    if (r != -2) return r;
    mode_t mode = 0;
    if (flags & O_CREAT) { va_list ap; va_start(ap, flags); mode = va_arg(ap, int); va_end(ap); }
    return real_open(path, flags, mode);
}
int open64(const char *path, int flags, ...) {
    init_reals();
    int r = open_common(path);
    if (r != -2) return r;
    mode_t mode = 0;
    if (flags & O_CREAT) { va_list ap; va_start(ap, flags); mode = va_arg(ap, int); va_end(ap); }
    return real_open64 ? real_open64(path, flags, mode) : real_open(path, flags, mode);
}
int openat(int dirfd, const char *path, int flags, ...) {
    init_reals();
    int r = open_common(path);
    if (r != -2) return r;
    mode_t mode = 0;
    if (flags & O_CREAT) { va_list ap; va_start(ap, flags); mode = va_arg(ap, int); va_end(ap); }
    return real_openat(dirfd, path, flags, mode);
}
int openat64(int dirfd, const char *path, int flags, ...) {
    init_reals();
    int r = open_common(path);
    if (r != -2) return r;
    mode_t mode = 0;
    if (flags & O_CREAT) { va_list ap; va_start(ap, flags); mode = va_arg(ap, int); va_end(ap); }
    return real_openat64 ? real_openat64(dirfd, path, flags, mode) : real_openat(dirfd, path, flags, mode);
}

int ioctl(int fd, unsigned long request, ...) {
    init_reals();
    va_list ap; va_start(ap, request); void *arg = va_arg(ap, void *); va_end(ap);
    pthread_mutex_lock(&g_lock);
    struct managed *m = find(fd);
    if (!m) { pthread_mutex_unlock(&g_lock); return real_ioctl(fd, request, arg); }

    int ret = 0;
    if (request == (unsigned long)HIDIOCGRDESCSIZE) {
        *(int *)arg = (int)m->report_desc_len;
    } else if (request == (unsigned long)HIDIOCGRDESC) {
        struct haven_hidraw_report_descriptor *r = arg;
        uint32_t cp = r->size < m->report_desc_len ? r->size : m->report_desc_len;
        if (cp == 0) cp = m->report_desc_len;
        memcpy(r->value, m->report_desc, cp);
        r->size = cp;
    } else if (request == (unsigned long)HIDIOCGRAWINFO) {
        struct haven_hidraw_devinfo *info = arg;
        info->bustype = BUS_USB; info->vendor = m->vendor; info->product = m->product;
    } else if (_IOC_NR(request) == 0x04) { /* HIDIOCGRAWNAME(len) */
        snprintf((char *)arg, _IOC_SIZE(request), "Haven USB %04x:%04x",
                 (uint16_t)m->vendor, (uint16_t)m->product);
        ret = (int)strlen((char *)arg);
    } else if (_IOC_NR(request) == 0x05) { /* HIDIOCGRAWPHYS(len) */
        snprintf((char *)arg, _IOC_SIZE(request), "haven-usb");
        ret = (int)strlen((char *)arg);
    } else {
        errno = ENOTTY; ret = -1;
    }
    pthread_mutex_unlock(&g_lock);
    return ret;
}

ssize_t read(int fd, void *buf, size_t count) {
    init_reals();
    pthread_mutex_lock(&g_lock);
    struct managed *m = find(fd);
    if (!m) { pthread_mutex_unlock(&g_lock); return real_read(fd, buf, count); }
    int ep = m->ep_in, sock = m->fd;
    pthread_mutex_unlock(&g_lock);
    if (!ep) { errno = EIO; return -1; }
    if (count > (size_t)(MAX_FRAME - 32) || count > INT_MAX) { errno = EOVERFLOW; return -1; }
    uint8_t *out = NULL; uint32_t out_len = 0;
    int rc = proxy_bulk(sock, ep, NULL, 0, (int)count, &out, &out_len);
    if (rc < 0) { free(out); errno = EIO; return -1; }
    uint32_t cp = out_len < count ? out_len : (uint32_t)count;
    if (out && cp) memcpy(buf, out, cp);
    free(out);
    return (ssize_t)cp;
}

ssize_t write(int fd, const void *buf, size_t count) {
    init_reals();
    pthread_mutex_lock(&g_lock);
    struct managed *m = find(fd);
    if (!m) { pthread_mutex_unlock(&g_lock); return real_write(fd, buf, count); }
    int ep = m->ep_out, sock = m->fd;
    pthread_mutex_unlock(&g_lock);
    if (!ep) { errno = EIO; return -1; }
    if (count > (size_t)(MAX_FRAME - 32) || count > INT_MAX) { errno = EOVERFLOW; return -1; }
    int rc = proxy_bulk(sock, ep, buf, (int)count, 0, NULL, NULL);
    if (rc < 0) { errno = EIO; return -1; }
    return rc; /* bytes written */
}

int close(int fd) {
    init_reals();
    pthread_mutex_lock(&g_lock);
    struct managed *m = find(fd);
    if (m) {
        uint8_t op = OP_CLOSE;
        proxy_request(m->fd, &op, 1, NULL, NULL);
        m->fd = -1;
        pthread_mutex_unlock(&g_lock);
        return real_close(fd);
    }
    pthread_mutex_unlock(&g_lock);
    return real_close(fd);
}

int poll(struct pollfd *fds, nfds_t nfds, int timeout) {
    init_reals();
    /* If any polled fd is ours, report it readable: reads are synchronous proxy
     * round-trips that block on the device, so the caller can read() directly.
     * Non-managed fds are left to the real poll in a separate pass. */
    int managed_ready = 0, has_other = 0;
    pthread_mutex_lock(&g_lock);
    for (nfds_t i = 0; i < nfds; i++) {
        if (find(fds[i].fd)) { fds[i].revents = fds[i].events & POLLIN; if (fds[i].revents) managed_ready++; }
        else has_other = 1;
    }
    pthread_mutex_unlock(&g_lock);
    if (managed_ready && !has_other) return managed_ready;
    return real_poll(fds, nfds, managed_ready ? 0 : timeout);
}
