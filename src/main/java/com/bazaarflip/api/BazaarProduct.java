package com.bazaarflip.api;

/**
 * Snapshot of a single Bazaar product's quick_status.
 *
 * Note on naming (this trips people up): Hypixel's "buyPrice" is the price
 * YOU pay when you INSTANT-BUY (i.e. the lowest active sell order), and
 * "sellPrice" is what YOU get when you INSTANT-SELL (i.e. the highest active
 * buy order). So buyPrice >= sellPrice normally, and the gap between them
 * is the spread a flipper profits from.
 */
public class BazaarProduct {
	// Hypixel deducts this tax from the proceeds of every Bazaar sale (both
	// instant-sells and sell offers that fill) - it does NOT apply to the
	// buying side of a flip. Base rate is 1.25%; the "Bazaar Flipper"
	// Community Center perk can reduce it to 1.125% (level 1) or 1% (level
	// 2, max). Set this to match your actual perk level so margins reflect
	// what you'd really keep.
	// Note: some Mayor perks (e.g. Mayor Aura) temporarily raise this to
	// 2.25% - bump it manually during those if you want accurate numbers.
	private static final double BAZAAR_TAX_RATE = 0.0125;

	public final String productId;
	public final double instantBuyPrice;   // buyPrice
	public final double instantSellPrice;  // sellPrice
	public final double buyMovingWeek;
	public final double sellMovingWeek;
	public final int buyOrders;
	public final int sellOrders;

	// Live order book depth - total units currently posted at all visible
	// price tiers on each side. Hypixel only exposes the top handful of
	// tiers, so this understates true depth somewhat, but it's a much more
	// realistic "what's actually available right now" number than a
	// 7-day market-wide average.
	public final double sellOrderBookDepth;
	public final double buyOrderBookDepth;

	public BazaarProduct(String productId, double instantBuyPrice, double instantSellPrice,
						  double buyMovingWeek, double sellMovingWeek,
						  int buyOrders, int sellOrders,
						  double sellOrderBookDepth, double buyOrderBookDepth) {
		this.productId = productId;
		this.instantBuyPrice = instantBuyPrice;
		this.instantSellPrice = instantSellPrice;
		this.buyMovingWeek = buyMovingWeek;
		this.sellMovingWeek = sellMovingWeek;
		this.buyOrders = buyOrders;
		this.sellOrders = sellOrders;
		this.sellOrderBookDepth = sellOrderBookDepth;
		this.buyOrderBookDepth = buyOrderBookDepth;
	}

	/**
	 * Net margin if you place a sell order at instantBuyPrice and a buy order
	 * at instantSellPrice, after the Bazaar sell tax on the proceeds. This is
	 * what you'd actually keep per unit flipped, not the raw price gap.
	 */
	public double getMargin() {
		double netSellProceeds = instantBuyPrice * (1 - BAZAAR_TAX_RATE);
		return netSellProceeds - instantSellPrice;
	}

	/** Margin as a percentage of the sell-order (buy-in) price. */
	public double getMarginPercent() {
		if (instantSellPrice <= 0) return 0;
		return (getMargin() / instantSellPrice) * 100.0;
	}

	/** Rough weekly volume estimate, used to filter out illiquid items. */
	public double getWeeklyVolume() {
		return Math.min(buyMovingWeek, sellMovingWeek);
	}

	/**
	 * Rough estimate of how many units per hour actually trade through the
	 * book (not just sit posted). Uses the smaller of the two moving-week
	 * volumes since a flip needs BOTH a buy order to fill and a sell order
	 * to fill, so the slower side of the book is the real bottleneck.
	 * <p>
	 * This is then capped against the live order book depth - the actual
	 * number of units currently posted at visible price tiers. Weekly
	 * volume alone assumes you personally capture the entire market's
	 * activity, every hour, forever; the order book depth check keeps that
	 * from producing wildly inflated numbers on items where huge weekly
	 * volume comes from a handful of large trades rather than a genuinely
	 * liquid, continuously-refilling market (e.g. cheap essence items
	 * bought in huge bulk occasionally, not steadily).
	 */
	public double getEstimatedVolumePerHour() {
		double weeklyDerivedRate = getWeeklyVolume() / (7.0 * 24.0);
		double liveBookDepth = Math.min(sellOrderBookDepth, buyOrderBookDepth);
		// liveBookDepth of 0 usually just means the API returned an empty
		// summary for a very thin/rare item - don't let that zero out an
		// otherwise-valid weekly estimate.
		if (liveBookDepth <= 0) return weeklyDerivedRate;
		return Math.min(weeklyDerivedRate, liveBookDepth);
	}

	/**
	 * Estimated coins/hour if you could continuously flip this item at
	 * current volume. This is optimistic (assumes you always get filled at
	 * the observed rate) but is a solid relative ranking signal - it favors
	 * items that are both profitable AND liquid over items with a huge
	 * margin that might sit unfilled for hours.
	 */
	public double getEstimatedProfitPerHour() {
		return getMargin() * getEstimatedVolumePerHour();
	}
}
