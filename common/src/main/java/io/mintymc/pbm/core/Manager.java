package io.mintymc.pbm.core;

import io.mintymc.pbm.core.Descriptors.Platform;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import java.util.stream.Stream;

/** Files are only changed in the current server's plugins directory, never across servers. */
public final class Manager {
    private final Path plugins, base, staged, disabled, configPath;
    private final Platform platform;
    private final Sources sources=new Sources();
    private final ExecutorService worker=Executors.newSingleThreadExecutor(r->{
        Thread t=new Thread(r,"pbm-worker");t.setDaemon(true);return t;
    });
    private volatile Config config;
    private static final DateTimeFormatter BATCH=DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS");
    public Manager(Path pluginFolder,Platform platform)throws IOException {
        this.plugins=pluginFolder.toAbsolutePath().normalize();
        this.platform=platform;
        this.base=plugins.resolve("PluginBatchManager");
        this.staged=base.resolve("staged");
        this.disabled=plugins.resolve(".disabled");
        this.configPath=base.resolve("plugins.yml");
        Files.createDirectories(plugins);
        Files.createDirectories(base);
        if(Files.notExists(configPath))Files.writeString(configPath,SAMPLE,StandardCharsets.UTF_8,StandardOpenOption.CREATE_NEW);
        reload();
    }
    public synchronized void reload() throws IOException {config=Config.load(configPath);}
    public Collection<Config.Entry> entries() {return config.entries();}
    public Path configPath(){return configPath;}
    public void stop(){worker.shutdown();}
    public boolean submit(String operation,String[] args,Consumer<String> send) {
        if(worker.isShutdown()){send.accept("Updater is stopping");return false;}
        worker.execute(()->{
            try {
                switch(operation.toLowerCase(Locale.ROOT)) {
                    case "check" -> execute("check",args,send);
                    case "download" -> execute("download",args,send);
                    case "apply" -> execute("apply",args,send);
                    case "rollback" -> rollback(args.length==0?"":args[0],send);
                    default -> send.accept("Unknown operation: "+operation);
                }
            } catch(Exception ex){send.accept("ERROR: "+ex.getMessage());}
        });
        return true;
    }
    public void list(Consumer<String> send) {
        var ents=config.entries();
        send.accept("Registered "+ents.size()+" plugins ["+platform+"]:");
        for(Config.Entry e:ents) {
            try {
                var jars=installed(e.pluginId());
                send.accept("  "+e.label()+" ("+e.pluginId()+") : "+
                    (jars.isEmpty()?"not installed":String.join(", ",jars.stream().map(p->p.getFileName().toString()).toList()))+
                    (stagedJar(e.label())==null?"":" [STAGED]"));
            } catch(Exception ex){send.accept(e.label()+": "+ex.getMessage());}
        }
    }
    private List<Config.Entry> targets(String[] args)throws IOException {
        if(args.length==0||args[0].equalsIgnoreCase("all"))return List.copyOf(config.entries());
        Config.Entry e=config.get(args[0]);
        if(e==null)throw new IOException("Entry not found: "+args[0]);
        return List.of(e);
    }
    private void execute(String operation,String[] args,Consumer<String> send)throws IOException {
        List<Config.Entry> entries=targets(args);
        if(entries.isEmpty()){send.accept("No plugins configured. Edit "+configPath);return;}
        int ok=0,failed=0;
        for(Config.Entry e:entries) {
            try {
                switch(operation) {
                    case "check" -> {
                        Sources.Asset a=sources.resolve(e,platform);
                        var jars=installed(e.pluginId());
                        send.accept("[CHECK] "+e.label()+" => "+a.version()+" / "+a.filename()+
                            (jars.isEmpty()?" [not installed]":" [installed: "+jars.get(0).getFileName()+"]")+
                            " (compatibility/version must be reviewed)");
                    }
                    case "download" -> {
                        Sources.Asset a=sources.resolve(e,platform);
                        Path destination=staged.resolve(e.label());
                        Files.createDirectories(destination);
                        Path temp=Files.createTempDirectory(base,"fetch-");
                        try {
                            Path downloaded=sources.download(a,temp);
                            Descriptors.PluginInfo info=Descriptors.read(downloaded,platform);
                            if(!info.id().equalsIgnoreCase(e.pluginId()))
                                throw new IOException("Descriptor mismatch: expected "+e.pluginId()+", got "+info.id());
                            for(Path existing:jars(destination))Files.delete(existing);
                            Path dst=destination.resolve(downloaded.getFileName());
                            if(Files.exists(dst))throw new IOException("Staged destination already exists");
                            Files.move(downloaded,dst);
                            send.accept("[STAGED] "+e.label()+" => "+dst.getFileName()+" (version "+info.version()+")");
                        } finally {deleteDirectory(temp);}
                    }
                    case "apply" -> apply(e,send);
                }
                ok++;
            }catch(InterruptedException ex){Thread.currentThread().interrupt();send.accept("Interrupted");break;}
            catch(Exception ex){failed++;send.accept("[FAILED] "+e.label()+": "+ex.getMessage());}
        }
        send.accept(operation.toUpperCase(Locale.ROOT)+" finished: "+ok+" success, "+failed+" failed. "+
            (operation.equals("apply")?"Restart server/proxy to activate new JARs.":""));
    }
    private List<Path> jars(Path folder)throws IOException {
        if(!Files.isDirectory(folder))return List.of();
        try(Stream<Path> stream=Files.list(folder)) {
            return stream.filter(p->Files.isRegularFile(p,LinkOption.NOFOLLOW_LINKS) &&
                p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".jar")).sorted().toList();
        }
    }
    private List<Path> installed(String id)throws IOException {
        List<Path> matches=new ArrayList<>();
        for(Path p:jars(plugins))if(Descriptors.isMatch(p,platform,id))matches.add(p);
        return matches;
    }
    private Path stagedJar(String name)throws IOException {
        var files=jars(staged.resolve(name));
        if(files.size()>1)throw new IOException("Multiple staged JARs for "+name);
        return files.isEmpty()?null:files.get(0);
    }
    private static String digest(Path p)throws Exception {
        MessageDigest d=MessageDigest.getInstance("SHA-256");
        try(InputStream in=Files.newInputStream(p)){
            byte[] buf=new byte[8192];int n;
            while((n=in.read(buf))!=-1)d.update(buf,0,n);
        }
        return HexFormat.of().formatHex(d.digest());
    }
    private Path batchFolder() throws IOException {
        Files.createDirectories(disabled);
        Path p=disabled.resolve(LocalDateTime.now().format(BATCH)+"-"+UUID.randomUUID().toString().substring(0,8));
        Files.createDirectories(p);
        return p;
    }
    private static void move(Path from,Path to)throws IOException {
        if(Files.isSymbolicLink(from)||Files.isSymbolicLink(to))throw new IOException("Refusing to move symlink");
        Files.move(from,to); // Never overwrite an unrelated JAR
    }
    private void apply(Config.Entry e,Consumer<String> send)throws Exception {
        Path ready=stagedJar(e.label());
        if(ready==null){send.accept("[SKIP] "+e.label()+": nothing staged");return;}
        String id=Descriptors.read(ready,platform).id();
        if(!id.equalsIgnoreCase(e.pluginId()))throw new IOException("Staged plugin ID no longer matches config");
        var old=installed(id);
        if(old.size()==1 && digest(ready).equals(digest(old.get(0)))){
            Files.delete(ready);send.accept("[UNCHANGED] "+e.label()+" (same SHA-256)");return;
        }
        Path destination=plugins.resolve(ready.getFileName());
        if(Files.exists(destination) && !old.contains(destination))
            throw new IOException("Filename collision with another plugin: "+destination.getFileName());
        Path backup=batchFolder();
        List<Map.Entry<Path,Path>> moved=new ArrayList<>();
        try {
            for(Path previous:old){
                Path archive=backup.resolve(previous.getFileName());
                move(previous,archive);
                moved.add(Map.entry(previous,archive));
            }
            move(ready,destination);
            send.accept("[APPLIED] "+e.label()+": "+destination.getFileName()+"; moved "+old.size()+
                " old JAR(s) to plugins/.disabled/"+backup.getFileName()+"/");
        }catch(Exception failure){
            // Per-plugin transaction rollback: no deletion of any previous JAR.
            for(int i=moved.size()-1;i>=0;i--){
                var m=moved.get(i);
                try {move(m.getValue(),m.getKey());} catch(IOException restoreError){failure.addSuppressed(restoreError);}
            }
            throw failure;
        }
    }
    private void rollback(String label,Consumer<String> send)throws Exception {
        if(label.isBlank()||label.equalsIgnoreCase("all")){
            send.accept("Usage: /pbm rollback <entry> (one plugin at a time)");return;
        }
        Config.Entry e=config.get(label);
        if(e==null)throw new IOException("Unknown entry: "+label);
        List<Path> backups=new ArrayList<>();
        if(Files.isDirectory(disabled)) {
            try(Stream<Path> dirs=Files.list(disabled)){
                for(Path dir:dirs.filter(p->Files.isDirectory(p,LinkOption.NOFOLLOW_LINKS)).toList()) {
                    for(Path jar:jars(dir))if(Descriptors.isMatch(jar,platform,e.pluginId()))backups.add(jar);
                }
            }
        }
        backups.sort(Comparator.comparing((Path p)->p.getParent().getFileName().toString()).reversed());
        if(backups.isEmpty())throw new IOException("No backup in plugins/.disabled for "+e.label());
        Path backupJar=backups.get(0);
        var current=installed(e.pluginId());
        Path dest=plugins.resolve(backupJar.getFileName());
        if(Files.exists(dest)&&!current.contains(dest))throw new IOException("Rollback filename collision");
        Path archived=batchFolder();
        List<Map.Entry<Path,Path>> moved=new ArrayList<>();
        try{
            for(Path p:current){Path archive=archived.resolve(p.getFileName());move(p,archive);moved.add(Map.entry(p,archive));}
            move(backupJar,dest);
            send.accept("[ROLLBACK] "+e.label()+" -> "+dest.getFileName()+". Restart required.");
        }catch(Exception ex){
            for(int i=moved.size()-1;i>=0;i--){var m=moved.get(i);try{move(m.getValue(),m.getKey());}catch(IOException suppressed){ex.addSuppressed(suppressed);}}
            throw ex;
        }
    }
    private static void deleteDirectory(Path dir)throws IOException {
        if(!Files.exists(dir))return;
        try(Stream<Path> walk=Files.walk(dir)) {
            for(Path p:walk.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(p);
        }
    }
    private static final String SAMPLE="""
        # Add plugins here. Use two-space indentation; values are simple strings.
        # Keep different lists on your Paper and Velocity servers.
        plugins:
        #  TAB:
        #    plugin-id: TAB
        #    source: github
        #    repo: NEZNAMY/TAB
        #    filename-regex: '.*\\.jar'
        #  ViaVersion:
        #    plugin-id: ViaVersion
        #    source: modrinth
        #    project: viaversion
        #    loader: paper
        #    game-version: 1.21.11
        #  MyPlugin:
        #    plugin-id: myplugin
        #    source: url
        #    url: https://example.org/MyPlugin-1.2.jar
        """;
}
