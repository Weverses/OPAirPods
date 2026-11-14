#include "../app/src/main/cpp/sdp_record.h"
#include <cassert>
#include <cstring>
static int count;
static bool expectApple;
static uint16_t original(const DiRecord* r, uint32_t* h) {
    ++count;
    assert(r->vendor==(expectApple?0x004c:0x1234));
    assert(r->vendor_id_source==(expectApple?1:2));
    assert(r->product==0x5678 && r->primary_record && r->client_executable_url[0]=='a');
    *h=42; return 0x123;
}
int main() {
    DiRecord r{}; r.vendor=0x1234; r.vendor_id_source=2; r.product=0x5678; r.primary_record=true; r.client_executable_url[0]='a';
    auto before=r; uint32_t handle=0;
    expectApple=true;
    assert(submitIdentity(original,&r,&handle,true)==0x123);
    assert(count==1 && handle==42 && !memcmp(&before,&r,sizeof(r)));
    expectApple=false;
    assert(submitIdentity(original,&r,&handle,false)==0x123);
    assert(count==2 && !memcmp(&before,&r,sizeof(r)));
}
