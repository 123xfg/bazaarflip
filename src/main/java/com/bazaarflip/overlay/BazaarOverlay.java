package com.bazaarflip.overlay;

import com.bazaarflip.api.BazaarProduct;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.ingame.HandledScreen;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

/**
 * Draws a small always-on panel to the right of the screen, listing the
 * current best flips, whenever any Bazaar-titled screen is open. It does not
 * open its own screen or grab input - it just paints on top of the vanilla
 * Bazaar GUI you already have open, the same way overlay mods like SkyCofl do.
 *
 * UNVERIFIED WITHOUT A REAL BUILD: this was written in a sandbox with no
 * access to Minecraft/Fabric's Maven repos, so none of it has been
 * test-compiled. The two things most likely to need small fixes on your
 * mapping/version:
 *   - DrawContext method names (fill / drawText) - these have been stable
 *     across recent 1.20.x-1.21.x, but double check against the Yarn
 *     javadoc for whatever build you land on.
 *   - ScreenEvents.afterRender's callback parameter order/types - confirm
 *     against Fabric API's ScreenEvents class for your fabric_version.
 *
 * Deliberately does NOT try to read the vanilla GUI's own pixel position
 * (that requires a mixin accessor for HandledScreen's protected x/y fields,
 * which differs more across versions than anything else here). Instead it
 * just anchors to a fixed margin from the game window's right edge, which
 * lands to the right of the GUI for any centered container screen - simpler
 * and far less likely to break on a version bump.
 */
public class BazaarOverlay {

	private static final Pattern BAZAAR_TITLE = Pattern.compile("(?i)bazaar");

	private static final int MARGIN_X = 8; // distance from the window's RIGHT edge
	private static final int MARGIN_Y = 40;
	private static final int PANEL_WIDTH = 260;
	private static final int ROW_HEIGHT = 11;
	private static final int PADDING = 5;

	private static final int BG_COLOR = 0xC0101010;       // semi-transparent dark background
	private static final int BORDER_COLOR = 0xFF3A3A3A;
	private static final int TITLE_COLOR = 0xFFFFD54A;    // gold
	private static final int NAME_COLOR = 0xFFE8E8E8;
	private static final int MARGIN_COLOR = 0xFF55DD55;   // green
	private static final int RATE_COLOR = 0xFF7FB8FF;     // light blue
	private static final int VOLUME_COLOR = 0xFFCCA0FF;   // light purple, units/hr
	private static final int STALE_COLOR = 0xFFAA5555;    // red, shown if data is old

	/** Recomputed once per bazaar refresh (~30s), not per frame - render() just reads this. */
	private static final AtomicReference<List<BazaarProduct>> TOP_FLIPS = new AtomicReference<>(List.of());
	private static final AtomicReference<Long> LAST_UPDATED_MILLIS = new AtomicReference<>(0L);
	private static final AtomicBoolean ENABLED = new AtomicBoolean(true);

	private static final long STALE_AFTER_MS = 90_000; // if data's this old, flag it instead of pretending it's fresh

	/** Call once from onInitializeClient(). */
	public static void register() {
		ScreenEvents.AFTER_INIT.register((client, screen, scaledWidth, scaledHeight) -> {
			if (!isBazaarScreen(screen)) return;

			ScreenEvents.afterRender(screen).register(BazaarOverlay::render);
		});
	}

	/** Called from BazaarFlipClient right after every successful bazaar refresh. */
	public static void updateFlips(List<BazaarProduct> topFlips) {
		TOP_FLIPS.set(topFlips);
		LAST_UPDATED_MILLIS.set(System.currentTimeMillis());
	}

	public static void toggle() {
		ENABLED.set(!ENABLED.get());
	}

	public static boolean isEnabled() {
		return ENABLED.get();
	}

	private static boolean isBazaarScreen(Screen screen) {
		if (!(screen instanceof HandledScreen<?>)) return false;
		return BAZAAR_TITLE.matcher(screen.getTitle().getString()).find();
	}

	private static void render(Screen screen, DrawContext context, int mouseX, int mouseY, float tickDelta) {
		if (!ENABLED.get()) return;

		List<BazaarProduct> flips = TOP_FLIPS.get();
		MinecraftClient client = MinecraftClient.getInstance();
		if (client.textRenderer == null) return;

		int rowCount = Math.max(flips.size(), 1);
		int panelHeight = PADDING * 2 + ROW_HEIGHT /* title */ + ROW_HEIGHT /* header */ + rowCount * ROW_HEIGHT;

		int x = screen.width - MARGIN_X - PANEL_WIDTH;
		int y = MARGIN_Y;

		context.fill(x, y, x + PANEL_WIDTH, y + panelHeight, BG_COLOR);
		drawBorder(context, x, y, PANEL_WIDTH, panelHeight, BORDER_COLOR);

		int textX = x + PADDING;
		int textY = y + PADDING;

		boolean stale = System.currentTimeMillis() - LAST_UPDATED_MILLIS.get() > STALE_AFTER_MS;
		String title = "BazaarFlip - Best Flips" + (stale ? " (stale)" : "");
		context.drawText(client.textRenderer, title, textX, textY, stale ? STALE_COLOR : TITLE_COLOR, true);
		textY += ROW_HEIGHT;

		context.drawText(client.textRenderer, "Item              Margin  Vol/hr   $/hr", textX, textY, 0xFF999999, false);
		textY += ROW_HEIGHT;

		if (flips.isEmpty()) {
			context.drawText(client.textRenderer, "(loading bazaar data...)", textX, textY, 0xFF999999, false);
			return;
		}

		for (BazaarProduct p : flips) {
			String name = shorten(p.productId.replace('_', ' '), 15);
			context.drawText(client.textRenderer, name, textX, textY, NAME_COLOR, false);
			context.drawText(client.textRenderer, formatCoins(p.getMargin()), textX + 100, textY, MARGIN_COLOR, false);
			context.drawText(client.textRenderer, formatUnits(p.getEstimatedVolumePerHour()) + "/h", textX + 145, textY, VOLUME_COLOR, false);
			context.drawText(client.textRenderer, formatCoins(p.getEstimatedProfitPerHour()) + "/h", textX + 200, textY, RATE_COLOR, false);
			textY += ROW_HEIGHT;
		}
	}

	private static void drawBorder(DrawContext context, int x, int y, int w, int h, int color) {
		context.fill(x, y, x + w, y + 1, color);              // top
		context.fill(x, y + h - 1, x + w, y + h, color);      // bottom
		context.fill(x, y, x + 1, y + h, color);              // left
		context.fill(x + w - 1, y, x + w, y + h, color);      // right
	}

	private static String shorten(String s, int maxLen) {
		return s.length() <= maxLen ? s : s.substring(0, maxLen - 1) + "\u2026";
	}

	private static String formatCoins(double value) {
		double abs = Math.abs(value);
		if (abs >= 1_000_000) return String.format("%.1fM", value / 1_000_000.0);
		if (abs >= 1_000) return String.format("%.1fK", value / 1_000.0);
		return String.format("%.0f", value);
	}

	/** Same K/M shorthand as formatCoins, but for raw unit counts (no decimals below 1K). */
	private static String formatUnits(double value) {
		double abs = Math.abs(value);
		if (abs >= 1_000_000) return String.format("%.1fM", value / 1_000_000.0);
		if (abs >= 1_000) return String.format("%.1fK", value / 1_000.0);
		return String.format("%.0f", value);
	}
}
