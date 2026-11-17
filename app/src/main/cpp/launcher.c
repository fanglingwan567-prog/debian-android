#define _GNU_SOURCE
#include <arpa/inet.h>
#include <errno.h>
#include <fcntl.h>
#include <grp.h>
#include <limits.h>
#include <poll.h>
#include <pty.h>
#include <sched.h>
#include <signal.h>
#include <stdarg.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/file.h>
#include <sys/ioctl.h>
#include <sys/mount.h>
#include <sys/stat.h>
#include <sys/types.h>
#include <sys/wait.h>
#include <unistd.h>

#define MAX_IMAGE (256U * 1024U * 1024U)
#define APP_ID "app.birch.debian"
#define TOY "/system/bin/toybox"

static void die(const char *s) {
    perror(s);
    exit(1);
}

static void fail(const char *s) {
    fprintf(stderr, "%s\n", s);
    exit(1);
}

static void make_dir(const char *s, mode_t m) {
    if (mkdir(s, m) < 0 && errno != EEXIST)
        die(s);
}

// Walk from / by descriptor. Never follow an app-writable symlink as root.
static int private_dir(int parent, const char *name, uid_t owner, mode_t forbidden) {
    int fd = openat(parent, name, O_RDONLY | O_DIRECTORY | O_NOFOLLOW | O_CLOEXEC);
    if (fd < 0)
        die(name);

    struct stat st;
    if (fstat(fd, &st) < 0)
        die(name);
    if (st.st_uid != owner || (st.st_mode & forbidden))
        fail("Unexpected private directory owner or mode");
    return fd;
}

static void enter_storage(uid_t uid, const char *files) {
    char prefix[128];
    const char *base = "/data";
    if (!strncmp(files, "/mnt/expand/", 12)) {
        const char *end = strchr(files + 12, '/');
        if (!end || end == files + 12 || end - files >= 100 ||
            (size_t)(end - files - 12) != strspn(files + 12, "0123456789abcdefABCDEF-"))
            fail("Invalid private volume");
        memcpy(prefix, files, (size_t)(end - files));
        prefix[end - files] = '\0';
        base = prefix;
    }

    char expected[256];
    snprintf(expected, sizeof expected, "%s/user/%u/" APP_ID "/files", base, uid / 100000U);
    if (strcmp(files, expected) &&
        !(uid < 100000U && !strcmp(files, "/data/data/" APP_ID "/files")))
        fail("Not this application's files directory");

    char path[256];
    snprintf(path, sizeof path, "%s", files);
    char *save = NULL;
    int fd = open("/", O_RDONLY | O_DIRECTORY | O_CLOEXEC);
    if (fd < 0)
        die("/");
    for (char *part = strtok_r(path, "/", &save); part; part = strtok_r(NULL, "/", &save)) {
        int next;
        if (!strcmp(part, APP_ID) || !strcmp(part, "files"))
            next = private_dir(fd, part, uid, S_IWOTH);
        else {
            next = openat(fd, part, O_RDONLY | O_DIRECTORY | O_NOFOLLOW | O_CLOEXEC);
            if (next < 0)
                die(part);
        }
        close(fd);
        fd = next;
    }

    if (mkdirat(fd, "debian", 0700) < 0 && errno != EEXIST)
        die("create Debian directory");
    int runtime = private_dir(fd, "debian", 0, 0077);
    close(fd);
    if (fchdir(runtime) < 0)
        die("enter Debian directory");
    close(runtime);
}

static void sendall(int fd, const void *v, size_t n) {
    const char *p = v;
    while (n) {
        ssize_t k = write(fd, p, n);
        if (k < 0 && errno == EINTR)
            continue;
        if (k <= 0)
            die("write");
        p += k;
        n -= (size_t)k;
    }
}

static int take(int fd, void *v, size_t n) {
    char *p = v;
    while (n) {
        ssize_t k = read(fd, p, n);
        if (k < 0 && errno == EINTR)
            continue;
        if (k < 0)
            die("read");
        if (k == 0)
            return 0;
        p += k;
        n -= (size_t)k;
    }
    return 1;
}

static void file_text(const char *path, int flags, const char *fmt, ...) {
    int fd = open(path, flags | O_WRONLY | O_NOFOLLOW | O_CLOEXEC, 0644);
    if (fd < 0)
        die(path);

    va_list ap;
    va_start(ap, fmt);
    if (vdprintf(fd, fmt, ap) < 0)
        die(path);
    va_end(ap);
    if (close(fd) < 0)
        die(path);
}

static int status(pid_t p) {
    int s;
    while (waitpid(p, &s, 0) < 0)
        if (errno != EINTR)
            die("waitpid");

    if (WIFEXITED(s))
        return WEXITSTATUS(s);
    if (WIFSIGNALED(s))
        return 128 + WTERMSIG(s);
    return 1;
}

static void run(char *const argv[]) {
    pid_t p = fork();
    if (p < 0)
        die("fork");
    if (!p) {
        execv(argv[0], argv);
        _exit(127);
    }
    if (status(p))
        fail(argv[1]);
}

static void ready(void) {
    if (access("root/.birch-ready", F_OK) < 0)
        fail("Debian installation is incomplete");
}

static void install(void) {
    int lock = open("install.lock", O_CREAT | O_RDWR | O_NOFOLLOW | O_CLOEXEC, 0600);
    if (lock < 0 || flock(lock, LOCK_EX | LOCK_NB) < 0)
        die("installation lock");
    if (access("root", F_OK) == 0) {
        ready();
        return;
    }

    // The app resolves the official ARM64 manifest over TLS. Only hex + a bounded
    // decimal count cross the privilege boundary; no URL, tag, or path is accepted.
    char digest[65], length[10];
    if (!take(0, digest, 64))
        fail("Missing image digest");
    digest[64] = '\0';
    if (strspn(digest, "0123456789abcdef") != 64)
        fail("Invalid image digest");

    char ch;
    if (!take(0, &ch, 1) || ch != ' ')
        fail("Invalid image metadata");
    size_t digits = 0;
    while (take(0, &ch, 1) && ch != '\n') {
        if (digits >= sizeof(length) - 1 || ch < '0' || ch > '9')
            fail("Invalid image length");
        length[digits++] = ch;
    }
    if (!digits || ch != '\n')
        fail("Missing image length");
    length[digits] = '\0';
    unsigned long expected = strtoul(length, NULL, 10);
    if (!expected || expected > MAX_IMAGE)
        fail("Unsupported image length");

    // Staging is never mounted or entered by a user. Only a fully probed rootfs is renamed into service.
    run((char *[]){TOY, "rm", "-rf", "stage", NULL});
    if (mkdir("stage", 0700) < 0 || chdir("stage") < 0)
        die("stage");

    int archive = open("image", O_WRONLY | O_EXCL | O_CREAT | O_NOFOLLOW, 0600);
    if (archive < 0)
        die("image");
    char buf[65536];
    size_t total = 0;
    ssize_t n;
    while ((n = read(0, buf, sizeof buf)) > 0) {
        total += (size_t)n;
        if (total > expected)
            fail("Unexpected image size");
        sendall(archive, buf, (size_t)n);
    }
    if (n < 0 || close(archive) < 0)
        die("copy image");
    if (total != expected)
        fail("Incomplete image");

    file_text("checksum", O_CREAT | O_EXCL, "%s  image\n", digest);
    run((char *[]){TOY, "sha256sum", "-c", "checksum", NULL});
    if (mkdir("tree", 0755) < 0)
        die("tree");
    run((char *[]){TOY, "tar", "-xzf", "image", "--numeric-owner", "-C", "tree", NULL});

    pid_t p = fork();
    if (p < 0)
        die("fork");
    if (!p) {
        if (chroot("tree") < 0 || chdir("/") < 0)
            die("chroot smoke test");
        execl(
            "/bin/bash", "bash", "-ec",
            "test -x /usr/bin/apt && test -x /usr/sbin/chroot && /usr/bin/dpkg --print-architecture",
            (char *)NULL);
        die("Debian bash");
    }
    if (status(p))
        fail("Debian smoke test failed");

    file_text("tree/.birch-ready", O_CREAT | O_EXCL, "1\n");
    if (rename("tree", "../root") < 0 || chdir("..") < 0)
        die("activate");
    run((char *[]){TOY, "rm", "-rf", "stage", NULL});
}

static void mounts(void) {
    if (unshare(CLONE_NEWNS) < 0 || mount(NULL, "/", NULL, MS_REC | MS_PRIVATE, NULL) < 0)
        die("private mount namespace");

    // Debian's authenticated account tools use set-ID bits on this private rootfs mount.
    if (mount("root", "root", NULL, MS_BIND, NULL) < 0 ||
        mount(NULL, "root", NULL, MS_BIND | MS_REMOUNT | MS_NODEV, NULL) < 0)
        die("rootfs mount");

    if (mount("tmpfs", "root/dev", "tmpfs", MS_NOSUID, "mode=755,size=1m") < 0)
        die("mount /dev");
    const char *devs[] = {"null", "zero", "full", "random", "urandom", "tty"};
    for (size_t i = 0; i < sizeof devs / sizeof devs[0]; i++) {
        char src[64], dst[64];
        snprintf(src, sizeof src, "/dev/%s", devs[i]);
        snprintf(dst, sizeof dst, "root/dev/%s", devs[i]);
        int fd = open(dst, O_CREAT | O_EXCL | O_WRONLY, 0600);
        if (fd < 0)
            die(dst);
        close(fd);
        if (mount(src, dst, NULL, MS_BIND, NULL) < 0)
            die(dst);
    }

    make_dir("root/dev/pts", 0755);
    if (mount("devpts", "root/dev/pts", "devpts", MS_NOSUID | MS_NOEXEC,
              "newinstance,ptmxmode=0666,mode=0620,gid=5") < 0 ||
        symlink("pts/ptmx", "root/dev/ptmx") < 0)
        die("devpts");
    make_dir("root/dev/shm", 01777);
    if (chmod("root/dev/shm", 01777) < 0)
        die("shm");

    if (mount("proc", "root/proc", "proc", MS_RDONLY | MS_NOSUID | MS_NODEV | MS_NOEXEC, NULL) < 0)
        die("proc");
    const char *links[][2] = {{"/proc/self/fd", "root/dev/fd"},
                              {"/proc/self/fd/0", "root/dev/stdin"},
                              {"/proc/self/fd/1", "root/dev/stdout"},
                              {"/proc/self/fd/2", "root/dev/stderr"}};
    for (size_t i = 0; i < 4; i++)
        if (symlink(links[i][0], links[i][1]) < 0)
            die("dev link");
}

static void dns_file(int argc, char **argv) {
    if (argc <= 4)
        return;

    char tmp[] = "root/etc/.resolv-XXXXXX";
    int fd = mkstemp(tmp);
    if (fd < 0)
        die("resolver");
    for (int i = 4; i < argc; i++) {
        unsigned char buf[16];
        if (inet_pton(AF_INET, argv[i], buf) != 1 && inet_pton(AF_INET6, argv[i], buf) != 1)
            fail("Invalid resolver");
        if (dprintf(fd, "nameserver %s\n", argv[i]) < 0)
            die("resolver");
    }
    if (fchmod(fd, 0644) < 0 || close(fd) < 0 || rename(tmp, "root/etc/resolv.conf") < 0)
        die("resolver");
}

static void session(int argc, char **argv) {
    ready();
    mounts();
    dns_file(argc, argv);
    if (chroot("root") < 0 || chdir("/") < 0)
        die("chroot");

    int master;
    struct winsize ws = {24, 80, 0, 0};
    pid_t pid = forkpty(&master, NULL, NULL, &ws);
    if (pid < 0)
        die("forkpty");
    if (!pid) {
        gid_t net = 3003;
        if (setgroups(1, &net) < 0)
            die("setgroups");
        if (chdir("/root") < 0)
            die("home");

        clearenv();
        setenv("HOME", "/root", 1);
        setenv("USER", "root", 1);
        setenv("LOGNAME", "root", 1);
        setenv("SHELL", "/bin/bash", 1);
        setenv("TERM", "xterm-256color", 1);
        setenv("LANG", "C.UTF-8", 1);
        setenv("PATH", "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin", 1);
        umask(0022);
        execl("/bin/bash", "bash", "-i", (char *)NULL);
        die("bash");
    }

    // The guest controls only the slave. Parent interprets framed input from the app, never shell output.
    struct pollfd fds[2] = {{0, POLLIN, 0}, {master, POLLIN, 0}};
    unsigned char buf[16384];
    for (;;) {
        if (poll(fds, 2, -1) < 0) {
            if (errno == EINTR)
                continue;
            die("poll");
        }

        if (fds[1].revents & POLLIN) {
            ssize_t n = read(master, buf, sizeof buf);
            if (n <= 0)
                break;
            sendall(1, buf, (size_t)n);
        } else if (fds[1].revents & (POLLERR | POLLHUP | POLLNVAL))
            break;

        if (fds[0].revents & POLLIN) {
            unsigned char h[3];
            if (!take(0, h, 3))
                break;
            size_t n = ((size_t)h[1] << 8) | h[2];
            if (!n || n > sizeof buf || !take(0, buf, n))
                break;

            if (h[0] == 'I')
                sendall(master, buf, n);
            else if (h[0] == 'W' && n == 4) {
                ws.ws_col = (unsigned short)(buf[0] * 256 + buf[1]);
                ws.ws_row = (unsigned short)(buf[2] * 256 + buf[3]);
                if (ws.ws_col && ws.ws_row)
                    ioctl(master, TIOCSWINSZ, &ws);
            } else
                break;
        } else if (fds[0].revents & (POLLERR | POLLHUP | POLLNVAL))
            break;
    }

    close(master);
    kill(pid, SIGHUP);
    exit(status(pid));
}

int main(int argc, char **argv) {
    if (argc < 4 || argc > 8 || geteuid() != 0)
        fail("Root authorization required");

    char *end = NULL;
    unsigned long u = strtoul(argv[2], &end, 10);
    if (!*argv[2] || *end || u < 10000 || u > INT32_MAX)
        fail("Invalid application uid");

    umask(0022);
    clearenv();
    setenv("PATH", "/system/bin", 1);
    enter_storage((uid_t)u, argv[3]);

    if (!strcmp(argv[1], "check")) {
        if (access("root", F_OK) < 0 && errno == ENOENT) {
            puts("BIRCH_NEW");
            return 0;
        }
        ready();
        puts("BIRCH_READY");
        return 0;
    }
    if (!strcmp(argv[1], "install")) {
        install();
        return 0;
    }
    if (!strcmp(argv[1], "shell"))
        session(argc, argv);
    else
        fail("Unknown operation");
    return 0;
}
