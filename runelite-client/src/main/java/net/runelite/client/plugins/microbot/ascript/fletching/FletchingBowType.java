package net.runelite.client.plugins.microbot.ascript.fletching;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
@Getter
@RequiredArgsConstructor
public enum FletchingBowType {
    NONE(" ", "none"),
    OAK_SHORTBOW("Oak shortbow (u)", "oak shortbow (u)"),
    OAK_LONGBOW("Oak longbow (u)", "oak longbow (u)"),
    WILLOW_SHORTBOW("Willow shortbow (u)", "willow shortbow (u)"),
    WILLOW_LONGBOW("Willow longbow (u)", "willow longbow (u)"),
    MAPLE_SHORTBOW("Maple shortbow (u)", "maple shortbow (u)"),
    MAPLE_LONGBOW("Maple longbow (u)", "maple longbow (u)"),
    YEW_SHORTBOW("Yew shortbow (u)", "yew shortbow (u)"),
    YEW_LONGBOW("Yew longbow (u)", "yew longbow (u)"),
    MAGIC_SHORTBOW("Magic shortbow (u)", "magic shortbow (u)"),
    MAGIC_LONGBOW("Magic longbow (u)", "magic longbow (u)");

    private final String label;
    private final String unstrungName;

    @Override
    public String toString() {
        return label;
    }
}
