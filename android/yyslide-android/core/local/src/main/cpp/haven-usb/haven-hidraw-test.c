/*
 * haven-hidraw-test — Slice-3 verification harness for libhaven_usb.so.
 *
 * A minimal hidraw consumer: open("/dev/hidraw0"), then HIDIOCGRAWINFO,
 * HIDIOCGRDESCSIZE, HIDIOCGRDESC, and one read() of an input report. Run it as
 *   LD_PRELOAD=/usr/local/lib/haven/libhaven_usb.so haven-hidraw-test
 * so the shim intercepts the calls and routes them to the brokered device.
 * Must be DYNAMICALLY linked (no -static) or LD_PRELOAD can't interpose.
 *
 * Exit 0 if the report descriptor came back non-empty; the read is best-effort
 * (an idle HID device may have no input report pending within the timeout).
 */
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <stdint.h>
#include <unistd.h>
#include <fcntl.h>
#include <errno.h>
#include <sys/ioctl.h>

#define HID_MAX_DESCRIPTOR_SIZE 4096
struct rd { uint32_t size; uint8_t value[HID_MAX_DESCRIPTOR_SIZE]; };
struct devinfo { uint32_t bustype; int16_t vendor; int16_t product; };
#define HIDIOCGRDESCSIZE _IOR('H', 0x01, int)
#define HIDIOCGRDESC     _IOR('H', 0x02, struct rd)
#define HIDIOCGRAWINFO   _IOR('H', 0x03, struct devinfo)

int main(int argc, char **argv) {
    const char *path = (argc > 1) ? argv[1] : "/dev/hidraw0";
    int fd = open(path, O_RDWR);
    if (fd < 0) { fprintf(stderr, "open(%s) failed: %s\n", path, strerror(errno)); return 2; }
    fprintf(stderr, "opened %s as fd %d\n", path, fd);

    struct devinfo info;
    memset(&info, 0, sizeof(info));
    if (ioctl(fd, HIDIOCGRAWINFO, &info) == 0)
        printf("HIDIOCGRAWINFO bustype=0x%x vendor=%04x product=%04x\n",
               info.bustype, (uint16_t)info.vendor, (uint16_t)info.product);
    else fprintf(stderr, "HIDIOCGRAWINFO failed: %s\n", strerror(errno));

    int dsize = 0;
    if (ioctl(fd, HIDIOCGRDESCSIZE, &dsize) != 0) {
        fprintf(stderr, "HIDIOCGRDESCSIZE failed: %s\n", strerror(errno));
        close(fd); return 3;
    }
    printf("HIDIOCGRDESCSIZE = %d\n", dsize);

    struct rd desc;
    memset(&desc, 0, sizeof(desc));
    desc.size = (uint32_t)dsize;
    if (ioctl(fd, HIDIOCGRDESC, &desc) != 0) {
        fprintf(stderr, "HIDIOCGRDESC failed: %s\n", strerror(errno));
        close(fd); return 4;
    }
    printf("report descriptor (%u bytes): ", desc.size);
    for (uint32_t i = 0; i < desc.size && i < 64; i++) printf("%02x ", desc.value[i]);
    printf("\n");

    /* Best-effort input read — may legitimately return 0/timeout if idle. */
    uint8_t report[64];
    ssize_t r = read(fd, report, sizeof(report));
    if (r > 0) {
        printf("read %zd bytes: ", r);
        for (ssize_t i = 0; i < r && i < 32; i++) printf("%02x ", report[i]);
        printf("\n");
    } else {
        fprintf(stderr, "read returned %zd (%s) — ok if device is idle\n", r, strerror(errno));
    }

    close(fd);
    return (desc.size > 0) ? 0 : 5;
}
