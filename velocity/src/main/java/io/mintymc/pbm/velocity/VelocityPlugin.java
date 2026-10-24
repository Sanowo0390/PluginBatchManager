package io.mintymc.pbm.velocity;

import com.google.inject.Inject;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.ProxyServer;
import org.slf4j.Logger;
import java.nio.file.Path;
import java.util.*;
import io.mintymc.pbm.core.Manager;
import io.mintymc.pbm.core.Descriptors.Platform;

public final class VelocityPlugin {
    private final ProxyServer proxy;
    private final Logger logger;
    private final Path pluginDataDir;
    private Manager manager;
    @Inject public VelocityPlugin(ProxyServer proxy, Logger logger,@DataDirectory Path pluginDataDir){
        this.proxy=proxy;this.logger=logger;this.pluginDataDir=pluginDataDir;
    }
    @Subscribe public void onStart(ProxyInitializeEvent event) {
        try{
            manager=new Manager(pluginDataDir.toAbsolutePath().getParent(),Platform.VELOCITY);
            var meta=proxy.getCommandManager().metaBuilder("pbmv").plugin(this).build();
            proxy.getCommandManager().register(meta,new Commands());
            logger.info("PluginBatchManager (Velocity) ready; config: {}",manager.configPath());
        } catch(Exception e){logger.error("PluginBatchManager cannot start",e);}
    }
    @Subscribe public void onStop(ProxyShutdownEvent event){if(manager!=null)manager.stop();}
    private final class Commands implements SimpleCommand {
        private void send(Invocation inv,String message){inv.source().sendPlainMessage("[PBM] "+message);}
        @Override public boolean hasPermission(Invocation invocation) {
            return invocation.source().hasPermission("pbm.admin");
        }
        @Override public void execute(Invocation invocation){
            String[] args=invocation.arguments();
            if(manager==null){send(invocation,"Not initialized");return;}
            if(args.length==0){send(invocation,"/pbmv list | reload | check [all|name] | download [all|name] | apply [all|name] | rollback <name>");return;}
            String action=args[0].toLowerCase(Locale.ROOT);
            switch(action) {
                case "list"->manager.list(s->send(invocation,s));
                case "reload"->{
                    try{manager.reload();send(invocation,"Config reloaded");}
                    catch(Exception ex){send(invocation,"Reload failed: "+ex.getMessage());}
                }
                case "check","download","apply","rollback"->{
                    send(invocation,"Started "+action+"...");
                    manager.submit(action,Arrays.copyOfRange(args,1,args.length),s->send(invocation,s));
                }
                default->send(invocation,"Unknown command: "+action);
            }
        }
        @Override public List<String> suggest(Invocation invocation){
            String[] args=invocation.arguments();
            if(args.length==1)return List.of("list","reload","check","download","apply","rollback").stream()
                .filter(s->s.startsWith(args[0].toLowerCase(Locale.ROOT))).toList();
            if(args.length==2 && manager!=null)return java.util.stream.Stream.concat(
                java.util.stream.Stream.of("all"),manager.entries().stream().map(e->e.label()))
                .filter(s->s.toLowerCase(Locale.ROOT).startsWith(args[1].toLowerCase(Locale.ROOT))).toList();
            return List.of();
        }
    }
}
