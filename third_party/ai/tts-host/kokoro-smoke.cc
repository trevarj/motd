#include <atomic>
#include <chrono>
#include <cmath>
#include <condition_variable>
#include <exception>
#include <filesystem>
#include <future>
#include <iostream>
#include <memory>
#include <mutex>
#include <stdexcept>
#include <string>
#include <thread>

#include "sherpa-onnx/csrc/offline-tts.h"
#include "sherpa-onnx/csrc/wave-writer.h"

namespace {
void Require(bool condition, const char *message) {
  if (!condition) throw std::runtime_error(message);
}

void Save(const sherpa_onnx::GeneratedAudio &audio,
          const std::filesystem::path &output) {
  Require(audio.sample_rate == 24000, "Wrong Kokoro sample rate");
  Require(audio.samples.size() >= 2400, "Speech is shorter than 100 ms");
  double energy = 0;
  for (float sample : audio.samples) {
    Require(std::isfinite(sample), "Non-finite PCM sample");
    energy += static_cast<double>(sample) * sample;
  }
  Require(energy / audio.samples.size() > 1e-7, "Generated speech is silent");
  Require(sherpa_onnx::WriteWave(output.string(), audio.sample_rate,
                               audio.samples.data(), audio.samples.size()),
          "Could not complete speech WAV");
  Require(std::filesystem::file_size(output) == 44 + audio.samples.size() * 2,
          "Speech WAV was not fully written");
  std::cout << output << ": sample_rate=" << audio.sample_rate
            << " samples=" << audio.samples.size() << std::endl;
}
}  // namespace

int main(int argc, char **argv) {
  try {
    Require(argc == 3, "Usage: motd-kokoro-smoke CHECKED_ASSETS OUTPUT_DIRECTORY");
    const std::filesystem::path assets(argv[1]), output(argv[2]);
    std::filesystem::create_directories(output);
    sherpa_onnx::OfflineTtsConfig config;
    config.model.kokoro.model = (assets / "model.int8.onnx").string();
    config.model.kokoro.voices = (assets / "voices.bin").string();
    config.model.kokoro.tokens = (assets / "tokens.txt").string();
    config.model.kokoro.data_dir = (assets / "espeak-ng-data").string();
    config.model.kokoro.lexicon = (assets / "lexicon-us-en.txt").string() + "," +
                                  (assets / "lexicon-gb-en.txt").string();
    config.model.kokoro.lang = "en-us";
    config.model.num_threads = 1;
    config.model.provider = "cpu";
    config.max_num_sentences = 1;
    Require(config.Validate(), "Invalid Kokoro assets/configuration");
    auto tts = std::make_unique<sherpa_onnx::OfflineTts>(config);
    Require(tts->SampleRate() == 24000 && tts->NumSpeakers() == 54,
            "Expected the pinned 54-voice 24 kHz model");
    sherpa_onnx::GenerationConfig generation;
    generation.extra["lang"] = "en-us";
    generation.speed = 1.0f;
    generation.sid = 3;  // Heart, female.
    const std::string sender = "Alex says, the synthetic chat reader is ready.";
    auto female = tts->Generate(sender, generation);
    Save(female, output / "female-heart-sender.wav");
    generation.speed = 1.5f;
    Save(tts->Generate(sender, generation), output / "female-heart-sender-fast.wav");
    generation.speed = 1.0f;
    Save(tts->Generate("Alex waves hello to the synthetic test channel.", generation),
         output / "female-heart-action.wav");
    generation.sid = 16;  // Michael, male.
    auto male = tts->Generate(sender, generation);
    Save(male, output / "male-michael-sender.wav");
    Require(female.samples != male.samples, "Distinct speaker IDs produced identical PCM");
    Save(tts->Generate("Alex waves hello to the synthetic test channel.", generation),
         output / "male-michael-action.wav");
    generation.sid = 21;  // Emma, British female, with real British pronunciation.
    generation.extra["lang"] = "en";  // eSpeak filename for English (Great Britain).
    Save(tts->Generate(sender, generation), output / "female-emma-british.wav");

    generation.sid = 3;
    generation.extra["lang"] = "en-us";
    const std::string long_text =
        "Alex says, this is the first synthetic sentence. "
        "The next sentence must not be synthesized after cancellation. "
        "Neither should the third sentence reach playback. "
        "The fourth sentence is for a completed-length comparison.";
    auto full = tts->Generate(long_text, generation);
    std::atomic<bool> cancelled{false}, returned{false};
    std::atomic<int> callbacks{0};
    std::promise<void> first_chunk;
    auto first = first_chunk.get_future();
    std::mutex mutex;
    std::condition_variable cancellation;
    sherpa_onnx::GeneratedAudio partial;
    std::exception_ptr failure;
    std::thread worker([&] {
      try {
        partial = tts->Generate(long_text, generation,
            [&](const float *, int32_t, float) -> int32_t {
              if (callbacks.fetch_add(1) == 0) first_chunk.set_value();
              std::unique_lock<std::mutex> lock(mutex);
              cancellation.wait(lock, [&] { return cancelled.load(); });
              return 0;  // No exception through the generation callback.
            });
      } catch (...) {
        failure = std::current_exception();
      }
      returned.store(true);
    });
    const bool saw_chunk = first.wait_for(std::chrono::seconds(120)) == std::future_status::ready;
    {
      std::lock_guard<std::mutex> lock(mutex);
      cancelled.store(true);
    }
    cancellation.notify_all();
    worker.join();  // The native owner stays alive until its synchronous call actually drains.
    if (failure) std::rethrow_exception(failure);
    Require(saw_chunk && returned.load() && callbacks.load() == 1,
            "Callback cancellation did not stop and drain at the first chunk");
    Require(partial.samples.size() < full.samples.size(),
            "Cancelled generation retained the complete utterance");
    // Discard partial PCM, never publish a cancelled WAV. A subsequent real call proves reuse.
    partial.samples.clear();
    Save(tts->Generate("Alex says, synthesis safely resumed after cancellation.", generation),
         output / "after-cancel.wav");
    tts.reset();
    std::cout << "cancelled=1 callbacks=1 worker_drained=1 owner_released=1" << std::endl;
    return 0;
  } catch (const std::exception &failure) {
    std::cerr << failure.what() << std::endl;
    return 1;
  }
}
