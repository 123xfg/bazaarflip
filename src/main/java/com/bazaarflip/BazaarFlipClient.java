package com.bazaarflip;

import com.bazaarflip.api.BazaarApi;
import com.bazaarflip.api.BazaarProduct;
import com.bazaarflip.overlay.BazaarOverlay;
import com.bazaarflip.overlay.BazaarOverlay.Tab;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import org.lwjgl.glfw.GLFW;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

public class BazaarFlipClient implements ClientModInitializer {

	// Cached snapshot so the overlay/command reply instantly instead of blocking on a fetch.
	private static final AtomicReference<List<BazaarProduct>> CACHE = new AtomicReference<>(List.of());
	private static final AtomicReference<Long> LAST_UPDATED = new AtomicReference<>(0L);

	private static final long REFRESH_INTERVAL_MS = 30_000; // Hypixel updates bazaar roughly every 20-30s

	// Tunable filters - lower MIN_VOLUME_PER_HOUR to see rarer/riskier items,
	// raise MAX_MARGIN_PERCENT to allow bigger (often stale) margins through.
	// MIN_VOLUME_PER_HOUR of 20 means an item needs to plausibly trade at
	// least ~20 units an hour to be considered fillable within a session -
	// items only moving single digits/hour get excluded even if their
	// theoretical margin*volume math looks huge, since you'd likely never
	// actually see that volume come through.
	private static final double MIN_VOLUME_PER_HOUR = 20;
	private static final double MAX_MARGIN_PERCENT = 40;
	private static final int OVERLAY_ROW_COUNT = 15;

	// Set this to your actual Catacombs level. Items requiring a higher
	// level than this (per RequirementTable) get filtered out entirely,
	// since you literally can't trade them yet regardless of how good the
	// numbers look.
	private static final int PLAYER_CATACOMBS_LEVEL = 0;

	// Filters out items you can't meaningfully afford. Uses live Purse+Bank
	// read straight off the Hypixel sidebar (see PlayerEconomy) rather than
	// a manually typed number. MIN_AFFORDABLE_UNITS of 8 means: don't
	// suggest a flip unless you could buy at least 8 units of it right now
	// with what you have - buying 1-2 units of something isn't worth the
	// clicks even if the margin looks good.
	private static final int MIN_AFFORDABLE_UNITS = 8;

	// As of Minecraft 1.21.9, keybinding categories are structured objects
	// rather than plain translation-key strings, so this has to be created
	// once and reused instead of passing "category.bazaarflip" directly.
	private static final KeyBinding.Category KEYBIND_CATEGORY =
			KeyBinding.Category.create(Identifier.of("bazaarflip", "main"));

	private static KeyBinding toggleOverlayKey;

	@Override
	public void onInitializeClient() {
		BazaarOverlay.register();
		refreshCache(); // kick off an initial fetch immediately

		toggleOverlayKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
				"key.bazaarflip.toggle",
				InputUtil.Type.KEYSYM,
				GLFW.GLFW_KEY_B,
				KEYBIND_CATEGORY
		));

		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			while (toggleOverlayKey.wasPressed()) {
				BazaarOverlay.toggle();
			}
			PlayerEconomy.refresh(client);
			maybeRefreshCache();
		});

		ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
			dispatcher.register(ClientCommandManager.literal("bazaarflip")
					.executes(ctx -> {
						BazaarOverlay.toggle();
						sendMessage("§eBazaarFlip overlay " + (BazaarOverlay.isEnabled() ? "enabled" : "disabled") + ".");
						return 1;
					})
					.then(ClientCommandManager.literal("chat")
							.executes(ctx -> {
								runChatFlipCommand(10, "hourly");
								return 1;
							})
							.then(ClientCommandManager.argument("count", IntegerArgumentType.integer(1, 25))
									.executes(ctx -> {
										int count = IntegerArgumentType.getInteger(ctx, "count");
										runChatFlipCommand(count, "hourly");
										return 1;
									})
									.then(ClientCommandManager.argument("sortBy", StringArgumentType.word())
											.executes(ctx -> {
												int count = IntegerArgumentType.getInteger(ctx, "count");
												String sortBy = StringArgumentType.getString(ctx, "sortBy");
												runChatFlipCommand(count, sortBy);
												return 1;
											})
									)
							)
					)
			);
		});
	}

	/** Plain-text fallback: `/bazaarflip chat [count] [margin|percent|hourly]`. `/bazaarflip` alone toggles the overlay. */
	private void runChatFlipCommand(int count, String sortBy) {
		List<BazaarProduct> snapshot = CACHE.get();
		if (snapshot.isEmpty()) {
			sendMessage("§eBazaar data isn't loaded yet, try again in a few seconds.");
			return;
		}

		List<BazaarProduct> results = topFlips(snapshot, count, sortBy);

		sendMessage("§6§lTop " + results.size() + " Bazaar Flips (" + sortBy + ")");
		int rank = 1;
		for (BazaarProduct p : results) {
			sendMessage(String.format(
					"§7%d. §f%s §7- Buy@§a%.1f §7Sell@§c%.1f §7Margin: §b%.1f §7(%.1f%%) §7~§d%.0f§7/hr",
					rank++, p.productId, p.instantSellPrice, p.instantBuyPrice,
					p.getMargin(), p.getMarginPercent(), p.getEstimatedProfitPerHour()
			));
		}
	}

	/** Catacombs-requirement + affordability filtering, shared by every ranking (chat command and all overlay tabs). */
	private static List<BazaarProduct> filterTradable(List<BazaarProduct> snapshot) {
		double totalCoins = PlayerEconomy.getTotalCoins();

		return snapshot.stream()
				.filter(p -> RequirementTable.requiredCatacombsLevel(p.productId) <= PLAYER_CATACOMBS_LEVEL)
				// totalCoins <= 0 means we haven't successfully read the sidebar yet
				// (e.g. not in-game, or Purse/Bank lines weren't found this tick) -
				// in that case, don't filter anything out rather than hiding everything.
				.filter(p -> totalCoins <= 0 || p.instantSellPrice * MIN_AFFORDABLE_UNITS <= totalCoins)
				.collect(Collectors.toList());
	}

	private static List<BazaarProduct> topFlips(List<BazaarProduct> snapshot, int count, String sortBy) {
		List<BazaarProduct> tradable = filterTradable(snapshot);

		if (sortBy.equalsIgnoreCase("percent")) {
			return FlipFinder.topFlipsByMarginPercent(tradable, count, MIN_VOLUME_PER_HOUR, MAX_MARGIN_PERCENT);
		}
		if (sortBy.equalsIgnoreCase("margin")) {
			return FlipFinder.topFlipsByMargin(tradable, count, MIN_VOLUME_PER_HOUR, MAX_MARGIN_PERCENT);
		}
		// default: "hourly" - balances margin against how fast the item actually trades
		return FlipFinder.topFlipsByProfitPerHour(tradable, count, MIN_VOLUME_PER_HOUR, MAX_MARGIN_PERCENT);
	}

	/**
	 * Builds all four overlay tabs from one snapshot. Every tab ranks purely
	 * by Vol/hr (fastest-moving first) - what makes the tabs different from
	 * each other is the minimum margin each one requires before an item is
	 * even considered, which you set per tab by scrolling on the overlay
	 * panel (see BazaarOverlay.MIN_MARGIN / getMinMargin). A tab with its
	 * threshold at 0 just shows the fastest movers, full stop; raise it and
	 * that tab narrows down to "fastest movers that are still worth at least
	 * this much margin."
	 */
	private static Map<Tab, List<BazaarProduct>> buildTabFlips(List<BazaarProduct> snapshot) {
		List<BazaarProduct> tradable = filterTradable(snapshot);

		Map<Tab, List<BazaarProduct>> byTab = new EnumMap<>(Tab.class);
		for (Tab tab : Tab.values()) {
			double minMargin = BazaarOverlay.getMinMargin(tab);
			List<BazaarProduct> candidates = tradable.stream()
					.filter(p -> p.getMargin() >= minMargin)
					.collect(Collectors.toList());
			byTab.put(tab, FlipFinder.topFlipsByVolume(candidates, OVERLAY_ROW_COUNT, MIN_VOLUME_PER_HOUR, MAX_MARGIN_PERCENT));
		}
		return byTab;
	}

	/**
	 * Re-ranks the overlay tabs from the last cached snapshot without waiting
	 * for the next API refresh - called by BazaarOverlay right after a scroll
	 * changes a tab's min-margin threshold, so the change is reflected
	 * instantly instead of up to REFRESH_INTERVAL_MS later.
	 */
	public static void recomputeTabs() {
		List<BazaarProduct> snapshot = CACHE.get();
		if (snapshot.isEmpty()) return;
		BazaarOverlay.updateFlips(buildTabFlips(snapshot));
	}

	private void maybeRefreshCache() {
		long now = System.currentTimeMillis();
		if (now - LAST_UPDATED.get() > REFRESH_INTERVAL_MS) {
			refreshCache();
		}
	}

	/** Public in case something else wants to force an immediate fetch. */
	public static void forceRefresh() {
		refreshCache();
	}

	public static List<BazaarProduct> getCachedProducts() {
		return CACHE.get();
	}

	public static long getLastUpdatedMillis() {
		return LAST_UPDATED.get();
	}

	private static void refreshCache() {
		BazaarApi.fetchBazaar()
				.thenAccept(products -> {
					CACHE.set(products);
					LAST_UPDATED.set(System.currentTimeMillis());

					// Recomputed once per refresh (~30s), not per frame - the overlay just
					// reads whatever this last produced per tab. This is what makes it
					// "constantly updating": as soon as a better flip appears in a fresh
					// snapshot, the next render shows it without any extra action from you.
					BazaarOverlay.updateFlips(buildTabFlips(products));
				})
				.exceptionally(ex -> {
					sendMessage("§cFailed to fetch bazaar data: " + ex.getMessage());
					return null;
				});
	}

	private static void sendMessage(String message) {
		MinecraftClient client = MinecraftClient.getInstance();
		if (client.player != null) {
			client.player.sendMessage(Text.literal(message), false);
		}
	}
}
