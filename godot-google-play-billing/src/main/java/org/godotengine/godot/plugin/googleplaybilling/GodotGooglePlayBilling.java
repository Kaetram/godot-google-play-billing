package org.godotengine.godot.plugin.googleplaybilling;

import org.godotengine.godot.Dictionary;
import org.godotengine.godot.Godot;
import org.godotengine.godot.plugin.GodotPlugin;
import org.godotengine.godot.plugin.SignalInfo;
import org.godotengine.godot.plugin.googleplaybilling.utils.GooglePlayBillingUtils;
import org.godotengine.godot.plugin.UsedByGodot;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.collection.ArraySet;

import com.android.billingclient.api.AcknowledgePurchaseParams;
import com.android.billingclient.api.AcknowledgePurchaseResponseListener;
import com.android.billingclient.api.BillingClient;
import com.android.billingclient.api.BillingClientStateListener;
import com.android.billingclient.api.BillingFlowParams;
import com.android.billingclient.api.BillingResult;
import com.android.billingclient.api.ConsumeParams;
import com.android.billingclient.api.ConsumeResponseListener;
import com.android.billingclient.api.Purchase;
import com.android.billingclient.api.PurchasesResponseListener;
import com.android.billingclient.api.PurchasesUpdatedListener;
import com.android.billingclient.api.QueryPurchasesParams;
import com.android.billingclient.api.ProductDetails;
import com.android.billingclient.api.QueryProductDetailsParams;
import com.android.billingclient.api.ProductDetailsResponseListener;
import com.android.billingclient.api.ProductDetails.OneTimePurchaseOfferDetails;
import com.android.billingclient.api.ProductDetails.SubscriptionOfferDetails;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Set;

public class GodotGooglePlayBilling extends GodotPlugin implements PurchasesUpdatedListener, BillingClientStateListener {

	private final BillingClient billingClient;
	private final HashMap<String, ProductDetails> productDetailsCache = new HashMap<>(); // productId → ProductDetails
	private boolean calledStartConnection;
	private String obfuscatedAccountId;
	private String obfuscatedProfileId;

	public GodotGooglePlayBilling(Godot godot) {
		super(godot);

		billingClient = BillingClient
								.newBuilder(getActivity())
								.enablePendingPurchases()
								.setListener(this)
								.build();
		calledStartConnection = false;
		obfuscatedAccountId = "";
		obfuscatedProfileId = "";
	}

	public void startConnection() {
		calledStartConnection = true;
		billingClient.startConnection(this);
	}

	public void endConnection() {
		billingClient.endConnection();
	}
	@UsedByGodot
	public boolean isReady() {
		return this.billingClient.isReady();
	}
	@UsedByGodot
	public int getConnectionState() {
		return billingClient.getConnectionState();
	}

	@UsedByGodot
	public void queryPurchases(String type) {
		QueryPurchasesParams queryPurchasesParams = QueryPurchasesParams.newBuilder()
            .setProductType(type)
            .build();
        
		billingClient.queryPurchasesAsync(queryPurchasesParams, new PurchasesResponseListener() {
			@Override
			public void onQueryPurchasesResponse(BillingResult billingResult,
					List<Purchase> purchaseList) {
				Dictionary returnValue = new Dictionary();
				if (billingResult.getResponseCode() == BillingClient.BillingResponseCode.OK) {
					returnValue.put("status", 0); // OK = 0
					returnValue.put("purchases", GooglePlayBillingUtils.convertPurchaseListToDictionaryObjectArray(purchaseList));
				} else {
					returnValue.put("status", 1); // FAILED = 1
					returnValue.put("response_code", billingResult.getResponseCode());
					returnValue.put("debug_message", billingResult.getDebugMessage());
				}
				emitSignal("query_purchases_response", (Object)returnValue);
			}
		});
	}
	@UsedByGodot
	public void queryProductDetails(final String[] list, String type) {
		List<String> productIdList = Arrays.asList(list);

		QueryProductDetailsParams.Product product = QueryProductDetailsParams.Product.newBuilder()
            .setProductId(productIdList.get(0))
            .setProductType(type)
            .build();

		QueryProductDetailsParams params = QueryProductDetailsParams.newBuilder()
            .setProductList(Arrays.asList(product))
            .build();

		billingClient.queryProductDetailsAsync(params, new ProductDetailsResponseListener() {
			@Override
			public void onProductDetailsResponse(BillingResult billingResult,
					List<ProductDetails> productDetailsList) {
				if (billingResult.getResponseCode() == BillingClient.BillingResponseCode.OK) {
					for (ProductDetails productDetails : productDetailsList) {
						productDetailsCache.put(productDetails.getProductId(), productDetails);
					}
					emitSignal("product_details_query_completed", (Object)convertProductDetailsListToDictionaryObjectArray(productDetailsList));
				} else {
					emitSignal("product_details_query_error", billingResult.getResponseCode(), billingResult.getDebugMessage(), list);
				}
			}
		});
	}
	@UsedByGodot
	public void acknowledgePurchase(final String purchaseToken) {
		AcknowledgePurchaseParams acknowledgePurchaseParams =
				AcknowledgePurchaseParams.newBuilder()
						.setPurchaseToken(purchaseToken)
						.build();
		billingClient.acknowledgePurchase(acknowledgePurchaseParams, new AcknowledgePurchaseResponseListener() {
			@Override
			public void onAcknowledgePurchaseResponse(BillingResult billingResult) {
				if (billingResult.getResponseCode() == BillingClient.BillingResponseCode.OK) {
					emitSignal("purchase_acknowledged", purchaseToken);
				} else {
					emitSignal("purchase_acknowledgement_error", billingResult.getResponseCode(), billingResult.getDebugMessage(), purchaseToken);
				}
			}
		});
	}

	@UsedByGodot
	public void consumePurchase(String purchaseToken) {
		ConsumeParams consumeParams = ConsumeParams.newBuilder()
											  .setPurchaseToken(purchaseToken)
											  .build();

		billingClient.consumeAsync(consumeParams, new ConsumeResponseListener() {
			@Override
			public void onConsumeResponse(BillingResult billingResult, String purchaseToken) {
				if (billingResult.getResponseCode() == BillingClient.BillingResponseCode.OK) {
					emitSignal("purchase_consumed", purchaseToken);
				} else {
					emitSignal("purchase_consumption_error", billingResult.getResponseCode(), billingResult.getDebugMessage(), purchaseToken);
				}
			}
		});
	}

	@Override
	public void onBillingSetupFinished(BillingResult billingResult) {
		if (billingResult.getResponseCode() == BillingClient.BillingResponseCode.OK) {
			emitSignal("connected");
		} else {
			emitSignal("connect_error", billingResult.getResponseCode(), billingResult.getDebugMessage());
		}
	}

	@Override
	public void onBillingServiceDisconnected() {
		emitSignal("disconnected");
	}
	@UsedByGodot
	public Dictionary purchase(String productId) {
		return purchaseInternal("", productId, 
			BillingFlowParams.ProrationMode.UNKNOWN_SUBSCRIPTION_UPGRADE_DOWNGRADE_POLICY);
	}
	@UsedByGodot
	public Dictionary updateSubscription(String oldToken, String productId, int prorationMode) {
		return purchaseInternal(oldToken, productId, prorationMode);
	}

	private Dictionary purchaseInternal(String oldToken, String productId, int prorationMode) {
		if (!productDetailsCache.containsKey(productId)) {
			Dictionary returnValue = new Dictionary();
			returnValue.put("status", 1); // FAILED = 1
			returnValue.put("response_code", null); // Null since there is no ResponseCode to return but to keep the interface (status, response_code, debug_message)
			returnValue.put("debug_message", "You must query the product details and wait for the result before purchasing!");
			return returnValue;
		}

		ProductDetails productDetails = productDetailsCache.get(productId);
		BillingFlowParams.Builder purchaseParamsBuilder = BillingFlowParams.newBuilder();
		purchaseParamsBuilder.setProductDetailsParamsList(Arrays.asList(BillingFlowParams.ProductDetailsParams.newBuilder()
            .setProductDetails(productDetails)
            .build()));
		if (!obfuscatedAccountId.isEmpty()) {
			purchaseParamsBuilder.setObfuscatedAccountId(obfuscatedAccountId);
		}
		if (!obfuscatedProfileId.isEmpty()) {
			purchaseParamsBuilder.setObfuscatedProfileId(obfuscatedProfileId);
		}
		if (!oldToken.isEmpty() && prorationMode != BillingFlowParams.ProrationMode.UNKNOWN_SUBSCRIPTION_UPGRADE_DOWNGRADE_POLICY) {
			BillingFlowParams.SubscriptionUpdateParams updateParams =
				BillingFlowParams.SubscriptionUpdateParams.newBuilder()
					.setOldPurchaseToken(oldToken)
					.setReplaceProrationMode(prorationMode)
					.build();
			purchaseParamsBuilder.setSubscriptionUpdateParams(updateParams);
		}
		BillingResult result = billingClient.launchBillingFlow(getActivity(), purchaseParamsBuilder.build());

		Dictionary returnValue = new Dictionary();
		if (result.getResponseCode() == BillingClient.BillingResponseCode.OK) {
			returnValue.put("status", 0); // OK = 0
		} else {
			returnValue.put("status", 1); // FAILED = 1
			returnValue.put("response_code", result.getResponseCode());
			returnValue.put("debug_message", result.getDebugMessage());
		}

		return returnValue;
	}

	private Object[] convertProductDetailsListToDictionaryObjectArray(List<ProductDetails> productDetailsList) {
		List<Dictionary> dictionaries = new ArrayList<>();
		for (ProductDetails productDetails : productDetailsList) {
			Dictionary dictionary = new Dictionary();
			dictionary.put("productId", productDetails.getProductId());
			dictionary.put("title", productDetails.getTitle());
			dictionary.put("description", productDetails.getDescription());
	
			// Handle one-time purchase offer details
			if (productDetails.getOneTimePurchaseOfferDetails() != null) {
				dictionary.put("price", productDetails.getOneTimePurchaseOfferDetails().getFormattedPrice());
				dictionary.put("priceCurrencyCode", productDetails.getOneTimePurchaseOfferDetails().getPriceCurrencyCode());
			}
	
			// Handle subscription offer details
			if (productDetails.getSubscriptionOfferDetails() != null && !productDetails.getSubscriptionOfferDetails().isEmpty()) {
				// Assuming only one subscription offer detail is considered
				ProductDetails.SubscriptionOfferDetails subscriptionOffer = productDetails.getSubscriptionOfferDetails().get(0);
				dictionary.put("subscriptionPeriod", subscriptionOffer.getPricingPhases().getPricingPhaseList().get(0).getBillingPeriod());
				dictionary.put("subscriptionPrice", subscriptionOffer.getPricingPhases().getPricingPhaseList().get(0).getPriceAmountMicros());
				dictionary.put("subscriptionPriceCurrencyCode", subscriptionOffer.getPricingPhases().getPricingPhaseList().get(0).getPriceCurrencyCode());
			}
	
			dictionaries.add(dictionary);
		}
		return dictionaries.toArray();
	}

	@UsedByGodot
	public void setObfuscatedAccountId(String accountId) {
		obfuscatedAccountId = accountId;
	}
	@UsedByGodot
	public void setObfuscatedProfileId(String profileId) {
		obfuscatedProfileId = profileId;
	}

	@Override
	public void onPurchasesUpdated(final BillingResult billingResult, @Nullable final List<Purchase> list) {
		if (billingResult.getResponseCode() == BillingClient.BillingResponseCode.OK && list != null) {
			emitSignal("purchases_updated", (Object)GooglePlayBillingUtils.convertPurchaseListToDictionaryObjectArray(list));
		} else {
			emitSignal("purchase_error", billingResult.getResponseCode(), billingResult.getDebugMessage());
		}
	}

	@Override
	public void onMainResume() {
		if (calledStartConnection) {
			emitSignal("billing_resume");
		}
	}

	@NonNull
	@Override
	public String getPluginName() {
		return "GodotGooglePlayBilling";
	}

	@NonNull
	@Override
	public List<String> getPluginMethods() {
		return Arrays.asList("startConnection", "endConnection", "purchase", "updateSubscription", "queryProductDetails", "isReady", "getConnectionState", "queryPurchases", "acknowledgePurchase", "consumePurchase", "setObfuscatedAccountId", "setObfuscatedProfileId");
	}

	@NonNull
	@Override
	public Set<SignalInfo> getPluginSignals() {
		Set<SignalInfo> signals = new ArraySet<>();

		signals.add(new SignalInfo("connected"));
		signals.add(new SignalInfo("disconnected"));
		signals.add(new SignalInfo("billing_resume"));
		signals.add(new SignalInfo("connect_error", Integer.class, String.class));
		signals.add(new SignalInfo("purchases_updated", Object[].class));
		signals.add(new SignalInfo("query_purchases_response", Object.class));
		signals.add(new SignalInfo("purchase_error", Integer.class, String.class));
		signals.add(new SignalInfo("product_details_query_completed", Object[].class));
		signals.add(new SignalInfo("product_details_query_error", Integer.class, String.class, String[].class));
		signals.add(new SignalInfo("purchase_acknowledged", String.class));
		signals.add(new SignalInfo("purchase_acknowledgement_error", Integer.class, String.class, String.class));
		signals.add(new SignalInfo("purchase_consumed", String.class));
		signals.add(new SignalInfo("purchase_consumption_error", Integer.class, String.class, String.class));

		return signals;
	}
}
