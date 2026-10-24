package io.mintymc.pbm.core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.regex.Pattern;

/** A deliberately limited YAML subset, so no external dependencies are required. */
public final class Config {
    public record Entry(String label, String pluginId, String source, String repo, String project,
                        String url, String filename, String filenameRegex, String loader,
                        String gameVersion, String channel) {
        public Entry {
            if (!label.matches("[A-Za-z0-9_.-]{1,80}")) throw new IllegalArgumentException("Invalid entry name: "+label);
            if (pluginId==null||pluginId.isBlank()) throw new IllegalArgumentException(label+": plugin-id is required");
            if (source==null||!Set.of("github","modrinth","hangar","url").contains(source.toLowerCase(Locale.ROOT)))
                throw new IllegalArgumentException(label+": source must be github, modrinth, hangar, or url");
            if (filenameRegex!=null && !filenameRegex.isEmpty()) Pattern.compile(filenameRegex);
        }
    }
    private final Map<String,Entry> entries;
    private Config(Map<String,Entry> entries) {this.entries=Collections.unmodifiableMap(entries);}
    public Collection<Entry> entries() {return entries.values();}
    public Entry get(String name) {
        for(Entry e:entries.values()) if(e.label().equalsIgnoreCase(name))return e;
        return null;
    }
    private static String val(Map<String,String> props, String name) {return props.getOrDefault(name, "");}
    public static Config load(Path path) throws IOException {
        LinkedHashMap<String,Map<String,String>> raw=new LinkedHashMap<>();
        String current=null;
        int ln=0;
        for(String line:Files.readAllLines(path,StandardCharsets.UTF_8)) {
            ln++;
            if(line.isBlank()||line.stripLeading().startsWith("#"))continue;
            if(line.indexOf('\t')>=0)throw new IllegalArgumentException("Tabs in config, line "+ln);
            int spaces=0; while(spaces<line.length() && line.charAt(spaces)==' ')spaces++;
            String content=line.substring(spaces).stripTrailing();
            if(spaces==0 && content.equals("plugins:")) continue;
            if(spaces==2 && content.endsWith(":")) {
                current=content.substring(0,content.length()-1).trim();
                if(raw.putIfAbsent(current,new LinkedHashMap<>())!=null) throw new IllegalArgumentException("Duplicate entry: "+current);
            } else if(spaces==4 && current!=null) {
                int sep=content.indexOf(':');
                if(sep<1)throw new IllegalArgumentException("Bad YAML line "+ln);
                String key=content.substring(0,sep).trim();
                String v=content.substring(sep+1).trim();
                if(v.length()>=2 && ((v.startsWith("\"")&&v.endsWith("\"")) || (v.startsWith("'")&&v.endsWith("'"))))
                    v=v.substring(1,v.length()-1);
                raw.get(current).put(key,v.replace("\\\\","\\"));
            } else throw new IllegalArgumentException("Unsupported YAML structure on line "+ln+": "+content);
        }
        Map<String,Entry> parsed=new LinkedHashMap<>();
        for(var ent:raw.entrySet()) {
            var p=ent.getValue();
            var e=new Entry(ent.getKey(),val(p,"plugin-id"),val(p,"source"),val(p,"repo"),
                val(p,"project"),val(p,"url"),val(p,"filename"),val(p,"filename-regex"),
                val(p,"loader"),val(p,"game-version"),val(p,"channel"));
            if(e.pluginId().equalsIgnoreCase("pluginbatchmanager")||e.pluginId().equalsIgnoreCase("pbm"))
                throw new IllegalArgumentException("Refusing to self-update: "+e.label());
            if(parsed.values().stream().anyMatch(x->x.pluginId().equalsIgnoreCase(e.pluginId())))
                throw new IllegalArgumentException("Duplicate plugin-id: "+e.pluginId());
            parsed.put(ent.getKey(),e);
        }
        return new Config(parsed);
    }
}
