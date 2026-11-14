package com.digsite.util;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/** MiniMessage 统一输出。前缀取自 config（DigSitePlugin 注入），未注入时用默认。 */
public final class Msg {
    private static final MiniMessage MM = MiniMessage.miniMessage();
    private static volatile String prefix =
            "<gray>[</gray><gradient:#c98f2d:#7a4d17>考古</gradient><gray>]</gray> ";

    private Msg() {}

    public static void setPrefix(String p) {
        if (p != null && !p.isBlank()) prefix = p;
    }

    public static Component mm(String mini) {
        return MM.deserialize(mini);
    }

    public static Component prefixed(String mini) {
        return mm(prefix + mini);
    }

    public static void send(CommandSender to, String mini) {
        to.sendMessage(prefixed(mini));
    }

    public static void send(Player to, String mini) {
        to.sendMessage(prefixed(mini));
    }

    public static void broadcast(String mini) {
        Bukkit.broadcast(prefixed(mini));
    }

    /** 纯文本（控制台/日志友好）。 */
    public static String plain(String mini) {
        return net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText()
                .serialize(mm(mini));
    }
}
