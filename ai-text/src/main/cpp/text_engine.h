#pragma once
#include <atomic>
#include <memory>
#include <stdexcept>
#include <string>
#include <string_view>
#ifdef MOTD_HOST_SMOKE
#include <functional>
#endif

namespace motd::text {
enum class Operation : int { Correct=0, Formal=1, Business=2, Silly=3, Custom=4, Translate=5 };
enum class Termination : int { Eog=0, OutputLimit=1 };
enum class Error : int {
    ModelOpen=1, InvalidFormat=2, CorruptModel=3, UnsupportedArchitecture=4,
    UnsupportedTemplate=5, InvalidRequest=6, InputTooLong=7, OutOfMemory=8,
    NoModelLoaded=9, Inference=10, InvalidOutput=11, Native=12
};
class Exception final : public std::exception {
public:
    explicit Exception(Error code) noexcept : code(code) {}
    const char* what() const noexcept override { return "Local text operation failed"; }
    const Error code;
};
class Cancelled final : public std::exception {
public:
    const char* what() const noexcept override { return "Local text operation cancelled"; }
};
struct Cancellation {
    std::atomic_bool requested{false};
#ifdef MOTD_HOST_SMOKE
    enum class Stage { Load, Prefill, Decode };
    std::function<void(Stage)> barrier;
#endif
};
struct Request { Operation operation; std::string_view text, instruction, target_language; int cpu_threads; };
struct Result { std::string text; Termination termination; };
struct ModelInfo { std::string architecture, quantization, template_id; int context_tokens, maximum_cpu_threads; };
class Engine final {
public:
    Engine();
    ~Engine();
    Engine(const Engine&) = delete;
    Engine& operator=(const Engine&) = delete;
    ModelInfo inspect(std::string_view path, Cancellation& cancellation);
    ModelInfo load(std::string_view path, int cpu_threads, Cancellation& cancellation);
    Result transform(const Request& request, Cancellation& cancellation);
    void unload();
#ifdef MOTD_HOST_SMOKE
    void self_checks(const std::string& path);
#endif
private:
    struct State;
    std::unique_ptr<State> state;
};
}
