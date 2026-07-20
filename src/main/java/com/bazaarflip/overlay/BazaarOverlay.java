package com.bazaarflip.overlay;

import com.bazaarflip.BazaarFlipClient;
import com.bazaarflip.api.BazaarProduct;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenMouseEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.client.input.MouseInput;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

/**
 * Draws a small always-on panel to the right of the screen, listing the
 * current best flips, whenever any Bazaar-titled screen is open. It does not
 * open its own screen or grab input beyond its own title bar (drag to move)
 * and tab row (click to switch) - clicks anywhere else pass straight through
 * to the vanilla Bazaar GUI underneath, the same way overlay mods like
 * SkyCofl do.
 *
 * The mouse-click handlers (ScreenMouseEvents.allowMouseClick / allowMouseRelease)
 * were verified against the actual Fabric API 0.141.1+1.21.11 source: as of
 * Minecraft 1.21.11 those callbacks only receive (Screen, MouseInput) - no
 * mouseX/mouseY - so the click position is read from the value render() cached
 * last frame (lastMouseX/lastMouseY) rather than from the click event itself.
 * The drag logic uses that same per-frame mouseX/mouseY from render(), so both
 * paths share one coordinate source.
 */
public class BazaarOverlay {

	/** Which ranking the panel currently shows. Click a tab in-game to switch. */
	public enum Tab {
		ALL("All"),
		FAST("Fast"),
		PROFIT("Profit"),
		SPEED("Speed");

		public final String label;

		Tab(String label) {
			this.label = label;
		}
	}

	private static final Pattern BAZAAR_TITLE = Pattern.compile("(?i)bazaar");

	private static final int DEFAULT_MARGIN_X = 8; // distance from the window's RIGHT edge, before any drag offset
	private static final int DEFAULT_MARGIN_Y = 40;
	private static final int PANEL_WIDTH = 260;
	private static final int ROW_HEIGHT = 11;
	private static final int TAB_ROW_HEIGHT = 13;
	private static final int PADDING = 5;
	private static final int TAB_GAP = 4;

	private static final int BG_COLOR = 0xC0101010;       // semi-transparent dark background
	private static final int BORDER_COLOR = 0xFF3A3A3A;
	private static final int TITLE_COLOR = 0xFFFFD54A;    // gold
	private static final int TITLE_DRAGGING_COLOR = 0xFFFFFFFF; // white while actively being dragged, as a cue
	private static final int NAME_COLOR = 0xFFE8E8E8;
	private static final int MARGIN_COLOR = 0xFF55DD55;   // green
	private static final int RATE_COLOR = 0xFF7FB8FF;     // light blue
	private static final int VOLUME_COLOR = 0xFFCCA0FF;   // light purple, units/hr
	private static final int STALE_COLOR = 0xFFAA5555;    // red, shown if data is old
	private static final int TAB_TEXT_COLOR = 0xFFCCCCCC;
	private static final int TAB_TEXT_SELECTED_COLOR = 0xFF000000;
	private static final int TAB_BG_SELECTED_COLOR = 0xFFFFD54A; // gold, matches title
	private static final int TAB_BG_COLOR = 0xFF2A2A2A;

	/** Recomputed once per bazaar refresh (~30s), not per frame - one ranked list per tab. */
	private static final AtomicReference<Map<Tab, List<BazaarProduct>>> TAB_FLIPS =
			new AtomicReference<>(new EnumMap<>(Tab.class));
	private static final AtomicReference<Tab> SELECTED_TAB = new AtomicReference<>(Tab.ALL);
	private static final AtomicReference<Long> LAST_UPDATED_MILLIS = new AtomicReference<>(0L);
	private static final AtomicBoolean ENABLED = new AtomicBoolean(true);

	// Per-tab minimum margin, scroll-adjustable in-panel (see allowMouseScroll
	// below). Every tab ranks by pure Vol/hr - this threshold is what makes
	// the tabs different from each other: "show me the fastest-moving flips
	// that still clear at least this much margin per unit." Starting values
	// are just reasonable defaults, not fixed - scroll on any tab to change it.
	private static final Map<Tab, Double> MIN_MARGIN = new EnumMap<>(Tab.class);
	private static final double MARGIN_SCROLL_STEP = 500;
	static {
		MIN_MARGIN.put(Tab.ALL, 0.0);
		MIN_MARGIN.put(Tab.FAST, 2000.0);
		MIN_MARGIN.put(Tab.PROFIT, 5000.0);
		MIN_MARGIN.put(Tab.SPEED, 0.0);
	}

	private static final long STALE_AFTER_MS = 90_000; // if data's this old, flag it instead of pretending it's fresh

	// Drag state. Minecraft's client-side render/input handling is single
	// threaded, so plain fields (not Atomic*) are fine here - everything
	// touching these runs on the client thread.
	private static int offsetX = 0;
	private static int offsetY = 0;
	private static boolean dragging = false;
	private static double dragStartMouseX;
	private static double dragStartMouseY;
	private static int dragStartOffsetX;
	private static int dragStartOffsetY;
	private static int lastPanelHeight = 60; // updated every render(), used for click hit-testing/clamping

	// As of Fabric API 0.141.1+1.21.11 (Minecraft 1.21.11), ScreenMouseEvents'
	// AllowMouseClick/AllowMouseRelease callbacks only receive (Screen, MouseInput) -
	// MouseInput just wraps the button + modifier keys, it no longer carries the
	// cursor position. So we cache the position from render() (updated every frame,
	// same source the drag logic already uses) and read it back in the click handlers.
	private static double lastMouseX = 0;
	private static double lastMouseY = 0;

	/** Call once from onInitializeClient(). */
	public static void register() {
		ScreenEvents.AFTER_INIT.register((client, screen, scaledWidth, scaledHeight) -> {
			if (!isBazaarScreen(screen)) return;

			ScreenEvents.afterRender(screen).register(BazaarOverlay::render);

			ScreenMouseEvents.allowMouseClick(screen).register((scr, context) -> {
				if (!ENABLED.get()) return true;

				double mouseX = lastMouseX;
				double mouseY = lastMouseY;
				int button = context.button();

				Tab clicked = tabAt(scr, mouseX, mouseY);
				if (clicked != null) {
					SELECTED_TAB.set(clicked);
					return false; // swallow the click so it doesn't hit the Bazaar GUI underneath
				}

				if (button == 0 && isOnTitleBar(scr, mouseX, mouseY)) {
					dragging = true;
					dragStartMouseX = mouseX;
					dragStartMouseY = mouseY;
					dragStartOffsetX = offsetX;
					dragStartOffsetY = offsetY;
					return false; // swallow so the Bazaar GUI doesn't also react to this click
				}

				return true; // outside the panel entirely - let the click through as normal
			});

			ScreenMouseEvents.allowMouseRelease(screen).register((scr, context) -> {
				if (dragging && context.button() == 0) {
					dragging = false;
					return false;
				}
				return true;
			});

			// Unlike the click events above, scroll events on 0.141.1+1.21.11
			// still take (Screen, mouseX, mouseY, horizontalAmount, verticalAmount)
			// directly - no MouseInput wrapper here, so this one's unaffected by
			// that change. Only swallow the scroll (and adjust the threshold) when
			// the cursor is actually over our panel; otherwise let it through so
			// the vanilla Bazaar item list underneath keeps scrolling normally.
			ScreenMouseEvents.allowMouseScroll(screen).register((scr, mouseX, mouseY, horizontalAmount, verticalAmount) -> {
				if (!ENABLED.get() || !isWithinPanel(scr, mouseX, mouseY)) return true;

				Tab tab = SELECTED_TAB.get();
				double current = MIN_MARGIN.getOrDefault(tab, 0.0);
				double updated = Math.max(0, current + Math.signum(verticalAmount) * MARGIN_SCROLL_STEP);
				MIN_MARGIN.put(tab, updated);
				BazaarFlipClient.recomputeTabs();
				return false;
			});
		});
	}

	/** Called from BazaarFlipClient right after every successful bazaar refresh, one ranked list per tab. */
	public static void updateFlips(Map<Tab, List<BazaarProduct>> flipsByTab) {
		TAB_FLIPS.set(flipsByTab);
		LAST_UPDATED_MILLIS.set(System.currentTimeMillis());
	}

	public static void toggle() {
		ENABLED.set(!ENABLED.get());
	}

	public static boolean isEnabled() {
		return ENABLED.get();
	}

	/** Current scroll-adjusted min-margin threshold for a tab, read by BazaarFlipClient when ranking. */
	public static double getMinMargin(Tab tab) {
		return MIN_MARGIN.getOrDefault(tab, 0.0);
	}

	private static boolean isBazaarScreen(Screen screen) {
		if (!(screen instanceof HandledScreen<?>)) return false;
		return BAZAAR_TITLE.matcher(screen.getTitle().getString()).find();
	}

	private static void render(Screen screen, DrawContext context, int mouseX, int mouseY, float tickDelta) {
		if (!ENABLED.get()) return;

		// Update the drag offset first, using the mouse position render()
		// already receives this frame - this is what makes dragging feel
		// live/continuous without needing to poll the mouse separately.
		if (dragging) {
			offsetX = dragStartOffsetX + (int) Math.round(mouseX - dragStartMouseX);
			offsetY = dragStartOffsetY + (int) Math.round(mouseY - dragStartMouseY);
		}
		lastMouseX = mouseX;
		lastMouseY = mouseY;

		List<BazaarProduct> flips = TAB_FLIPS.get().getOrDefault(SELECTED_TAB.get(), List.of());
		MinecraftClient client = MinecraftClient.getInstance();
		if (client.textRenderer == null) return;

		int rowCount = Math.max(flips.size(), 1);
		int panelHeight = PADDING * 2 + ROW_HEIGHT /* title */ + TAB_ROW_HEIGHT /* tabs */
				+ ROW_HEIGHT /* min-margin line */ + ROW_HEIGHT /* column header */ + rowCount * ROW_HEIGHT;
		lastPanelHeight = panelHeight;

		int x = panelX(screen);
		int y = panelY(screen, panelHeight);

		context.fill(x, y, x + PANEL_WIDTH, y + panelHeight, BG_COLOR);
		drawBorder(context, x, y, PANEL_WIDTH, panelHeight, BORDER_COLOR);

		int textX = x + PADDING;
		int textY = y + PADDING;

		boolean stale = System.currentTimeMillis() - LAST_UPDATED_MILLIS.get() > STALE_AFTER_MS;
		String title = "BazaarFlip - Best Flips" + (stale ? " (stale)" : "") + (dragging ? " [drag]" : "");
		int titleColor = dragging ? TITLE_DRAGGING_COLOR : (stale ? STALE_COLOR : TITLE_COLOR);
		context.drawText(client.textRenderer, title, textX, textY, titleColor, true);
		textY += ROW_HEIGHT;

		drawTabs(context, client, textX, textY);
		textY += TAB_ROW_HEIGHT;

		double minMargin = MIN_MARGIN.getOrDefault(SELECTED_TAB.get(), 0.0);
		context.drawText(client.textRenderer, "Min margin: " + formatCoins(minMargin) + " (scroll to adjust)", textX, textY, 0xFF999999, false);
		textY += ROW_HEIGHT;

		context.drawText(client.textRenderer, "Item", textX, textY, 0xFF999999, false);
		context.drawText(client.textRenderer, "Margin", textX + 100, textY, 0xFF999999, false);
		context.drawText(client.textRenderer, "Vol/hr", textX + 145, textY, 0xFF999999, false);
		context.drawText(client.textRenderer, "$/hr", textX + 200, textY, 0xFF999999, false);
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

	/** Default anchor (top-right corner) plus whatever the panel has been dragged by, clamped so it can't be dragged fully off-window. */
	private static int panelX(Screen screen) {
		int x = screen.width - DEFAULT_MARGIN_X - PANEL_WIDTH + offsetX;
		return Math.max(0, Math.min(x, screen.width - PANEL_WIDTH));
	}

	private static int panelY(Screen screen, int panelHeight) {
		int y = DEFAULT_MARGIN_Y + offsetY;
		return Math.max(0, Math.min(y, screen.height - panelHeight));
	}

	/** True if the click landed on the title row (the drag handle), as opposed to the tab row or the flip list below it. */
	private static boolean isOnTitleBar(Screen screen, double mouseX, double mouseY) {
		int x = panelX(screen);
		int y = panelY(screen, lastPanelHeight);
		return mouseX >= x && mouseX <= x + PANEL_WIDTH
				&& mouseY >= y && mouseY <= y + PADDING + ROW_HEIGHT;
	}

	/** True if the cursor is anywhere over the panel at all, used to gate scroll handling. */
	private static boolean isWithinPanel(Screen screen, double mouseX, double mouseY) {
		int x = panelX(screen);
		int y = panelY(screen, lastPanelHeight);
		return mouseX >= x && mouseX <= x + PANEL_WIDTH
				&& mouseY >= y && mouseY <= y + lastPanelHeight;
	}

	private static void drawTabs(DrawContext context, MinecraftClient client, int startX, int y) {
		int x = startX;
		for (Tab tab : Tab.values()) {
			int textWidth = client.textRenderer.getWidth(tab.label);
			int boxWidth = textWidth + 8;
			boolean selected = SELECTED_TAB.get() == tab;

			context.fill(x, y, x + boxWidth, y + TAB_ROW_HEIGHT - 1, selected ? TAB_BG_SELECTED_COLOR : TAB_BG_COLOR);
			context.drawText(client.textRenderer, tab.label, x + 4, y + 2,
					selected ? TAB_TEXT_SELECTED_COLOR : TAB_TEXT_COLOR, false);

			x += boxWidth + TAB_GAP;
		}
	}

	/**
	 * Hit-tests a click against the current tab bar. Recomputes the exact
	 * same geometry drawTabs() used last render, using the CURRENT
	 * (possibly dragged) panel position rather than the fixed default one.
	 */
	private static Tab tabAt(Screen screen, double mouseX, double mouseY) {
		MinecraftClient client = MinecraftClient.getInstance();
		if (client.textRenderer == null) return null;

		int panelX = panelX(screen);
		int tabY = panelY(screen, lastPanelHeight) + PADDING + ROW_HEIGHT; // title row, then tabs
		int x = panelX + PADDING;

		if (mouseY < tabY || mouseY > tabY + TAB_ROW_HEIGHT - 1) return null;

		for (Tab tab : Tab.values()) {
			int textWidth = client.textRenderer.getWidth(tab.label);
			int boxWidth = textWidth + 8;
			if (mouseX >= x && mouseX <= x + boxWidth) {
				return tab;
			}
			x += boxWidth + TAB_GAP;
		}
		return null;
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
