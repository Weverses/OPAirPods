// SPDX-License-Identifier: GPL-3.0-or-later
// Symbol lookup / .gnu_debugdata strategy adapted from LibrePods contributors.
// Unlike upstream, hook SDP_SetLocalDiRecord by its exact, verified ABI.
#include "sdp_record.h"
#include <android/log.h>
#include <jni.h>
#include <elf.h>
#include <atomic>
#include <cstdio>
#include <cstring>
#include <fstream>
#include <mutex>
#include <string>
#include <vector>
extern "C" {
#include "xz.h"
}

#define INFO(...) __android_log_print(ANDROID_LOG_INFO, "ColorPodsNative", __VA_ARGS__)
using HookFn = int (*)(void*, void*, void**);
using OnLibraryLoaded = void (*)(const char*, void*);
struct NativeAPIEntries { uint32_t version; HookFn hook_func; void* unhook_func; };
static HookFn hookFunction;
static SdpOriginal originalSdp;
static std::atomic<bool> enabled{true}, installed{false};
static std::atomic<long> calls{0}, patched{0};
static std::mutex hookMutex;

static uint16_t replacement(const DiRecord* record, uint32_t* handle) {
    calls.fetch_add(1);
    bool change = enabled.load() && record;
    auto result = submitIdentity(originalSdp, record, handle, change);
    if (change && result == 0) {
        patched.fetch_add(1);
        INFO("SDP identity submitted: vendor=004c source=0001 status=%u", result);
    }
    return result;
}

static bool range(size_t size, uint64_t offset, uint64_t count) {
    return offset <= size && count <= size - offset;
}
// Bounds-checked ELF64 parsing; accept a full .symtab or .dynsym, never offsets
// from a different firmware and never a substring match on function signatures.
struct ElfView {
    const std::vector<uint8_t>& data;
    const Elf64_Ehdr* head = nullptr;
    const Elf64_Shdr* sections = nullptr;
    explicit ElfView(const std::vector<uint8_t>& d) : data(d) {
        if (d.size() < sizeof(Elf64_Ehdr)) return;
        auto* e = reinterpret_cast<const Elf64_Ehdr*>(d.data());
        if (memcmp(e->e_ident, ELFMAG, SELFMAG) || e->e_ident[EI_CLASS] != ELFCLASS64
            || e->e_ident[EI_DATA] != ELFDATA2LSB || e->e_machine != EM_AARCH64
            || e->e_shentsize != sizeof(Elf64_Shdr) || !e->e_shnum
            || !range(d.size(), e->e_shoff, uint64_t(e->e_shnum) * sizeof(Elf64_Shdr))) return;
        head = e;
        sections = reinterpret_cast<const Elf64_Shdr*>(d.data() + e->e_shoff);
    }
    const char* string(const Elf64_Shdr& sec, size_t index) const {
        if (!range(data.size(), sec.sh_offset, sec.sh_size) || index >= sec.sh_size) return nullptr;
        const char* s = reinterpret_cast<const char*>(data.data() + sec.sh_offset + index);
        return memchr(s, 0, sec.sh_size - index) ? s : nullptr;
    }
    uint64_t symbol(const char* name) const {
        if (!head) return 0;
        for (size_t i = 0; i < head->e_shnum; ++i) {
            auto& s = sections[i];
            if ((s.sh_type != SHT_SYMTAB && s.sh_type != SHT_DYNSYM) || s.sh_link >= head->e_shnum
                || s.sh_entsize != sizeof(Elf64_Sym) || !range(data.size(), s.sh_offset, s.sh_size)) continue;
            for (size_t n = 0; n < s.sh_size / sizeof(Elf64_Sym); ++n) {
                Elf64_Sym entry;
                memcpy(&entry, data.data() + s.sh_offset + n * sizeof(entry), sizeof(entry));
                const char* candidate = string(sections[s.sh_link], entry.st_name);
                if (candidate && ELF64_ST_TYPE(entry.st_info) == STT_FUNC && entry.st_shndx != SHN_UNDEF
                    && !strcmp(candidate, name)) return entry.st_value;
            }
        }
        return 0;
    }
    std::vector<uint8_t> debugData() const {
        if (!head || head->e_shstrndx >= head->e_shnum) return {};
        for (size_t i = 0; i < head->e_shnum; ++i) {
            auto& s = sections[i];
            const char* name = string(sections[head->e_shstrndx], s.sh_name);
            if (name && !strcmp(name, ".gnu_debugdata") && range(data.size(), s.sh_offset, s.sh_size))
                return {data.begin() + s.sh_offset, data.begin() + s.sh_offset + s.sh_size};
        }
        return {};
    }
};
static std::vector<uint8_t> decompress(const std::vector<uint8_t>& input) {
    if (input.empty()) return {};
    xz_crc32_init(); xz_crc64_init();
    auto* decoder = xz_dec_init(XZ_DYNALLOC, 64U << 20);
    if (!decoder) return {};
    std::vector<uint8_t> output(32U << 20);
    xz_buf buffer{};
    buffer.in = input.data(); buffer.in_size = input.size();
    buffer.out = output.data(); buffer.out_size = output.size();
    auto result = xz_dec_run(decoder, &buffer);
    xz_dec_end(decoder);
    if (result != XZ_STREAM_END) return {};
    output.resize(buffer.out_pos);
    return output;
}
static void tryInstall(const char* library) {
    std::lock_guard<std::mutex> lock(hookMutex);
    if (installed.load() || !hookFunction) return;
    FILE* maps = fopen("/proc/self/maps", "r");
    if (!maps) return;
    char line[2048]; uintptr_t base = 0; std::string path;
    while (fgets(line, sizeof(line), maps)) {
        unsigned long start, end, offset; char perms[5];
        if (sscanf(line, "%lx-%lx %4s %lx", &start, &end, perms, &offset) != 4 || offset != 0) continue;
        char* candidate = strchr(line, '/');
        if (!candidate) continue;
        std::string p(candidate); p.erase(p.find_last_not_of("\r\n") + 1);
        if (p.substr(p.find_last_of('/') + 1) != library) continue;
        base = start; path = p; break;
    }
    fclose(maps);
    if (!base) return;
    std::ifstream stream(path, std::ios::binary | std::ios::ate);
    if (!stream) return;
    auto size = stream.tellg();
    if (size <= 0 || size > 128 * 1024 * 1024) return;
    std::vector<uint8_t> bytes(static_cast<size_t>(size));
    stream.seekg(0); if (!stream.read(reinterpret_cast<char*>(bytes.data()), size)) return;
    constexpr const char* symbol = "_Z20SDP_SetLocalDiRecordPK15t_sdp_di_recordPj";
    ElfView view(bytes);
    uint64_t address = view.symbol(symbol);
    if (!address) {
        auto debug = decompress(view.debugData());
        address = ElfView(debug).symbol(symbol);
    }
    if (!address) { INFO("SDP exact symbol unavailable in %s; no hook applied", library); return; }
    // Validate that the resolved address is inside an executable PT_LOAD segment.
    if (!view.head || view.head->e_phentsize != sizeof(Elf64_Phdr)
        || !range(bytes.size(), view.head->e_phoff, uint64_t(view.head->e_phnum) * sizeof(Elf64_Phdr))) return;
    bool executable = false;
    for (size_t i = 0; i < view.head->e_phnum; ++i) {
        Elf64_Phdr p; memcpy(&p, bytes.data() + view.head->e_phoff + i * sizeof(p), sizeof(p));
        if (p.p_type == PT_LOAD && (p.p_flags & PF_X) && address >= p.p_vaddr && address - p.p_vaddr < p.p_memsz) executable = true;
    }
    if (!executable) return;
    int result = hookFunction(reinterpret_cast<void*>(base + address), reinterpret_cast<void*>(replacement), reinterpret_cast<void**>(&originalSdp));
    installed.store(result == 0 && originalSdp);
    INFO("SDP hook %s offset=%llx result=%d installed=%d", library, (unsigned long long)address, result, installed.load());
}
static void loaded(const char* name, void*) {
    if (!name) return;
    const char* basename = strrchr(name, '/'); basename = basename ? basename + 1 : name;
    if (!strcmp(basename, "libbluetooth_jni.so") || !strcmp(basename, "libbluetooth_qti.so")) tryInstall(basename);
}
extern "C" __attribute__((visibility("default"), used))
OnLibraryLoaded native_init(const NativeAPIEntries* entries) {
    if (!entries || !entries->hook_func) return nullptr;
    hookFunction = entries->hook_func;
    tryInstall("libbluetooth_jni.so"); tryInstall("libbluetooth_qti.so");
    return loaded;
}
extern "C" JNIEXPORT void JNICALL
Java_com_tlsu_pods_NativeBridge_setEnabled(JNIEnv*, jclass, jboolean value) { enabled.store(value); }
extern "C" JNIEXPORT jlongArray JNICALL
Java_com_tlsu_pods_NativeBridge_status(JNIEnv* env, jclass) {
    jlong values[]{enabled.load(), installed.load(), calls.load(), patched.load()};
    auto result = env->NewLongArray(4); if (result) env->SetLongArrayRegion(result, 0, 4, values); return result;
}
