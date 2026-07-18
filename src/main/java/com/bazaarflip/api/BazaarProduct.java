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
	public final String productId;
	public final double instantBuyPrice;   // buyPrice
	public final double instantSellPrice;  // sellPrice
	public final double buyMovingWeek;
	public final double sellMovingWeek;
	public final int buyOrders;
	public final int sellOrders;

	public BazaarProduct(String productId, double instantBuyPrice, double instantSellPrice,
						  double buyMovingWeek, double sellMovingWeek,
						  int buyOrders, int sellOrders) {
		this.productId = productId;
		this.instantBuyPrice = instantBuyPrice;
		this.instantSellPrice = instantSellPrice;
		this.buyMovingWeek = buyMovingWeek;
		this.sellMovingWeek = sellMovingWeek;
		this.buyOrders = buyOrders;
		this.sellOrders = sellOrders;
	}

	/** Raw margin if you place a sell order at instantBuyPrice and a buy order at instantSellPrice. */
	public double getMargin() {
		return instantBuyPrice - instantSellPrice;
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
	 */
	public double getEstimatedVolumePerHour() {
		return getWeeklyVolume() / (7.0 * 24.0);
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
