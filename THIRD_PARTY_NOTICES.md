# Third-party notices

## LibrePods

Source: https://github.com/librepods-org/librepods

Reference revision: 89356c08cea3db0b6b54792d9329d09a87cc85fa

Copyright (C) 2025 LibrePods contributors. GPL-3.0-or-later.

AACP control commands, Classic ATT handle/layout information, and TiPi/Smart Routing frame encodings are derived from LibrePods, particularly `AACPManager.kt`, `ATTManager.kt`, `data/Transparency.kt`, `data/HearingAid.kt`, and `AirPodsService.kt`. ColorPods is distributed with corresponding source and the GPL text in `LICENSE`.

The hardware three-band EQ format and stem event/configuration values also follow `data/CustomEq.kt`, `data/StemAction.kt`, `AACPManager.kt`, and `AirPodsViewModel.kt` at the same reference revision. Native COUI adapters, session guards, and assistant targeting are implemented in this module.

The integration uses independent API102 adapters and the host's original UI/runtime. It does not redistribute the proprietary host APKs.

## XZ Embedded

Files under `app/src/main/cpp/xz` are copied from the XZ Embedded decoder included in LibrePods, retaining their original author and SPDX headers. They are licensed under **0BSD**. Authors include Lasse Collin, Igor Pavlov, and contributors identified in individual source files.

Permission to use, copy, modify, and/or distribute this software for any purpose with or without fee is hereby granted.

THE SOFTWARE IS PROVIDED "AS IS" AND THE AUTHOR DISCLAIMS ALL WARRANTIES WITH REGARD TO THIS SOFTWARE INCLUDING ALL IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS. IN NO EVENT SHALL THE AUTHOR BE LIABLE FOR ANY SPECIAL, DIRECT, INDIRECT, OR CONSEQUENTIAL DAMAGES OR ANY DAMAGES WHATSOEVER RESULTING FROM LOSS OF USE, DATA OR PROFITS, WHETHER IN AN ACTION OF CONTRACT, NEGLIGENCE OR OTHER TORTIOUS ACTION, ARISING OUT OF OR IN CONNECTION WITH THE USE OR PERFORMANCE OF THIS SOFTWARE.

## libxposed API

Compile-only API: `io.github.libxposed:api:102.0.0`, https://github.com/libxposed/api. The runtime implementation is supplied by the installed Xposed framework; the API JAR is not bundled in this module.

## DexKit

Runtime dependency: `org.luckypray:dexkit:2.3.0`, https://github.com/LuckyPray/DexKit. Copyright (C) LuckyPray and contributors. Dual-licensed under **LGPL-3.0-or-later** and **Apache-2.0**; this module uses it under LGPL-3.0-or-later. Source and license texts are available at the upstream repository.

The library (Java bindings plus `libdexkit.so` for arm64-v8a) is bundled unmodified in the APK and is used at runtime to locate host classes, methods and fields by dex fingerprints. It is dynamically loaded as a separate shared library; ColorPods does not statically link against it. Its transitive runtime dependencies are `com.google.flatbuffers:flatbuffers-java:23.5.26`, `dev.rikka.ndk.thirdparty:cxx:1.2.0` and `org.jetbrains.kotlin:kotlin-stdlib:1.5.0`.


## Protocol cross-checks

The OPACK format and decoded fixtures were independently checked with pyatv's OPACK implementation (https://github.com/postlund/pyatv/blob/master/pyatv/support/opack.py). That reference script is used locally for verification and is not bundled in the APK or source distribution. The ColorPods codec is implemented locally from the wire format.

Handoff sequencing was also compared with the public hardware observations in https://github.com/zackb/tether/blob/main/docs/BLUETOOTH.md. These observations guide tests; they do not establish compatibility with every AirPods firmware.

The AirPods-only HFP XAPL battery-extension workaround follows the firmware failure observations in https://github.com/librepods-org/librepods/issues/782. The local API102 JNI adapter is implemented independently; AACP battery reporting remains enabled.
