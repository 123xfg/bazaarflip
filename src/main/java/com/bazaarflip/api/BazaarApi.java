package com.bazaarflip.api;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Fetches live Bazaar data from Hypixel's public API.
 * This endpoint is public and does NOT require an API key.
 */
public class BazaarApi {

	private static final String BAZAAR_URL = "https://api.hypixel.net/v2/skyblock/bazaar";

	private static final HttpClient CLIENT = HttpClient.newBuilder()
			.connectTimeout(Duration.ofSeconds(10))
			.build();

	/**
	 * Fetches the current bazaar snapshot asynchronously.
	 */
	public static CompletableFuture<List<BazaarProduct>> fetchBazaar() {
		HttpRequest request = HttpRequest.newBuilder()
				.uri(URI.create(BAZAAR_URL))
				.timeout(Duration.ofSeconds(10))
				.GET()
				.build();

		return CLIENT.sendAsync(request, HttpResponse.BodyHandlers.ofString())
				.thenApply(BazaarApi::parseResponse);
	}

	private static List<BazaarProduct> parseResponse(HttpResponse<String> response) {
		List<BazaarProduct> products = new ArrayList<>();

		if (response.statusCode() != 200) {
			throw new RuntimeException("Bazaar API returned status " + response.statusCode());
		}

		JsonObject root = JsonParser.parseString(response.body()).getAsJsonObject();

		if (!root.get("success").getAsBoolean()) {
			throw new RuntimeException("Bazaar API responded with success=false");
		}

		JsonObject productsJson = root.getAsJsonObject("products");

		for (String productId : productsJson.keySet()) {
			JsonObject productObj = productsJson.getAsJsonObject(productId);
			JsonObject quickStatus = productObj.getAsJsonObject("quick_status");

			double buyPrice = quickStatus.get("buyPrice").getAsDouble();   // instant-buy price
			double sellPrice = quickStatus.get("sellPrice").getAsDouble(); // instant-sell price
			double buyMovingWeek = quickStatus.get("buyMovingWeek").getAsDouble();
			double sellMovingWeek = quickStatus.get("sellMovingWeek").getAsDouble();
			int buyOrders = quickStatus.get("buyOrders").getAsInt();
			int sellOrders = quickStatus.get("sellOrders").getAsInt();

			// sell_summary/buy_summary are the live order book - the actual
			// quantities other players currently have posted at each price
			// tier. This is what caps how much you could realistically fill
			// right now, as opposed to buyMovingWeek/sellMovingWeek which
			// are a market-wide 7-day total that assumes you capture 100%
			// of everyone's trading activity - unrealistic for anything
			// with real competition.
			double sellOrderBookDepth = sumOrderBookQuantities(productObj.getAsJsonArray("sell_summary"));
			double buyOrderBookDepth = sumOrderBookQuantities(productObj.getAsJsonArray("buy_summary"));

			products.add(new BazaarProduct(
					productId,
					buyPrice,
					sellPrice,
					buyMovingWeek,
					sellMovingWeek,
					buyOrders,
					sellOrders,
					sellOrderBookDepth,
					buyOrderBookDepth
			));
		}

		return products;
	}

	private static double sumOrderBookQuantities(com.google.gson.JsonArray summary) {
		if (summary == null) return 0;
		double total = 0;
		for (int i = 0; i < summary.size(); i++) {
			total += summary.get(i).getAsJsonObject().get("amount").getAsDouble();
		}
		return total;
	}
}
