// SPDX-License-Identifier: GPL-3.0-or-later
package com.tlsu.pods;

import org.junit.Test;
import static org.junit.Assert.*;

public class DefaultAssistantTest {
    @Test public void prefersTheConfiguredAssistantBeforeTheVoiceService(){
        assertArrayEquals(new String[]{"com.heytap.breeno","com.example.voice"},
            DefaultAssistant.packages("com.heytap.breeno/.Assistant","com.example.voice/VoiceService"));
    }
    @Test public void serviceOnlyConfigurationAndFullyQualifiedComponentsWork(){
        assertArrayEquals(new String[]{"com.example.voice"},DefaultAssistant.packages(null,"com.example.voice/com.example.voice.Service"));
        assertArrayEquals(new String[]{"com.example.voice"},DefaultAssistant.packages("", "com.example.voice/.Service"));
    }
    @Test public void theSamePackageIsOnlyTriedOnce(){
        assertArrayEquals(new String[]{"com.example.voice"},DefaultAssistant.packages("com.example.voice/.Assistant","com.example.voice/.Service"));
    }
    @Test public void missingOrMalformedSettingsNeverBecomeAnImplicitGoogleOrSettingsIntent(){
        assertArrayEquals(new String[0],DefaultAssistant.packages(null,null));
        for(String invalid:new String[]{""," ","com.example.voice","/Service","com.example/","bad package/.Service","com.example/.Service/extra","com.example/../Service"})
            assertArrayEquals(new String[0],DefaultAssistant.packages(invalid,null));
    }
}
