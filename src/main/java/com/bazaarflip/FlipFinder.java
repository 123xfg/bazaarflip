package com.bazaarflip;

import com.bazaarflip.api.BazaarProduct;

import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class FlipFinder {

	/**
	 * Returns the top N flips sorted by absolute margin, filtering out
	 * illiquid items (fewer than minVolumePerHour units actually trading per
	 * hour) and items with a margin percent above maxMarginPercent (huge %s
	 * on obscure items are usually stale/manipulated data, not real
	 * opportunities).
	 */
	public static List<BazaarProduct> topFlipsByMargin(List<BazaarProduct> products, int limit,
														double minVolumePerHour, double maxMarginPercent) {
		return filtered(products, minVolumePerHour, maxMarginPercent)
				.sorted(Comparator.comparingDouble(BazaarProduct::getMargin).reversed())
				.limit(limit)
				.collect(Collectors.toList());
	}

	/**
	 * Returns the top N flips sorted by margin percent instead of raw coins.
	 * Better for finding efficient use of a small amount of capital.
	 */
	public static List<BazaarProduct> topFlipsByMarginPercent(List<BazaarProduct> products, int limit,
																double minVolumePerHour, double maxMarginPercent) {
		return filtered(products, minVolumePerHour, maxMarginPercent)
				.sorted(Comparator.comparingDouble(BazaarProduct::getMarginPercent).reversed())
				.limit(limit)
				.collect(Collectors.toList());
	}

	/**
	 * Returns the top N flips sorted by estimated coins/hour - balances raw
	 * margin against how fast you'll actually get filled (via each side's
	 * moving-week volume). This is the ranking the live overlay uses, since
	 * "biggest margin" alone is misleading if the item barely trades.
	 */
	public static List<BazaarProduct> topFlipsByProfitPerHour(List<BazaarProduct> products, int limit,
																double minVolumePerHour, double maxMarginPercent) {
		return filtered(products, minVolumePerHour, maxMarginPercent)
				.sorted(Comparator.comparingDouble(BazaarProduct::getEstimatedProfitPerHour).reversed())
				.limit(limit)
				.collect(Collectors.toList());
	}

	/**
	 * minVolumePerHour is checked against actual units/hour, not weekly
	 * volume - an item can clear the old weekly-volume bar while still only
	 * moving a handful of units an hour, which isn't something you can
	 * realistically flip on repeatedly within a play session.
	 */
	private static Stream<BazaarProduct> filtered(List<BazaarProduct> products,
												   double minVolumePerHour, double maxMarginPercent) {
		return products.stream()
				.filter(p -> p.getMargin() > 0)
				.filter(p -> p.getEstimatedVolumePerHour() >= minVolumePerHour)
				.filter(p -> p.getMarginPercent() <= maxMarginPercent);
	}
}
