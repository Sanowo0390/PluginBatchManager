package io.mintymc.pbm.core;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public final class Descriptors {
    public enum Platform { PAPER, VELOCITY }
    public record PluginInfo(String id, String version) {}
    private Descriptors() {}
    public static PluginInfo read(Path jar, Platform platform) throws IOException {
        try(ZipFile zip=new ZipFile(jar.toFile())) {
            if(platform==Platform.VELOCITY) {
                ZipEntry entry=zip.getEntry("velocity-plugin.json");
                if(entry==null)throw new IOException("Not a Velocity plugin: "+jar.getFileName());
                var m=Json.obj(Json.parse(readText(zip,entry)));
                String id=Json.str(m.get("id"));
                if(id.isBlank())throw new IOException("Missing Velocity plugin ID");
                return new PluginInfo(id, Json.str(m.get("version")));
            }
            ZipEntry entry=zip.getEntry("paper-plugin.yml");
            if(entry==null)entry=zip.getEntry("plugin.yml");
            if(entry==null)throw new IOException("Not a Paper/Bukkit plugin: "+jar.getFileName());
            String yaml=readText(zip,entry);
            String id=field(yaml,"name");
            if(id.isBlank())throw new IOException("Missing Bukkit/Paper plugin name");
            return new PluginInfo(id,field(yaml,"version"));
        } catch(IllegalArgumentException ex) {
            throw new IOException("Invalid plugin descriptor: "+ex.getMessage(),ex);
        }
    }
    private static String readText(ZipFile zip,ZipEntry e)throws IOException {
        if(e.getSize()>65536)throw new IOException("Plugin descriptor too large");
        try(InputStream in=zip.getInputStream(e)) {
            byte[] bytes=in.readNBytes(65537);
            if(bytes.length>65536)throw new IOException("Plugin descriptor too large");
            return new String(bytes, StandardCharsets.UTF_8);
        }
    }
    private static String field(String yaml,String field){
        for(String line:yaml.split("\\R")) {
            if(line.startsWith(field+":")||line.matches("^"+field+"\\s*:.*")) {
                String v=line.substring(line.indexOf(':')+1).trim();
                if(v.startsWith("'")||v.startsWith("\"")){
                    char quote=v.charAt(0);int idx=v.indexOf(quote,1);
                    if(idx>0)return v.substring(1,idx);
                }
                int comment=v.indexOf(" #");
                return (comment<0?v:v.substring(0,comment)).trim();
            }
        }
        return "";
    }
    public static boolean isMatch(Path jar,Platform p,String id) {
        try {return read(jar,p).id().equalsIgnoreCase(id);} catch(Exception ignored) {return false;}
    }
}
