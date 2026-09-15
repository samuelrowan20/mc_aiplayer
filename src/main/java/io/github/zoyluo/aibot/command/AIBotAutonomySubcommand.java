package io.github.zoyluo.aibot.command;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import io.github.zoyluo.aibot.auth.BotAuthorizationGate;
import io.github.zoyluo.aibot.auth.BotAuthorizationPolicy;
import io.github.zoyluo.aibot.autonomy.AutonomyCoordinator;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.text.Text;
import static net.minecraft.server.command.CommandManager.argument;
import static net.minecraft.server.command.CommandManager.literal;

public final class AIBotAutonomySubcommand {
    private AIBotAutonomySubcommand() { }

    public static LiteralArgumentBuilder<ServerCommandSource> build() {
        var root = literal("autonomy");
        for (String operation : new String[]{"start", "pause", "resume", "stop", "status", "manual"}) {
            root.then(literal(operation).then(argument("name", StringArgumentType.word())
                    .executes(context -> execute(context.getSource(),
                            StringArgumentType.getString(context, "name"), operation))));
        }
        return root;
    }

    private static int execute(ServerCommandSource source, String name, String operation) {
        var target = BotAuthorizationGate.INSTANCE.resolveAuthorized(source, name,
                operation.equals("status") ? BotAuthorizationPolicy.Operation.VIEW
                        : BotAuthorizationPolicy.Operation.COMMAND, "command:autonomy_" + operation);
        if (target.isEmpty()) return 0;
        var bot = target.get();
        var autonomy = AutonomyCoordinator.INSTANCE;
        try {
            switch (operation) {
                case "start" -> autonomy.start(bot);
                case "pause" -> autonomy.pause(bot);
                case "resume" -> autonomy.resume(bot);
                case "stop" -> autonomy.stop(bot);
                case "manual" -> autonomy.manual(bot);
                default -> { }
            }
            source.sendFeedback(() -> Text.literal("[AIBot] " + name + " " + autonomy.status(bot)), false);
            return 1;
        } catch (IllegalStateException exception) {
            source.sendError(Text.literal("[AIBot] " + exception.getMessage()));
            return 0;
        }
    }
}
