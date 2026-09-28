package io.github.offsetpanda.zenithautomount.command;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.zenith.command.api.Command;
import com.zenith.command.api.CommandCategory;
import com.zenith.command.api.CommandContext;
import com.zenith.command.api.CommandUsage;
import com.zenith.command.api.IExecutes;
import io.github.offsetpanda.zenithautomount.module.AutoMountModule;

/** The plugin intentionally exposes only .automount on and .automount off. */
public final class AutoMountCommand extends Command {
    private final AutoMountModule autoMountModule;

    public AutoMountCommand(final AutoMountModule autoMountModule) {
        this.autoMountModule = autoMountModule;
    }

    @Override
    public CommandUsage commandUsage() {
        return CommandUsage.builder()
            .name("automount")
            .category(CommandCategory.MODULE)
            .description("Automatically remount the same standard Minecart after dismounting")
            .usageLines("on", "off")
            .build();
    }

    @Override
    public LiteralArgumentBuilder<CommandContext> register() {
        return command("automount")
            .then(literal("on").executes((IExecutes<CommandContext>) context -> setEnabled(context, true)))
            .then(literal("off").executes((IExecutes<CommandContext>) context -> setEnabled(context, false)));
    }

    private void setEnabled(final com.mojang.brigadier.context.CommandContext<CommandContext> context, final boolean enabled) {
        autoMountModule.setAutomountEnabled(enabled);
        context.getSource().getEmbed()
            .title(enabled ? "Automount enabled." : "Automount disabled.")
            .primaryColor();
    }
}
