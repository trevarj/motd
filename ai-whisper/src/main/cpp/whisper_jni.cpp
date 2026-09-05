#include <jni.h>
#include <ggml.h>
#include <whisper.h>

#include <algorithm>
#include <array>
#include <atomic>
#include <cstdint>
#include <cstring>
#include <fstream>
#include <limits>
#include <memory>
#include <mutex>
#include <stdexcept>
#include <string>
#include <thread>
#include <unordered_map>
#include <utility>
#include <vector>

extern "C" bool motd_whisper_model_load_allocation_failed() noexcept;

namespace {
constexpr uint64_t MAX_MODEL = 8ULL << 30;
constexpr uint64_t MAX_SAMPLES = 15ULL * 60 * WHISPER_SAMPLE_RATE;
constexpr size_t MAX_PATH = 16384, MAX_LANGUAGE = 64, MAX_PROMPT = 65536, MAX_TEXT = 1048576;

enum class Kind { OPEN, MAGIC, TRUNCATED, CORRUPT, UNSUPPORTED, AUDIO, REQUEST, NO_MODEL, TRANSCRIBE, OOM, NATIVE, CANCELLED };
class Failure final : public std::runtime_error {
public:
    Failure(Kind kind, const char * message) : std::runtime_error(message), kind(kind) {}
    Kind kind;
};

std::mutex request_mutex, model_mutex;
std::unordered_map<jlong, std::shared_ptr<std::atomic_bool>> requests;
whisper_context * resident = nullptr;

const char * exception_name(Kind kind) {
    switch (kind) {
        case Kind::OPEN: return "io/github/trevarj/motd/ai/whisper/WhisperModelOpenException";
        case Kind::MAGIC: return "io/github/trevarj/motd/ai/whisper/WhisperModelMagicException";
        case Kind::TRUNCATED: return "io/github/trevarj/motd/ai/whisper/WhisperTruncatedModelException";
        case Kind::CORRUPT: return "io/github/trevarj/motd/ai/whisper/WhisperCorruptModelException";
        case Kind::UNSUPPORTED: return "io/github/trevarj/motd/ai/whisper/WhisperUnsupportedModelException";
        case Kind::AUDIO: return "io/github/trevarj/motd/ai/whisper/WhisperAudioException";
        case Kind::REQUEST: return "io/github/trevarj/motd/ai/whisper/WhisperInvalidRequestException";
        case Kind::NO_MODEL: return "io/github/trevarj/motd/ai/whisper/WhisperNoModelLoadedException";
        case Kind::TRANSCRIBE: return "io/github/trevarj/motd/ai/whisper/WhisperTranscriptionException";
        case Kind::OOM: return "io/github/trevarj/motd/ai/whisper/WhisperOutOfMemoryException";
        case Kind::CANCELLED: return "io/github/trevarj/motd/ai/whisper/WhisperCancellationException";
        default: return "io/github/trevarj/motd/ai/whisper/WhisperNativeException";
    }
}
void raise(JNIEnv * env, Kind kind, const char * message) noexcept {
    if (env->ExceptionCheck()) return;
    jclass cls = env->FindClass(exception_name(kind));
    if (!cls) { env->ExceptionClear(); cls = env->FindClass("java/lang/RuntimeException"); }
    if (cls) { env->ThrowNew(cls, message); env->DeleteLocalRef(cls); }
}
template<class R, class F> R guard(JNIEnv * env, R fallback, F && body) noexcept {
    try { return body(); }
    catch (const Failure & e) { raise(env, e.kind, e.what()); }
    catch (const std::bad_alloc &) { raise(env, Kind::OOM, "Whisper could not allocate enough memory"); }
    catch (...) { raise(env, Kind::NATIVE, "Native Whisper operation failed"); }
    return fallback;
}
template<class F> void guard_void(JNIEnv * env, F && body) noexcept {
    guard<int>(env, 0, [&] { body(); return 0; });
}
void cancelled(const std::shared_ptr<std::atomic_bool> & flag) {
    if (flag->load(std::memory_order_relaxed)) throw Failure(Kind::CANCELLED, "Whisper request cancelled");
}
std::shared_ptr<std::atomic_bool> flag_for(jlong id) {
    std::lock_guard<std::mutex> lock(request_mutex);
    auto it = requests.find(id);
    if (it == requests.end()) throw Failure(Kind::NATIVE, "Whisper request is not registered");
    return it->second;
}
std::string bytes(JNIEnv * env, jbyteArray array, size_t maximum, Kind kind, const char * empty, const char * too_long) {
    if (!array) throw Failure(kind, empty);
    jsize size = env->GetArrayLength(array);
    if (size <= 0) throw Failure(kind, empty);
    if (static_cast<size_t>(size) > maximum) throw Failure(kind, too_long);
    std::string value(static_cast<size_t>(size), '\0');
    env->GetByteArrayRegion(array, 0, size, reinterpret_cast<jbyte *>(value.data()));
    if (env->ExceptionCheck()) { env->ExceptionClear(); throw Failure(Kind::OOM, "Whisper could not read a Kotlin value"); }
    if (value.find('\0') != std::string::npos) throw Failure(kind, "Whisper input contains an embedded NUL byte");
    return value;
}

class Reader final {
public:
    Reader(const std::string & path, Kind open, Kind short_read, std::shared_ptr<std::atomic_bool> flag)
        : file(path, std::ios::binary), short_kind(short_read), flag(std::move(flag)) {
        if (!file) throw Failure(open, open == Kind::AUDIO ? "Unable to open PCM WAV input" : "Unable to open Whisper model");
        file.seekg(0, std::ios::end); auto end = file.tellg();
        if (end < 0) throw Failure(open, "Unable to read input file");
        length = static_cast<uint64_t>(end); file.seekg(0);
    }
    uint64_t size() const { return length; }
    uint64_t pos() const { return offset; }
    uint64_t left() const { return length - offset; }
    void read(void * out, size_t count) {
        cancelled(flag);
        if (count > left()) fail();
        file.read(static_cast<char *>(out), static_cast<std::streamsize>(count));
        if (static_cast<size_t>(file.gcount()) != count) fail();
        offset += count;
    }
    uint16_t u16() { std::array<uint8_t,2> b{}; read(b.data(),2); return b[0] | static_cast<uint16_t>(b[1]) << 8; }
    uint32_t u32() { std::array<uint8_t,4> b{}; read(b.data(),4); return b[0] | uint32_t(b[1])<<8 | uint32_t(b[2])<<16 | uint32_t(b[3])<<24; }
    int32_t i32() { return static_cast<int32_t>(u32()); }
    void skip(uint64_t count) {
        cancelled(flag);
        if (count > left() || count > uint64_t(std::numeric_limits<std::streamoff>::max())) fail();
        file.seekg(static_cast<std::streamoff>(count), std::ios::cur); if (!file) fail(); offset += count;
    }
private:
    [[noreturn]] void fail() { throw Failure(short_kind, short_kind == Kind::AUDIO ? "PCM WAV input is truncated" : "Whisper model is truncated"); }
    std::ifstream file; Kind short_kind; std::shared_ptr<std::atomic_bool> flag; uint64_t length=0, offset=0;
};
uint64_t mul(uint64_t a, uint64_t b) {
    if (b && a > std::numeric_limits<uint64_t>::max()/b) throw Failure(Kind::CORRUPT, "Whisper model size is corrupt");
    return a*b;
}
uint64_t add(uint64_t a, uint64_t b) {
    if (a > std::numeric_limits<uint64_t>::max()-b) throw Failure(Kind::CORRUPT, "Whisper model size is corrupt");
    return a+b;
}
uint64_t row_bytes(ggml_type type, uint64_t elements) {
    auto block=ggml_blck_size(type);
    if (block<=0 || elements%static_cast<uint64_t>(block)) throw Failure(Kind::CORRUPT, "Whisper model dimensions are incompatible with its quantization");
    return mul(elements/static_cast<uint64_t>(block),ggml_type_size(type));
}
bool ftype_ok(int value) {
    switch (static_cast<ggml_ftype>(value)) {
        case GGML_FTYPE_ALL_F32: case GGML_FTYPE_MOSTLY_F16: case GGML_FTYPE_MOSTLY_Q4_0:
        case GGML_FTYPE_MOSTLY_Q4_1: case GGML_FTYPE_MOSTLY_Q8_0: case GGML_FTYPE_MOSTLY_Q5_0:
        case GGML_FTYPE_MOSTLY_Q5_1: case GGML_FTYPE_MOSTLY_Q2_K: case GGML_FTYPE_MOSTLY_Q3_K:
        case GGML_FTYPE_MOSTLY_Q4_K: case GGML_FTYPE_MOSTLY_Q5_K: case GGML_FTYPE_MOSTLY_Q6_K:
        case GGML_FTYPE_MOSTLY_IQ2_XXS: case GGML_FTYPE_MOSTLY_IQ2_XS: case GGML_FTYPE_MOSTLY_IQ3_XXS:
        case GGML_FTYPE_MOSTLY_IQ1_S: case GGML_FTYPE_MOSTLY_IQ4_NL: case GGML_FTYPE_MOSTLY_IQ3_S:
        case GGML_FTYPE_MOSTLY_IQ2_S: case GGML_FTYPE_MOSTLY_IQ4_XS: case GGML_FTYPE_MOSTLY_IQ1_M:
        case GGML_FTYPE_MOSTLY_BF16: case GGML_FTYPE_MOSTLY_MXFP4: case GGML_FTYPE_MOSTLY_NVFP4:
        case GGML_FTYPE_MOSTLY_Q1_0: case GGML_FTYPE_MOSTLY_Q2_0: return true;
        default: return false;
    }
}
bool tensor_type_ok(int value) {
    return value >= 0 && value < GGML_TYPE_COUNT && value != 4 && value != 5 &&
        value != 31 && value != 32 && value != 33 && value != 36 && value != 37 && value != 38;
}
void validate_model(const std::string & path, const std::shared_ptr<std::atomic_bool> & flag) {
    Reader r(path, Kind::OPEN, Kind::TRUNCATED, flag);
    if (r.size() > MAX_MODEL) throw Failure(Kind::UNSUPPORTED, "Whisper model exceeds the 8 GiB limit");
    if (r.size() < 48) throw Failure(Kind::TRUNCATED, "Whisper model is truncated");
    if (r.u32() != GGML_FILE_MAGIC) throw Failure(Kind::MAGIC, "File is not a whisper.cpp model");
    int vocab=r.i32(), actx=r.i32(), state=r.i32(), ahead=r.i32(), alayers=r.i32();
    int tctx=r.i32(), tstate=r.i32(), thead=r.i32(), tlayers=r.i32(), mels=r.i32(), raw_type=r.i32();
    bool sane = vocab>=1000 && vocab<=100000 && actx>0 && actx<=4096 && state>0 && state<=2048 &&
        ahead>0 && ahead<=128 && state%ahead==0 && tctx>0 && tctx<=4096 && tstate==state &&
        thead>0 && thead<=128 && state%thead==0 && tlayers>0 && tlayers<=64 && (mels==80 || mels==128);
    if (!sane) throw Failure(Kind::CORRUPT, "Whisper model header is corrupt");
    if (alayers!=4 && alayers!=6 && alayers!=12 && alayers!=24 && alayers!=32) throw Failure(Kind::UNSUPPORTED, "Whisper model architecture is unsupported");
    if (raw_type<0 || raw_type/GGML_QNT_VERSION_FACTOR>GGML_QNT_VERSION || !ftype_ok(raw_type%GGML_QNT_VERSION_FACTOR)) throw Failure(Kind::UNSUPPORTED, "Whisper model quantization is unsupported");
    auto wtype=ggml_ftype_to_ggml_type(static_cast<ggml_ftype>(raw_type%GGML_QNT_VERSION_FACTOR));
    const uint64_t a=state,t=tstate,ca=actx,ct=tctx,v=vocab,m=mels;
    const uint64_t row_state=row_bytes(wtype,a),row_wide=row_bytes(wtype,mul(4,a));
    const uint64_t vbytes=wtype==GGML_TYPE_F32?4:2;
    uint64_t declared_tensor_bytes=0;
    auto account=[&](uint64_t bytes){declared_tensor_bytes=add(declared_tensor_bytes,bytes);};
    account(mul(mul(4,a),ca));
    account(mul(mul(mul(3,vbytes),a),add(m,a)));
    account(mul(16,a));
    account(mul(mul(4,t),ct));
    account(mul(row_state,v));
    account(mul(8,t));
    account(mul(alayers,add(mul(48,a),add(mul(mul(8,a),row_state),mul(a,row_wide)))));
    account(mul(tlayers,add(mul(68,t),add(mul(mul(12,t),row_state),mul(t,row_wide)))));
    if (declared_tensor_bytes>MAX_MODEL) throw Failure(Kind::CORRUPT,"Whisper model dimensions exceed the 8 GiB model limit");
    int fmels=r.i32(), fft=r.i32();
    if (fmels!=mels || fft!=WHISPER_N_FFT/2+1) throw Failure(Kind::CORRUPT, "Whisper model filters are corrupt");
    r.skip(mul(mul(fmels,fft),sizeof(float)));
    int stored_vocab=r.i32();
    if (stored_vocab<=0 || stored_vocab>vocab) throw Failure(Kind::CORRUPT, "Whisper model vocabulary is corrupt");
    for (int i=0;i<stored_vocab;i++) { uint32_t n=r.u32(); if (n>1048576) throw Failure(Kind::CORRUPT,"Whisper model vocabulary is corrupt"); r.skip(n); }
    if (declared_tensor_bytes>r.left()) throw Failure(Kind::TRUNCATED,"Whisper model tensor data is truncated");
    uint64_t tensor_bytes=0;
    int tensors=0;
    while (r.left()) {
        if (r.left()<12) throw Failure(Kind::TRUNCATED,"Whisper model is truncated");
        int dims=r.i32(), name=r.i32(), type_value=r.i32();
        if (dims<=0 || dims>GGML_MAX_DIMS || name<=0 || name>4096 || !tensor_type_ok(type_value)) throw Failure(Kind::CORRUPT,"Whisper model tensor metadata is corrupt");
        std::array<uint64_t,GGML_MAX_DIMS> shape{1,1,1,1}; uint64_t elements=1;
        for (int d=0;d<dims;d++) { int n=r.i32(); if (n<=0) throw Failure(Kind::CORRUPT,"Whisper model tensor shape is corrupt"); shape[d]=n; elements=mul(elements,n); if (elements>INT32_MAX) throw Failure(Kind::CORRUPT,"Whisper model tensor is too large"); }
        r.skip(name); auto type=static_cast<ggml_type>(type_value); uint64_t block=ggml_blck_size(type), unit=ggml_type_size(type);
        if (!block || !unit || shape[0]%block) throw Failure(Kind::CORRUPT,"Whisper model tensor type is corrupt");
        auto bytes=mul(elements/block,unit); tensor_bytes=add(tensor_bytes,bytes); r.skip(bytes); if (++tensors>10000) throw Failure(Kind::CORRUPT,"Whisper model has too many tensors");
    }
    if (tensors!=11+15*alayers+24*tlayers || tensor_bytes!=declared_tensor_bytes) throw Failure(Kind::CORRUPT,"Whisper model tensor layout is corrupt");
}

struct ModelInput { std::ifstream file; std::shared_ptr<std::atomic_bool> flag; bool short_read=false;
    ModelInput(const std::string & path,std::shared_ptr<std::atomic_bool> f):file(path,std::ios::binary),flag(std::move(f)){if(!file)throw Failure(Kind::OPEN,"Unable to open Whisper model");} };
size_t model_read(void * p,void * out,size_t wanted) {
    auto & in=*static_cast<ModelInput*>(p); size_t done=0;
    while(done<wanted){cancelled(in.flag);size_t n=std::min<size_t>(65536,wanted-done);in.file.read(static_cast<char*>(out)+done,n);size_t got=in.file.gcount();done+=got;if(got!=n){in.short_read=true;break;}}
    return done;
}
bool model_eof(void * p){return static_cast<ModelInput*>(p)->file.eof();}
void model_close(void * p){static_cast<ModelInput*>(p)->file.close();}
struct ContextDelete { void operator()(whisper_context * p)const noexcept{if(p)whisper_free(p);} };
using Context=std::unique_ptr<whisper_context,ContextDelete>;
Context open_model(const std::string & path,const std::shared_ptr<std::atomic_bool> & flag){
    ModelInput input(path,flag); whisper_model_loader loader{&input,model_read,model_eof,model_close};
    auto params=whisper_context_default_params();params.use_gpu=false;params.flash_attn=false;
    Context context(whisper_init_with_params(&loader,params));
    if(!context){if(flag->load())throw Failure(Kind::CANCELLED,"Whisper request cancelled");if(input.short_read)throw Failure(Kind::TRUNCATED,"Whisper model changed while loading");if(motd_whisper_model_load_allocation_failed())throw Failure(Kind::OOM,"Whisper could not allocate enough memory");throw Failure(Kind::CORRUPT,"Whisper could not load this model");}
    cancelled(flag);return context;
}
void free_model()noexcept{if(resident){whisper_free(resident);resident=nullptr;}}
int max_threads(){unsigned n=std::thread::hardware_concurrency();return std::clamp(n?int(n):1,1,32);}

jobject info(JNIEnv * env,whisper_context * model){
    jclass cls=env->FindClass("io/github/trevarj/motd/ai/whisper/WhisperModelInfo");
    if(!cls){env->ExceptionClear();throw Failure(Kind::NATIVE,"WhisperModelInfo class is unavailable");}
    jmethodID ctor=env->GetMethodID(cls,"<init>","(Ljava/lang/String;ZIIIILjava/lang/String;III)V");
    if(!ctor){env->ExceptionClear();env->DeleteLocalRef(cls);throw Failure(Kind::NATIVE,"WhisperModelInfo constructor is unavailable");}
    jstring model_type=env->NewStringUTF(whisper_model_type_readable(model));
    auto ftype=static_cast<ggml_ftype>(whisper_model_ftype(model));
    jstring quant=env->NewStringUTF(ggml_type_name(ggml_ftype_to_ggml_type(ftype)));
    if(!model_type||!quant||env->ExceptionCheck()){env->ExceptionClear();throw Failure(Kind::OOM,"Whisper could not allocate model metadata");}
    jobject result=env->NewObject(cls,ctor,model_type,jboolean(whisper_is_multilingual(model)!=0),
        jint(whisper_model_n_vocab(model)),jint(whisper_model_n_audio_ctx(model)),jint(whisper_model_n_text_ctx(model)),
        jint(whisper_model_n_mels(model)),quant,jint(WHISPER_SAMPLE_RATE),jint(MAX_SAMPLES/WHISPER_SAMPLE_RATE),jint(max_threads()));
    env->DeleteLocalRef(model_type);env->DeleteLocalRef(quant);env->DeleteLocalRef(cls);
    if(!result||env->ExceptionCheck()){env->ExceptionClear();throw Failure(Kind::OOM,"Whisper could not allocate model metadata");}return result;
}

struct Wav {uint64_t offset;uint32_t bytes;};
Wav inspect_wav(const std::string & path,const std::shared_ptr<std::atomic_bool> & flag){
    Reader r(path,Kind::AUDIO,Kind::AUDIO,flag);if(r.size()<44)throw Failure(Kind::AUDIO,"PCM WAV input is truncated");std::array<char,4> id{};
    r.read(id.data(),4);if(memcmp(id.data(),"RIFF",4))throw Failure(Kind::AUDIO,"Audio input must be RIFF WAV");uint32_t riff=r.u32();if(uint64_t(riff)+8!=r.size())throw Failure(Kind::AUDIO,"PCM WAV length is invalid");
    r.read(id.data(),4);if(memcmp(id.data(),"WAVE",4))throw Failure(Kind::AUDIO,"Audio input must be WAVE");bool fmt=false,data=false;Wav result{};
    while(r.left()){if(r.left()<8)throw Failure(Kind::AUDIO,"PCM WAV chunk is truncated");r.read(id.data(),4);uint32_t n=r.u32();
        if(!memcmp(id.data(),"fmt ",4)){if(fmt||n!=16)throw Failure(Kind::AUDIO,"PCM WAV format is invalid");fmt=true;uint16_t encoding=r.u16(),channels=r.u16();uint32_t rate=r.u32(),byte_rate=r.u32();uint16_t align=r.u16(),bits=r.u16();if(encoding!=1||channels!=1||rate!=WHISPER_SAMPLE_RATE||byte_rate!=WHISPER_SAMPLE_RATE*2||align!=2||bits!=16)throw Failure(Kind::AUDIO,"Audio must be mono 16 kHz PCM16 little-endian WAV");}
        else if(!memcmp(id.data(),"data",4)){if(!fmt||data)throw Failure(Kind::AUDIO,"PCM WAV data is invalid");data=true;result={r.pos(),n};r.skip(n);}else r.skip(n);if(n&1)r.skip(1);
    }
    if(!fmt||!data||!result.bytes||(result.bytes&1))throw Failure(Kind::AUDIO,"PCM WAV has no complete samples");if(uint64_t(result.bytes)/2>MAX_SAMPLES)throw Failure(Kind::AUDIO,"PCM WAV exceeds 15 minutes");return result;
}
std::vector<float> read_pcm(const std::string & path,Wav wav,const std::shared_ptr<std::atomic_bool> & flag){
    std::vector<float> pcm(wav.bytes/2);std::ifstream f(path,std::ios::binary);if(!f)throw Failure(Kind::AUDIO,"Unable to reopen PCM WAV");f.seekg(wav.offset);std::array<uint8_t,65536>b{};uint64_t left=wav.bytes;size_t dst=0;
    while(left){cancelled(flag);size_t n=std::min<uint64_t>(left,b.size());f.read(reinterpret_cast<char*>(b.data()),n);if(size_t(f.gcount())!=n)throw Failure(Kind::AUDIO,"PCM WAV changed while reading");for(size_t i=0;i<n;i+=2){uint16_t raw=b[i]|uint16_t(b[i+1])<<8;int sample=raw>=0x8000?int(raw)-0x10000:int(raw);pcm[dst++]=float(sample)/32768.0f;}left-=n;}return pcm;
}
struct Progress{JNIEnv*env;jobject callback;jmethodID accept;std::shared_ptr<std::atomic_bool>flag;jthrowable error=nullptr;int last=-1;};
void progress(Progress&p,int value)noexcept{if(p.error)return;value=std::clamp(value,0,100);if(value==p.last)return;p.last=value;p.env->CallVoidMethod(p.callback,p.accept,jint(value));if(p.env->ExceptionCheck()){p.error=p.env->ExceptionOccurred();p.env->ExceptionClear();p.flag->store(true);}}
void progress_callback(whisper_context*,whisper_state*,int value,void*p){progress(*static_cast<Progress*>(p),value);}
bool abort_callback(void*p){return static_cast<std::atomic_bool*>(p)->load();}
jbyteArray output(JNIEnv*env,const std::string&text){jbyteArray out=env->NewByteArray(jsize(text.size()));if(!out||env->ExceptionCheck()){env->ExceptionClear();throw Failure(Kind::OOM,"Whisper could not allocate transcript");}if(!text.empty())env->SetByteArrayRegion(out,0,jsize(text.size()),reinterpret_cast<const jbyte*>(text.data()));if(env->ExceptionCheck()){env->ExceptionClear();throw Failure(Kind::OOM,"Whisper could not copy transcript");}return out;}
void discard_log(ggml_log_level,const char*,void*){}
} // namespace

extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM*,void*){whisper_log_set(discard_log,nullptr);ggml_log_set(discard_log,nullptr);return JNI_VERSION_1_6;}
extern "C" JNIEXPORT void JNICALL JNI_OnUnload(JavaVM*,void*){std::lock_guard<std::mutex>m(model_mutex);free_model();std::lock_guard<std::mutex>r(request_mutex);requests.clear();}
extern "C" JNIEXPORT void JNICALL Java_io_github_trevarj_motd_ai_whisper_WhisperRuntime_nativeBegin(JNIEnv*env,jobject,jlong id){guard_void(env,[&]{if(id<=0)throw Failure(Kind::NATIVE,"Whisper request ID must be positive");auto flag=std::make_shared<std::atomic_bool>(false);std::lock_guard<std::mutex>lock(request_mutex);if(!requests.emplace(id,std::move(flag)).second)throw Failure(Kind::NATIVE,"Whisper request ID is already active");});}
extern "C" JNIEXPORT void JNICALL Java_io_github_trevarj_motd_ai_whisper_WhisperRuntime_nativeEnd(JNIEnv*env,jobject,jlong id){guard_void(env,[&]{std::lock_guard<std::mutex>lock(request_mutex);requests.erase(id);});}
extern "C" JNIEXPORT void JNICALL Java_io_github_trevarj_motd_ai_whisper_WhisperRuntime_nativeCancel(JNIEnv*env,jobject,jlong id){guard_void(env,[&]{std::lock_guard<std::mutex>lock(request_mutex);auto it=requests.find(id);if(it!=requests.end())it->second->store(true);});}
extern "C" JNIEXPORT jobject JNICALL Java_io_github_trevarj_motd_ai_whisper_WhisperRuntime_nativeInspect(JNIEnv*env,jobject,jlong id,jbyteArray path_bytes){return guard<jobject>(env,nullptr,[&]{auto flag=flag_for(id);auto path=bytes(env,path_bytes,MAX_PATH,Kind::OPEN,"Whisper model path is empty","Whisper model path is too long");std::lock_guard<std::mutex>lock(model_mutex);free_model();cancelled(flag);validate_model(path,flag);auto model=open_model(path,flag);return info(env,model.get());});}
extern "C" JNIEXPORT jobject JNICALL Java_io_github_trevarj_motd_ai_whisper_WhisperRuntime_nativeLoad(JNIEnv*env,jobject,jlong id,jbyteArray path_bytes){return guard<jobject>(env,nullptr,[&]{auto flag=flag_for(id);auto path=bytes(env,path_bytes,MAX_PATH,Kind::OPEN,"Whisper model path is empty","Whisper model path is too long");std::lock_guard<std::mutex>lock(model_mutex);cancelled(flag);validate_model(path,flag);free_model();auto model=open_model(path,flag);jobject result=info(env,model.get());resident=model.release();return result;});}
extern "C" JNIEXPORT jbyteArray JNICALL Java_io_github_trevarj_motd_ai_whisper_WhisperRuntime_nativeTranscribe(JNIEnv*env,jobject,jlong id,jbyteArray path_bytes,jbyteArray language_bytes,jbyteArray prompt_bytes,jint threads,jobject callback){return guard<jbyteArray>(env,nullptr,[&]{
    auto flag=flag_for(id);auto path=bytes(env,path_bytes,MAX_PATH,Kind::AUDIO,"PCM WAV path is empty","PCM WAV path is too long");auto language=bytes(env,language_bytes,MAX_LANGUAGE,Kind::REQUEST,"Whisper language is empty","Whisper language is too long");std::string prompt;if(prompt_bytes&&env->GetArrayLength(prompt_bytes))prompt=bytes(env,prompt_bytes,MAX_PROMPT,Kind::REQUEST,"","Whisper prompt is too long");if(threads<1||threads>max_threads())throw Failure(Kind::REQUEST,"Whisper thread count is outside limits");if(!callback)throw Failure(Kind::REQUEST,"Whisper progress callback is required");
    std::lock_guard<std::mutex>lock(model_mutex);cancelled(flag);if(!resident)throw Failure(Kind::NO_MODEL,"No Whisper model is loaded");if(language=="auto"){if(!whisper_is_multilingual(resident))language="en";}else{int lang=whisper_lang_id(language.c_str());if(lang<0)throw Failure(Kind::REQUEST,"Whisper language is unsupported");if(!whisper_is_multilingual(resident)&&lang!=whisper_lang_id("en"))throw Failure(Kind::REQUEST,"This Whisper model supports English only");}
    jclass cls=env->GetObjectClass(callback);jmethodID accept=cls?env->GetMethodID(cls,"accept","(I)V"):nullptr;if(cls)env->DeleteLocalRef(cls);if(!accept){env->ExceptionClear();throw Failure(Kind::REQUEST,"Whisper progress callback is invalid");}Progress p{env,callback,accept,flag};progress(p,0);if(p.error){env->Throw(p.error);return static_cast<jbyteArray>(nullptr);}auto wav=inspect_wav(path,flag);auto pcm=read_pcm(path,wav,flag);
    auto params=whisper_full_default_params(WHISPER_SAMPLING_GREEDY);params.n_threads=threads;params.translate=false;params.no_context=true;params.no_timestamps=true;params.print_special=false;params.print_progress=false;params.print_realtime=false;params.print_timestamps=false;params.token_timestamps=false;params.initial_prompt=prompt.empty()?nullptr:prompt.c_str();params.language=language.c_str();params.detect_language=false;params.progress_callback=progress_callback;params.progress_callback_user_data=&p;params.abort_callback=abort_callback;params.abort_callback_user_data=flag.get();
    int rc=whisper_full(resident,params,pcm.data(),int(pcm.size()));if(p.error){env->Throw(p.error);return static_cast<jbyteArray>(nullptr);}cancelled(flag);if(rc)throw Failure(Kind::TRANSCRIBE,"Whisper could not transcribe PCM audio");std::string text;int count=whisper_full_n_segments(resident);for(int i=0;i<count;i++){cancelled(flag);const char*part=whisper_full_get_segment_text(resident,i);if(part){size_t n=strlen(part);if(text.size()>MAX_TEXT||n>MAX_TEXT-text.size())throw Failure(Kind::TRANSCRIBE,"Whisper transcript exceeds limit");text.append(part,n);}}progress(p,100);if(p.error){env->Throw(p.error);return static_cast<jbyteArray>(nullptr);}return output(env,text);
});}
extern "C" JNIEXPORT void JNICALL Java_io_github_trevarj_motd_ai_whisper_WhisperRuntime_nativeUnload(JNIEnv*env,jobject,jlong id){guard_void(env,[&]{flag_for(id);std::lock_guard<std::mutex>lock(model_mutex);free_model();});}
