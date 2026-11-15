// SPDX-License-Identifier: GPL-3.0-or-later
package com.tlsu.pods;

import org.junit.Test;
import static org.junit.Assert.*;

public class HostListenerTypesTest {
    static class RenamedPreference {
        interface A { boolean x(RenamedPreference preference); }
        interface Z { boolean x(RenamedPreference preference,Object value); }
        interface Other { void x(); }
        A click; Z change;
        void q(A value) { click=value; }
        void p(Z value) { change=value; }
    }
    static class AmbiguousPreference {
        interface A { boolean x(AmbiguousPreference p); }
        interface B { boolean x(AmbiguousPreference p); }
    }
    @Test public void resolvesRenamedCallbacksAndSetters() throws Exception {
        var click=HostListenerTypes.type(RenamedPreference.class,false);
        var change=HostListenerTypes.type(RenamedPreference.class,true);
        RenamedPreference row=new RenamedPreference();
        RenamedPreference.A clickValue=p->true;
        RenamedPreference.Z changeValue=(p,v)->true;
        HostListenerTypes.setter(RenamedPreference.class,click).invoke(row,clickValue);
        HostListenerTypes.setter(RenamedPreference.class,change).invoke(row,changeValue);
        assertSame(clickValue,row.click);assertSame(changeValue,row.change);
    }
    @Test(expected=IllegalStateException.class) public void rejectsAmbiguousCallbacks() throws Exception {
        HostListenerTypes.type(AmbiguousPreference.class,false);
    }
}
