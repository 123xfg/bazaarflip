package com.bazaarflip;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Some Bazaar products can't actually be bought/sold until you hit a
 * certain Catacombs level (e.g. Crimson Essence requires Catacombs 20).
 * Hypixel doesn't expose this requirement anywhere in the public API -
 * neither /skyblock/bazaar nor /resources/skyblock/items include it - so
 * this is a manually maintained table.
 * <p>
 * Add to this as you run into more locked items in-game (the in-game
 * Bazaar tooltip tells you the requirement directly, like the "You must
 * have Catacombs Skill 20!" message).
 */
public class RequirementTable {

	private static final Map<String, Integer> CATACOMBS_LEVEL_REQUIRED = new ConcurrentHashMap<>();

	static {
		// productId -> minimum Catacombs level needed to trade it
		CATACOMBS_LEVEL_REQUIRED.put("ESSENCE_CRIMSON", 20);
		// Add more as you discover them, e.g.:
		// CATACOMBS_LEVEL_REQUIRED.put("ESSENCE_FROZEN", X);
	}

	/** Returns the Catacombs level required to trade this product, or 0 if there's no known requirement. */
	public static int requiredCatacombsLevel(String productId) {
		return CATACOMBS_LEVEL_REQUIRED.getOrDefault(productId, 0);
	}

	/** Lets you register a new requirement at runtime too, if you'd rather not edit source each time. */
	public static void register(String productId, int catacombsLevel) {
		CATACOMBS_LEVEL_REQUIRED.put(productId, catacombsLevel);
	}
}
