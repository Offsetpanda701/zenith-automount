package io.github.offsetpanda.zenithautomount;

import com.zenith.plugin.api.Plugin;
import com.zenith.plugin.api.PluginAPI;
import com.zenith.plugin.api.ZenithProxyPlugin;
import io.github.offsetpanda.zenithautomount.command.AutoMountCommand;
import io.github.offsetpanda.zenithautomount.module.AutoMountModule;

@Plugin(
    id = BuildConstants.PLUGIN_ID,
    version = BuildConstants.VERSION,
    description = "Remounts the same standard Minecart after dismount",
    url = "https://github.com/OffsetPanda/zenith-automount",
    authors = {"OffsetPanda"},
    mcVersions = {BuildConstants.MC_VERSION}
)
public final class ZenithAutoMountPlugin implements ZenithProxyPlugin {
    public static AutoMountConfig CONFIG;

    @Override
    public void onLoad(final PluginAPI pluginAPI) {
        CONFIG = pluginAPI.registerConfig(BuildConstants.PLUGIN_ID, AutoMountConfig.class);

        final AutoMountModule autoMountModule = new AutoMountModule();
        pluginAPI.registerModule(autoMountModule);
        pluginAPI.registerCommand(new AutoMountCommand(autoMountModule));
    }
}
