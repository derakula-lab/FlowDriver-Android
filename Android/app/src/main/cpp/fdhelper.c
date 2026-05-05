#include <jni.h>
#include <fcntl.h>
#include <unistd.h>
#include <stdlib.h>
#include <string.h>

JNIEXPORT jint JNICALL
Java_com_github_derakula_TunnelVpnService_clearCloexec(
        JNIEnv *env, jobject thiz, jint fd) {
    return fcntl(fd, F_SETFD, 0);
}

JNIEXPORT jint JNICALL
Java_com_github_derakula_TunnelVpnService_forkExec(
        JNIEnv *env, jobject thiz, jobjectArray cmdArray,
        jint pipeFd) {
    int argc = (*env)->GetArrayLength(env, cmdArray);
    char **argv = (char **) malloc((argc + 1) * sizeof(char *));
    for (int i = 0; i < argc; i++) {
        jstring s = (jstring)(*env)->GetObjectArrayElement(env, cmdArray, i);
        const char *cs = (*env)->GetStringUTFChars(env, s, NULL);
        argv[i] = strdup(cs);
        (*env)->ReleaseStringUTFChars(env, s, cs);
        (*env)->DeleteLocalRef(env, s);
    }
    argv[argc] = NULL;

    pid_t pid = fork();
    if (pid == 0) {
        // stdout و stderr رو به pipe بفرست
        dup2(pipeFd, STDOUT_FILENO);
        dup2(pipeFd, STDERR_FILENO);
        close(pipeFd);
        execv(argv[0], argv);
        _exit(127);
    }

    for (int i = 0; i < argc; i++) free(argv[i]);
    free(argv);
    return (jint) pid;
}

JNIEXPORT void JNICALL
Java_com_github_derakula_TunnelVpnService_killPid(
        JNIEnv *env, jobject thiz, jint pid) {
    if (pid > 0) kill((pid_t) pid, SIGKILL);
}