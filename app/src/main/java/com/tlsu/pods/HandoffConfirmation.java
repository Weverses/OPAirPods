// SPDX-License-Identifier: GPL-3.0-or-later
package com.tlsu.pods;

/** Selection can precede playback. Requiring MEDIA before resuming creates a
 * deadlock; an owner ACK while audio is still remote is not selection either. */
final class HandoffConfirmation {
    private HandoffConfirmation(){}
    static boolean selected(long before,long ownerSequence,int owner,long sourceSequence,boolean local){
        return sourceSequence>before&&local&&(owner<0||owner==1||sourceSequence>=ownerSequence);
    }
    static boolean confirmed(long before,long ownerSequence,int owner,long sourceSequence,boolean local,int type,boolean playback){
        boolean remote=sourceSequence>before&&!local&&type!=0;
        boolean owned=owner==1&&ownerSequence>before&&(!remote||ownerSequence>=sourceSequence);
        return (selected(before,ownerSequence,owner,sourceSequence,local)&&type!=0)||(!playback&&owned);
    }
}
