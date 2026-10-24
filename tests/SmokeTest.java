import io.mintymc.pbm.core.*;
import io.mintymc.pbm.core.Descriptors.Platform;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.io.*;
import java.util.zip.*;

public class SmokeTest {
    static void assertTrue(boolean cond,String msg){if(!cond)throw new AssertionError(msg);}
    static void jar(Path target,Platform platform,String id,String version) throws Exception{
        Files.createDirectories(target.getParent());
        try(ZipOutputStream out=new ZipOutputStream(Files.newOutputStream(target))){
            String entry=platform==Platform.PAPER?"plugin.yml":"velocity-plugin.json";
            String manifest=platform==Platform.PAPER?
                "name: "+id+"\nversion: '"+version+"'\nmain: example.Main\n":
                "{\"id\":\""+id+"\",\"version\":\""+version+"\",\"main\":\"example.Main\"}";
            out.putNextEntry(new ZipEntry(entry));out.write(manifest.getBytes());out.closeEntry();
            out.putNextEntry(new ZipEntry("dummy-"+version));out.write("hello".getBytes());out.closeEntry();
        }
    }
    static void operation(Manager m,String op,String name)throws Exception {
        CountDownLatch done=new CountDownLatch(1);
        ArrayList<String> messages=new ArrayList<>();
        m.submit(op,new String[]{name},s->{synchronized(messages){messages.add(s);}if(s.startsWith(op.toUpperCase()+" finished")||s.startsWith("[ROLLBACK]"))done.countDown();});
        assertTrue(done.await(8,TimeUnit.SECONDS),"Timed out: "+op+" "+messages);
        synchronized(messages){messages.forEach(System.out::println);}
    }
    static void run(Platform platform)throws Exception {
        String id=platform==Platform.PAPER?"TestPlugin":"testplugin";
        Path plugins=Files.createTempDirectory("pbm-"+platform).resolve("plugins");
        Files.createDirectories(plugins);
        Path old=plugins.resolve("TestPlugin-1.jar");
        jar(old,platform,id,"1.0");
        Manager manager=new Manager(plugins,platform);
        Path cfg=plugins.resolve("PluginBatchManager/plugins.yml");
        Files.writeString(cfg,"plugins:\n  Test:\n    plugin-id: "+id+"\n    source: url\n    url: https://example.com/TestPlugin-2.jar\n");
        manager.reload();
        assertTrue(manager.entries().size()==1,"config parsed");
        assertTrue(Descriptors.read(old,platform).version().equals("1.0"),"old plugin descriptor");
        Path newer=plugins.resolve("PluginBatchManager/staged/Test/TestPlugin-2.jar");
        jar(newer,platform,id,"2.0");
        operation(manager,"apply","all");
        assertTrue(Files.exists(plugins.resolve("TestPlugin-2.jar")),"new jar not in plugins/");
        assertTrue(!Files.exists(old),"old jar not moved");
        Path disabled=plugins.resolve(".disabled");
        try(var all=Files.walk(disabled)){
            assertTrue(all.anyMatch(p->p.getFileName().toString().equals("TestPlugin-1.jar")),"backup missing");
        }
        operation(manager,"rollback","Test");
        assertTrue(Files.exists(old),"rollback missing old");
        assertTrue(!Files.exists(plugins.resolve("TestPlugin-2.jar")),"rollback left new JAR");
        // Same filename update: preserve basename while archiving old one.
        Path sameNew=plugins.resolve("PluginBatchManager/staged/Test/TestPlugin-1.jar");
        jar(sameNew,platform,id,"3.0");
        operation(manager,"apply","all");
        assertTrue(Descriptors.read(old,platform).version().equals("3.0"),"same filename update failed");
        manager.stop();
        System.out.println("PASS: "+platform);
    }
    public static void main(String[]args)throws Exception {
        assertTrue(Json.str(Json.obj(Json.parse("{\"key\":[1,true]}")).get("key")).startsWith("["),"json");
        run(Platform.PAPER);run(Platform.VELOCITY);
    }
}
