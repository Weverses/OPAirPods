// SPDX-License-Identifier: GPL-3.0-or-later
#include <jni.h>
#include <linux/input.h>
#include <linux/uhid.h>
#include <poll.h>
#include <sys/eventfd.h>
#include <fcntl.h>
#include <unistd.h>
#include <atomic>
#include <cerrno>
#include <cstring>
#include <memory>
#include <thread>

namespace {
// Android Head Tracker HID v1. Feature 2 carries the Bluetooth persistent ID.
// The supported motion subscription reports about every 40 ms.
static const uint8_t descriptor[] = {
    0x05,0x20, 0x09,0xe1, 0xa1,0x01,
    0x85,0x02, 0x0a,0x08,0x03, 0x15,0x00, 0x26,0xff,0x00, 0x75,0x08, 0x95,23, 0xb1,0x03,
    0x0a,0x02,0x03, 0x95,16, 0xb1,0x03,
    0x85,0x01, 0x0a,0x16,0x03, 0x15,0x00, 0x25,0x01, 0x75,1, 0x95,1, 0xa1,0x02,
    0x0a,0x40,0x08, 0x0a,0x41,0x08, 0xb1,0x00, 0xc0,
    0x0a,0x19,0x03, 0xa1,0x02, 0x0a,0x55,0x08, 0x0a,0x51,0x08, 0xb1,0x00, 0xc0,
    0x0a,0x0e,0x03, 0x15,0, 0x25,63, 0x35,40, 0x45,100, 0x75,6, 0x95,1,
    0x66,0x01,0x10, 0x55,0x0d, 0xb1,0x02,
    0x0a,0x44,0x05, 0x16,0x01,0x80, 0x26,0xff,0x7f,
    0x37,0x60,0x4f,0x46,0xed, 0x47,0xa1,0xb0,0xb9,0x12,
    0x55,0x08, 0x75,16, 0x95,3, 0x81,0x02,
    0x0a,0x45,0x05, 0x35,0xe0, 0x45,0x20, 0x55,0, 0x81,0x02,
    0x0a,0x46,0x05, 0x16,0,0, 0x26,0xff,0, 0x35,0, 0x45,0, 0x75,8, 0x95,1, 0x81,0x02, 0xc0
};

struct Tracker {
    int fd = -1, wake = -1;
    uint8_t identity[16] = {};
    std::atomic<int> control{0};
    std::thread io;

    bool send(const uhid_event& event) {
        ssize_t n;
        do { n = write(fd,&event,sizeof(event)); } while (n < 0 && errno == EINTR);
        return n == sizeof(event);
    }
    bool reply(const uhid_event& request) {
        uhid_event out{};
        if (request.type == UHID_GET_REPORT) {
            out.type=UHID_GET_REPORT_REPLY;
            auto& r=out.u.get_report_reply;
            r.id=request.u.get_report.id;
            uint8_t id=request.u.get_report.rnum;
            r.data[0]=id;
            if (request.u.get_report.rtype != UHID_FEATURE_REPORT) r.err=EINVAL;
            else if (id==2) {
                memcpy(r.data+1,"#AndroidHeadTracker#1.0",23);
                memcpy(r.data+24,identity,sizeof(identity)); r.size=40;
            } else if (id==1) {
                r.data[1]=static_cast<uint8_t>(control.load()); r.size=2;
            } else r.err=EINVAL;
        } else if (request.type == UHID_SET_REPORT) {
            out.type=UHID_SET_REPORT_REPLY;
            const auto& q=request.u.set_report;
            out.u.set_report_reply.id=q.id;
            if (q.rtype==UHID_FEATURE_REPORT && q.rnum==1 && q.size==2 && q.data[0]==1)
                control.store(q.data[1]);
            else out.u.set_report_reply.err=EINVAL;
        } else {
            if (request.type==UHID_CLOSE || request.type==UHID_STOP) control.store(0);
            return true;
        }
        return send(out);
    }
    void run() {
        pollfd fds[]={{fd,POLLIN,0},{wake,POLLIN,0}};
        for (;;) {
            if (poll(fds,2,-1)<0) { if (errno==EINTR) continue; break; }
            if (fds[1].revents) return;
            if (!(fds[0].revents&POLLIN)) break;
            uhid_event request{};
            ssize_t n=read(fd,&request,sizeof(request));
            if (n<0 && (errno==EINTR || errno==EAGAIN)) continue;
            if (n<=0 || !reply(request)) break;
        }
        control.store(-1);
    }
    ~Tracker() {
        if (io.joinable()) {
            uint64_t one=1;
            while (write(wake,&one,sizeof(one))<0 && errno==EINTR) {}
            io.join();
        }
        if (fd>=0) {
            uhid_event stop{}; stop.type=UHID_DESTROY; send(stop); close(fd);
        }
        if (wake>=0) close(wake);
    }
};
}

extern "C" JNIEXPORT jlong JNICALL Java_com_tlsu_pods_NativeBridge_openHeadTracker(JNIEnv* env,jclass,jbyteArray mac) {
    if (!mac || env->GetArrayLength(mac)!=6) return 0;
    auto t=std::make_unique<Tracker>();
    t->identity[8]='B'; t->identity[9]='T';
    env->GetByteArrayRegion(mac,0,6,reinterpret_cast<jbyte*>(t->identity+10));
    t->fd=open("/dev/uhid",O_RDWR|O_CLOEXEC|O_NONBLOCK);
    if (t->fd>=0) t->wake=eventfd(0,EFD_CLOEXEC|EFD_NONBLOCK);
    uhid_event ev{}; ev.type=UHID_CREATE2;
    auto& c=ev.u.create2;
    memcpy(c.name,"ColorPods AirPods Head Tracker",sizeof("ColorPods AirPods Head Tracker"));
    c.bus=BUS_BLUETOOTH; c.vendor=0xffff; c.product=0x0037; c.version=1;
    c.rd_size=sizeof(descriptor); memcpy(c.rd_data,descriptor,sizeof(descriptor));
    if (t->fd<0 || t->wake<0 || !t->send(ev)) {
        env->ThrowNew(env->FindClass("java/io/IOException"),strerror(errno)); return 0;
    }
    try { t->io=std::thread(&Tracker::run,t.get()); }
    catch (const std::exception& e) {
        env->ThrowNew(env->FindClass("java/io/IOException"),e.what()); return 0;
    }
    return reinterpret_cast<jlong>(t.release());
}
extern "C" JNIEXPORT jint JNICALL Java_com_tlsu_pods_NativeBridge_headTrackerControl(JNIEnv*,jclass,jlong h) {
    return reinterpret_cast<Tracker*>(h)->control.load();
}
extern "C" JNIEXPORT jboolean JNICALL Java_com_tlsu_pods_NativeBridge_writeHeadPose(JNIEnv*,jclass,jlong h,jshort x,jshort y,jshort z,jint reset) {
    auto* t=reinterpret_cast<Tracker*>(h);
    int control=t->control.load();
    if (control<0 || (control&3)!=3) return false;
    uhid_event ev{}; ev.type=UHID_INPUT2;
    auto& report=ev.u.input2; report.size=14; report.data[0]=1;
    const int16_t axes[]={x,y,z};
    for (int i=0;i<3;i++) {
        report.data[1+2*i]=static_cast<uint8_t>(axes[i]);
        report.data[2+2*i]=static_cast<uint16_t>(axes[i])>>8;
    }
    report.data[13]=reset & 255;
    return t->send(ev);
}
extern "C" JNIEXPORT void JNICALL Java_com_tlsu_pods_NativeBridge_closeHeadTracker(JNIEnv*,jclass,jlong h) {
    delete reinterpret_cast<Tracker*>(h);
}
