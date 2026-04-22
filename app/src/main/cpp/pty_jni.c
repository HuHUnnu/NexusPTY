#include <jni.h>
#include <stdlib.h>
#include <unistd.h>
#include <fcntl.h>
#include <string.h>
#include <errno.h>
#include <signal.h>
#include <sys/ioctl.h>
#include <sys/wait.h>
#include <termios.h>
#include <android/log.h>

#define TAG "NexusPTY"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

static int create_subprocess(
    const char *cmd, char *const argv[], char *const envp[],
    int rows, int cols, pid_t *pid_out
) {
    int ptm = open("/dev/ptmx", O_RDWR | O_CLOEXEC);
    if (ptm < 0) {
        LOGE("open /dev/ptmx failed: %s", strerror(errno));
        return -1;
    }

    if (grantpt(ptm) != 0) {
        LOGE("grantpt failed: %s", strerror(errno));
        close(ptm);
        return -1;
    }

    if (unlockpt(ptm) != 0) {
        LOGE("unlockpt failed: %s", strerror(errno));
        close(ptm);
        return -1;
    }

    char *devname = ptsname(ptm);
    if (!devname) {
        LOGE("ptsname failed: %s", strerror(errno));
        close(ptm);
        return -1;
    }

    char pts_path[256];
    strncpy(pts_path, devname, sizeof(pts_path) - 1);
    pts_path[sizeof(pts_path) - 1] = '\0';

    struct winsize ws;
    memset(&ws, 0, sizeof(ws));
    ws.ws_row = (unsigned short)rows;
    ws.ws_col = (unsigned short)cols;
    ioctl(ptm, TIOCSWINSZ, &ws);

    pid_t pid = fork();
    if (pid < 0) {
        LOGE("fork failed: %s", strerror(errno));
        close(ptm);
        return -1;
    }

    if (pid == 0) {
        close(ptm);
        setsid();

        int pts = open(pts_path, O_RDWR);
        if (pts < 0) _exit(127);

        ioctl(pts, TIOCSCTTY, 0);

        dup2(pts, STDIN_FILENO);
        dup2(pts, STDOUT_FILENO);
        dup2(pts, STDERR_FILENO);
        if (pts > 2) close(pts);

        if (envp) {
            execve(cmd, argv, envp);
        } else {
            execvp(cmd, argv);
        }
        LOGE("exec failed: %s", strerror(errno));
        _exit(127);
    }

    *pid_out = pid;
    return ptm;
}

JNIEXPORT jintArray JNICALL
Java_com_nexus_tools_NativePty_nativeCreateSubprocess(
    JNIEnv *env, jclass clazz,
    jstring cmd, jobjectArray args, jobjectArray envVars,
    jint rows, jint cols
) {
    const char *cmd_str = (*env)->GetStringUTFChars(env, cmd, NULL);

    int argc = args ? (*env)->GetArrayLength(env, args) : 0;
    char **argv = calloc(argc + 2, sizeof(char *));
    argv[0] = (char *)cmd_str;
    for (int i = 0; i < argc; i++) {
        jstring s = (*env)->GetObjectArrayElement(env, args, i);
        argv[i + 1] = (char *)(*env)->GetStringUTFChars(env, s, NULL);
    }
    argv[argc + 1] = NULL;

    char **envp = NULL;
    int envc = envVars ? (*env)->GetArrayLength(env, envVars) : 0;
    if (envc > 0) {
        envp = calloc(envc + 1, sizeof(char *));
        for (int i = 0; i < envc; i++) {
            jstring s = (*env)->GetObjectArrayElement(env, envVars, i);
            envp[i] = (char *)(*env)->GetStringUTFChars(env, s, NULL);
        }
        envp[envc] = NULL;
    }

    pid_t pid = 0;
    int ptm = create_subprocess(cmd_str, argv, envp, rows, cols, &pid);

    (*env)->ReleaseStringUTFChars(env, cmd, cmd_str);
    for (int i = 0; i < argc; i++) {
        jstring s = (*env)->GetObjectArrayElement(env, args, i);
        (*env)->ReleaseStringUTFChars(env, s, argv[i + 1]);
    }
    free(argv);

    if (envp) {
        for (int i = 0; i < envc; i++) {
            jstring s = (*env)->GetObjectArrayElement(env, envVars, i);
            (*env)->ReleaseStringUTFChars(env, s, envp[i]);
        }
        free(envp);
    }

    jintArray result = (*env)->NewIntArray(env, 2);
    jint buf[2] = { ptm, (jint)pid };
    (*env)->SetIntArrayRegion(env, result, 0, 2, buf);
    return result;
}

JNIEXPORT jint JNICALL
Java_com_nexus_tools_NativePty_nativeRead(
    JNIEnv *env, jclass clazz, jint fd, jbyteArray buffer, jint len
) {
    jbyte *buf = (*env)->GetByteArrayElements(env, buffer, NULL);
    int n = read(fd, buf, len);
    (*env)->ReleaseByteArrayElements(env, buffer, buf, 0);
    if (n < 0 && errno == EIO) return -1;
    return n;
}

JNIEXPORT jint JNICALL
Java_com_nexus_tools_NativePty_nativeWrite(
    JNIEnv *env, jclass clazz, jint fd, jbyteArray data, jint len
) {
    jbyte *buf = (*env)->GetByteArrayElements(env, data, NULL);
    int n = write(fd, buf, len);
    (*env)->ReleaseByteArrayElements(env, data, buf, JNI_ABORT);
    return n;
}

JNIEXPORT void JNICALL
Java_com_nexus_tools_NativePty_nativeClose(JNIEnv *env, jclass clazz, jint fd) {
    close(fd);
}

JNIEXPORT jint JNICALL
Java_com_nexus_tools_NativePty_nativeWaitFor(JNIEnv *env, jclass clazz, jint pid) {
    int status;
    if (waitpid(pid, &status, 0) < 0) return -1;
    if (WIFEXITED(status)) return WEXITSTATUS(status);
    if (WIFSIGNALED(status)) return 128 + WTERMSIG(status);
    return -1;
}

JNIEXPORT void JNICALL
Java_com_nexus_tools_NativePty_nativeSetSize(
    JNIEnv *env, jclass clazz, jint fd, jint rows, jint cols
) {
    struct winsize ws;
    memset(&ws, 0, sizeof(ws));
    ws.ws_row = (unsigned short)rows;
    ws.ws_col = (unsigned short)cols;
    ioctl(fd, TIOCSWINSZ, &ws);
}

JNIEXPORT void JNICALL
Java_com_nexus_tools_NativePty_nativeKill(
    JNIEnv *env, jclass clazz, jint pid, jint sig
) {
    kill(pid, sig);
    kill(-pid, sig);
}
