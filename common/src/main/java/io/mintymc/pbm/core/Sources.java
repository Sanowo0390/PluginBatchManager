package io.mintymc.pbm.core;

import io.mintymc.pbm.core.Descriptors.Platform;
import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.time.Duration;
import java.util.*;
import java.util.regex.*;

/** Resolves configured download sources without silently renaming original assets. */
public final class Sources {
    public record Asset(String version,String filename,URI url,String sha512,String sha256) {}
    private final HttpClient client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15))
        .followRedirects(HttpClient.Redirect.NORMAL).build();
    private static final long MAX_FILE=128L*1024*1024;
    private static final int MAX_JSON=5*1024*1024;
    private static final String AGENT="MintyMC-PluginBatchManager/0.1 (+server-admin)";

    private static URI uri(String text) {
        URI u=URI.create(text);
        if(!"https".equalsIgnoreCase(u.getScheme()) || u.getHost()==null || u.getUserInfo()!=null)
            throw new IllegalArgumentException("HTTPS URL required: "+text);
        return u;
    }
    private HttpRequest.Builder request(URI u) {
        return HttpRequest.newBuilder(u).timeout(Duration.ofSeconds(70)).header("User-Agent",AGENT);
    }
    private HttpResponse<InputStream> open(URI u) throws IOException,InterruptedException {
        var req=request(u).GET();
        if("api.github.com".equalsIgnoreCase(u.getHost())) {
            String tok=System.getenv("PBM_GITHUB_TOKEN");
            if(tok!=null&&!tok.isBlank())req.header("Authorization","Bearer "+tok);
        }
        var r=client.send(req.build(),HttpResponse.BodyHandlers.ofInputStream());
        if(r.statusCode()!=200){r.body().close();throw new IOException("HTTP "+r.statusCode()+" from "+u.getHost());}
        if(!"https".equalsIgnoreCase(r.uri().getScheme())){r.body().close();throw new IOException("Non-HTTPS redirect refused");}
        return r;
    }
    private String json(URI uri)throws IOException,InterruptedException {
        var r=open(uri);
        try(InputStream in=r.body()) {
            byte[] all=in.readNBytes(MAX_JSON+1);
            if(all.length>MAX_JSON)throw new IOException("API JSON is too large");
            return new String(all,StandardCharsets.UTF_8);
        }
    }
    private static String q(String s){return URLEncoder.encode(s,StandardCharsets.UTF_8);}
    public Asset resolve(Config.Entry entry,Platform platform)throws Exception {
        return switch(entry.source().toLowerCase(Locale.ROOT)) {
            case "github"->github(entry);
            case "modrinth"->modrinth(entry,platform);
            case "hangar"->hangar(entry,platform);
            case "url"->direct(entry);
            default->throw new IOException("Unknown source");
        };
    }
    private Asset github(Config.Entry e)throws Exception {
        if(!e.repo().matches("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+"))throw new IOException(e.label()+": invalid GitHub repo");
        var root=Json.obj(Json.parse(json(uri("https://api.github.com/repos/"+e.repo()+"/releases/latest"))));
        List<Asset> matches=new ArrayList<>();
        for(Object item:Json.arr(root.get("assets"))) {
            var a=Json.obj(item);
            String fn=Json.str(a.get("name"));
            if(candidate(fn,e.filenameRegex())) {
                String url=Json.str(a.get("browser_download_url"));
                matches.add(new Asset(Json.str(root.get("tag_name")),fn,uri(url),"",""));
            }
        }
        return single(matches,e,"GitHub release");
    }
    private Asset modrinth(Config.Entry e,Platform platform)throws Exception {
        String project=e.project().isBlank()?e.repo():e.project();
        if(!project.matches("[A-Za-z0-9_-]{1,100}"))throw new IOException(e.label()+": invalid Modrinth project");
        String loader=e.loader().isBlank()?(platform==Platform.PAPER?"paper":"velocity"):e.loader();
        String target="https://api.modrinth.com/v2/project/"+project+"/version?loaders="+q("[\""+loader+"\"]")+
            (e.gameVersion().isBlank()?"":"&game_versions="+q("[\""+e.gameVersion()+"\"]"))+"&include_changelog=false";
        for(Object item:Json.arr(Json.parse(json(uri(target))))) {
            var ver=Json.obj(item);
            String channel=e.channel().isBlank()?"release":e.channel();
            if(!channel.equalsIgnoreCase(Json.str(ver.get("version_type"))))continue;
            var files=Json.arr(ver.get("files"));
            List<Asset> candidates=new ArrayList<>();
            for(Object file:files) {
                var f=Json.obj(file);
                String fn=Json.str(f.get("filename"));
                if(!candidate(fn,e.filenameRegex()))continue;
                if(e.filenameRegex().isBlank() && !Boolean.TRUE.equals(f.get("primary")))continue;
                candidates.add(new Asset(Json.str(ver.get("version_number")),fn,uri(Json.str(f.get("url"))),
                    Json.str(Json.obj(f.get("hashes")).get("sha512")),""));
            }
            if(candidates.isEmpty() && e.filenameRegex().isBlank() && files.size()==1) {
                var f=Json.obj(files.get(0));
                String fn=Json.str(f.get("filename"));
                if(candidate(fn,""))candidates.add(new Asset(Json.str(ver.get("version_number")),fn,
                    uri(Json.str(f.get("url"))),Json.str(Json.obj(f.get("hashes")).get("sha512")),""));
            }
            if(!candidates.isEmpty())return single(candidates,e,"Modrinth version");
        }
        throw new IOException("No matching Modrinth release for "+e.label()+" (loader="+loader+")");
    }
    private Asset hangar(Config.Entry e,Platform platform)throws Exception {
        String project=e.project().isBlank()?e.repo():e.project();
        if(!project.matches("[A-Za-z0-9_.-]{1,100}"))throw new IOException(e.label()+": invalid Hangar project slug");
        String target=platform==Platform.PAPER?"PAPER":"VELOCITY";
        for(int offset=0;offset<100;offset+=20) {
            String url="https://hangar.papermc.io/api/v1/projects/"+project+"/versions?limit=20&offset="+offset;
            List<Object> versions=Json.arr(Json.obj(Json.parse(json(uri(url)))).get("result"));
            for(Object item:versions) {
                var v=Json.obj(item);
                if(!e.gameVersion().isBlank()) {
                    var deps=Json.arr(Json.obj(v.get("platformDependencies")).get(target));
                    boolean supported=deps.stream().map(Json::str).anyMatch(r->withinRange(e.gameVersion(),r));
                    if(!supported)continue;
                }
                if(!e.channel().isBlank()) {
                    Object channel=v.get("channel");
                    String channelName=channel instanceof Map<?,?> ? Json.str(Json.obj(channel).get("name")):Json.str(channel);
                    if(!channelName.equalsIgnoreCase(e.channel()))continue;
                }
                var dl=Json.obj(Json.obj(v.get("downloads")).get(target));
                String address=Json.str(dl.get("downloadUrl"));
                var fileInfo=Json.obj(dl.get("fileInfo"));
                String filename=Json.str(fileInfo.get("name"));
                if(!address.isBlank()&&candidate(filename,e.filenameRegex()))
                    return new Asset(Json.str(v.get("name")),filename,uri(address),"",
                        Json.str(fileInfo.get("sha256Hash")));
                // External download fallback: require an explicit filename, if the URL cannot infer it.
                String external=Json.str(dl.get("externalUrl"));
                if(!external.isBlank()) {
                    String name=e.filename().isBlank()?filenameFromUrl(uri(external)):e.filename();
                    if(candidate(name,e.filenameRegex()))return new Asset(Json.str(v.get("name")),name,uri(external),"","");
                }
            }
            if(versions.size()<20)break;
        }
        throw new IOException("No Hangar version matching platform "+target+" and requested constraints: "+e.label());
    }
    private static boolean withinRange(String actual,String declaration) {
        if(actual.equals(declaration))return true;
        if(declaration.endsWith(".x") && actual.startsWith(declaration.substring(0,declaration.length()-1)))return true;
        String[] range=declaration.split("-",2);
        if(range.length!=2)return false;
        try {return versionCompare(actual,range[0])>=0 && versionCompare(actual,range[1])<=0;}
        catch(NumberFormatException ex){return false;}
    }
    private static int versionCompare(String left,String right) {
        String[] a=left.split("\\.");String[] b=right.split("\\.");
        for(int i=0;i<Math.max(a.length,b.length);i++) {
            int x=i<a.length?Integer.parseInt(a[i]):0;
            int y=i<b.length?Integer.parseInt(b[i]):0;
            if(x!=y)return Integer.compare(x,y);
        }
        return 0;
    }
    private Asset direct(Config.Entry e)throws Exception {
        URI u=uri(e.url());
        String fn=e.filename().isBlank()?filenameFromUrl(u):e.filename();
        // Dynamic URLs often have /latest/download rather than a JAR name; infer from response headers during download.
        return new Asset("direct",fn.endsWith(".jar")?fn:"",u,"","");
    }
    private static boolean candidate(String filename,String regex){
        return filename.toLowerCase(Locale.ROOT).endsWith(".jar") &&
            (regex==null||regex.isBlank()||Pattern.compile(regex).matcher(filename).matches());
    }
    private static Asset single(List<Asset> matches,Config.Entry e,String src)throws IOException {
        if(matches.isEmpty())throw new IOException("No matching JAR on "+src+" for "+e.label());
        if(matches.size()>1)throw new IOException("Multiple JAR assets for "+e.label()+"; set filename-regex");
        return matches.get(0);
    }
    private static String filenameFromUrl(URI u) {
        String path=u.getPath();
        if(path==null||path.isBlank())return "";
        return URLDecoder.decode(path.substring(path.lastIndexOf('/')+1),StandardCharsets.UTF_8);
    }
    private static String filenameFromDisposition(String disposition) {
        if(disposition==null)return "";
        Matcher m=Pattern.compile("(?i)filename\\*=UTF-8''([^;]+)").matcher(disposition);
        if(m.find())return URLDecoder.decode(m.group(1),StandardCharsets.UTF_8);
        m=Pattern.compile("(?i)filename=\\\"([^\\\"]+)\\\"|filename=([^; ]+)").matcher(disposition);
        return m.find()?(m.group(1)!=null?m.group(1):m.group(2)):"";
    }
    private static String safeFile(String filename)throws IOException {
        if(filename==null||filename.isBlank()||filename.length()>240||filename.startsWith(".")||
           !filename.toLowerCase(Locale.ROOT).endsWith(".jar") ||filename.contains("/")||filename.contains("\\")||
           filename.contains("..")||filename.chars().anyMatch(c->c<32||c==127))
            throw new IOException("Unsafe or missing original JAR filename: "+filename);
        return filename;
    }
    public Path download(Asset asset,Path destinationDir)throws Exception {
        Files.createDirectories(destinationDir);
        var r=open(asset.url());
        String disposition=r.headers().firstValue("Content-Disposition").orElse("");
        // Provider-declared asset name is authoritative; direct URLs use headers/final redirect path.
        String fn=asset.filename();
        if(fn.isBlank()) {
            fn=filenameFromDisposition(disposition);
            if(fn.isBlank())fn=filenameFromUrl(r.uri());
        }
        try(InputStream in=r.body()) {
            fn=safeFile(fn);
            Path file=destinationDir.resolve(fn);
            Path partial=Files.createTempFile(destinationDir,"pbm-", ".part");
            try {
                MessageDigest digest=MessageDigest.getInstance("SHA-512");
                MessageDigest digest256=MessageDigest.getInstance("SHA-256");
                long received=0;
                byte[] buffer=new byte[64*1024];
                try(OutputStream out=Files.newOutputStream(partial)) {
                    int n;
                    while((n=in.read(buffer))!=-1){
                        received+=n;
                        if(received>MAX_FILE)throw new IOException("JAR exceeds 128 MiB limit");
                        digest.update(buffer,0,n);digest256.update(buffer,0,n);out.write(buffer,0,n);
                    }
                }
                if(received<100)throw new IOException("Downloaded file is unexpectedly small");
                if(!asset.sha256().isBlank()) {
                    String actual=HexFormat.of().formatHex(digest256.digest());
                    if(!actual.equalsIgnoreCase(asset.sha256()))throw new IOException("SHA-256 mismatch for "+fn);
                }
                if(!asset.sha512().isBlank()) {
                    String actual=HexFormat.of().formatHex(digest.digest());
                    if(!actual.equalsIgnoreCase(asset.sha512()))throw new IOException("SHA-512 mismatch for "+fn);
                }
                Files.move(partial,file,StandardCopyOption.REPLACE_EXISTING);
                return file;
            } finally {Files.deleteIfExists(partial);}
        }
    }
}
