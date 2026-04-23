#include <jni.h>
#include <stdlib.h>
#include <unistd.h>
#include <fcntl.h>
#include <string.h>
#include <errno.h>
#include <signal.h>
#include <dirent.h>
#include <sys/ioctl.h>
#include <sys/wait.h>
#include <termios.h>
#include <android/log.h>

#define TAG "NexusPTY"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

static void close_all_fds_except(int preserve_fd) {
    DIR *d = opendir("/proc/self/fd");
    if (!d) return;
    int dfd = dirfd(d);
    struct dirent *ent;
    while ((ent = readdir(d)) != NULL) {
        int fd = atoi(ent->d_name);
        if (fd > 2 && fd != dfd && fd != preserve_fd)
            close(fd);
    }
    closedir(d);
}

JNIEXPORT jintArray JNICALL
Java_com_chaoxing_eduapp_NativePty_nativeCreateSubprocess(
    JNIEnv *env, jclass clazz,
    jstring cmd, jobjectArray args, jobjectArray envVars,
    jint rows, jint cols
) {
    const char *cmd_str = (*env)->GetStringUTFChars(env, cmd, NULL);

    int argc = args ? (*env)->GetArrayLength(env, args) : 0;
    char **argv = calloc(argc + 2, sizeof(char *));
    argv[0] = strdup(cmd_str);
    for (int i = 0; i < argc; i++) {
        jstring s = (*env)->GetObjectArrayElement(env, args, i);
        const char *cs = (*env)->GetStringUTFChars(env, s, NULL);
        argv[i + 1] = strdup(cs);
        (*env)->ReleaseStringUTFChars(env, s, cs);
    }
    argv[argc + 1] = NULL;

    char **envp = NULL;
    int envc = envVars ? (*env)->GetArrayLength(env, envVars) : 0;
    if (envc > 0) {
        envp = calloc(envc + 1, sizeof(char *));
        for (int i = 0; i < envc; i++) {
            jstring s = (*env)->GetObjectArrayElement(env, envVars, i);
            const char *cs = (*env)->GetStringUTFChars(env, s, NULL);
            envp[i] = strdup(cs);
            (*env)->ReleaseStringUTFChars(env, s, cs);
        }
        envp[envc] = NULL;
    }

    (*env)->ReleaseStringUTFChars(env, cmd, cmd_str);

    int ptm = open("/dev/ptmx", O_RDWR | O_CLOEXEC);
    if (ptm < 0) {
        LOGE("open /dev/ptmx: %s", strerror(errno));
        jintArray r = (*env)->NewIntArray(env, 2);
        jint b[2] = { -1, -1 };
        (*env)->SetIntArrayRegion(env, r, 0, 2, b);
        return r;
    }

    if (grantpt(ptm) || unlockpt(ptm)) {
        LOGE("grantpt/unlockpt: %s", strerror(errno));
        close(ptm);
        jintArray r = (*env)->NewIntArray(env, 2);
        jint b[2] = { -1, -1 };
        (*env)->SetIntArrayRegion(env, r, 0, 2, b);
        return r;
    }

    char slave_path[64];
    if (ptsname_r(ptm, slave_path, sizeof(slave_path))) {
        LOGE("ptsname_r: %s", strerror(errno));
        close(ptm);
        jintArray r = (*env)->NewIntArray(env, 2);
        jint b[2] = { -1, -1 };
        (*env)->SetIntArrayRegion(env, r, 0, 2, b);
        return r;
    }

    struct termios tio;
    tcgetattr(ptm, &tio);
    tio.c_iflag &= ~(IXON | IXOFF);
    tcsetattr(ptm, TCSANOW, &tio);

    struct winsize ws;
    memset(&ws, 0, sizeof(ws));
    ws.ws_row = (unsigned short)rows;
    ws.ws_col = (unsigned short)cols;
    ioctl(ptm, TIOCSWINSZ, &ws);

    LOGI("PTY ready: master=%d slave=%s", ptm, slave_path);

    pid_t pid = fork();
    if (pid < 0) {
        LOGE("fork: %s", strerror(errno));
        close(ptm);
        jintArray r = (*env)->NewIntArray(env, 2);
        jint b[2] = { -1, -1 };
        (*env)->SetIntArrayRegion(env, r, 0, 2, b);
        return r;
    }

    if (pid == 0) {
        sigset_t ss;
        sigfillset(&ss);
        sigprocmask(SIG_BLOCK, &ss, NULL);

        close(ptm);
        setsid();

        int pts = open(slave_path, O_RDWR);
        if (pts < 0) _exit(126);

        dup2(pts, 0);
        dup2(pts, 1);
        dup2(pts, 2);

        close_all_fds_except(-1);

        if (envp) {
            for (int i = 0; envp[i]; i++)
                putenv(envp[i]);
        }

        sigprocmask(SIG_UNBLOCK, &ss, NULL);

        execvp(argv[0], argv);
        LOGE("execvp(%s): %s", argv[0], strerror(errno));
        _exit(127);
    }

    for (int i = 0; argv[i]; i++) free(argv[i]);
    free(argv);
    if (envp) {
        for (int i = 0; envp[i]; i++) free(envp[i]);
        free(envp);
    }

    LOGI("Child spawned: pid=%d", pid);

    jintArray result = (*env)->NewIntArray(env, 2);
    jint buf[2] = { ptm, (jint)pid };
    (*env)->SetIntArrayRegion(env, result, 0, 2, buf);
    return result;
}

JNIEXPORT jint JNICALL
Java_com_chaoxing_eduapp_NativePty_nativeRead(
    JNIEnv *env, jclass clazz, jint fd, jbyteArray buffer, jint len
) {
    jbyte *buf = (*env)->GetByteArrayElements(env, buffer, NULL);
    int n = read(fd, buf, len);
    (*env)->ReleaseByteArrayElements(env, buffer, buf, 0);
    if (n < 0 && errno == EIO) return -1;
    return n;
}

JNIEXPORT jint JNICALL
Java_com_chaoxing_eduapp_NativePty_nativeWrite(
    JNIEnv *env, jclass clazz, jint fd, jbyteArray data, jint len
) {
    jbyte *buf = (*env)->GetByteArrayElements(env, data, NULL);
    int n = write(fd, buf, len);
    (*env)->ReleaseByteArrayElements(env, data, buf, JNI_ABORT);
    return n;
}

JNIEXPORT void JNICALL
Java_com_chaoxing_eduapp_NativePty_nativeClose(JNIEnv *env, jclass clazz, jint fd) {
    close(fd);
}

JNIEXPORT jint JNICALL
Java_com_chaoxing_eduapp_NativePty_nativeWaitFor(JNIEnv *env, jclass clazz, jint pid) {
    int status;
    if (waitpid(pid, &status, 0) < 0) return -1;
    if (WIFEXITED(status)) return WEXITSTATUS(status);
    if (WIFSIGNALED(status)) return 128 + WTERMSIG(status);
    return -1;
}

JNIEXPORT void JNICALL
Java_com_chaoxing_eduapp_NativePty_nativeSetSize(
    JNIEnv *env, jclass clazz, jint fd, jint rows, jint cols
) {
    struct winsize ws;
    memset(&ws, 0, sizeof(ws));
    ws.ws_row = (unsigned short)rows;
    ws.ws_col = (unsigned short)cols;
    ioctl(fd, TIOCSWINSZ, &ws);
}

JNIEXPORT void JNICALL
Java_com_chaoxing_eduapp_NativePty_nativeKill(
    JNIEnv *env, jclass clazz, jint pid, jint sig
) {
    kill(pid, sig);
    kill(-pid, sig);
}
