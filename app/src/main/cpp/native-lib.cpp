// JNI bridge for nodejs-mobile: boots the embedded Yaxin Box Node server in-process.
// Implements com.fongmi.android.tv.yaxin.NodeRuntime.startNodeWithArguments(String[]).
//
// Requires libnode.so (per ABI) + node headers from a nodejs-mobile release — see
// docs SETUP_NODEJS_MOBILE.md. Modeled on the official nodejs-mobile Android sample.
#include <jni.h>
#include <android/log.h>
#include <pthread.h>
#include <unistd.h>
#include <cstring>
#include <string>
#include <vector>

#include "node.h"

#define LOG_TAG "yaxin-node"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)

// Pipe stdout/stderr to logcat so server logs are visible.
static int out_pipe[2];
static pthread_t log_thread;

static void *log_pump(void *) {
    ssize_t n;
    char buf[1024];
    while ((n = read(out_pipe[0], buf, sizeof(buf) - 1)) > 0) {
        if (buf[n - 1] == '\n') --n;
        buf[n] = 0;
        LOGD("%s", buf);
    }
    return nullptr;
}

static void start_log_pump() {
    setvbuf(stdout, nullptr, _IOLBF, 0);
    setvbuf(stderr, nullptr, _IONBF, 0);
    pipe(out_pipe);
    dup2(out_pipe[1], STDOUT_FILENO);
    dup2(out_pipe[1], STDERR_FILENO);
    pthread_create(&log_thread, nullptr, log_pump, nullptr);
    pthread_detach(log_thread);
}

extern "C" JNIEXPORT jint JNICALL
Java_com_fongmi_android_tv_yaxin_NodeRuntime_startNodeWithArguments(
        JNIEnv *env, jclass clazz, jobjectArray arguments) {
    start_log_pump();

    jsize argc = env->GetArrayLength(arguments);
    std::vector<std::string> args;
    args.reserve(argc);
    for (jsize i = 0; i < argc; i++) {
        auto s = (jstring) env->GetObjectArrayElement(arguments, i);
        const char *c = env->GetStringUTFChars(s, nullptr);
        args.emplace_back(c);
        env->ReleaseStringUTFChars(s, c);
        env->DeleteLocalRef(s);
    }

    std::vector<char *> argv;
    argv.reserve(args.size() + 1);
    for (auto &a : args) argv.push_back(&a[0]);
    argv.push_back(nullptr);

    // Blocks until the Node event loop exits (call from a background thread).
    return (jint) node::Start((int) args.size(), argv.data());
}
