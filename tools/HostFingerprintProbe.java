// SPDX-License-Identifier: GPL-3.0-or-later
package com.tlsu.pods;

import org.luckypray.dexkit.DexKitBridge;

/** Read-only device probe: run production queries against supplied APKs via app_process. */
public final class HostFingerprintProbe {
    public static void main(String[] args)throws Exception {
        System.load(args[0]);
        int failures=0;
        for(int i=1;i<args.length;i++)try(DexKitBridge bridge=DexKitBridge.create(args[i])) {
            bridge.setThreadNum(2);
            int checked=0;
            System.out.println("APK "+args[i]);
            for(String key:HostTargets.registeredKeys())if(HostTargets.hasFingerprint(key)) {
                checked++;
                try {
                    var hits=HostTargets.fingerprintMatches(key,bridge);
                    if(hits.size()!=1){failures++;System.out.println("FAIL "+key+" count="+hits.size()+" "+hits);}
                    else System.out.println("PASS "+key+" "+hits.get(0));
                }catch(Throwable failure){failures++;System.out.println("FAIL "+key+" "+failure);}
            }
            System.out.println("Checked "+checked+" production fingerprints");
        }
        if(failures!=0)throw new AssertionError(failures+" fingerprint failures");
    }
}
