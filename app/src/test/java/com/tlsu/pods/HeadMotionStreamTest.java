package com.tlsu.pods;

import static org.junit.Assert.*;
import static com.tlsu.pods.HeadMotionStream.Consumer.*;
import org.junit.Test;
import java.util.ArrayList;
import java.util.List;

public class HeadMotionStreamTest {
    @Test public void releasingOneSubscriberKeepsThePhysicalStream() throws Exception {
        List<String> writes=new ArrayList<>();
        HeadMotionStream stream=new HeadMotionStream((a,s,start,variant)->writes.add(start+"/"+variant));
        Object a=new Object();
        stream.change(SPATIAL,"ear",a,true,true);
        stream.change(GESTURE,"ear",a,true,false);
        stream.change(SPATIAL,"ear",a,false,true);
        stream.change(GESTURE,"ear",a,false,false);
        assertEquals(List.of("true/true","false/true"),writes);
    }
    @Test public void oldConnectionCannotStopItsReplacement() throws Exception {
        List<Object> writes=new ArrayList<>();
        HeadMotionStream stream=new HeadMotionStream((a,s,start,v)->writes.add(s));
        Object old=new Object(),now=new Object();
        stream.change(GESTURE,"ear",old,true,true);
        stream.change(SPATIAL,"ear",now,true,true);
        stream.forget("ear",old); stream.change(GESTURE,"ear",old,false,true);
        stream.change(SPATIAL,"ear",now,false,true);
        assertEquals(List.of(old,now,now),writes);
    }
    @Test public void retryStopsTheCurrentServiceBeforeStartingTheOtherService() throws Exception {
        List<String> writes = new ArrayList<>();
        HeadMotionStream stream = new HeadMotionStream((a,s,start,variant) -> writes.add(start+"/"+variant));
        Object socket = new Object();
        stream.change(SPATIAL, "ear", socket, true, true);
        stream.change(SPATIAL, "ear", socket, false, true);
        stream.change(SPATIAL, "ear", socket, true, false);
        assertEquals(List.of("true/true", "false/true", "true/false"), writes);
    }
}
