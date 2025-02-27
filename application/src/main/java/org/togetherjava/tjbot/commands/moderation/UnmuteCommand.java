package org.togetherjava.tjbot.commands.moderation;

import net.dv8tion.jda.api.entities.*;
import net.dv8tion.jda.api.events.GenericEvent;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.interactions.callbacks.IReplyCallback;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.requests.RestAction;
import net.dv8tion.jda.api.requests.restaction.AuditableRestAction;
import net.dv8tion.jda.api.utils.Result;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.togetherjava.tjbot.commands.SlashCommandAdapter;
import org.togetherjava.tjbot.commands.SlashCommandVisibility;
import org.togetherjava.tjbot.config.Config;

import javax.annotation.Nullable;
import java.util.Objects;

/**
 * This command can unmute muted users. Unmuting can also be paired with a reason. The command will
 * also try to DM the user to inform them about the action and the reason.
 * <p>
 * The command fails if the user triggering it is lacking permissions to either unmute other users
 * or to unmute the specific given user (for example a moderator attempting to unmute an admin).
 */
public final class UnmuteCommand extends SlashCommandAdapter {
    private static final Logger logger = LoggerFactory.getLogger(UnmuteCommand.class);
    private static final String TARGET_OPTION = "user";
    private static final String REASON_OPTION = "reason";
    private static final String COMMAND_NAME = "unmute";
    private static final String ACTION_VERB = "unmute";
    private final ModerationActionsStore actionsStore;
    private final Config config;

    /**
     * Constructs an instance.
     *
     * @param actionsStore used to store actions issued by this command
     * @param config the config to use for this
     */
    public UnmuteCommand(ModerationActionsStore actionsStore, Config config) {
        super(COMMAND_NAME,
                "Unmutes the given already muted user so that they can send messages again",
                SlashCommandVisibility.GUILD);

        getData().addOption(OptionType.USER, TARGET_OPTION, "The user who you want to unmute", true)
            .addOption(OptionType.STRING, REASON_OPTION, "Why the user should be unmuted", true);

        this.config = config;
        this.actionsStore = Objects.requireNonNull(actionsStore);
    }

    private static void handleNotMutedTarget(IReplyCallback event) {
        event.reply("The user is not muted.").setEphemeral(true).queue();
    }

    private static RestAction<Boolean> sendDm(ISnowflake target, String reason, Guild guild,
            GenericEvent event) {
        String dmMessage = """
                Hey there, you have been unmuted in the server %s.
                This means you can now send messages in the server again.
                The reason for the unmute is: %s
                """.formatted(guild.getName(), reason);
        return event.getJDA()
            .openPrivateChannelById(target.getId())
            .flatMap(channel -> channel.sendMessage(dmMessage))
            .mapToResult()
            .map(Result::isSuccess);
    }

    private static MessageEmbed sendFeedback(boolean hasSentDm, Member target, Member author,
            String reason) {
        String dmNoticeText = "";
        if (!hasSentDm) {
            dmNoticeText = "(Unable to send them a DM.)";
        }
        return ModerationUtils.createActionResponse(author.getUser(), ModerationAction.UNMUTE,
                target.getUser(), dmNoticeText, reason);
    }

    private AuditableRestAction<Void> unmuteUser(Member target, Member author, String reason,
            Guild guild) {
        logger.info("'{}' ({}) unmuted the user '{}' ({}) in guild '{}' for reason '{}'.",
                author.getUser().getAsTag(), author.getId(), target.getUser().getAsTag(),
                target.getId(), guild.getName(), reason);

        actionsStore.addAction(guild.getIdLong(), author.getIdLong(), target.getIdLong(),
                ModerationAction.UNMUTE, null, reason);

        return guild
            .removeRoleFromMember(target, ModerationUtils.getMutedRole(guild, config).orElseThrow())
            .reason(reason);
    }

    private void unmuteUserFlow(Member target, Member author, String reason, Guild guild,
            SlashCommandInteractionEvent event) {
        sendDm(target, reason, guild, event)
            .flatMap(
                    hasSentDm -> unmuteUser(target, author, reason, guild).map(result -> hasSentDm))
            .map(hasSentDm -> sendFeedback(hasSentDm, target, author, reason))
            .flatMap(event::replyEmbeds)
            .queue();
    }

    private boolean handleChecks(Member bot, Member author, @Nullable Member target,
            CharSequence reason, Guild guild, IReplyCallback event) {
        if (!ModerationUtils.handleRoleChangeChecks(
                ModerationUtils.getMutedRole(guild, config).orElse(null), ACTION_VERB, target, bot,
                author, guild, reason, event)) {
            return false;
        }
        if (Objects.requireNonNull(target)
            .getRoles()
            .stream()
            .map(Role::getName)
            .noneMatch(ModerationUtils.getIsMutedRolePredicate(config))) {
            handleNotMutedTarget(event);
            return false;
        }
        return true;
    }

    @Override
    public void onSlashCommand(SlashCommandInteractionEvent event) {
        Member target = Objects.requireNonNull(event.getOption(TARGET_OPTION), "The target is null")
            .getAsMember();
        Member author = Objects.requireNonNull(event.getMember(), "The author is null");
        String reason = Objects.requireNonNull(event.getOption(REASON_OPTION), "The reason is null")
            .getAsString();

        Guild guild = Objects.requireNonNull(event.getGuild());
        Member bot = guild.getSelfMember();

        if (!handleChecks(bot, author, target, reason, guild, event)) {
            return;
        }
        unmuteUserFlow(Objects.requireNonNull(target), author, reason, guild, event);
    }
}
