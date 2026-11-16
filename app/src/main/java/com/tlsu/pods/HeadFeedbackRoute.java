// SPDX-License-Identifier: GPL-3.0-or-later
package com.tlsu.pods;

import java.util.List;

/** Select only this headset, following an existing call route without changing
 * the global communication device or starting a new SCO connection. */
final class HeadFeedbackRoute {
    enum Kind { A2DP, BLE, SCO, OTHER }
    enum AudioState { IDLE, RINGING, VOICE }
    record Endpoint(int id,String address,Kind kind) {}
    record Format(int rate,int channels,boolean communication) {}
    private HeadFeedbackRoute() {}

    static Endpoint select(String address,boolean call,AudioState state,Endpoint communication,List<Endpoint> outputs){
        if(call&&state!=AudioState.IDLE&&matches(address,communication)
                &&(state!=AudioState.VOICE||communication.kind!=Kind.A2DP)){
            for(Endpoint output:outputs)if(output.id==communication.id&&matches(address,output))return output;
        }
        // During an active call, wait for its real Bluetooth route. Media
        // output can still be listed while A2DP is suspended or the user has
        // selected the phone/another headset for the call.
        if(call&&state==AudioState.VOICE)return null;
        for(Endpoint output:outputs)if((output.kind==Kind.A2DP||output.kind==Kind.BLE)&&matches(address,output))return output;
        return null;
    }

    static Format format(Endpoint endpoint,boolean call,AudioState state){
        if(endpoint.kind==Kind.SCO)return new Format(16000,1,true);
        return new Format(HeadFeedbackSound.RATE,2,call&&state!=AudioState.IDLE);
    }

    static boolean matches(String address,Endpoint endpoint){
        return endpoint!=null&&endpoint.kind!=Kind.OTHER&&address!=null&&!address.isEmpty()
                &&address.equalsIgnoreCase(endpoint.address);
    }
}
