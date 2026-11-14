// SPDX-License-Identifier: GPL-3.0-or-later
#pragma once
#include <cstdint>
#include <cstddef>

// AOSP sdp_api.h; same ABI as the device's SDP_SetLocalDiRecord(const*, uint32_t*).
struct DiRecord {
    uint16_t vendor, vendor_id_source, product, version;
    bool primary_record;
    char client_executable_url[400], service_description[400], documentation_url[400];
};
static_assert(offsetof(DiRecord, primary_record) == 8);
static_assert(sizeof(DiRecord) == 1210);
using SdpOriginal = uint16_t (*)(const DiRecord*, uint32_t*);

inline uint16_t submitIdentity(SdpOriginal original, const DiRecord* record,
                               uint32_t* handle, bool enabled) {
    if (!original) return 0x0010; // SDP_NO_RESOURCES
    if (!record || !enabled) return original(record, handle);
    DiRecord copy = *record;
    copy.vendor = 0x004c;
    copy.vendor_id_source = 0x0001; // Bluetooth SIG company identifier
    // Preserve product/version/primary flag; invoke the original exactly once.
    return original(&copy, handle);
}
