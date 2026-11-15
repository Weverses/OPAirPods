// SPDX-License-Identifier: GPL-3.0-or-later
package com.tlsu.pods;

import org.junit.Test;
import static org.junit.Assert.*;

public class ListeningModesTest {
    @Test public void addingAndRemovingAModePreservesEveryOtherSelection(){
        assertEquals(7,ListeningModes.select(6,1,true));
        assertEquals(15,ListeningModes.select(7,8,true));
        assertEquals(13,ListeningModes.select(15,2,false));
        assertEquals(9,ListeningModes.select(13,4,false));
    }
    @Test public void aSwitchCannotRemoveOneOfTheLastTwoModes(){
        assertThrows(IllegalArgumentException.class,()->ListeningModes.select(6,2,false));
        assertThrows(IllegalArgumentException.class,()->ListeningModes.select(6,4,false));
        assertEquals(6,ListeningModes.select(6,2,true));
        assertEquals(6,ListeningModes.select(6,1,false));
    }
    @Test public void missingOrReservedStateCannotBeOverwrittenWithInventedDefaults(){
        for(int mask:new int[]{-1,0,1,2,4,8,16,255}){
            assertFalse(ListeningModes.available(mask,1,true,true));
            assertThrows(IllegalArgumentException.class,()->ListeningModes.select(mask,1,true));
        }
        assertThrows(IllegalArgumentException.class,()->ListeningModes.select(6,3,true));
        assertThrows(IllegalArgumentException.class,()->ListeningModes.select(6,16,true));
    }
    @Test public void addingOffAndAdaptiveRequiresTheirAvailability(){
        assertFalse(ListeningModes.available(6,1,false,true));
        assertTrue(ListeningModes.available(6,1,true,false));
        assertFalse(ListeningModes.available(6,8,true,false));
        assertTrue(ListeningModes.available(6,8,false,true));
        assertTrue(ListeningModes.available(6,2,false,false));
        assertTrue(ListeningModes.available(6,4,false,false));
    }
    @Test public void anExistingSelectionCanBeRemovedAfterCapabilitiesChange(){
        assertTrue(ListeningModes.available(15,1,false,false));
        assertTrue(ListeningModes.available(15,8,false,false));
        assertEquals(14,ListeningModes.select(15,1,false));
        assertEquals(7,ListeningModes.select(15,8,false));
    }
    @Test public void summaryNamesFollowWireBitsRatherThanListeningModeIds(){
        assertEquals("降噪、通透",ListeningModes.label(6));
        assertEquals("关闭、降噪、通透、自适应",ListeningModes.label(15));
    }
}
