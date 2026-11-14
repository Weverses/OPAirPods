package com.tlsu.pods;
import org.junit.Test;
import static org.junit.Assert.*;
public class HfpCompatibilityTest {
    @Test public void disablesOnlyTheBrokenAirpodsBatteryExtension(){
        assertEquals("+XAPL=iPhone,0",HfpCompatibility.response(true,"+XAPL=iPhone,2"));
        assertEquals("+XAPL=iPhone,2",HfpCompatibility.response(false,"+XAPL=iPhone,2"));
        assertEquals("OK",HfpCompatibility.response(true,"OK"));
        assertEquals("+XAPL=iPhone,7",HfpCompatibility.response(true,"+XAPL=iPhone,7"));
        assertNull(HfpCompatibility.response(true,null));
    }
}
