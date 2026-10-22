/* SPDX-License-Identifier: GPL-3.0-or-later */
package dev.mcrashcraft.namelessforge;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.namelessmc.plugin.common.Permission;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.TextColor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeAll;

import static org.junit.jupiter.api.Assertions.*;

class ForgeAdapterPolicyTest {
    @BeforeAll
    static void initializeNativeChatRegistries() {
        NativeChatTestSupport.bootstrap();
    }

    @Test
    void adventureRichLinkReallyReachesTheNativeMinecraftStyle() {
        var rich = Component.text("Website", TextColor.color(0x12ab34))
                .clickEvent(ClickEvent.openUrl("https://example.test/account"))
                .hoverEvent(Component.text("Open your account"));
        var converted = ForgeAudienceProvider.minecraftComponent(rich);
        assertEquals("Website", converted.getString());
        assertNotNull(converted.getStyle().getClickEvent(), "A plain-text fallback silently loses account links");
        JsonObject nativeJson = JsonParser.parseString(NativeChatTestSupport.json(converted)).getAsJsonObject();
        JsonObject click = nativeEvent(nativeJson, "clickEvent", "click_event");
        assertEquals("open_url", click.get("action").getAsString());
        assertEquals("https://example.test/account", nativeString(click, "value", "url"));
        assertEquals(0x12ab34, converted.getStyle().getColor().getValue());
        JsonObject hover = nativeEvent(nativeJson, "hoverEvent", "hover_event");
        assertEquals("show_text", hover.get("action").getAsString());
        var contents = hover.has("contents") ? hover.get("contents") : hover.get("value");
        assertEquals("Open your account", contents.isJsonPrimitive() ? contents.getAsString()
                : contents.getAsJsonObject().get("text").getAsString());
    }

    @Test
    void commandClickReachesMinecraftAsASuggestion() {
        var rich = Component.text("Verify").clickEvent(ClickEvent.runCommand("/verify example"));
        var converted = ForgeAudienceProvider.minecraftComponent(rich);
        assertNotNull(converted.getStyle().getClickEvent());
        JsonObject nativeJson = JsonParser.parseString(NativeChatTestSupport.json(converted)).getAsJsonObject();
        JsonObject click = nativeEvent(nativeJson, "clickEvent", "click_event");
        assertEquals("suggest_command", click.get("action").getAsString());
        assertEquals("/verify example", nativeString(click, "value", "command"));
    }

    private static JsonObject nativeEvent(JsonObject component, String legacy, String modern) {
        assertTrue(component.has(legacy) || component.has(modern), "Native serialization lost the rich event");
        return component.getAsJsonObject(component.has(modern) ? modern : legacy);
    }

    private static String nativeString(JsonObject value, String legacy, String modern) {
        return value.get(value.has(modern) ? modern : legacy).getAsString();
    }

    @Test
    void selfServiceCommandsWorkWithoutGrantingAdministrativeWebsiteActions() {
        assertTrue(ForgePlayer.fallbackPermission(Permission.COMMAND_REGISTER, false));
        assertTrue(ForgePlayer.fallbackPermission(Permission.COMMAND_VERIFY, false));
        assertTrue(ForgePlayer.fallbackPermission(Permission.COMMAND_GET_NOTIFICATIONS, false));
        assertFalse(ForgePlayer.fallbackPermission(Permission.COMMAND_PLUGIN, false));
        assertFalse(ForgePlayer.fallbackPermission(Permission.COMMAND_STORE_CHANGE_CREDITS, false));
        assertFalse(ForgePlayer.fallbackPermission(Permission.COMMAND_STORE_VIEW_CREDITS_OTHERS, false));
        assertFalse(ForgePlayer.fallbackPermission(Permission.COMMAND_REPORT_NOTIFY_BROADCAST, false));
        assertFalse(ForgePlayer.fallbackPermission(Permission.COMMAND_USER_INFO_OTHERS, false));
        assertTrue(ForgePlayer.fallbackPermission(Permission.COMMAND_USER_INFO, false));
        assertTrue(ForgePlayer.fallbackPermission(Permission.COMMAND_USER_INFO_OTHERS, true));
        assertTrue(ForgePlayer.fallbackPermission(Permission.COMMAND_PLUGIN, true));
    }

    @Test
    void websiteLinkRemainsClickableButUnsafeLocalLinkIsRemovedEvenInsideHoverText() {
        String raw = """
                {"text":"Account","clickEvent":{"action":"open_url","value":"https://example.test/verify?code=abc"},
                 "hoverEvent":{"action":"show_text","contents":{"text":"unsafe","clickEvent":{"action":"open_url","value":"file:///C:/secret"}}}}
                """;
        JsonObject result = JsonParser.parseString(ForgeAudienceProvider.sanitizeChatJson(raw)).getAsJsonObject();
        assertEquals("https://example.test/verify?code=abc", result.getAsJsonObject("clickEvent").get("value").getAsString());
        assertFalse(result.getAsJsonObject("hoverEvent").getAsJsonObject("contents").has("clickEvent"));
    }

    @Test
    void WebsiteCannotMakeAChatClickExecuteACommandOrOpenAFile() {
        JsonObject command = JsonParser.parseString(ForgeAudienceProvider.sanitizeChatJson(
                "{\"text\":\"Click\",\"clickEvent\":{\"action\":\"run_command\",\"value\":\"/verify example\"}}"))
                .getAsJsonObject();
        assertEquals("suggest_command", command.getAsJsonObject("clickEvent").get("action").getAsString());
        JsonObject file = JsonParser.parseString(ForgeAudienceProvider.sanitizeChatJson(
                "{\"text\":\"Click\",\"clickEvent\":{\"action\":\"open_file\",\"value\":\"C:/secret\"}}"))
                .getAsJsonObject();
        assertFalse(file.has("clickEvent"));
    }

    @Test
    void modernChatSchemaRetainsTheSameClickSafetyPolicy() {
        JsonObject command = JsonParser.parseString(ForgeAudienceProvider.sanitizeChatJson(
                "{\"text\":\"Click\",\"click_event\":{\"action\":\"run_command\",\"command\":\"/verify example\"}}"))
                .getAsJsonObject();
        assertEquals("suggest_command", command.getAsJsonObject("click_event").get("action").getAsString());
        JsonObject nested = JsonParser.parseString(ForgeAudienceProvider.sanitizeChatJson(
                "{\"text\":\"Click\",\"hover_event\":{\"action\":\"show_text\",\"value\":{\"text\":\"Unsafe\","
                + "\"click_event\":{\"action\":\"open_url\",\"url\":\"file:///private\"}}}}"))
                .getAsJsonObject();
        assertFalse(nested.getAsJsonObject("hover_event").getAsJsonObject("value").has("click_event"));
    }

    @Test
    void conflictingLegacyAndModernClickValuesCannotBypassSanitization() {
        JsonObject result = JsonParser.parseString(ForgeAudienceProvider.sanitizeChatJson(
                "{\"text\":\"Click\",\"click_event\":{\"action\":\"open_url\","
                + "\"value\":\"https://example.test/safe\",\"url\":\"file:///private\"}}"))
                .getAsJsonObject();
        assertFalse(result.has("click_event"));
    }

    @Test
    void commandArgumentsPreserveReportWordsAndTheEmptyTabCompletionPosition() {
        assertArrayEquals(new String[]{"Player", "a", "report", "reason"},
                ForgeCommands.arguments("Player a report reason", false));
        assertArrayEquals(new String[]{"Player", ""}, ForgeCommands.arguments("Player ", true));
        assertArrayEquals(new String[]{""}, ForgeCommands.arguments("", true));
        assertArrayEquals(new String[0], ForgeCommands.arguments("", false));
        assertThrows(IllegalArgumentException.class, () -> ForgeCommands.arguments("x".repeat(4_097), false));
    }

    @Test
    void configuredAliasesCannotInjectArgumentsOrANewCommand() {
        assertTrue(ForgeCommands.validName("namelessforge:register"));
        assertFalse(ForgeCommands.validName("register extra"));
        assertFalse(ForgeCommands.validName("register\nstop"));
        assertFalse(ForgeCommands.validName("/register"));
        assertFalse(ForgeCommands.validName(""));
    }
}
