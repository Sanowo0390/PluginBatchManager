package io.mintymc.pbm.paper;

import io.mintymc.pbm.core.Manager;
import io.mintymc.pbm.core.Descriptors.Platform;
import org.bukkit.Bukkit;
import org.bukkit.command.*;
import org.bukkit.plugin.java.JavaPlugin;
import java.util.*;

public final class PaperPlugin extends JavaPlugin implements CommandExecutor, TabCompleter {
    private Manager manager;
    @Override public void onEnable() {
        try {
            manager=new Manager(getDataFolder().toPath().getParent(), Platform.PAPER);
            PluginCommand cmd=getCommand("pbm");
            if(cmd==null)throw new IllegalStateException("Missing command in plugin.yml");
            cmd.setExecutor(this);cmd.setTabCompleter(this);
            getLogger().info("PluginBatchManager (Paper) ready; config: "+manager.configPath());
        }catch(Exception ex){getLogger().severe("Cannot start: "+ex.getMessage());getServer().getPluginManager().disablePlugin(this);}
    }
    @Override public void onDisable(){if(manager!=null)manager.stop();}
    @Override public boolean onCommand(CommandSender sender,Command command,String alias,String[] args) {
        if(!sender.hasPermission("pbm.admin")){sender.sendMessage("[PBM] No permission (pbm.admin)");return true;}
        if(manager==null){sender.sendMessage("[PBM] Not initialized");return true;}
        if(args.length==0){sender.sendMessage("[PBM] /pbm list | reload | check [all|name] | download [all|name] | apply [all|name] | rollback <name>");return true;}
        String action=args[0].toLowerCase(Locale.ROOT);
        switch(action){
            case "list" -> manager.list(text->sender.sendMessage("[PBM] "+text));
            case "reload" -> {
                try{manager.reload();sender.sendMessage("[PBM] Config reloaded");}
                catch(Exception ex){sender.sendMessage("[PBM] Reload failed: "+ex.getMessage());}
            }
            case "check","download","apply","rollback" -> {
                String[] rest=Arrays.copyOfRange(args,1,args.length);
                sender.sendMessage("[PBM] Started "+action+"...");
                manager.submit(action,rest,text->{
                    // Bukkit APIs must be called back on the server thread.
                    if(isEnabled())Bukkit.getScheduler().runTask(this,()->sender.sendMessage("[PBM] "+text));
                    else getLogger().info(text);
                });
            }
            default->sender.sendMessage("[PBM] Unknown command: "+action);
        }
        return true;
    }
    @Override public List<String> onTabComplete(CommandSender sender,Command command,String alias,String[] args){
        if(!sender.hasPermission("pbm.admin"))return List.of();
        if(args.length==1)return List.of("list","reload","check","download","apply","rollback").stream()
            .filter(s->s.startsWith(args[0].toLowerCase(Locale.ROOT))).toList();
        if(args.length==2)return manager==null?List.of():java.util.stream.Stream.concat(
            java.util.stream.Stream.of("all"),manager.entries().stream().map(e->e.label()))
            .filter(s->s.toLowerCase(Locale.ROOT).startsWith(args[1].toLowerCase(Locale.ROOT))).toList();
        return List.of();
    }
}
