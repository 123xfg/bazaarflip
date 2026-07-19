package com.bazaarflip;

import net.minecraft.client.MinecraftClient;
import net.minecraft.scoreboard.Scoreboard;
import net.minecraft.scoreboard.ScoreboardDisplaySlot;
import net.minecraft.scoreboard.ScoreboardEntry;
import net.minecraft.scoreboard.ScoreboardObjective;

import java.util.function.DoubleConsumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads Purse and Bank directly off Hypixel's sidebar scoreboard, so the
 * affordability cap doesn't need you to type your balance in manually.
 * <p>
 * As of Minecraft 1.21.11, each ScoreboardEntry already carries its
 * fully-rendered line as a Text (entry.display()), so unlike older
 * versions there's no need to manually resolve team prefixes/suffixes.
 */
public class PlayerEconomy {

	// e.g. "Purse: 4,821,309" or "Purse: 4.2M" - Hypixel occasionally
	// switches to K/M/B shorthand once numbers get large.
	private static final Pattern PURSE_PATTERN = Pattern.compile("Purse:\\s*([0-9][0-9.,]*)\\s*([kKmMbB]?)");
	private static final Pattern BANK_PATTERN = Pattern.compile("Bank:\\s*([0-9][0-9.,]*)\\s*([kKmMbB]?)");

	private static double purse = 0;
	private static double bank = 0;

	/** Call once per client tick to keep the balance current. Safe to call even when no sidebar is showing. */
	public static void refresh(MinecraftClient client) {
		if (client.world == null) return;

		Scoreboard scoreboard = client.world.getScoreboard();
		ScoreboardObjective sidebar = scoreboard.getObjectiveForSlot(ScoreboardDisplaySlot.SIDEBAR);
		if (sidebar == null) return;

		for (ScoreboardEntry entry : scoreboard.getScoreboardEntries(sidebar)) {
			if (entry.display() == null) continue;
			String line = entry.display().getString();
			tryParse(line, PURSE_PATTERN, v -> purse = v);
			tryParse(line, BANK_PATTERN, v -> bank = v);
		}
	}

	private static void tryParse(String line, Pattern pattern, DoubleConsumer setter) {
		Matcher m = pattern.matcher(line);
		if (m.find()) {
			setter.accept(parseAmount(m.group(1), m.group(2)));
		}
	}

	private static double parseAmount(String number, String suffix) {
		double value = Double.parseDouble(number.replace(",", ""));
		switch (suffix.toUpperCase()) {
			case "K": return value * 1_000;
			case "M": return value * 1_000_000;
			case "B": return value * 1_000_000_000;
			default: return value;
		}
	}

	public static double getPurse() {
		return purse;
	}

	public static double getBank() {
		return bank;
	}

	/** Purse + Bank combined - what you could realistically deploy into flips (bank withdraws are instant/free on Hypixel). */
	public static double getTotalCoins() {
		return purse + bank;
	}
}
