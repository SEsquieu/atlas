#include <jni.h>
#include <quickjs.h>
#include <chrono>
#include <cmath>
#include <string>

namespace {
using Clock = std::chrono::steady_clock;
struct Run { JSValue files; Clock::time_point deadline; bool timed_out = false; };
int interrupt(JSRuntime*, void* opaque) {
    auto* run = static_cast<Run*>(opaque);
    if (Clock::now() >= run->deadline) run->timed_out = true;
    return run->timed_out ? 1 : 0;
}
JSModuleDef* load(JSContext* ctx, const char* name, void* opaque) {
    // QuickJS resolves relative imports. No host filesystem or network fallback exists.
    auto* run = static_cast<Run*>(opaque);
    JSValue source = JS_GetPropertyStr(ctx, run->files, name);
    if (!JS_IsString(source)) {
        JS_FreeValue(ctx, source);
        JS_ThrowReferenceError(ctx, "Module is not packaged: %s", name);
        return nullptr;
    }
    size_t length = 0;
    const char* code = JS_ToCStringLen(ctx, &length, source);
    JSValue compiled = code ? JS_Eval(ctx, code, length, name, JS_EVAL_TYPE_MODULE | JS_EVAL_FLAG_COMPILE_ONLY) : JS_EXCEPTION;
    if (code) JS_FreeCString(ctx, code);
    JS_FreeValue(ctx, source);
    if (JS_IsException(compiled)) return nullptr;
    auto* module = static_cast<JSModuleDef*>(JS_VALUE_GET_PTR(compiled));
    JS_FreeValue(ctx, compiled);
    return module;
}
std::string bytes(JNIEnv* env, jbyteArray value) {
    const jsize size = env->GetArrayLength(value);
    std::string result(size, '\0');
    env->GetByteArrayRegion(value, 0, size, reinterpret_cast<jbyte*>(result.data()));
    return result;
}
jbyteArray output(JNIEnv* env, const std::string& value) {
    auto result = env->NewByteArray(static_cast<jsize>(value.size()));
    if (result) env->SetByteArrayRegion(result, 0, static_cast<jsize>(value.size()), reinterpret_cast<const jbyte*>(value.data()));
    return result;
}
JSValue await(JSContext* ctx, JSRuntime* rt, JSValue value, Run& run) {
    while (!JS_IsException(value) && JS_PromiseState(ctx, value) == JS_PROMISE_PENDING) {
        if (interrupt(rt, &run)) { JS_FreeValue(ctx, value); return JS_ThrowInternalError(ctx, "Evaluation timed out"); }
        JSContext* job_ctx = nullptr;
        int status = JS_ExecutePendingJob(rt, &job_ctx);
        if (status < 0) { JS_FreeValue(ctx, value); return JS_EXCEPTION; }
        if (status == 0) { JS_FreeValue(ctx, value); return JS_ThrowInternalError(ctx, "Promise cannot settle without an unavailable host capability"); }
    }
    if (JS_IsException(value)) return value;
    const auto state = JS_PromiseState(ctx, value);
    if (state == JS_PROMISE_FULFILLED || state == JS_PROMISE_REJECTED) {
        JSValue settled = JS_PromiseResult(ctx, value);
        JS_FreeValue(ctx, value);
        return state == JS_PROMISE_REJECTED ? JS_Throw(ctx, settled) : settled;
    }
    return value;
}
bool json_value(JSContext* ctx, JSValueConst value, Run& run, int depth = 0) {
    if (interrupt(JS_GetRuntime(ctx), &run) || depth > 32) return false;
    if (JS_IsNull(value) || JS_IsBool(value) || JS_IsString(value)) return true;
    if (JS_IsNumber(value)) {
        double number = 0;
        return JS_ToFloat64(ctx, &number, value) == 0 && std::isfinite(number) && std::abs(number) <= 9007199254740991.0;
    }
    if (!JS_IsObject(value) || JS_IsFunction(ctx, value)) return false;
    JSValue to_json = JS_GetPropertyStr(ctx, value, "toJSON");
    const bool custom_json = !JS_IsUndefined(to_json);
    JS_FreeValue(ctx, to_json);
    if (custom_json) return false;
    if (JS_IsArray(value)) {
        JSValue length_value = JS_GetPropertyStr(ctx, value, "length");
        uint32_t length = 0;
        const bool length_ok = JS_ToUint32(ctx, &length, length_value) == 0 && length <= 10000;
        JS_FreeValue(ctx, length_value);
        if (!length_ok) return false;
        for (uint32_t i = 0; i < length; ++i) {
            JSValue child = JS_GetPropertyUint32(ctx, value, i);
            bool valid = !JS_IsException(child) && json_value(ctx, child, run, depth + 1);
            JS_FreeValue(ctx, child);
            if (!valid) return false;
        }
        return true;
    }
    JSPropertyEnum* properties = nullptr;
    uint32_t count = 0;
    if (JS_GetOwnPropertyNames(ctx, &properties, &count, value, JS_GPN_STRING_MASK | JS_GPN_ENUM_ONLY) < 0) return false;
    bool valid = count <= 10000;
    for (uint32_t i = 0; i < count; ++i) {
        JSValue child = valid ? JS_GetProperty(ctx, value, properties[i].atom) : JS_UNDEFINED;
        if (valid) valid = !JS_IsException(child) && json_value(ctx, child, run, depth + 1);
        JS_FreeValue(ctx, child);
        JS_FreeAtom(ctx, properties[i].atom);
    }
    js_free(ctx, properties);
    return valid;
}
std::string failure(JSContext* ctx, const char* code) {
    JSValue exception = JS_GetException(ctx);
    const char* text = JS_ToCString(ctx, exception);
    std::string summary = text ? text : "JavaScript evaluation failed";
    if (summary.size() > 1024) summary = "JavaScript evaluation failed; error text exceeds limit";
    std::string quoted;
    const char* hex = "0123456789abcdef";
    for (unsigned char c : summary) {
        if (c == '"' || c == '\\') { quoted += '\\'; quoted += static_cast<char>(c); }
        else if (c < 32) { quoted += "\\u00"; quoted += hex[c >> 4]; quoted += hex[c & 15]; }
        else quoted += static_cast<char>(c);
    }
    if (text) JS_FreeCString(ctx, text);
    JS_FreeValue(ctx, exception);
    return std::string("{\"ok\":false,\"code\":\"") + code + "\",\"summary\":\"" + quoted + "\"}";
}
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_grinningfrog_atlas_workspace_application_WorkspaceNativeEngine_evaluateNative(
    JNIEnv* env, jobject, jbyteArray files_json, jbyteArray module_path, jbyteArray export_name, jbyteArray input_json) {
    // Byte arrays preserve actual UTF-8, avoiding JNI modified-UTF-8 corruption of emoji.
    const std::string files = bytes(env, files_json), module = bytes(env, module_path), name = bytes(env, export_name), input = bytes(env, input_json);
    if (files.size() > 1024 * 1024 || input.size() > 65536 || module.size() > 200 || name.size() > 64)
        return output(env, "{\"ok\":false,\"code\":\"QUOTA_EXCEEDED\",\"summary\":\"Worker payload limit\"}");
    JSRuntime* rt = JS_NewRuntime();
    if (!rt) return output(env, "{\"ok\":false,\"code\":\"QUOTA_EXCEEDED\",\"summary\":\"Runtime allocation failed\"}");
    JS_SetMemoryLimit(rt, 32 * 1024 * 1024);
    JS_SetMaxStackSize(rt, 256 * 1024);
    JSContext* ctx = JS_NewContext(rt);
    if (!ctx) { JS_FreeRuntime(rt); return output(env, "{\"ok\":false,\"code\":\"QUOTA_EXCEEDED\",\"summary\":\"Context allocation failed\"}"); }
    Run run{JS_ParseJSON(ctx, files.data(), files.size(), "files.json"), Clock::now() + std::chrono::milliseconds(1000)};
    JS_SetInterruptHandler(rt, interrupt, &run);
    JS_SetModuleLoaderFunc(rt, nullptr, load, &run);
    JSValue code = JS_GetPropertyStr(ctx, run.files, module.c_str());
    size_t size = 0;
    const char* source = JS_IsString(code) ? JS_ToCStringLen(ctx, &size, code) : nullptr;
    JSValue compiled = source ? JS_Eval(ctx, source, size, module.c_str(), JS_EVAL_TYPE_MODULE | JS_EVAL_FLAG_COMPILE_ONLY) : JS_ThrowReferenceError(ctx, "Entry module is missing");
    if (source) JS_FreeCString(ctx, source);
    JS_FreeValue(ctx, code);
    std::string result;
    if (!JS_IsException(compiled)) {
        auto* module_def = static_cast<JSModuleDef*>(JS_VALUE_GET_PTR(compiled));
        JSValue evaluated = await(ctx, rt, JS_EvalFunction(ctx, compiled), run); // consumes compiled
        if (!JS_IsException(evaluated)) {
            JSValue ns = JS_GetModuleNamespace(ctx, module_def);
            JSValue function = JS_GetPropertyStr(ctx, ns, name.c_str());
            JSValue arg = JS_ParseJSON(ctx, input.data(), input.size(), "input.json");
            JSValue value = JS_IsFunction(ctx, function) && !JS_IsException(arg) ? await(ctx, rt, JS_Call(ctx, function, JS_UNDEFINED, 1, &arg), run) : JS_ThrowTypeError(ctx, "Export is not callable or input is invalid");
            if (!JS_IsException(value) && json_value(ctx, value, run)) {
                JSValue serialized = JS_JSONStringify(ctx, value, JS_UNDEFINED, JS_UNDEFINED);
                const char* raw = JS_IsException(serialized) ? nullptr : JS_ToCStringLen(ctx, &size, serialized);
                if (raw && size <= 64512) result = "{\"ok\":true,\"value\":" + std::string(raw, size) + "}";
                else JS_ThrowInternalError(ctx, "Result exceeds the JSON output limit");
                if (raw) JS_FreeCString(ctx, raw);
                JS_FreeValue(ctx, serialized);
            } else if (!JS_IsException(value)) JS_ThrowTypeError(ctx, "Export did not return strict JSON");
            JS_FreeValue(ctx, value); JS_FreeValue(ctx, arg); JS_FreeValue(ctx, function); JS_FreeValue(ctx, ns);
        }
        JS_FreeValue(ctx, evaluated);
    }
    if (result.empty()) result = failure(ctx, run.timed_out ? "TIMEOUT" : "EXECUTION_FAILED");
    JS_FreeValue(ctx, run.files);
    JS_FreeContext(ctx);
    JS_FreeRuntime(rt);
    return output(env, result);
}
