package io.mintymc.pbm.core;

import java.util.*;

/** Minimal, strict JSON reader for the public download APIs and Velocity manifests. */
public final class Json {
    private final String s;
    private int pos;
    private Json(String s) { this.s = s; }
    public static Object parse(String json) {
        Json p = new Json(json);
        Object value = p.value();
        p.ws();
        if (p.pos != json.length()) throw new IllegalArgumentException("Extra JSON content");
        return value;
    }
    @SuppressWarnings("unchecked")
    public static Map<String,Object> obj(Object o) { return o instanceof Map<?, ?> m ? (Map<String,Object>) m : Map.of(); }
    @SuppressWarnings("unchecked")
    public static List<Object> arr(Object o) { return o instanceof List<?> l ? (List<Object>) l : List.of(); }
    public static String str(Object o) { return o == null ? "" : o.toString(); }
    private void ws() { while(pos < s.length() && Character.isWhitespace(s.charAt(pos))) pos++; }
    private char peek() { ws(); if(pos>=s.length()) throw new IllegalArgumentException("Unexpected end of JSON"); return s.charAt(pos); }
    private boolean eat(char c) { if (peek()==c) { pos++; return true; } return false; }
    private Object value() {
        char c=peek();
        if(c=='"') return string();
        if(c=='{') return object();
        if(c=='[') return array();
        if(s.startsWith("true",pos)){pos+=4;return true;}
        if(s.startsWith("false",pos)){pos+=5;return false;}
        if(s.startsWith("null",pos)){pos+=4;return null;}
        if(c=='-'||Character.isDigit(c)) return number();
        throw new IllegalArgumentException("Invalid JSON near index "+pos);
    }
    private Object object() {
        pos++;
        Map<String,Object> m=new LinkedHashMap<>();
        if(eat('}')) return m;
        do {
            if(peek()!='"') throw new IllegalArgumentException("Object key must be a string");
            String k=string();
            if(!eat(':')) throw new IllegalArgumentException("Expected colon");
            m.put(k,value());
            if(eat('}')) return m;
            if(!eat(',')) throw new IllegalArgumentException("Expected comma");
        } while(true);
    }
    private Object array() {
        pos++;
        List<Object> l=new ArrayList<>();
        if(eat(']')) return l;
        do {
            l.add(value());
            if(eat(']')) return l;
            if(!eat(',')) throw new IllegalArgumentException("Expected comma");
        } while(true);
    }
    private String string() {
        if(!eat('"')) throw new IllegalArgumentException("Expected string");
        StringBuilder b=new StringBuilder();
        while(pos<s.length()) {
            char c=s.charAt(pos++);
            if(c=='"') return b.toString();
            if(c=='\\') {
                if(pos>=s.length()) throw new IllegalArgumentException("Bad escape");
                char e=s.charAt(pos++);
                switch(e) {
                    case '"','\\','/' -> b.append(e);
                    case 'b' -> b.append('\b');
                    case 'f' -> b.append('\f');
                    case 'n' -> b.append('\n');
                    case 'r' -> b.append('\r');
                    case 't' -> b.append('\t');
                    case 'u' -> {
                        if(pos+4>s.length()) throw new IllegalArgumentException("Bad unicode escape");
                        b.append((char)Integer.parseInt(s.substring(pos,pos+4),16)); pos+=4;
                    }
                    default -> throw new IllegalArgumentException("Bad escape character");
                }
            } else if(c<32) throw new IllegalArgumentException("Invalid control character");
            else b.append(c);
        }
        throw new IllegalArgumentException("Unterminated string");
    }
    private Number number() {
        int start=pos;
        if(s.charAt(pos)=='-') pos++;
        while(pos<s.length()&&Character.isDigit(s.charAt(pos)))pos++;
        if(pos<s.length()&&s.charAt(pos)=='.'){pos++;while(pos<s.length()&&Character.isDigit(s.charAt(pos)))pos++;}
        if(pos<s.length()&&(s.charAt(pos)=='e'||s.charAt(pos)=='E')){
            pos++; if(pos<s.length()&&(s.charAt(pos)=='+'||s.charAt(pos)=='-'))pos++;
            while(pos<s.length()&&Character.isDigit(s.charAt(pos)))pos++;
        }
        return Double.parseDouble(s.substring(start,pos));
    }
}
