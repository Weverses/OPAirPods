// SPDX-License-Identifier: GPL-3.0-or-later
// OPACK wire types cross-checked against pyatv's documented format.
package com.tlsu.pods;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.*;

final class Opack {
    private Opack(){}
    static byte[] encode(Map<String,Object> values){ByteArrayOutputStream out=new ByteArrayOutputStream();write(out,values);return out.toByteArray();}
    private static void write(ByteArrayOutputStream out,Object value){
        if(value==null){out.write(4);return;}
        if(value instanceof Boolean b){out.write(b?1:2);return;}
        if(value instanceof Number number){long n=number.longValue();if(n<0)throw new IllegalArgumentException("Negative OPACK integer");if(n<40){out.write((int)n+8);return;}int bytes=n<=255?1:n<=65535?2:n<=0xffffffffL?4:8;out.write(bytes==1?0x30:bytes==2?0x31:bytes==4?0x32:0x33);for(int i=0;i<bytes;i++)out.write((int)(n>>(8*i))&255);return;}
        if(value instanceof String text){byte[] data=text.getBytes(StandardCharsets.UTF_8);if(data.length<=32)out.write(0x40+data.length);else if(data.length<=255){out.write(0x61);out.write(data.length);}else throw new IllegalArgumentException("OPACK string too long");out.writeBytes(data);return;}
        if(value instanceof Map<?,?> map){if(map.size()>14)throw new IllegalArgumentException("OPACK dictionary too large");out.write(0xe0+map.size());for(var e:map.entrySet()){write(out,e.getKey());write(out,e.getValue());}return;}
        throw new IllegalArgumentException("Unsupported OPACK value");
    }
    static Map<String,Object> decode(byte[] bytes){
        if(bytes.length>4096)throw new IllegalArgumentException("OPACK too large");Reader reader=new Reader(bytes);Object value=reader.value(0);
        if(reader.p!=bytes.length||!(value instanceof Map<?,?>))throw new IllegalArgumentException("Invalid OPACK dictionary");
        @SuppressWarnings("unchecked")Map<String,Object> result=(Map<String,Object>)value;return result;
    }
    private static final class Reader {
        final byte[] data;int p;final List<Object> objects=new ArrayList<>();Reader(byte[] data){this.data=data;}
        int u8(){if(p>=data.length)throw new IllegalArgumentException("Truncated OPACK");return data[p++]&255;}
        byte[] bytes(int n){if(n<0||n>data.length-p)throw new IllegalArgumentException("Truncated OPACK");byte[] value=Arrays.copyOfRange(data,p,p+n);p+=n;return value;}
        long integer(int n){if(n>8)throw new IllegalArgumentException("Invalid OPACK number");long result=0;for(int i=0;i<n;i++)result|=(long)u8()<<(8*i);return result;}
        int length(int n){long size=integer(n);if(size<0||size>4096)throw new IllegalArgumentException("Invalid OPACK length");return(int)size;}
        Object value(int depth){
            if(depth>8||objects.size()>256)throw new IllegalArgumentException("OPACK nesting limit");int tag=u8();Object value;boolean remember=true;
            if(tag==1||tag==2)return tag==1;
            if(tag==4)return null;
            if(tag>=8&&tag<=0x2f)return(long)(tag-8);
            if(tag>=0xa0&&tag<=0xc4){int index=tag<=0xc0?tag-0xa0:length(tag-0xc0);if(index>=objects.size())throw new IllegalArgumentException("Invalid OPACK reference");return objects.get(index);}
            if(tag>=0x40&&tag<=0x64)value=new String(bytes(tag<=0x60?tag-0x40:length(tag-0x60)),StandardCharsets.UTF_8);
            else if(tag>=0x30&&tag<=0x33)value=integer(1<<(tag-0x30));
            else if(tag==0x35)value=ByteBuffer.wrap(bytes(4)).order(ByteOrder.LITTLE_ENDIAN).getFloat();
            else if(tag==0x36)value=ByteBuffer.wrap(bytes(8)).order(ByteOrder.LITTLE_ENDIAN).getDouble();
            else if(tag==5)value=bytes(16);
            else if(tag==6)value=integer(8);
            else if(tag>=0x70&&tag<=0x94)value=bytes(tag<=0x90?tag-0x70:length(1<<(tag-0x91)));
            else if(tag>=0xd0&&tag<=0xdf){List<Object> list=new ArrayList<>();int count=tag&15;for(int i=0;i<(count==15?256:count);i++){if(count==15&&p<data.length&&data[p]==3){p++;break;}list.add(value(depth+1));}value=list;remember=false;}
            else if(tag>=0xe0&&tag<=0xef){Map<String,Object> map=new LinkedHashMap<>();int count=tag&15;for(int i=0;i<(count==15?256:count);i++){if(count==15&&p<data.length&&data[p]==3){p++;break;}Object key=value(depth+1);if(!(key instanceof String))throw new IllegalArgumentException("Invalid OPACK key");map.put((String)key,value(depth+1));}value=map;remember=false;}
            else throw new IllegalArgumentException("Unsupported OPACK tag "+tag);
            if(remember&&!objects.contains(value))objects.add(value);return value;
        }
    }
}
