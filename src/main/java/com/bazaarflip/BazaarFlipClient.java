package com.bazaarflip;

import com.bazaarflip.api.BazaarApi;
import com.bazaarflip.api.BazaarProduct;
import com.bazaarflip.overlay.BazaarOverlay;
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
import org.lwjgl.glfw.GLFW;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

public class BazaarFlipClient implements ClientModInitializer {

	// Cached snapshot so the overlay/command reply instantly instead of blocking on a fetch.
	private static final AtomicReference<List<BazaarProduct>> CACHE = new AtomicReference<>(List.of());
	private static final AtomicReference<Long> LAST_UPDATED = new AtomicReference<>(0L);

	private static final long REFRESH_INTERVAL_MS = 30_000; // Hypixel updates bazaar roughly every 20-30s

	// Tunable filters - lower MIN_WEEKLY_VOLUME to see rarer/riskier items, raise
	// MAX_MARGIN_PERCENT to allow bigger (often stale) margins through.
	private static final double MIN_WEEKLY_VOLUME = 2000;
	private static final double MAX_MARGIN_PERCENT = 40;
	private static final int OVERLAY_ROW_COUNT = 8;

	private static KeyBinding toggleOverlayKey;

	@Override
	public void onInitializeClient() {
		BazaarOverlay.register();
		refreshCache(); // kick off an initial fetch immediately

		toggleOverlayKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
				"key.bazaarflip.toggle",
				InputUtil.Type.KEYSYM,
				GLFW.GLFW_KEY_B,
				"category.bazaarflip"
		));

		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			while (toggleOverlayKey.wasPressed()) {
				BazaarOverlay.toggle();
			}
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

	private static List<BazaarProduct> topFlips(List<BazaarProduct> snapshot, int count, String sortBy) {
		if (sortBy.equalsIgnoreCase("percent")) {
			return FlipFinder.topFlipsByMarginPercent(snapshot, count, MIN_WEEKLY_VOLUME, MAX_MARGIN_PERCENT);
		}
		if (sortBy.equalsIgnoreCase("margin")) {
			return FlipFinder.topFlipsByMargin(snapshot, count, MIN_WEEKLY_VOLUME, MAX_MARGIN_PERCENT);
		}
		// default: "hourly" - balances margin against how fast the item actually trades
		return FlipFinder.topFlipsByProfitPerHour(snapshot, count, MIN_WEEKLY_VOLUME, MAX_MARGIN_PERCENT);
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
					// reads whatever this last produced. This is what makes it "constantly
					// updating": as soon as a better flip appears in a fresh snapshot, the
					// next render (already-open Bazaar screen or the next one you open)
					// shows it without any extra action from you.
					BazaarOverlay.updateFlips(topFlips(products, OVERLAY_ROW_COUNT, "hourly"));
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
