package com.remodex.android.core.payments

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Subscription management service.
 *
 * Scaffolded for RevenueCat Android SDK integration. Currently implements
 * a free-send quota (5 attempts) matching iOS behavior.
 *
 * To integrate RevenueCat:
 * 1. Add dependency: implementation("com.revenuecat.purchases:purchases:8.+")
 * 2. Configure in RemodexApplication: Purchases.configure(PurchasesConfiguration.Builder(this, apiKey).build())
 * 3. Replace hasProAccess with RevenueCat entitlement check
 * 4. Replace purchase flow with RevenueCat Purchases.shared.purchase()
 */
class SubscriptionService(context: Context) {

    companion object {
        private const val PREFS_NAME = "remodex_subscription"
        private const val KEY_FREE_SEND_COUNT = "free_send_count"
        private const val FREE_SEND_LIMIT = 5
    }

    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    data class SubscriptionState(
        val hasProAccess: Boolean = false,
        val freeSendCount: Int = 0,
        val freeSendLimit: Int = FREE_SEND_LIMIT,
    ) {
        val hasAppAccess: Boolean
            get() = hasProAccess || remainingFreeSends > 0

        val remainingFreeSends: Int
            get() = (freeSendLimit - freeSendCount).coerceAtLeast(0)
    }

    private val _state = MutableStateFlow(
        SubscriptionState(
            freeSendCount = prefs.getInt(KEY_FREE_SEND_COUNT, 0),
        ),
    )
    val state: StateFlow<SubscriptionState> = _state.asStateFlow()

    /**
     * Consume one free send attempt. Call before each turn/start.
     * Returns true if the send is allowed.
     */
    fun consumeFreeSendIfNeeded(): Boolean {
        val current = _state.value
        if (current.hasProAccess) return true
        if (current.remainingFreeSends <= 0) return false

        val newCount = current.freeSendCount + 1
        prefs.edit().putInt(KEY_FREE_SEND_COUNT, newCount).apply()
        _state.update { it.copy(freeSendCount = newCount) }
        return true
    }

    /**
     * Refresh subscription state from RevenueCat.
     * TODO: Implement when RevenueCat SDK is integrated.
     */
    suspend fun refresh() {
        // When RevenueCat is integrated:
        // val customerInfo = Purchases.sharedInstance.awaitCustomerInfo()
        // val entitlement = customerInfo.entitlements[ENTITLEMENT_NAME]
        // _state.update { it.copy(hasProAccess = entitlement?.isActive == true) }
    }

    /**
     * Initiate a purchase flow.
     * TODO: Implement when RevenueCat SDK is integrated.
     */
    suspend fun purchase() {
        // When RevenueCat is integrated:
        // val offerings = Purchases.sharedInstance.awaitOfferings()
        // val package = offerings.current?.availablePackages?.firstOrNull()
        // if (package != null) Purchases.sharedInstance.awaitPurchase(package)
    }
}
